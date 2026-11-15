-- TVKu — Row Level Security, privileges, public catalog views and search.
--
-- Supabase grants ALL on new public tables to anon and authenticated by
-- default. TVKu starts from zero and grants only what each role needs.

do $$
declare
  t text;
begin
  for t in select tablename from pg_tables where schemaname = 'public' loop
    execute format('alter table public.%I enable row level security', t);
    execute format('revoke all on public.%I from anon, authenticated', t);
  end loop;
end;
$$;

alter table private.rate_limits enable row level security;
revoke all on private.rate_limits from anon, authenticated;

-- ---------------------------------------------------------------------------
-- Reference data: readable by everyone, writable by admins
-- ---------------------------------------------------------------------------

grant select on public.countries, public.categories, public.languages to anon, authenticated;
grant insert, update, delete on public.countries, public.categories, public.languages to authenticated;

create policy countries_read  on public.countries  for select to anon, authenticated using (true);
create policy categories_read on public.categories for select to anon, authenticated using (true);
create policy languages_read  on public.languages  for select to anon, authenticated using (true);

create policy countries_admin_write  on public.countries  for all to authenticated
  using (private.has_admin_role('admin')) with check (private.has_admin_role('admin'));
create policy categories_admin_write on public.categories for all to authenticated
  using (private.has_admin_role('admin')) with check (private.has_admin_role('admin'));
create policy languages_admin_write  on public.languages  for all to authenticated
  using (private.has_admin_role('admin')) with check (private.has_admin_role('admin'));

-- ---------------------------------------------------------------------------
-- Channels
-- ---------------------------------------------------------------------------

grant select on public.channels, public.channel_languages to anon, authenticated;
grant insert (channel_name, slug, country_code, category_id, logo_url, description, is_active,
              external_source, external_id)
  on public.channels to authenticated;
grant update (channel_name, slug, country_code, category_id, logo_url, description, is_active)
  on public.channels to authenticated;
grant delete on public.channels to authenticated;
grant insert, delete on public.channel_languages to authenticated;

create policy channels_public_read on public.channels for select to anon, authenticated
  using (is_published or private.has_admin_role('reviewer'));
create policy channels_admin_insert on public.channels for insert to authenticated
  with check (private.has_admin_role('admin'));
create policy channels_admin_update on public.channels for update to authenticated
  using (private.has_admin_role('admin')) with check (private.has_admin_role('admin'));
create policy channels_admin_delete on public.channels for delete to authenticated
  using (private.has_admin_role('admin'));

create policy channel_languages_read on public.channel_languages for select to anon, authenticated
  using (exists (select 1 from public.channels c where c.id = channel_id));  -- inherits channels RLS
create policy channel_languages_admin_insert on public.channel_languages for insert to authenticated
  with check (private.has_admin_role('admin'));
create policy channel_languages_admin_delete on public.channel_languages for delete to authenticated
  using (private.has_admin_role('admin'));

-- ---------------------------------------------------------------------------
-- Streams: health/permission columns are never writable through the API
-- ---------------------------------------------------------------------------

grant select (id, channel_id, stream_url, stream_format, quality_label, resolution_width, resolution_height,
              source_name, source_url, permission_status, validation_status, consecutive_failures,
              first_verified_at, last_checked_at, last_success_at, failure_reason, priority, is_preferred,
              is_published, created_at, updated_at)
  on public.streams to anon, authenticated;
grant insert (channel_id, stream_url, stream_format, quality_label, source_name, source_url, priority)
  on public.streams to authenticated;
grant update (channel_id, stream_url, stream_format, quality_label, source_name, source_url, priority)
  on public.streams to authenticated;
grant delete on public.streams to authenticated;

create policy streams_public_read on public.streams for select to anon, authenticated
  using (is_published or private.has_admin_role('reviewer'));
create policy streams_admin_insert on public.streams for insert to authenticated
  with check (private.has_admin_role('admin'));
create policy streams_admin_update on public.streams for update to authenticated
  using (private.has_admin_role('admin')) with check (private.has_admin_role('admin'));
create policy streams_admin_delete on public.streams for delete to authenticated
  using (private.has_admin_role('admin'));

grant select on public.stream_health_logs to authenticated;
create policy stream_health_logs_admin_read on public.stream_health_logs for select to authenticated
  using (private.has_admin_role('reviewer'));

-- ---------------------------------------------------------------------------
-- Accounts and entitlements: admins read, nobody writes through the API
-- ---------------------------------------------------------------------------

grant select, update (display_name) on public.users to authenticated;
create policy users_self_read on public.users for select to authenticated
  using (auth_user_id = auth.uid() or private.has_admin_role('reviewer'));
create policy users_self_update on public.users for update to authenticated
  using (auth_user_id = auth.uid()) with check (auth_user_id = auth.uid());

grant select on public.admin_users to authenticated;
create policy admin_users_read on public.admin_users for select to authenticated
  using (auth_user_id = auth.uid() or private.has_admin_role('owner'));

