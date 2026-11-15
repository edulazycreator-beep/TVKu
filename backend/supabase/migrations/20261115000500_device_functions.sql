-- TVKu — server-authoritative installation, trial, entitlement and donation logic.
--
-- These functions are executable only by service_role, i.e. only by Edge
-- Functions after they have validated input, rate limits and Firebase App
-- Check. The Android app never calls them directly.

-- ---------------------------------------------------------------------------
-- Trial
-- ---------------------------------------------------------------------------

-- Creates the trial for an installation if it has none. When another
-- installation with the same device key already consumed a trial, the new
-- installation inherits that trial's window instead of getting a fresh one.
create function private.ensure_trial(p_installation_id uuid)
returns public.trial_entitlements
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_trial     public.trial_entitlements;
  v_previous  public.trial_entitlements;
  v_device    text;
  v_duration  integer := private.config_int('trial_duration_seconds', 18000);
begin
  select * into v_trial from public.trial_entitlements t where t.installation_id = p_installation_id;
  if found then
    return v_trial;
  end if;

  select i.device_key_hash into v_device from public.installations i where i.id = p_installation_id;

  if v_device is not null then
    -- serialise concurrent registrations of the same device
    perform pg_advisory_xact_lock(hashtextextended('trial:' || v_device, 0));
    select * into v_previous
      from public.trial_entitlements t
     where t.device_key_hash = v_device
     order by t.started_at
     limit 1;
  end if;

  if v_previous.id is not null then
    insert into public.trial_entitlements
      (installation_id, device_key_hash, started_at, expires_at, status, inherited_from)
    values
      (p_installation_id, v_device, v_previous.started_at, v_previous.expires_at,
       case when v_previous.expires_at <= now() then 'EXPIRED' else 'ACTIVE' end::public.trial_status,
       v_previous.id)
    returning * into v_trial;
  else
    insert into public.trial_entitlements (installation_id, device_key_hash, started_at, expires_at)
    values (p_installation_id, v_device, now(), now() + make_interval(secs => v_duration))
    returning * into v_trial;
  end if;

  return v_trial;
end;
$$;

-- ---------------------------------------------------------------------------
-- Entitlement evaluation (always against server time)
-- ---------------------------------------------------------------------------

create function private.entitlement_for(p_installation_id uuid)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  v_now            timestamptz := now();
  v_inst           public.installations;
  v_trial          public.trial_entitlements;
  v_active         public.premium_entitlements;
  v_latest         public.premium_entitlements;
  v_pending_ref    text;
  v_trial_status   public.trial_status;
  v_status         public.premium_status;
begin
  select * into v_inst from public.installations i where i.id = p_installation_id;
  select * into v_trial from public.trial_entitlements t where t.installation_id = p_installation_id;

  select * into v_active
    from public.premium_entitlements p
   where (p.installation_id = v_inst.id or (v_inst.user_id is not null and p.user_id = v_inst.user_id))
     and p.status = 'ACTIVE' and p.starts_at <= v_now and p.expires_at > v_now
   order by p.expires_at desc
   limit 1;

  select * into v_latest
    from public.premium_entitlements p
   where (p.installation_id = v_inst.id or (v_inst.user_id is not null and p.user_id = v_inst.user_id))
   order by p.updated_at desc, p.created_at desc
   limit 1;

  select d.donation_reference into v_pending_ref
    from public.donations d
   where d.installation_id = v_inst.id and d.status = 'PENDING'
   order by d.created_at desc
   limit 1;

  v_trial_status := case
    when v_trial.id is null then 'NOT_STARTED'
    when v_now >= v_trial.expires_at then 'EXPIRED'
    else 'ACTIVE' end;

  v_status := case
    when v_active.id is not null        then 'PREMIUM'
    when v_trial_status = 'ACTIVE'      then 'TRIAL'
    when v_pending_ref is not null      then 'PENDING_DONATION'
    when v_latest.status = 'REVOKED'    then 'REVOKED'
    when v_latest.id is not null        then 'EXPIRED'
    else 'FREE' end;

  return jsonb_build_object(
    'installation_id',           v_inst.id,
    'public_code',               v_inst.public_code,
    'status',                    v_status,
    'premium_features_unlocked', v_status in ('PREMIUM', 'TRIAL'),
    'server_time',               v_now,
    'trial', jsonb_build_object(
      'status',            v_trial_status,
      'started_at',        v_trial.started_at,
      'expires_at',        v_trial.expires_at,
      'remaining_seconds', case when v_trial_status = 'ACTIVE'
                                then floor(extract(epoch from v_trial.expires_at - v_now))::bigint
                                else 0 end),
    'premium', case when v_active.id is null then null else jsonb_build_object(
      'entitlement_id', v_active.id,
      'type',           v_active.entitlement_type,
      'starts_at',      v_active.starts_at,
      'expires_at',     v_active.expires_at) end,
    'pending_donation_reference', v_pending_ref);
