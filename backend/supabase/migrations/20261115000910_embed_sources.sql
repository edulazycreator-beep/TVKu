-- TVKu — official embedded sources (YouTube live via the official IFrame player).
--
-- Some broadcasters publish their live channel only (or most reliably) on
-- their official YouTube channel. TVKu may show those through YouTube's own
-- embedded player, never by extracting the underlying video stream.
--
-- An embed source is still a stream row: it goes through the same licensing
-- review and publication rule. Its health check is different: VERIFIED means
-- the YouTube Data API confirmed, at check time, that the channel is the
-- expected one, a live broadcast is running and embedding is allowed.

alter table public.streams
  add column embed_provider    text check (embed_provider is null or embed_provider = 'youtube'),
  add column embed_channel_ref text check (embed_channel_ref is null or embed_channel_ref ~ '^(UC[A-Za-z0-9_-]{22}|@[A-Za-z0-9._-]{3,100})$'),
  add column embed_channel_id  text check (embed_channel_id is null or embed_channel_id ~ '^UC[A-Za-z0-9_-]{22}$'),
  add column embed_video_id    text check (embed_video_id is null or embed_video_id ~ '^[A-Za-z0-9_-]{11}$'),
  add constraint streams_embed_fields_consistent check (
    (stream_format = 'YOUTUBE_EMBED') = (embed_provider is not null and embed_channel_ref is not null)
  ),
  add constraint streams_embed_url check (
    stream_format <> 'YOUTUBE_EMBED' or stream_url ~ '^https://www\.youtube\.com/(channel/UC[A-Za-z0-9_-]{22}|@[A-Za-z0-9._-]{3,100})$'
  );

create unique index streams_embed_channel_unique on public.streams (embed_provider, embed_channel_ref)
  where embed_provider is not null;

-- Re-pointing an embed to another YouTube channel is a target change too.
create or replace function private.streams_reset_on_target_change()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.stream_url is distinct from old.stream_url
     or new.channel_id is distinct from old.channel_id
     or new.embed_channel_ref is distinct from old.embed_channel_ref then
    new.validation_status      := 'PENDING_REVIEW';
    new.first_verified_at      := null;
    new.last_success_at        := null;
    new.consecutive_failures   := 0;
    new.failure_reason         := null;
    new.permission_status      := 'UNREVIEWED';
    new.permission_reviewed_by := null;
    new.permission_reviewed_at := null;
    new.is_preferred           := false;
    new.embed_channel_id       := null;
    new.embed_video_id         := null;
  end if;
  return new;
end;
$$;

drop trigger streams_reset_on_target_change on public.streams;
create trigger streams_reset_on_target_change before update of stream_url, channel_id, embed_channel_ref on public.streams
  for each row execute function private.streams_reset_on_target_change();

-- ---------------------------------------------------------------------------
-- Health checks: same entry point, extended for embeds.
-- ---------------------------------------------------------------------------

drop function public.record_stream_check(uuid, public.health_test_type, public.health_outcome, integer, numeric, text,
  public.stream_format, integer, boolean, boolean, text, text, text);

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
  v_w          integer;
  v_h          integer;
begin
  select * into v_s from public.streams s where s.id = p_stream_id for update;
  if not found then
    raise exception 'stream_not_found' using errcode = 'P0002';
  end if;
  v_is_embed := v_s.stream_format = 'YOUTUBE_EMBED';

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
        if p_test_type in ('TIER_B_PLAYBACK', 'EMBED_STATUS_CHECK') then
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
         first_verified_at    = case when v_new = 'VERIFIED' and p_test_type in ('TIER_B_PLAYBACK', 'EMBED_STATUS_CHECK')
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

revoke all on function public.record_stream_check(uuid, public.health_test_type, public.health_outcome, integer, numeric, text,
  public.stream_format, integer, boolean, boolean, text, text, text, text, text, boolean) from public, anon, authenticated;
grant execute on function public.record_stream_check(uuid, public.health_test_type, public.health_outcome, integer, numeric, text,
  public.stream_format, integer, boolean, boolean, text, text, text, text, text, boolean) to service_role;

-- The runner needs the embed reference to dispatch the right checker.
drop function public.streams_due_for_check(integer);
create function public.streams_due_for_check(p_limit integer default 100)
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
               when s.validation_status = 'VERIFIED' then interval '6 hours'
               when s.validation_status = 'TESTING'  then interval '1 hour'
               else interval '6 hours' * power(2, least(s.consecutive_failures, 5))
             end)
   order by s.is_published desc, s.last_checked_at nulls first
   limit greatest(1, least(p_limit, 1000));
$$;

revoke all on function public.streams_due_for_check(integer) from public, anon, authenticated;
grant execute on function public.streams_due_for_check(integer) to service_role;

-- API grants for the new columns.
grant select (embed_provider, embed_channel_ref, embed_channel_id, embed_video_id) on public.streams to anon, authenticated;
grant insert (embed_provider, embed_channel_ref) on public.streams to authenticated;
grant update (embed_provider, embed_channel_ref) on public.streams to authenticated;