-- secret_hash and device_key_hash are never readable through the API.
grant select (id, installation_uuid, public_code, user_id, first_registered_at, last_seen_at, app_version,
              platform, integrity_verdict, blocked_at, block_reason, created_at)
  on public.installations to authenticated;
create policy installations_admin_read on public.installations for select to authenticated
  using (private.has_admin_role('reviewer'));

grant select (id, installation_id, started_at, expires_at, status, inherited_from, created_at)
  on public.trial_entitlements to authenticated;
create policy trial_entitlements_admin_read on public.trial_entitlements for select to authenticated
  using (private.has_admin_role('reviewer'));

grant select on public.donations to authenticated;
create policy donations_admin_read on public.donations for select to authenticated
  using (private.has_admin_role('admin'));

grant select on public.premium_entitlements to authenticated;
create policy premium_admin_read on public.premium_entitlements for select to authenticated
  using (private.has_admin_role('admin'));

grant select on public.admin_audit_logs to authenticated;
create policy audit_admin_read on public.admin_audit_logs for select to authenticated
  using (private.has_admin_role('admin'));

-- ---------------------------------------------------------------------------
-- Favorites of signed-in users
-- ---------------------------------------------------------------------------

grant select, insert, delete on public.user_favorites to authenticated;
create policy favorites_own_read on public.user_favorites for select to authenticated
  using (user_id = private.current_user_row_id());
create policy favorites_own_insert on public.user_favorites for insert to authenticated
  with check (user_id = private.current_user_row_id());
create policy favorites_own_delete on public.user_favorites for delete to authenticated
  using (user_id = private.current_user_row_id());

-- ---------------------------------------------------------------------------
-- Configuration: public keys readable by the app, all keys by admins.
-- Writes only through public.admin_set_config.
-- ---------------------------------------------------------------------------

grant select (config_key, config_value, is_public, description, updated_at) on public.app_config to anon, authenticated;
create policy app_config_public_read on public.app_config for select to anon, authenticated
  using (is_public or private.has_admin_role('reviewer'));

-- ---------------------------------------------------------------------------
-- Public catalog view and search
-- ---------------------------------------------------------------------------

create view public.catalog_channels
with (security_invoker = true)
as
select c.id,
       c.channel_name,
       c.slug,
       c.country_code,
       co.name   as country_name,
       co.region as region,
       cat.slug  as category_slug,
       cat.name  as category_name,
       coalesce((select array_agg(cl.language_code order by cl.language_code)
                   from public.channel_languages cl where cl.channel_id = c.id), '{}') as language_codes,
       c.logo_url,
       c.description,
       best.validation_status as availability_status,
       best.quality_label,
       best.last_success_at,
       c.updated_at
  from public.channels c
  join public.countries co on co.code = c.country_code
  left join public.categories cat on cat.id = c.category_id
  left join lateral (
        select s.validation_status, s.quality_label, s.last_success_at
          from public.streams s
         where s.channel_id = c.id and s.is_published
         order by (s.validation_status = 'VERIFIED') desc, s.is_preferred desc, s.priority, s.last_success_at desc nulls last
         limit 1) best on true
 where c.is_published;

grant select on public.catalog_channels to anon, authenticated;

create function public.search_channels(
  p_query         text default null,
  p_country_code  text default null,
  p_category_slug text default null,
  p_language_code text default null,
  p_limit         integer default 50,
  p_offset        integer default 0
)
returns setof public.catalog_channels
language sql
stable
security invoker
set search_path = ''
as $$
  select cc.*
    from public.catalog_channels cc
   cross join (select replace(replace(replace(trim(coalesce(p_query, '')), '\', '\\'), '%', '\%'), '_', '\_') as pat) q
   where (p_country_code  is null or cc.country_code = upper(p_country_code))
     and (p_category_slug is null or cc.category_slug = lower(p_category_slug))
     and (p_language_code is null or lower(p_language_code) = any (cc.language_codes))
     and (q.pat = ''
          or cc.channel_name operator(extensions.%) p_query
          or cc.channel_name ilike '%' || q.pat || '%'
          or cc.country_name ilike q.pat || '%')
   order by case when p_query is null then 0
                 else extensions.similarity(cc.channel_name, p_query) end desc,
            cc.channel_name
   limit greatest(1, least(coalesce(p_limit, 50), 200))
  offset greatest(0, coalesce(p_offset, 0));
$$;

grant execute on function public.search_channels(text, text, text, text, integer, integer) to anon, authenticated;

-- ---------------------------------------------------------------------------
-- Scheduled housekeeping (only where pg_cron is installed, e.g. on Supabase)
-- ---------------------------------------------------------------------------

do $$
begin
  if exists (select 1 from pg_extension where extname = 'pg_cron') then
    perform cron.schedule('tvku-expire-entitlements', '*/5 * * * *',
                          'select public.maintenance_expire_entitlements()');
  end if;
end;
$$;
