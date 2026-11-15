-- TVKu — administrator RPCs.
--
-- Callable by the authenticated role (dashboard or Edge Function forwarding the
-- admin's JWT). The admin identity always comes from auth.uid() inside the
-- database, never from a parameter, and requires an MFA (aal2) session.
-- Every action is written to admin_audit_logs.

-- True when the donation belongs (directly or through its installation) to the
-- calling admin's own account.
create function private.donation_owned_by_caller(p_donation public.donations)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select exists (
    select 1 from public.users u
     where u.auth_user_id = auth.uid()
       and (u.id = p_donation.user_id
            or u.id = (select i.user_id from public.installations i where i.id = p_donation.installation_id)));
$$;

create function public.admin_approve_donation(
  p_donation_id    uuid,
  p_duration_days  integer default null,
  p_expires_at     timestamptz default null,
  p_proof_reference text default null,
  p_notes          text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin     uuid := private.require_admin('admin');
  v_donation  public.donations;
  v_inst      public.installations;
  v_existing  public.premium_entitlements;
  v_days      integer := coalesce(p_duration_days, private.config_int('premium_default_days', 30));
  v_base      timestamptz;
  v_expires   timestamptz;
  v_ent       public.premium_entitlements;
begin
  select * into v_donation from public.donations d where d.id = p_donation_id for update;
  if not found then
    raise exception 'donation_not_found' using errcode = 'P0002';
  end if;

  -- Idempotency: approving twice returns the original result.
  if v_donation.status = 'APPROVED' then
    select * into v_existing from public.premium_entitlements p where p.donation_id = v_donation.id;
    return jsonb_build_object('donation_id', v_donation.id, 'status', v_donation.status,
                              'entitlement_id', v_existing.id, 'expires_at', v_existing.expires_at,
                              'idempotent_replay', true);
  end if;
  if v_donation.status <> 'PENDING' then
    raise exception 'donation_not_pending: %', v_donation.status using errcode = '55000';
  end if;
  if private.donation_owned_by_caller(v_donation) then
    raise exception 'self_approval_forbidden' using errcode = '42501';
  end if;
  if p_expires_at is null and (v_days < 1 or v_days > 3650) then
    raise exception 'invalid_duration_days' using errcode = '22023';
  end if;
  if p_expires_at is not null and p_expires_at <= now() then
    raise exception 'expires_at_must_be_in_future' using errcode = '22023';
  end if;

  select * into v_inst from public.installations i where i.id = v_donation.installation_id;

  -- Stack on top of any premium that is still running.
  select greatest(now(), coalesce(max(p.expires_at), now())) into v_base
    from public.premium_entitlements p
   where p.status = 'ACTIVE' and p.expires_at > now()
     and (p.installation_id = v_inst.id or (v_inst.user_id is not null and p.user_id = v_inst.user_id));

  v_expires := coalesce(p_expires_at, v_base + make_interval(days => v_days));

  insert into public.premium_entitlements
    (installation_id, user_id, entitlement_type, donation_id, starts_at, expires_at, granted_by)
  values
    (v_inst.id, v_inst.user_id, 'DONATION', v_donation.id, now(), v_expires, v_admin)
  returning * into v_ent;

  update public.donations
     set status = 'APPROVED', reviewed_by = v_admin, reviewed_at = now(),
         review_notes = p_notes, proof_reference = coalesce(p_proof_reference, proof_reference)
   where id = v_donation.id;

  perform private.log_audit('approve_donation', 'donation', v_donation.id::text,
    jsonb_build_object('entitlement_id', v_ent.id, 'expires_at', v_ent.expires_at,
                       'amount', v_donation.amount, 'currency', v_donation.currency,
                       'installation_id', v_inst.id, 'notes', p_notes));

  return jsonb_build_object('donation_id', v_donation.id, 'status', 'APPROVED',
                            'entitlement_id', v_ent.id, 'expires_at', v_ent.expires_at,
                            'idempotent_replay', false);
end;
$$;

create function public.admin_reject_donation(p_donation_id uuid, p_reason text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin    uuid := private.require_admin('admin');
  v_donation public.donations;
begin
  if p_reason is null or length(trim(p_reason)) = 0 then
    raise exception 'reason_required' using errcode = '22023';
  end if;
  select * into v_donation from public.donations d where d.id = p_donation_id for update;
  if not found then
    raise exception 'donation_not_found' using errcode = 'P0002';
  end if;
  if v_donation.status = 'REJECTED' then
    return jsonb_build_object('donation_id', v_donation.id, 'status', 'REJECTED', 'idempotent_replay', true);
  end if;
  if v_donation.status <> 'PENDING' then
    raise exception 'donation_not_pending: %', v_donation.status using errcode = '55000';
  end if;
  if private.donation_owned_by_caller(v_donation) then
    raise exception 'self_approval_forbidden' using errcode = '42501';
  end if;

  update public.donations
     set status = 'REJECTED', reviewed_by = v_admin, reviewed_at = now(), review_notes = p_reason
   where id = v_donation.id;

  perform private.log_audit('reject_donation', 'donation', v_donation.id::text,
                            jsonb_build_object('reason', p_reason));
  return jsonb_build_object('donation_id', v_donation.id, 'status', 'REJECTED', 'idempotent_replay', false);
end;
$$;

-- Extends the running premium entitlement of an installation, or creates an
-- ADMIN_GRANT when none is active. Exactly one of p_add_days / p_new_expires_at.
create function public.admin_extend_premium(
  p_installation_id uuid,
  p_add_days        integer default null,
  p_new_expires_at  timestamptz default null,
  p_notes           text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin   uuid := private.require_admin('admin');
  v_inst    public.installations;
  v_active  public.premium_entitlements;
  v_ent     public.premium_entitlements;
  v_target  timestamptz;
begin
  if (p_add_days is null) = (p_new_expires_at is null) then
    raise exception 'exactly_one_of_add_days_or_new_expires_at' using errcode = '22023';
  end if;
  if p_add_days is not null and (p_add_days < 1 or p_add_days > 3650) then
    raise exception 'invalid_add_days' using errcode = '22023';
  end if;
  if p_new_expires_at is not null and p_new_expires_at <= now() then
    raise exception 'expires_at_must_be_in_future' using errcode = '22023';
  end if;

  select * into v_inst from public.installations i where i.id = p_installation_id;
  if not found then
    raise exception 'installation_not_found' using errcode = 'P0002';
  end if;

  select * into v_active
    from public.premium_entitlements p
   where p.status = 'ACTIVE' and p.starts_at <= now() and p.expires_at > now()
     and (p.installation_id = v_inst.id or (v_inst.user_id is not null and p.user_id = v_inst.user_id))
   order by p.expires_at desc
   limit 1
   for update;

  if v_active.id is not null then
    v_target := coalesce(p_new_expires_at, v_active.expires_at + make_interval(days => p_add_days));
    update public.premium_entitlements set expires_at = v_target where id = v_active.id
    returning * into v_ent;
    perform private.log_audit('extend_premium', 'premium_entitlement', v_ent.id::text,
      jsonb_build_object('installation_id', v_inst.id, 'previous_expires_at', v_active.expires_at,
                         'new_expires_at', v_target, 'notes', p_notes));
  else
    v_target := coalesce(p_new_expires_at, now() + make_interval(days => p_add_days));
    insert into public.premium_entitlements
      (installation_id, user_id, entitlement_type, starts_at, expires_at, granted_by)
    values (v_inst.id, v_inst.user_id, 'ADMIN_GRANT', now(), v_target, v_admin)
    returning * into v_ent;
    perform private.log_audit('grant_premium', 'premium_entitlement', v_ent.id::text,
      jsonb_build_object('installation_id', v_inst.id, 'expires_at', v_target, 'notes', p_notes));
  end if;

  return jsonb_build_object('entitlement_id', v_ent.id, 'expires_at', v_ent.expires_at);
end;
$$;

create function public.admin_revoke_premium(p_installation_id uuid, p_reason text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin  uuid := private.require_admin('admin');
  v_inst   public.installations;
  v_ids    uuid[];
begin
  if p_reason is null or length(trim(p_reason)) = 0 then
    raise exception 'reason_required' using errcode = '22023';
  end if;
  select * into v_inst from public.installations i where i.id = p_installation_id;
  if not found then
    raise exception 'installation_not_found' using errcode = 'P0002';
  end if;

  with revoked as (
    update public.premium_entitlements p
       set status = 'REVOKED', revoked_at = now(), revoked_by = v_admin, revoke_reason = p_reason
     where p.status = 'ACTIVE'
       and (p.installation_id = v_inst.id or (v_inst.user_id is not null and p.user_id = v_inst.user_id))
    returning p.id)
  select coalesce(array_agg(id), '{}') into v_ids from revoked;

  perform private.log_audit('revoke_premium', 'installation', v_inst.id::text,
    jsonb_build_object('entitlement_ids', v_ids, 'reason', p_reason));
  return jsonb_build_object('installation_id', v_inst.id, 'revoked_entitlement_ids', to_jsonb(v_ids));
end;
$$;

-- Licensing decision for a stream. TAKEDOWN and DENIED withdraw it immediately.
create function public.admin_review_stream_permission(
  p_stream_id uuid,
  p_status    public.stream_permission_status,
  p_notes     text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin  uuid := private.require_admin('admin');
  v_old    public.streams;
  v_new    public.streams;
begin
  if p_status = 'UNREVIEWED' then
    raise exception 'cannot_set_unreviewed' using errcode = '22023';
  end if;
  if p_notes is null or length(trim(p_notes)) < 3 then
    raise exception 'evidence_notes_required' using errcode = '22023';
  end if;

  select * into v_old from public.streams s where s.id = p_stream_id for update;
  if not found then
    raise exception 'stream_not_found' using errcode = 'P0002';
  end if;

  update public.streams
     set permission_status = p_status,
         permission_notes = p_notes,
         permission_reviewed_by = v_admin,
         permission_reviewed_at = now(),
         validation_status = case when p_status = 'TAKEDOWN' then 'REMOVED'
                                  when p_status = 'DENIED'   then 'REJECTED'
                                  else validation_status end,
         is_preferred = case when p_status in ('TAKEDOWN', 'DENIED') then false else is_preferred end
   where id = p_stream_id
  returning * into v_new;

  perform private.log_audit('review_stream_permission', 'stream', p_stream_id::text,
    jsonb_build_object('from', v_old.permission_status, 'to', p_status, 'notes', p_notes));
  return jsonb_build_object('stream_id', p_stream_id, 'permission_status', v_new.permission_status,
                            'validation_status', v_new.validation_status, 'is_published', v_new.is_published);
end;
$$;

-- Manual health decisions. VERIFIED can never be set by hand: it requires a
-- recorded Tier B playback test (public.record_stream_check).
create function public.admin_set_stream_status(
  p_stream_id uuid,
  p_status    public.stream_validation_status,
  p_reason    text
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin uuid := private.require_admin('reviewer');
  v_old   public.streams;
begin
  if p_status not in ('PENDING_REVIEW', 'REJECTED', 'REMOVED') then
    raise exception 'status_not_settable_manually: %', p_status using errcode = '22023';
  end if;
  if p_reason is null or length(trim(p_reason)) = 0 then
    raise exception 'reason_required' using errcode = '22023';
  end if;
  select * into v_old from public.streams s where s.id = p_stream_id for update;
  if not found then
    raise exception 'stream_not_found' using errcode = 'P0002';
  end if;

  update public.streams
     set validation_status = p_status,
         failure_reason = case when p_status = 'PENDING_REVIEW' then failure_reason else p_reason end,
         consecutive_failures = case when p_status = 'PENDING_REVIEW' then 0 else consecutive_failures end,
         is_preferred = case when p_status in ('REJECTED', 'REMOVED') then false else is_preferred end
   where id = p_stream_id;

  perform private.log_audit('set_stream_status', 'stream', p_stream_id::text,
    jsonb_build_object('from', v_old.validation_status, 'to', p_status, 'reason', p_reason));
  return jsonb_build_object('stream_id', p_stream_id, 'validation_status', p_status);
end;
$$;

create function public.admin_set_preferred_stream(p_stream_id uuid)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin  uuid := private.require_admin('admin');
  v_stream public.streams;
begin
  select * into v_stream from public.streams s where s.id = p_stream_id for update;
  if not found then
    raise exception 'stream_not_found' using errcode = 'P0002';
  end if;
  if v_stream.validation_status in ('REJECTED', 'REMOVED') then
    raise exception 'stream_withdrawn' using errcode = '55000';
  end if;
  update public.streams set is_preferred = false where channel_id = v_stream.channel_id and is_preferred;
  update public.streams set is_preferred = true where id = p_stream_id;
  perform private.log_audit('set_preferred_stream', 'stream', p_stream_id::text,
                            jsonb_build_object('channel_id', v_stream.channel_id));
  return jsonb_build_object('stream_id', p_stream_id, 'channel_id', v_stream.channel_id);
end;
$$;

-- Validated configuration updates.
create function public.admin_set_config(p_key text, p_value jsonb)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin uuid := private.require_admin('admin');
  v_old   jsonb;
begin
  select c.config_value into v_old from public.app_config c where c.config_key = p_key for update;
  if not found then
    raise exception 'unknown_config_key' using errcode = 'P0002';
  end if;
  if jsonb_typeof(p_value) is distinct from jsonb_typeof(v_old) and jsonb_typeof(v_old) <> 'null'
     and jsonb_typeof(p_value) <> 'null' then
    raise exception 'config_type_mismatch' using errcode = '22023';
  end if;

  if p_key = 'whatsapp_admin_number' and jsonb_typeof(p_value) = 'string'
     and (p_value #>> '{}') !~ '^[1-9][0-9]{7,14}$' then
    raise exception 'whatsapp_number_must_be_e164_digits_without_plus' using errcode = '22023';
  end if;
  if p_key = 'donation_currency' and (p_value #>> '{}') !~ '^[A-Z]{3}$' then
    raise exception 'invalid_currency' using errcode = '22023';
  end if;
  if p_key = 'trial_duration_seconds' then
    raise exception 'trial_duration_is_fixed_by_product_rule' using errcode = '42501';
  end if;

  update public.app_config set config_value = p_value, updated_by = v_admin where config_key = p_key;
  perform private.log_audit('set_config', 'app_config', p_key, jsonb_build_object('from', v_old, 'to', p_value));
  return jsonb_build_object('config_key', p_key, 'config_value', p_value);
end;
$$;

create function public.admin_overview()
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform private.require_admin('reviewer');
  return jsonb_build_object(
    'total_installations', (select count(*) from public.installations),
    'active_installations_7d', (select count(*) from public.installations where last_seen_at > now() - interval '7 days'),
    'active_trials',      (select count(*) from public.trial_entitlements where expires_at > now()),
    'expired_trials',     (select count(*) from public.trial_entitlements where expires_at <= now()),
    'pending_donations',  (select count(*) from public.donations where status = 'PENDING'),
    'approved_donations', (select count(*) from public.donations where status = 'APPROVED'),
    'premium_installations', (select count(distinct installation_id) from public.premium_entitlements
                               where status = 'ACTIVE' and starts_at <= now() and expires_at > now()),
    'total_channels',     (select count(*) from public.channels),
    'published_channels', (select count(*) from public.channels where is_published),
    'streams_by_status',  (select coalesce(jsonb_object_agg(validation_status, n), '{}'::jsonb)
                             from (select validation_status, count(*) n from public.streams group by 1) s),
    'streams_by_permission', (select coalesce(jsonb_object_agg(permission_status, n), '{}'::jsonb)
                             from (select permission_status, count(*) n from public.streams group by 1) s),
    'generated_at', now());
end;
$$;

-- Owner-only management of admin accounts.
create function public.admin_set_admin_role(p_auth_user_id uuid, p_role public.admin_role, p_active boolean)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_admin uuid := private.require_admin('owner');
begin
  if p_auth_user_id = v_admin then
    raise exception 'cannot_change_own_admin_role' using errcode = '42501';
  end if;
  insert into public.admin_users (auth_user_id, role, is_active, created_by)
  values (p_auth_user_id, p_role, p_active, v_admin)
  on conflict (auth_user_id) do update set role = excluded.role, is_active = excluded.is_active;
  perform private.log_audit('set_admin_role', 'admin_user', p_auth_user_id::text,
                            jsonb_build_object('role', p_role, 'is_active', p_active));
  return jsonb_build_object('auth_user_id', p_auth_user_id, 'role', p_role, 'is_active', p_active);
end;
$$;

-- Permission evidence is not exposed through table grants; admins read it here.
create function public.admin_get_stream_review(p_stream_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform private.require_admin('reviewer');
  return (select jsonb_build_object('stream_id', s.id, 'permission_status', s.permission_status,
                                    'permission_notes', s.permission_notes,
                                    'permission_reviewed_by', s.permission_reviewed_by,
                                    'permission_reviewed_at', s.permission_reviewed_at)
            from public.streams s where s.id = p_stream_id);
end;
$$;

do $$
declare
  f text;
begin
  foreach f in array array[
    'public.admin_approve_donation(uuid, integer, timestamptz, text, text)',
    'public.admin_reject_donation(uuid, text)',
    'public.admin_extend_premium(uuid, integer, timestamptz, text)',
    'public.admin_revoke_premium(uuid, text)',
    'public.admin_review_stream_permission(uuid, public.stream_permission_status, text)',
    'public.admin_set_stream_status(uuid, public.stream_validation_status, text)',
    'public.admin_set_preferred_stream(uuid)',
    'public.admin_set_config(text, jsonb)',
    'public.admin_overview()',
    'public.admin_set_admin_role(uuid, public.admin_role, boolean)',
    'public.admin_get_stream_review(uuid)'
  ] loop
    execute format('revoke all on function %s from public, anon', f);
    execute format('grant execute on function %s to authenticated', f);
  end loop;
end;
$$;
