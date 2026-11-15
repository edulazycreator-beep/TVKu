-- "Watch on the official platform" links (Vidio, RCTI+, broadcaster sites).
delete from public.channels where external_source in ('iptv-org', 'tvku-curated');

do $$
declare
  v_admin uuid := tests.make_admin('link-admin@test', 'admin');
  v_ch    uuid := tests.make_channel('indosiar-test');
  v_link  uuid;
  v_r     jsonb;
  v_n     integer;
begin
  perform tests.assert_raises(format($q$insert into public.streams (channel_id, stream_url, source_name, stream_format)
    values (%L, 'https://www.vidio.com/live/indosiar-fixture', 'Vidio', 'EXTERNAL_LINK')$q$, v_ch),
    'streams_external_link_fields', 'link needs a platform name');
  perform tests.assert_raises(format($q$insert into public.streams (channel_id, stream_url, source_name, stream_format, external_platform)
    values (%L, 'http://www.vidio.com/live/indosiar-fixture', 'Vidio', 'EXTERNAL_LINK', 'Vidio')$q$, v_ch),
    'streams_external_link_https', 'links must be https');

  insert into public.streams (channel_id, stream_url, source_name, stream_format, external_platform)
  values (v_ch, 'https://www.vidio.com/live/indosiar-fixture', 'Official platform', 'EXTERNAL_LINK', 'Vidio')
  returning id into v_link;

  perform tests.as_service();
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 60, '1280x720', 'HLS', 10, true, true)$q$, v_link),
    'test_type_not_applicable_to_link', 'playback evidence not accepted for links');
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'LINK_CHECK', 'SUCCESS', 404)$q$, v_link),
    'link_success_requires_http_2xx_3xx', 'a 404 cannot be a successful link check');

  v_r := public.record_stream_check(v_link, 'LINK_CHECK', 'SUCCESS', 200, p_runner_id => 'link-checker');
  perform tests.assert_eq(v_r ->> 'status_after', 'VERIFIED', 'reachable official page -> VERIFIED');

  v_r := public.record_stream_check(v_link, 'LINK_CHECK', 'PERMANENT_FAILURE', 404, p_error_message => 'HTTP 404');
  perform tests.assert_eq(v_r ->> 'status_after', 'DEGRADED', 'broken link degrades');
  perform tests.as_superuser();

  -- Other stream kinds reject link evidence.
  perform tests.as_service();
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'LINK_CHECK', 'SUCCESS', 200)$q$,
    tests.make_stream(v_ch, 'https://cdn.example.id/live.m3u8')), 'link_check_on_non_link_stream', 'HLS rows reject link checks');
  perform tests.as_superuser();

  -- Still needs admin approval to appear; then the platform name is public.
  perform tests.assert_eq((select is_published from public.channels where id = v_ch), false, 'unreviewed link not published');
  perform tests.as_user(v_admin, 'aal2');
  perform public.admin_review_stream_permission(v_link, 'OFFICIAL_PUBLIC_SOURCE', 'Official Vidio live page for the channel; app only opens the link.');
  perform tests.as_superuser();
  perform tests.assert_eq((select is_published from public.channels where id = v_ch), true, 'approved link publishes channel');
  perform tests.as_anon();
  select count(*) into v_n from public.streams where id = v_link and external_platform = 'Vidio' and stream_format = 'EXTERNAL_LINK';
  perform tests.assert_eq(v_n, 1::integer, 'app can read platform name for the label');
  perform tests.as_superuser();
end $$;
