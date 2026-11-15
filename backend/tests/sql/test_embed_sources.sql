-- Official embedded sources (YouTube live via the official player).
-- Isolate from the discovery seed (rolled back with the test transaction).
delete from public.channels where external_source in ('iptv-org', 'tvku-curated');

do $$
declare
  v_admin uuid := tests.make_admin('embed-admin@test', 'admin');
  v_ch    uuid := tests.make_channel('kompas-tv-test');
  v_hls   uuid;
  v_yt    uuid;
  v_r     jsonb;
  v_n     integer;
  v_cid   constant text := 'UC5BMIWZe9isJXLZZWPWvBlg';  -- format-valid fixture id
  v_vid   constant text := 'dQw4w9WgXcQ';
begin
  v_hls := tests.make_stream(v_ch, 'https://edge.example.id/live/master.m3u8');

  -- Embed rows need a provider + channel reference and a canonical YouTube URL.
  perform tests.assert_raises(format($q$insert into public.streams (channel_id, stream_url, source_name, stream_format)
    values (%L, 'https://www.youtube.com/@tvkufixture', 'YouTube', 'YOUTUBE_EMBED')$q$, v_ch),
    'streams_embed_fields_consistent', 'embed without provider/ref rejected');
  perform tests.assert_raises(format($q$insert into public.streams (channel_id, stream_url, source_name, stream_format, embed_provider, embed_channel_ref)
    values (%L, 'https://evil.example/@tvkufixture', 'YouTube', 'YOUTUBE_EMBED', 'youtube', '@tvkufixture')$q$, v_ch),
    'streams_embed_url', 'embed URL must be a canonical youtube.com channel URL');
  perform tests.assert_raises(format($q$insert into public.streams (channel_id, stream_url, source_name, stream_format, embed_provider, embed_channel_ref)
    values (%L, 'https://www.youtube.com/@x', 'YouTube', 'YOUTUBE_EMBED', 'youtube', '@x')$q$, v_ch),
    'check constraint', 'too-short handle rejected');

  insert into public.streams (channel_id, stream_url, source_name, source_url, stream_format, embed_provider, embed_channel_ref)
  values (v_ch, 'https://www.youtube.com/@tvkufixture', 'KompasTV official YouTube', 'https://www.youtube.com/@tvkufixture',
          'YOUTUBE_EMBED', 'youtube', '@tvkufixture')
  returning id into v_yt;

  perform tests.assert_raises(format($q$insert into public.streams (channel_id, stream_url, source_name, stream_format, embed_provider, embed_channel_ref)
    values (%L, 'https://www.youtube.com/@tvkufixture', 'dup', 'YOUTUBE_EMBED', 'youtube', '@tvkufixture')$q$, v_ch),
    'duplicate key', 'same YouTube channel cannot be added twice');

  perform tests.as_service();

  -- Evidence types cannot be mixed.
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 60, '1280x720', 'HLS', 10, true, true)$q$, v_yt),
    'test_type_not_applicable_to_embed', 'Media3 playback evidence is not accepted for embeds');
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'EMBED_STATUS_CHECK', 'SUCCESS', p_embed_channel_id => %L, p_embed_video_id => %L, p_embeddable => true)$q$, v_hls, v_cid, v_vid),
    'embed_check_on_non_embed_stream', 'embed evidence is not accepted for HLS streams');

  -- Success requires: embeddable, a live video id and a channel id.
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'EMBED_STATUS_CHECK', 'SUCCESS', p_embed_channel_id => %L, p_embed_video_id => %L, p_embeddable => false)$q$, v_yt, v_cid, v_vid),
    'embed_success_criteria_not_met', 'non-embeddable video cannot verify');
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'EMBED_STATUS_CHECK', 'SUCCESS', p_embed_channel_id => %L, p_embeddable => true)$q$, v_yt, v_cid),
    'embed_success_criteria_not_met', 'no live video cannot verify');

  v_r := public.record_stream_check(v_yt, 'EMBED_STATUS_CHECK', 'SUCCESS', 200,
           p_runner_id => 'yt-checker', p_embed_channel_id => v_cid, p_embed_video_id => v_vid, p_embeddable => true);
  perform tests.assert_eq(v_r ->> 'status_after', 'VERIFIED', 'live + embeddable -> VERIFIED');
  perform tests.assert_eq((select embed_video_id from public.streams where id = v_yt), v_vid, 'current live video stored');
  perform tests.assert_eq((select embed_channel_id from public.streams where id = v_yt), v_cid, 'resolved channel id pinned');

  -- Once pinned, a different channel id is refused (handle hijack / rename protection).
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'EMBED_STATUS_CHECK', 'SUCCESS', p_embed_channel_id => %L, p_embed_video_id => %L, p_embeddable => true)$q$, v_yt, 'UCaaaaaaaaaaaaaaaaaaaaaa', v_vid),
    'embed_success_criteria_not_met', 'channel id must match the pinned channel');

  -- Not live right now: transient, and the stale video id is cleared.
  v_r := public.record_stream_check(v_yt, 'EMBED_STATUS_CHECK', 'TRANSIENT_FAILURE', p_error_message => 'no live broadcast at check time');
  perform tests.assert_eq(v_r ->> 'status_after', 'VERIFIED', 'one missed live check tolerated');
  perform tests.assert((select embed_video_id is null from public.streams where id = v_yt), 'video id cleared when not live');

  -- Embedding disabled by the owner -> REJECTED.
  v_r := public.record_stream_check(v_yt, 'EMBED_STATUS_CHECK', 'UNSUPPORTED', p_error_message => 'embedding disabled by channel owner');
  perform tests.assert_eq(v_r ->> 'status_after', 'REJECTED', 'embedding disabled -> REJECTED');

  -- Due list exposes embed metadata for the runner.
  perform tests.as_superuser();
  update public.streams set validation_status = 'VERIFIED', last_checked_at = now() - interval '31 minutes' where id = v_yt;
  perform tests.as_service();
  select count(*) into v_n from public.streams_due_for_check(100)
   where stream_id = v_yt and embed_provider = 'youtube' and embed_channel_ref = '@tvkufixture';
  perform tests.assert_eq(v_n, 1::integer, 'verified embeds are rechecked every 30 minutes');
  perform tests.as_superuser();

  -- Publication still requires a licensing decision.
  perform tests.assert_eq((select is_published from public.streams where id = v_yt), false, 'unreviewed embed is not published');
  perform tests.as_user(v_admin, 'aal2');
  perform public.admin_review_stream_permission(v_yt, 'OFFICIAL_PUBLIC_SOURCE',
    'Official broadcaster YouTube channel; embedding enabled by owner; YouTube API ToS apply.');
  perform tests.as_superuser();
  perform tests.assert_eq((select is_published from public.channels where id = v_ch), true, 'verified + approved embed publishes channel');

  -- Re-pointing the embed resets everything.
  perform tests.as_user(v_admin, 'aal2');
  update public.streams set embed_channel_ref = '@someoneelse', stream_url = 'https://www.youtube.com/@someoneelse' where id = v_yt;
  perform tests.as_superuser();
  perform tests.assert_eq((select permission_status from public.streams where id = v_yt),
                          'UNREVIEWED'::public.stream_permission_status, 'new channel ref resets permission');
  perform tests.assert((select embed_channel_id is null and embed_video_id is null and first_verified_at is null
                          from public.streams where id = v_yt), 'new channel ref clears pinned ids');
  perform tests.assert_eq((select is_published from public.channels where id = v_ch), false, 'channel unpublished after re-point');

  -- Anonymous clients can read the embed fields of published streams only.
  perform tests.as_anon();
  perform tests.assert_raises('select permission_notes from public.streams', 'permission denied', 'notes still hidden');
  perform tests.as_superuser();
end $$;
