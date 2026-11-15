-- The discovery seed adds leads only: nothing in it may be visible or verified.
do $$
declare
  v_n integer;
begin
  select count(*) into v_n from public.channels where external_source in ('iptv-org', 'tvku-curated');
  perform tests.assert(v_n >= 900, format('discovery seed loaded (%s channels)', v_n));
  select count(*) into v_n from public.channels where country_code = 'ID' and external_source in ('iptv-org', 'tvku-curated');
  perform tests.assert(v_n >= 30, format('Indonesian leads present (%s)', v_n));
  select count(*) into v_n from public.streams where stream_format = 'YOUTUBE_EMBED';
  perform tests.assert(v_n >= 5, format('official YouTube embed leads present (%s)', v_n));
  select count(*) into v_n from public.streams s join public.channels c on c.id = s.channel_id
   where c.category_id = (select id from public.categories where slug = 'sports');
  perform tests.assert(v_n >= 10, format('sports leads present (%s)', v_n));
  select count(*) into v_n from public.streams where stream_format = 'EXTERNAL_LINK' and external_platform is not null;
  perform tests.assert(v_n >= 10, format('official platform links present (%s)', v_n));

  select count(*) into v_n from public.channels where external_source in ('iptv-org', 'tvku-curated') and (is_active or is_published);
  perform tests.assert_eq(v_n, 0::integer, 'no seeded channel is active or published');
  select count(*) into v_n from public.streams
   where validation_status <> 'DISCOVERED' or permission_status <> 'UNREVIEWED' or first_verified_at is not null or is_published;
  perform tests.assert_eq(v_n, 0::integer, 'every seeded stream is DISCOVERED / UNREVIEWED and unverified');

  -- Premium sports brands and event streams must not be in the seed.
  select count(*) into v_n from public.channels
   where channel_name ~* '\m(espn|bein|dazn|tnt sports|eurosport|fox sports|sky sports|formula 1|nhl|nba|nfl|ufc)\M';
  perform tests.assert_eq(v_n, 0::integer, 'no premium-rights sports channels seeded');
  select count(*) into v_n from public.streams where stream_url ~ '^https?://\d{1,3}(\.\d{1,3}){3}[:/]' or stream_url like '%/promo/%';
  perform tests.assert_eq(v_n, 0::integer, 'no bare-IP relays or promo/event streams seeded');

  perform tests.as_anon();
  select count(*) into v_n from public.catalog_channels;
  perform tests.assert_eq(v_n, 0::integer, 'anonymous catalog is empty after seeding leads');
  perform tests.as_superuser();
end $$;
