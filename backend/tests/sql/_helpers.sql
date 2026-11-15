-- Test helpers (installed only in the throwaway test database).
create schema tests;
grant usage on schema tests to anon, authenticated, service_role;

-- Switch identity like PostgREST does.
create function tests.as_anon() returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', '{"role":"anon"}', true);
  execute 'set local role anon';
end $$;

create function tests.as_user(p_auth_user_id uuid, p_aal text default 'aal1') returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims',
    json_build_object('role', 'authenticated', 'sub', p_auth_user_id, 'aal', p_aal)::text, true);
  execute 'set local role authenticated';
end $$;

create function tests.as_service() returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', '{"role":"service_role"}', true);
  execute 'set local role service_role';
end $$;

create function tests.as_superuser() returns void language plpgsql as $$
begin
  perform set_config('request.jwt.claims', '', true);
  execute 'reset role';
end $$;

create function tests.assert(p_condition boolean, p_message text) returns void language plpgsql as $$
begin
  if p_condition is distinct from true then
    raise exception 'ASSERTION FAILED: %', p_message;
  end if;
end $$;

create function tests.assert_eq(p_actual anyelement, p_expected anyelement, p_message text) returns void language plpgsql as $$
begin
  if p_actual is distinct from p_expected then
    raise exception 'ASSERTION FAILED: % (expected %, got %)', p_message, p_expected, p_actual;
  end if;
end $$;

-- Runs p_sql and asserts it fails with an error whose message contains p_fragment.
create function tests.assert_raises(p_sql text, p_fragment text, p_message text) returns void language plpgsql as $$
begin
  begin
    execute p_sql;
  exception when others then
    if position(p_fragment in sqlerrm) = 0 then
      raise exception 'ASSERTION FAILED: % (expected error containing "%", got "%")', p_message, p_fragment, sqlerrm;
    end if;
    return;
  end;
  raise exception 'ASSERTION FAILED: % (expected error containing "%", but statement succeeded)', p_message, p_fragment;
end $$;

grant execute on all functions in schema tests to anon, authenticated, service_role;

-- Test fixtures created as superuser.
create function tests.make_auth_user(p_email text) returns uuid language sql as $$
  insert into auth.users (email) values (p_email) returning id
$$;

create function tests.make_admin(p_email text, p_role public.admin_role) returns uuid language plpgsql as $$
declare v uuid := tests.make_auth_user(p_email);
begin
  insert into public.admin_users (auth_user_id, role) values (v, p_role);
  return v;
end $$;

-- Rewinds a trial so that tests can simulate elapsed server time.
create function tests.age_trial(p_installation_uuid uuid, p_seconds integer) returns void language sql as $$
  update public.trial_entitlements t
     set started_at = t.started_at - make_interval(secs => p_seconds),
         expires_at = t.expires_at - make_interval(secs => p_seconds)
    from public.installations i
   where i.id = t.installation_id and i.installation_uuid = p_installation_uuid
$$;

create function tests.make_channel(p_slug text, p_active boolean default true) returns uuid language sql as $$
  insert into public.channels (channel_name, slug, country_code, category_id, is_active)
  values (initcap(replace(p_slug, '-', ' ')), p_slug, 'BN', (select id from public.categories where slug = 'news'), p_active)
  returning id
$$;

create function tests.make_stream(p_channel uuid, p_url text) returns uuid language sql as $$
  insert into public.streams (channel_id, stream_url, source_name, stream_format)
  values (p_channel, p_url, 'test-fixture', 'HLS') returning id
$$;
