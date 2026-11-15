-- TVKu — private helper functions shared by RLS policies and RPCs.

-- Current admin role, or null. Requires an MFA-verified session (aal2).
create function private.current_admin_role()
returns public.admin_role
language sql
stable
security definer
set search_path = ''
as $$
  select a.role
    from public.admin_users a
   where a.auth_user_id = auth.uid()
     and a.is_active
     and coalesce(auth.jwt() ->> 'aal', '') = 'aal2';
$$;

create function private.has_admin_role(p_min public.admin_role)
returns boolean
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce(private.current_admin_role() >= p_min, false);
$$;

-- Returns the caller's auth user id or raises 42501.
create function private.require_admin(p_min public.admin_role)
returns uuid
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  if not private.has_admin_role(p_min) then
    raise exception 'forbidden: % role with MFA required', p_min using errcode = '42501';
  end if;
  return auth.uid();
end;
$$;

create function private.current_user_row_id()
returns uuid
language sql
stable
security definer
set search_path = ''
as $$
  select u.id from public.users u where u.auth_user_id = auth.uid();
$$;

grant execute on function private.current_admin_role()        to anon, authenticated, service_role;
grant execute on function private.has_admin_role(public.admin_role) to anon, authenticated, service_role;
grant execute on function private.current_user_row_id()       to authenticated, service_role;

-- Typed config readers with defaults.
create function private.config_value(p_key text)
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select c.config_value from public.app_config c where c.config_key = p_key;
$$;

create function private.config_int(p_key text, p_default integer)
returns integer
language sql
stable
security definer
set search_path = ''
as $$
  select coalesce((select (c.config_value #>> '{}')::integer
                     from public.app_config c
                    where c.config_key = p_key
                      and jsonb_typeof(c.config_value) = 'number'), p_default);
$$;

-- Unambiguous alphabet (no 0/O/1/I). 256 is a multiple of 32, so modulo is unbiased.
create function private.random_code(p_length integer)
returns text
language plpgsql
volatile
set search_path = ''
as $$
declare
  v_alphabet constant text := 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  v_bytes bytea := extensions.gen_random_bytes(p_length);
  v_out   text := '';
begin
  for i in 0 .. p_length - 1 loop
    v_out := v_out || substr(v_alphabet, (get_byte(v_bytes, i) % 32) + 1, 1);
  end loop;
  return v_out;
end;
$$;

create function private.sha256(p_value text)
returns bytea
language sql
immutable
strict
set search_path = ''
as $$
  select extensions.digest(convert_to(p_value, 'UTF8'), 'sha256');
$$;

-- Verifies installation credentials and updates last_seen_at.
create function private.authenticate_installation(p_installation_uuid uuid, p_secret text)
returns public.installations
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_row public.installations;
begin
  select * into v_row
    from public.installations i
   where i.installation_uuid = p_installation_uuid
   for update;

  if not found or p_secret is null or v_row.secret_hash <> private.sha256(p_secret) then
    raise exception 'invalid_installation_credentials' using errcode = '28000';
  end if;
  if v_row.blocked_at is not null then
    raise exception 'installation_blocked' using errcode = '42501';
  end if;

  update public.installations set last_seen_at = now() where id = v_row.id
  returning * into v_row;
  return v_row;
end;
$$;

create function private.log_audit(p_action text, p_target_type text, p_target_id text, p_details jsonb)
returns void
language sql
security definer
set search_path = ''
as $$
  insert into public.admin_audit_logs (admin_id, action, target_type, target_id, details)
  values (auth.uid(), p_action, p_target_type, p_target_id, coalesce(p_details, '{}'::jsonb));
$$;

-- Records direct admin edits (through PostgREST) to catalog tables.
-- Automated validator updates (no auth.uid()) are already in stream_health_logs.
create function private.audit_row_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_id text;
begin
  -- Skip system changes and changes cascaded by other triggers (e.g. the
  -- derived channels.is_published refresh); only direct human edits are logged.
  if auth.uid() is null or pg_trigger_depth() > 1 then
    return null;
  end if;
  v_id := coalesce(
    (case when tg_op = 'DELETE' then to_jsonb(old) else to_jsonb(new) end) ->> 'id',
    (case when tg_op = 'DELETE' then to_jsonb(old) else to_jsonb(new) end) ->> 'channel_id');
  perform private.log_audit(
    lower(tg_op), tg_table_name, v_id,
    jsonb_build_object(
      'old', case when tg_op <> 'INSERT' then to_jsonb(old) end,
      'new', case when tg_op <> 'DELETE' then to_jsonb(new) end));
  return null;
end;
$$;

create trigger channels_audit          after insert or update or delete on public.channels
  for each row execute function private.audit_row_change();
create trigger channel_languages_audit after insert or update or delete on public.channel_languages
  for each row execute function private.audit_row_change();
create trigger streams_audit           after insert or update or delete on public.streams
  for each row execute function private.audit_row_change();
create trigger categories_audit        after insert or update or delete on public.categories
  for each row execute function private.audit_row_change();
