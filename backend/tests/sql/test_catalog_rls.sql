-- Catalog publication rules and RLS on channels / streams.
do $$
declare
  v_owner  uuid := tests.make_admin('owner@test', 'owner');
  v_admin  uuid := tests.make_admin('admin@test', 'admin');
  v_rev    uuid := tests.make_admin('reviewer@test', 'reviewer');
  v_user   uuid := tests.make_auth_user('user@test');
  v_ch     uuid := tests.make_channel('test-news');
  v_ch_off uuid := tests.make_channel('inactive-news', false);
  v_s1     uuid;
  v_s2     uuid;
  v_n      integer;
  v_pub    boolean;
begin
  v_s1 := tests.make_stream(v_ch, 'https://cdn.example.org/live/master.m3u8');
  v_s2 := tests.make_stream(v_ch_off, 'https://cdn.example.org/other/master.m3u8');

  -- Freshly discovered streams are invisible to the public.
  perform tests.as_anon();
  select count(*) into v_n from public.channels;
  perform tests.assert_eq(v_n, 0::integer, 'anon sees no unpublished channels');
  select count(*) into v_n from public.streams;
  perform tests.assert_eq(v_n, 0::integer, 'anon sees no unpublished streams');
  perform tests.as_superuser();

  -- Passing playback alone does not publish: licensing review is required.
  perform tests.as_service();
  perform public.record_stream_check(v_s1, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 65, '1280x720', 'HLS', 1500, true, true, 'US', 'ci', null);
  perform tests.as_superuser();
  select is_published into v_pub from public.channels where id = v_ch;
  perform tests.assert_eq(v_pub, false, 'verified but unreviewed stream does not publish the channel');

  -- Permission approval without MFA (aal1) is refused.
  perform tests.as_user(v_admin, 'aal1');
  perform tests.assert_raises(
    format($q$select public.admin_review_stream_permission(%L, 'OFFICIAL_PUBLIC_SOURCE', 'Embedded on broadcaster site')$q$, v_s1),
    'forbidden', 'admin without MFA cannot review permissions');
  perform tests.as_superuser();

  -- A reviewer cannot make licensing decisions.
  perform tests.as_user(v_rev, 'aal2');
  perform tests.assert_raises(
    format($q$select public.admin_review_stream_permission(%L, 'OFFICIAL_PUBLIC_SOURCE', 'x x x')$q$, v_s1),
    'forbidden', 'reviewer cannot approve licensing');
  perform tests.as_superuser();

  perform tests.as_user(v_admin, 'aal2');
  perform public.admin_review_stream_permission(v_s1, 'OFFICIAL_PUBLIC_SOURCE',
    'Broadcaster publishes this HLS URL on its official live page.');
  perform tests.as_superuser();

  select is_published into v_pub from public.channels where id = v_ch;
  perform tests.assert_eq(v_pub, true, 'verified + approved stream publishes an active channel');

  -- Inactive channels stay hidden even with a publishable stream.
  perform tests.as_service();
  perform public.record_stream_check(v_s2, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 61, '720x576', 'HLS', 900, true, true, 'US', 'ci', null);
  perform tests.as_superuser();
  perform tests.as_user(v_admin, 'aal2');
  perform public.admin_review_stream_permission(v_s2, 'EXPLICIT_PERMISSION', 'Written permission on file, ref #12');
  perform tests.as_superuser();
  select is_published into v_pub from public.channels where id = v_ch_off;
  perform tests.assert_eq(v_pub, false, 'inactive channel is not published');

  -- Anonymous catalog read.
  perform tests.as_anon();
  select count(*) into v_n from public.channels;
  perform tests.assert_eq(v_n, 1::integer, 'anon sees exactly the published channel');
  select count(*) into v_n from public.catalog_channels where slug = 'test-news' and availability_status = 'VERIFIED'
                                                       and quality_label = 'HD' and country_name = 'Brunei Darussalam';
  perform tests.assert_eq(v_n, 1::integer, 'catalog view exposes status, quality and country name');
  select count(*) into v_n from public.search_channels('news', null, null, null, 50, 0);
  perform tests.assert_eq(v_n, 1::integer, 'search finds the channel by name');
  select count(*) into v_n from public.search_channels(null, 'bn', 'news', null, 50, 0);
  perform tests.assert_eq(v_n, 1::integer, 'search filters by country and category');
  select count(*) into v_n from public.search_channels('%', null, null, null, 50, 0);
  perform tests.assert_eq(v_n, 0::integer, 'LIKE wildcards in the query are escaped');
  perform tests.assert_raises('select permission_notes from public.streams', 'permission denied',
    'anon cannot read permission evidence');
  perform tests.assert_raises(format('update public.streams set priority = 1 where id = %L', v_s1),
    'permission denied', 'anon cannot modify streams');
  perform tests.assert_raises($q$insert into public.channels (channel_name, slug, country_code) values ('X', 'x', 'BN')$q$,
    'permission denied', 'anon cannot insert channels');
  perform tests.as_superuser();

  -- Signed-in non-admins cannot write the catalog.
  perform tests.as_user(v_user, 'aal2');
  perform tests.assert_raises($q$insert into public.channels (channel_name, slug, country_code) values ('X', 'x', 'BN')$q$,
    'row-level security', 'normal user cannot insert channels');
  update public.streams set priority = 1 where id = v_s1;
  get diagnostics v_n = row_count;
  perform tests.assert_eq(v_n, 0::integer, 'normal user update affects no rows');
  perform tests.assert_raises(format($q$update public.streams set validation_status = 'VERIFIED' where id = %L$q$, v_s2),
    'permission denied', 'nobody can set validation_status through the API');
  perform tests.assert_raises(format($q$update public.channels set is_published = true where id = %L$q$, v_ch_off),
    'permission denied', 'is_published is not writable');
  perform tests.as_superuser();

  -- Admin edits through the API are allowed and audited.
  perform tests.as_user(v_admin, 'aal2');
  update public.channels set description = 'Updated' where id = v_ch;
  get diagnostics v_n = row_count;
  perform tests.assert_eq(v_n, 1::integer, 'admin can edit a channel');
  perform tests.as_superuser();
  select count(*) into v_n from public.admin_audit_logs
   where admin_id = v_admin and target_type = 'channels' and target_id = v_ch::text and action = 'update';
  perform tests.assert_eq(v_n, 1::integer, 'channel edit is audited');

  -- Swapping a stream URL resets verification and licensing (no silent replacement).
  perform tests.as_user(v_admin, 'aal2');
  update public.streams set stream_url = 'https://unrelated.example.net/x.m3u8' where id = v_s1;
  perform tests.as_superuser();
  perform tests.assert_eq((select validation_status from public.streams where id = v_s1),
                          'PENDING_REVIEW'::public.stream_validation_status, 'URL change resets validation');
  perform tests.assert_eq((select permission_status from public.streams where id = v_s1),
                          'UNREVIEWED'::public.stream_permission_status, 'URL change resets permission');
  perform tests.assert_eq((select is_published from public.channels where id = v_ch), false,
                          'channel is unpublished after its only stream changed');

  -- Takedown withdraws a stream immediately.
  perform tests.as_user(v_admin, 'aal2');
  update public.channels set is_active = true where id = v_ch_off;
  perform tests.assert_eq((select is_published from public.channels where id = v_ch_off), true, 'activation publishes');
  perform public.admin_review_stream_permission(v_s2, 'TAKEDOWN', 'Takedown notice received 2026-11-15');
  perform tests.as_superuser();
  perform tests.assert_eq((select validation_status from public.streams where id = v_s2),
                          'REMOVED'::public.stream_validation_status, 'takedown removes the stream');
  perform tests.assert_eq((select is_published from public.channels where id = v_ch_off), false, 'takedown unpublishes');

  -- Duplicate stream URLs are rejected.
  perform tests.assert_raises(format($q$select tests.make_stream(%L, 'https://cdn.example.org/other/master.m3u8')$q$, v_ch),
    'duplicate key', 'duplicate stream URL rejected');

  -- RLS is enabled on every public table.
  select count(*) into v_n from pg_tables where schemaname = 'public' and not rowsecurity;
  perform tests.assert_eq(v_n, 0::integer, 'RLS enabled on all public tables');
end $$;