end;
$$;

-- ---------------------------------------------------------------------------
-- Public RPCs for Edge Functions
-- ---------------------------------------------------------------------------

-- Idempotent: retrying with the same UUID and the same secret returns the same
-- installation. A different secret for an existing UUID is rejected.
create function public.register_installation(
  p_installation_uuid uuid,
  p_secret            text,
  p_device_key_hash   text default null,
  p_app_version       text default null,
  p_integrity_verdict text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_inst  public.installations;
  v_code  text;
begin
  if p_installation_uuid is null then
    raise exception 'installation_uuid_required' using errcode = '22023';
  end if;
  if p_secret is null or length(p_secret) < 32 or length(p_secret) > 256 then
    raise exception 'invalid_secret' using errcode = '22023';
  end if;
  if p_device_key_hash is not null and p_device_key_hash !~ '^[a-f0-9]{64}$' then
    raise exception 'invalid_device_key_hash' using errcode = '22023';
  end if;

  perform pg_advisory_xact_lock(hashtextextended('inst:' || p_installation_uuid::text, 0));

  select * into v_inst from public.installations i where i.installation_uuid = p_installation_uuid;

  if found then
    if v_inst.secret_hash <> private.sha256(p_secret) then
      raise exception 'installation_uuid_conflict' using errcode = '28000';
    end if;
    if v_inst.blocked_at is not null then
      raise exception 'installation_blocked' using errcode = '42501';
    end if;
    update public.installations
       set last_seen_at = now(),
           app_version = coalesce(p_app_version, app_version),
           integrity_verdict = coalesce(p_integrity_verdict, integrity_verdict)
     where id = v_inst.id
    returning * into v_inst;
  else
    loop
      v_code := 'TVK-' || private.random_code(4) || '-' || private.random_code(4);
      exit when not exists (select 1 from public.installations i where i.public_code = v_code);
    end loop;

    insert into public.installations
      (installation_uuid, public_code, secret_hash, device_key_hash, app_version, integrity_verdict)
    values
      (p_installation_uuid, v_code, private.sha256(p_secret), p_device_key_hash, p_app_version, p_integrity_verdict)
    returning * into v_inst;
  end if;

  -- The trial starts at the first successful registration.
  perform private.ensure_trial(v_inst.id);

  return private.entitlement_for(v_inst.id);
end;
$$;

-- Kept for API completeness: ensures the trial exists (e.g. for installations
-- registered before trials were enabled). Never restarts an existing trial.
create function public.start_trial(p_installation_uuid uuid, p_secret text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_inst public.installations := private.authenticate_installation(p_installation_uuid, p_secret);
begin
  perform private.ensure_trial(v_inst.id);
  return private.entitlement_for(v_inst.id);
end;
$$;

create function public.get_entitlement(p_installation_uuid uuid, p_secret text)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_inst public.installations := private.authenticate_installation(p_installation_uuid, p_secret);
begin
  return private.entitlement_for(v_inst.id);
end;
$$;

create function public.create_donation(
  p_installation_uuid uuid,
  p_secret            text,
  p_amount            numeric,
  p_currency          text,
  p_payment_method    text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_inst      public.installations := private.authenticate_installation(p_installation_uuid, p_secret);
  v_min       numeric := coalesce((private.config_value('donation_min_amount') #>> '{}')::numeric, 1);
  v_max       numeric := coalesce((private.config_value('donation_max_amount') #>> '{}')::numeric, 100000);
  v_max_pend  integer := private.config_int('donation_max_pending_per_installation', 3);
  v_currency  text := upper(coalesce(p_currency, ''));
  v_ref       text;
  v_donation  public.donations;
begin
  if p_amount is null or p_amount < v_min or p_amount > v_max or p_amount <> round(p_amount, 2) then
    raise exception 'invalid_amount' using errcode = '22023';
  end if;
  if v_currency !~ '^[A-Z]{3}$' then
    raise exception 'invalid_currency' using errcode = '22023';
  end if;
  if p_payment_method is not null and length(p_payment_method) > 64 then
    raise exception 'invalid_payment_method' using errcode = '22023';
  end if;
  if (select count(*) from public.donations d
       where d.installation_id = v_inst.id and d.status = 'PENDING') >= v_max_pend then
    raise exception 'too_many_pending_donations' using errcode = '54000';
  end if;

  loop
    v_ref := 'TVKD-' || to_char(now() at time zone 'UTC', 'YYMMDD') || '-' || private.random_code(6);
    exit when not exists (select 1 from public.donations d where d.donation_reference = v_ref);
  end loop;

  insert into public.donations (installation_id, user_id, donation_reference, amount, currency, payment_method)
  values (v_inst.id, v_inst.user_id, v_ref, p_amount, v_currency, p_payment_method)
  returning * into v_donation;

  return jsonb_build_object(
    'donation_id',        v_donation.id,
    'donation_reference', v_donation.donation_reference,
    'amount',             v_donation.amount,
    'currency',           v_donation.currency,
    'status',             v_donation.status,
    'public_code',        v_inst.public_code,
    'created_at',         v_donation.created_at);
end;
$$;

-- Links an installation to a Supabase Auth user so entitlements survive
-- reinstalls. p_auth_user_id must come from a JWT verified by the Edge Function.
create function public.link_installation_to_user(
  p_installation_uuid uuid,
  p_secret            text,
  p_auth_user_id      uuid,
  p_email             text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_inst    public.installations := private.authenticate_installation(p_installation_uuid, p_secret);
  v_user_id uuid;
begin
  if not exists (select 1 from auth.users u where u.id = p_auth_user_id) then
    raise exception 'unknown_auth_user' using errcode = '22023';
  end if;

  insert into public.users (auth_user_id, email)
  values (p_auth_user_id, p_email)
  on conflict (auth_user_id) do update set email = coalesce(excluded.email, public.users.email)
  returning id into v_user_id;

  update public.installations set user_id = v_user_id where id = v_inst.id;
  return private.entitlement_for(v_inst.id);
end;
$$;

-- Fixed-window rate limiter. Returns true when the request is allowed.
create function public.consume_rate_limit(p_bucket_key text, p_max integer, p_window_seconds integer)
returns boolean
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_count integer;
begin
  insert into private.rate_limits as r (bucket_key, window_start, request_count)
  values (p_bucket_key, now(), 1)
  on conflict (bucket_key) do update
     set request_count = case when r.window_start <= now() - make_interval(secs => p_window_seconds)
                              then 1 else r.request_count + 1 end,
         window_start  = case when r.window_start <= now() - make_interval(secs => p_window_seconds)
                              then now() else r.window_start end
  returning request_count into v_count;
  return v_count <= p_max;
end;
$$;

-- Periodic housekeeping (scheduled with pg_cron when available).
create function public.maintenance_expire_entitlements()
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_trials  integer;
  v_premium integer;
begin
  update public.trial_entitlements set status = 'EXPIRED'
   where status = 'ACTIVE' and expires_at <= now();
  get diagnostics v_trials = row_count;

  update public.premium_entitlements set status = 'EXPIRED'
   where status = 'ACTIVE' and expires_at <= now();
  get diagnostics v_premium = row_count;

  delete from private.rate_limits where window_start < now() - interval '1 day';

  return jsonb_build_object('trials_expired', v_trials, 'premium_expired', v_premium);
end;
$$;

revoke all on function public.register_installation(uuid, text, text, text, text)       from public, anon, authenticated;
revoke all on function public.start_trial(uuid, text)                                  from public, anon, authenticated;
revoke all on function public.get_entitlement(uuid, text)                              from public, anon, authenticated;
revoke all on function public.create_donation(uuid, text, numeric, text, text)         from public, anon, authenticated;
revoke all on function public.link_installation_to_user(uuid, text, uuid, text)        from public, anon, authenticated;
revoke all on function public.consume_rate_limit(text, integer, integer)               from public, anon, authenticated;
revoke all on function public.maintenance_expire_entitlements()                        from public, anon, authenticated;

grant execute on function public.register_installation(uuid, text, text, text, text)   to service_role;
grant execute on function public.start_trial(uuid, text)                              to service_role;
grant execute on function public.get_entitlement(uuid, text)                          to service_role;
grant execute on function public.create_donation(uuid, text, numeric, text, text)     to service_role;
grant execute on function public.link_installation_to_user(uuid, text, uuid, text)    to service_role;
grant execute on function public.consume_rate_limit(text, integer, integer)           to service_role;
grant execute on function public.maintenance_expire_entitlements()                    to service_role;
