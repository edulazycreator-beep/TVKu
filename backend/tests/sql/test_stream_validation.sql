-- Stream health state machine.
-- Isolate from the discovery seed (rolled back with the test transaction).
delete from public.channels where external_source in ('iptv-org', 'tvku-curated');

do $$
declare
  v_ch  uuid := tests.make_channel('state-machine');
  v_s   uuid;
  v_s2  uuid;
  v_s3  uuid;
  v_r   jsonb;
  v_n   integer;
  v_rev uuid := tests.make_admin('val-rev@test', 'reviewer');
begin
  v_s  := tests.make_stream(v_ch, 'https://a.example.org/1.m3u8');
  v_s2 := tests.make_stream(v_ch, 'https://a.example.org/2.m3u8');
  v_s3 := tests.make_stream(v_ch, 'https://a.example.org/3.mpd');

  perform tests.as_anon();
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_B_PLAYBACK', 'SUCCESS')$q$, v_s),
    'permission denied', 'anon cannot record checks');
  perform tests.as_superuser();

  perform tests.as_service();

  -- Fabricated or incomplete playback success is refused.
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 30, '1920x1080', 'HLS', 500, true, true)$q$, v_s),
    'tier_b_success_criteria_not_met', 'playback shorter than 60 s refused');
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 60, '1920x1080', 'HLS', 0, true, true)$q$, v_s),
    'tier_b_success_criteria_not_met', 'no rendered frames refused');
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 60, '1920x1080', 'HLS', 900, false, true)$q$, v_s),
    'tier_b_success_criteria_not_met', 'no decoded audio refused');
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 60, '1920x1080', 'HLS', 900, true, false)$q$, v_s),
    'tier_b_success_criteria_not_met', 'failed reconnect refused');
  perform tests.assert_raises(format($q$select public.record_stream_check(%L, 'TIER_A_PROBE', 'TRANSIENT_FAILURE')$q$, v_s),
    'error_message_required', 'failures must carry a reason');

  -- Tier A success -> TESTING; HTTP success alone never verifies.
  v_r := public.record_stream_check(v_s, 'TIER_A_PROBE', 'SUCCESS', 200, null, '1920x1080', 'HLS');
  perform tests.assert_eq(v_r ->> 'status_after', 'TESTING', 'probe success -> TESTING');

  -- Tier B success -> VERIFIED with resolution / quality.
  v_r := public.record_stream_check(v_s, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 62.5, '1920x1080', 'HLS', 1500, true, true, 'us', 'runner-1');
  perform tests.assert_eq(v_r ->> 'status_after', 'VERIFIED', 'playback success -> VERIFIED');
  perform tests.assert_eq((select quality_label from public.streams where id = v_s), 'FHD', 'quality derived from detected height');
  perform tests.assert((select first_verified_at is not null from public.streams where id = v_s), 'first_verified_at set');

  -- Transient failures: one is tolerated, two degrade, five take it offline.
  v_r := public.record_stream_check(v_s, 'TIER_A_PROBE', 'TRANSIENT_FAILURE', null, null, null, null, null, null, null, null, null, 'DNS timeout');
  perform tests.assert_eq(v_r ->> 'status_after', 'VERIFIED', 'single transient failure tolerated');
  v_r := public.record_stream_check(v_s, 'TIER_A_PROBE', 'TRANSIENT_FAILURE', 503, null, null, null, null, null, null, null, null, 'HTTP 503');
  perform tests.assert_eq(v_r ->> 'status_after', 'DEGRADED', 'two transient failures -> DEGRADED');

  -- Recovery from DEGRADED with a successful probe (stream verified before).
  v_r := public.record_stream_check(v_s, 'TIER_A_PROBE', 'SUCCESS', 200);
  perform tests.assert_eq(v_r ->> 'status_after', 'VERIFIED', 'recovered after successful retest');
  perform tests.assert_eq((v_r ->> 'consecutive_failures')::integer, 0, 'failure counter reset');

  for i in 1..5 loop
    v_r := public.record_stream_check(v_s, 'TIER_A_PROBE', 'TRANSIENT_FAILURE', null, null, null, null, null, null, null, null, null, 'timeout');
  end loop;
  perform tests.assert_eq(v_r ->> 'status_after', 'OFFLINE', 'five consecutive failures -> OFFLINE');

  -- OFFLINE needs a probe then a real playback test to come back.
  v_r := public.record_stream_check(v_s, 'TIER_A_PROBE', 'SUCCESS', 200);
  perform tests.assert_eq(v_r ->> 'status_after', 'TESTING', 'offline stream must be re-verified by playback');
  v_r := public.record_stream_check(v_s, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 60, '1280x720', 'HLS', 1400, true, true);
  perform tests.assert_eq(v_r ->> 'status_after', 'VERIFIED', 'restored after playback retest');

  -- Never-verified stream with a 404 goes OFFLINE immediately.
  v_r := public.record_stream_check(v_s2, 'TIER_A_PROBE', 'PERMANENT_FAILURE', 404, null, null, null, null, null, null, null, null, 'HTTP 404');
  perform tests.assert_eq(v_r ->> 'status_after', 'OFFLINE', 'unverified 404 -> OFFLINE');

  -- Geo-blocking and unsupported codecs.
  v_r := public.record_stream_check(v_s3, 'TIER_A_PROBE', 'GEO_BLOCKED', 403, null, null, null, null, null, null, 'US', null, 'HTTP 403 geo');
  perform tests.assert_eq(v_r ->> 'status_after', 'GEO_RESTRICTED', 'geo block recorded');
  v_r := public.record_stream_check(v_s3, 'TIER_B_PLAYBACK', 'UNSUPPORTED', 200, 0, null, 'DASH', 0, false, false, 'US', null, 'codec hev1 not supported');
  perform tests.assert_eq(v_r ->> 'status_after', 'REJECTED', 'unsupported -> REJECTED');
  v_r := public.record_stream_check(v_s3, 'TIER_B_PLAYBACK', 'SUCCESS', 200, 60, '1280x720', 'DASH', 900, true, true);
  perform tests.assert_eq(v_r ->> 'status_after', 'REJECTED', 'rejected streams are never auto-restored');

  -- Every check is logged with before/after status.
  select count(*) into v_n from public.stream_health_logs where stream_id = v_s;
  perform tests.assert_eq(v_n, 12::integer, 'every recorded check is logged');

  -- Backoff: the just-checked and rejected streams are not due.
  select count(*) into v_n from public.streams_due_for_check(100) where stream_id in (v_s, v_s2, v_s3);
  perform tests.assert_eq(v_n, 0::integer, 'recently checked / rejected streams not due');
  perform tests.as_superuser();
  update public.streams set last_checked_at = now() - interval '7 hours' where id in (v_s, v_s2);
  perform tests.as_service();
  select count(*) into v_n from public.streams_due_for_check(100) where stream_id = v_s;
  perform tests.assert_eq(v_n, 1::integer, 'verified stream rechecked after 6 h');
  select count(*) into v_n from public.streams_due_for_check(100) where stream_id = v_s2;
  perform tests.assert_eq(v_n, 0::integer, 'failing stream backs off exponentially');
  perform tests.as_superuser();

  -- Reviewers can withdraw or re-queue, but never mark VERIFIED by hand.
  perform tests.as_user(v_rev, 'aal2');
  perform tests.assert_raises(format($q$select public.admin_set_stream_status(%L, 'VERIFIED', 'trust me')$q$, v_s2),
    'status_not_settable_manually', 'VERIFIED cannot be set manually');
  v_r := public.admin_set_stream_status(v_s3, 'PENDING_REVIEW', 'New codec support in app 1.2');
  perform tests.assert_eq(v_r ->> 'validation_status', 'PENDING_REVIEW', 'reviewer can re-queue a stream');
  perform tests.as_superuser();
end $$;
