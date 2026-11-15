-- TVKu — "watch on the official platform" links.
--
-- Some broadcasters (e.g. Indosiar, SCTV, ANTV, RCTI, MNCTV, GTV, Trans TV,
-- Trans7) stream live only inside their own platforms (Vidio, RCTI+ / MNC
-- Now, their websites), behind login, DRM and/or regional limits. TVKu does
-- not play, embed or re-stream those. It may list the channel and open the
-- official platform when the user taps it. Such rows are EXTERNAL_LINK
-- streams: never played in-app, labelled with the platform name, and still
-- subject to admin review before they appear.
--
-- Health evidence for a link is LINK_CHECK: the official page answered with
-- HTTP 2xx/3xx at check time. It says nothing about playback.

alter table public.streams
  add column external_platform text check (external_platform is null or length(external_platform) between 2 and 60),
  add constraint streams_external_link_fields check (
    (stream_format = 'EXTERNAL_LINK') = (external_platform is not null)
  ),
  add constraint streams_external_link_https check (
    stream_format <> 'EXTERNAL_LINK' or stream_url ~ '^https://'
  );

create or replace function public.record_stream_check(
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
  p_error_message             text default null,
  p_embed_channel_id          text default null,
  p_embed_video_id            text default null,
  p_embeddable                boolean default null
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
  v_is_embed   boolean;
  v_is_link    boolean;
  v_w          integer;
  v_h          integer;
begin
  select * into v_s from public.streams s where s.id = p_stream_id for update;
  if not found then
    raise exception 'stream_not_found' using errcode = 'P0002';
  end if;
  v_is_embed := v_s.stream_format = 'YOUTUBE_EMBED';
  v_is_link  := v_s.stream_format = 'EXTERNAL_LINK';

  if v_is_link and p_test_type <> 'LINK_CHECK' then
    raise exception 'test_type_not_applicable_to_link: %', p_test_type using errcode = '22023';
  end if;
  if not v_is_link and p_test_type = 'LINK_CHECK' then
    raise exception 'link_check_on_non_link_stream' using errcode = '22023';
  end if;
  if p_outcome = 'SUCCESS' and p_test_type = 'LINK_CHECK' and coalesce(p_response_status, 0) not between 200 and 399 then
    raise exception 'link_success_requires_http_2xx_3xx' using errcode = '22023';
  end if;

  -- Each source kind has exactly one kind of evidence.
  if v_is_embed and p_test_type <> 'EMBED_STATUS_CHECK' then
    raise exception 'test_type_not_applicable_to_embed: %', p_test_type using errcode = '22023';
  end if;
  if not v_is_embed and p_test_type = 'EMBED_STATUS_CHECK' then
    raise exception 'embed_check_on_non_embed_stream' using errcode = '22023';
  end if;

  if p_outcome = 'SUCCESS' and p_test_type = 'TIER_B_PLAYBACK' and not (
       coalesce(p_playback_duration_seconds, 0) >= v_min_secs
       and coalesce(p_video_frames_rendered, 0) > 0
       and coalesce(p_audio_decoded, false)
       and coalesce(p_reconnect_success, false)) then
    raise exception 'tier_b_success_criteria_not_met' using errcode = '22023';
  end if;
  if p_outcome = 'SUCCESS' and p_test_type = 'EMBED_STATUS_CHECK' and not (
       coalesce(p_embeddable, false)
       and coalesce(p_embed_video_id, '') ~ '^[A-Za-z0-9_-]{11}$'
       and coalesce(p_embed_channel_id, '') ~ '^UC[A-Za-z0-9_-]{22}$'
       and (v_s.embed_channel_ref !~ '^UC' or v_s.embed_channel_ref = p_embed_channel_id)
       and (v_s.embed_channel_id is null or v_s.embed_channel_id = p_embed_channel_id)) then
    raise exception 'embed_success_criteria_not_met' using errcode = '22023';
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
        if p_test_type in ('TIER_B_PLAYBACK', 'EMBED_STATUS_CHECK', 'LINK_CHECK') then
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
        v_new := 'REJECTED';   -- includes "embedding disabled by the owner"
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
     upper(p_geo_vantage), p_runner_id, v_s.validation_status, v_new,
     case when p_test_type = 'EMBED_STATUS_CHECK' and p_embed_video_id is not null
          then concat_ws(' ', p_error_message, '[video ' || p_embed_video_id || ']')
          else p_error_message end);

  update public.streams
     set validation_status    = v_new,
         consecutive_failures = v_failures,
         last_checked_at      = now(),
         last_success_at      = case when p_outcome = 'SUCCESS' then now() else last_success_at end,
         first_verified_at    = case when v_new = 'VERIFIED' and p_test_type in ('TIER_B_PLAYBACK', 'EMBED_STATUS_CHECK', 'LINK_CHECK')
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
         embed_channel_id     = case when v_is_embed and p_outcome = 'SUCCESS' then p_embed_channel_id else embed_channel_id end,
         -- The live video changes when a broadcaster restarts its stream; keep only the current one.
         embed_video_id       = case when not v_is_embed then null
                                     when p_outcome = 'SUCCESS' then p_embed_video_id
                                     else null end,
         is_preferred         = case when v_new in ('REJECTED', 'REMOVED') then false else is_preferred end
   where id = p_stream_id;

  return jsonb_build_object('stream_id', p_stream_id, 'status_before', v_s.validation_status,
                            'status_after', v_new, 'consecutive_failures', v_failures);
end;
$$;

create or replace function public.streams_due_for_check(p_limit integer default 100)
returns table (
  stream_id            uuid,
  channel_id           uuid,
  stream_url           text,
  stream_format        public.stream_format,
  embed_provider       text,
  embed_channel_ref    text,
  embed_channel_id     text,
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
  select s.id, s.channel_id, s.stream_url, s.stream_format, s.embed_provider, s.embed_channel_ref, s.embed_channel_id,
         s.validation_status, s.consecutive_failures, s.first_verified_at, s.last_checked_at
    from public.streams s
   where s.validation_status not in ('REJECTED', 'REMOVED')
     and s.permission_status not in ('DENIED', 'TAKEDOWN')
     and (s.last_checked_at is null
          or s.last_checked_at < now() - case
               when s.stream_format = 'YOUTUBE_EMBED' and s.validation_status = 'VERIFIED' then interval '30 minutes'
               when s.stream_format = 'EXTERNAL_LINK' and s.validation_status = 'VERIFIED' then interval '24 hours'
               when s.validation_status = 'VERIFIED' then interval '6 hours'
               when s.validation_status = 'TESTING'  then interval '1 hour'
               else interval '6 hours' * power(2, least(s.consecutive_failures, 5))
             end)
   order by s.is_published desc, s.last_checked_at nulls first
   limit greatest(1, least(p_limit, 1000));
$$;

grant select (external_platform) on public.streams to anon, authenticated;
grant insert (external_platform) on public.streams to authenticated;
grant update (external_platform) on public.streams to authenticated;
