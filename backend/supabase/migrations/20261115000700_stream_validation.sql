-- TVKu — stream health state machine, driven only by recorded checks.
--
-- Called by the validation runner (service_role). Every call writes a row to
-- stream_health_logs, so the history is the evidence for every status.
--
-- Rules
--   SUCCESS / TIER_B_PLAYBACK  -> VERIFIED. Requires >= 60 s playback, rendered
--                                 video frames, decoded audio and a successful
--                                 reconnect. Anything less is refused.
--   SUCCESS / TIER_A_PROBE     -> TESTING for streams never verified or OFFLINE;
--                                 DEGRADED / GEO_RESTRICTED streams that were
--                                 verified before return to VERIFIED.
--   TRANSIENT_FAILURE          -> counts; VERIFIED becomes DEGRADED after 2,
--                                 anything becomes OFFLINE at the threshold.
--   PERMANENT_FAILURE          -> never-verified streams go OFFLINE at once;
--                                 verified ones DEGRADED, OFFLINE after 3.
--   GEO_BLOCKED                -> GEO_RESTRICTED
--   UNSUPPORTED                -> REJECTED (the app cannot decode it)
--   REJECTED / REMOVED streams are only logged, never changed automatically.

create function public.record_stream_check(
  p_stream_id                 uuid,
  p_test_type                 public.health_test_type,
  p_outcome                   public.health_outcome,
  p_response_status           integer default null,
  p_playback_duration_seconds numeric default null,
  p_detected_resolution       text default null,
  p_detected_format           public.stream_format default null,
  p_video_frames_rendered     integer default null,
  p_audio_decoded             boolean default null,
  p_reconnect_success         boolean default null,
  p_geo_vantage               text default null,
  p_runner_id                 text default null,
  p_error_message             text default null
)
returns jsonb
language plpgsql
security definer
set search_path = ''
as $$
declare
  v_s          public.streams;
  v_failures   integer;
  v_new        public.stream_validation_status;
  v_offline_at integer := private.config_int('stream_offline_failure_threshold', 5);
  v_min_secs   integer := private.config_int('stream_min_playback_seconds', 60);
  v_w          integer;
  v_h          integer;
begin
  select * into v_s from public.streams s where s.id = p_stream_id for update;
  if not found then
    raise exception 'stream_not_found' using errcode = 'P0002';
  end if;

  if p_outcome = 'SUCCESS' and p_test_type = 'TIER_B_PLAYBACK' and not (
       coalesce(p_playback_duration_seconds, 0) >= v_min_secs
       and coalesce(p_video_frames_rendered, 0) > 0
       and coalesce(p_audio_decoded, false)
       and coalesce(p_reconnect_success, false)) then
    raise exception 'tier_b_success_criteria_not_met' using errcode = '22023';
  end if;
  if p_outcome <> 'SUCCESS' and coalesce(trim(p_error_message), '') = '' then
    raise exception 'error_message_required_for_failures' using errcode = '22023';
  end if;

  v_failures := case when p_outcome = 'SUCCESS' then 0
                     when p_outcome in ('TRANSIENT_FAILURE', 'PERMANENT_FAILURE') then v_s.consecutive_failures + 1
                     else v_s.consecutive_failures end;
  v_new := v_s.validation_status;

  if v_s.validation_status not in ('REJECTED', 'REMOVED') then
    case p_outcome
      when 'SUCCESS' then
        if p_test_type = 'TIER_B_PLAYBACK' then
          v_new := 'VERIFIED';
        elsif v_s.first_verified_at is not null
              and v_s.validation_status in ('VERIFIED', 'DEGRADED', 'GEO_RESTRICTED') then
          v_new := 'VERIFIED';
        elsif v_s.validation_status <> 'VERIFIED' then
          v_new := 'TESTING';
        end if;
      when 'TRANSIENT_FAILURE' then
        if v_failures >= v_offline_at then
          v_new := 'OFFLINE';
        elsif v_s.validation_status = 'VERIFIED' and v_failures >= 2 then
          v_new := 'DEGRADED';
        end if;
      when 'PERMANENT_FAILURE' then
        if v_s.first_verified_at is null or v_failures >= 3 then
          v_new := 'OFFLINE';
        elsif v_s.validation_status in ('VERIFIED', 'GEO_RESTRICTED') then
          v_new := 'DEGRADED';
        end if;
      when 'GEO_BLOCKED' then
        v_new := 'GEO_RESTRICTED';
      when 'UNSUPPORTED' then
        v_new := 'REJECTED';
    end case;
  end if;

  if p_detected_resolution ~ '^[0-9]{1,5}x[0-9]{1,5}$' and p_outcome = 'SUCCESS' then
    v_w := split_part(p_detected_resolution, 'x', 1)::integer;
    v_h := split_part(p_detected_resolution, 'x', 2)::integer;
  end if;

  insert into public.stream_health_logs
    (stream_id, test_type, outcome, response_status, playback_success, playback_duration_seconds,
     detected_resolution, detected_format, video_frames_rendered, audio_decoded, reconnect_success,
     geo_vantage, runner_id, status_before, status_after, error_message)
  values
    (p_stream_id, p_test_type, p_outcome, p_response_status, p_outcome = 'SUCCESS', p_playback_duration_seconds,
     p_detected_resolution, p_detected_format, p_video_frames_rendered, p_audio_decoded, p_reconnect_success,
     upper(p_geo_vantage), p_runner_id, v_s.validation_status, v_new, p_error_message);

  update public.streams
     set validation_status    = v_new,
         consecutive_failures = v_failures,
         last_checked_at      = now(),
         last_success_at      = case when p_outcome = 'SUCCESS' then now() else last_success_at end,
         first_verified_at    = case when v_new = 'VERIFIED' and p_test_type = 'TIER_B_PLAYBACK'
                                     then coalesce(first_verified_at, now()) else first_verified_at end,
         failure_reason       = case when p_outcome = 'SUCCESS' then null else left(p_error_message, 2000) end,
         stream_format        = case when stream_format = 'UNKNOWN' and p_detected_format is not null
                                     then p_detected_format else stream_format end,
         resolution_width     = coalesce(v_w, resolution_width),
         resolution_height    = coalesce(v_h, resolution_height),
         quality_label        = case when v_h is null then quality_label
                                     when v_h >= 2160 then 'UHD'
                                     when v_h >= 1080 then 'FHD'
                                     when v_h >= 720  then 'HD'
                                     else 'SD' end,
         is_preferred         = case when v_new in ('REJECTED', 'REMOVED') then false else is_preferred end
   where id = p_stream_id;

  return jsonb_build_object('stream_id', p_stream_id, 'status_before', v_s.validation_status,
                            'status_after', v_new, 'consecutive_failures', v_failures);
end;
$$;

-- Streams the runner should check next, with exponential backoff for failing
-- streams so permanently broken URLs are not hammered.
create function public.streams_due_for_check(p_limit integer default 100)
returns table (
  stream_id            uuid,
  channel_id           uuid,
  stream_url           text,
  stream_format        public.stream_format,
  validation_status    public.stream_validation_status,
  consecutive_failures integer,
  first_verified_at    timestamptz,
  last_checked_at      timestamptz
)
language sql
stable
security definer
set search_path = ''
as $$
  select s.id, s.channel_id, s.stream_url, s.stream_format, s.validation_status,
         s.consecutive_failures, s.first_verified_at, s.last_checked_at
    from public.streams s
   where s.validation_status not in ('REJECTED', 'REMOVED')
     and s.permission_status not in ('DENIED', 'TAKEDOWN')
     and (s.last_checked_at is null
          or s.last_checked_at < now() - case
               when s.validation_status = 'VERIFIED' then interval '6 hours'
               when s.validation_status = 'TESTING'  then interval '1 hour'
               else interval '6 hours' * power(2, least(s.consecutive_failures, 5))
             end)
   order by s.is_published desc, s.last_checked_at nulls first
   limit greatest(1, least(p_limit, 1000));
$$;

revoke all on function public.record_stream_check(uuid, public.health_test_type, public.health_outcome, integer, numeric, text,
  public.stream_format, integer, boolean, boolean, text, text, text) from public, anon, authenticated;
grant execute on function public.record_stream_check(uuid, public.health_test_type, public.health_outcome, integer, numeric, text,
  public.stream_format, integer, boolean, boolean, text, text, text) to service_role;
revoke all on function public.streams_due_for_check(integer) from public, anon, authenticated;
grant execute on function public.streams_due_for_check(integer) to service_role;
