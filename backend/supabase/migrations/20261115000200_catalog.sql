-- TVKu — channel catalog: reference data, channels, streams, health history.

-- ---------------------------------------------------------------------------
-- Reference data
-- ---------------------------------------------------------------------------

create table public.countries (
  code        char(2) primary key check (code ~ '^[A-Z]{2}$'),  -- ISO 3166-1 alpha-2
  name        text not null check (length(name) between 1 and 100),
  region      text,   -- continent-level grouping, e.g. 'Asia'
  subregion   text,
  created_at  timestamptz not null default now()
);

create table public.categories (
  id          smallint generated always as identity primary key,
  slug        text not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
  name        text not null check (length(name) between 1 and 60),
  sort_order  smallint not null default 0
);

create table public.languages (
  code  text primary key check (code ~ '^[a-z]{3}$'),  -- ISO 639-3
  name  text not null check (length(name) between 1 and 100)
);

-- ---------------------------------------------------------------------------
-- Channels
-- ---------------------------------------------------------------------------

create table public.channels (
  id               uuid primary key default gen_random_uuid(),
  channel_name     text not null check (length(channel_name) between 1 and 200),
  slug             text not null unique check (slug ~ '^[a-z0-9]+(-[a-z0-9]+)*$' and length(slug) <= 120),
  country_code     char(2) not null references public.countries (code),
  category_id      smallint references public.categories (id) on delete set null,
  logo_url         text check (logo_url is null or (logo_url ~ '^https://' and length(logo_url) <= 2048)),
  description      text check (description is null or length(description) <= 2000),
  is_active        boolean not null default false,   -- admin switch
  is_published     boolean not null default false,   -- derived, maintained by trigger
  external_source  text,                              -- e.g. 'iptv-org'
  external_id      text,                              -- e.g. 'DWEnglish.de'
  created_at       timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  unique (external_source, external_id)
);
-- country_name is intentionally not duplicated here (normalisation):
-- it is joined from public.countries in public.catalog_channels.

create index channels_country_idx   on public.channels (country_code);
create index channels_category_idx  on public.channels (category_id);
create index channels_published_idx on public.channels (country_code, category_id) where is_published;
create index channels_name_trgm_idx on public.channels using gin (channel_name extensions.gin_trgm_ops);

create trigger channels_touch before update on public.channels
  for each row execute function private.touch_updated_at();

-- language_codes from the specification is modelled as a join table so that
-- every code is a foreign key to public.languages.
create table public.channel_languages (
  channel_id     uuid not null references public.channels (id) on delete cascade,
  language_code  text not null references public.languages (code),
  primary key (channel_id, language_code)
);
create index channel_languages_lang_idx on public.channel_languages (language_code);

-- ---------------------------------------------------------------------------
-- Streams
-- ---------------------------------------------------------------------------

create table public.streams (
  id                      uuid primary key default gen_random_uuid(),
  channel_id              uuid not null references public.channels (id) on delete cascade,
  stream_url              text not null unique
                            check (stream_url ~ '^https?://[^\s]+$' and length(stream_url) <= 2048),
  stream_format           public.stream_format not null default 'UNKNOWN',
  quality_label           text check (quality_label is null or quality_label in ('SD', 'HD', 'FHD', 'UHD')),
  resolution_width        integer check (resolution_width  is null or resolution_width  between 1 and 8192),
  resolution_height       integer check (resolution_height is null or resolution_height between 1 and 8192),

  -- source attribution
  source_name             text not null check (length(source_name) between 1 and 200),
  source_url              text check (source_url is null or length(source_url) <= 2048),

  -- licensing review (only changed through public.admin_review_stream_permission)
  permission_status       public.stream_permission_status not null default 'UNREVIEWED',
  permission_notes        text check (permission_notes is null or length(permission_notes) <= 4000),
  permission_reviewed_by  uuid references auth.users (id) on delete set null,
  permission_reviewed_at  timestamptz,

  -- technical health (only changed through public.record_stream_check / admin functions)
  validation_status       public.stream_validation_status not null default 'DISCOVERED',
  consecutive_failures    integer not null default 0 check (consecutive_failures >= 0),
  first_verified_at       timestamptz,   -- first successful Tier B playback test
  last_checked_at         timestamptz,
  last_success_at         timestamptz,
  failure_reason          text check (failure_reason is null or length(failure_reason) <= 2000),

  -- ordering of authorised feeds for the same channel
  priority                smallint not null default 100,
  is_preferred            boolean not null default false,

  -- A stream is visible to the public only when a human approved the source
  -- AND it has passed at least one real playback test AND it is not withdrawn.
  -- Its current health (VERIFIED, DEGRADED, OFFLINE, ...) is shown as-is.
  is_published            boolean generated always as (
                            permission_status in ('OFFICIAL_PUBLIC_SOURCE', 'EXPLICIT_PERMISSION')
                            and first_verified_at is not null
                            and validation_status not in ('REJECTED', 'REMOVED')
                          ) stored,

  created_at              timestamptz not null default now(),
  updated_at              timestamptz not null default now(),

  check ((resolution_width is null) = (resolution_height is null)),
  check (validation_status <> 'VERIFIED' or first_verified_at is not null),
  check (permission_status = 'UNREVIEWED' or permission_reviewed_at is not null)
);

create unique index streams_one_preferred_per_channel on public.streams (channel_id) where is_preferred;
create index streams_channel_idx      on public.streams (channel_id, priority);
create index streams_status_idx       on public.streams (validation_status);
create index streams_permission_idx   on public.streams (permission_status);
create index streams_due_idx          on public.streams (last_checked_at nulls first)
  where validation_status not in ('REJECTED', 'REMOVED');
create index streams_published_idx    on public.streams (channel_id) where is_published;

create trigger streams_touch before update on public.streams
  for each row execute function private.touch_updated_at();

-- Changing what a stream points to invalidates every previous verification and
-- licensing decision. This prevents silently swapping a channel to an
-- unrelated feed.
create function private.streams_reset_on_target_change()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.stream_url is distinct from old.stream_url
     or new.channel_id is distinct from old.channel_id then
    new.validation_status      := 'PENDING_REVIEW';
    new.first_verified_at      := null;
    new.last_success_at        := null;
    new.consecutive_failures   := 0;
    new.failure_reason         := null;
    new.permission_status      := 'UNREVIEWED';
    new.permission_reviewed_by := null;
    new.permission_reviewed_at := null;
    new.is_preferred           := false;
  end if;
  return new;
end;
$$;

create trigger streams_reset_on_target_change before update of stream_url, channel_id on public.streams
  for each row execute function private.streams_reset_on_target_change();

-- ---------------------------------------------------------------------------
-- Derived channel publication flag
-- ---------------------------------------------------------------------------

create function private.refresh_channel_publication(p_channel_id uuid)
returns void
language sql
security definer
set search_path = ''
as $$
  update public.channels c
     set is_published = c.is_active and exists (
           select 1 from public.streams s
            where s.channel_id = c.id and s.is_published)
   where c.id = p_channel_id
     and c.is_published is distinct from (c.is_active and exists (
           select 1 from public.streams s
            where s.channel_id = c.id and s.is_published));
$$;

create function private.streams_after_change()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  if tg_op in ('UPDATE', 'DELETE') then
    perform private.refresh_channel_publication(old.channel_id);
  end if;
  if tg_op in ('INSERT', 'UPDATE') and (tg_op = 'INSERT' or new.channel_id is distinct from old.channel_id
                                        or new.is_published is distinct from old.is_published) then
    perform private.refresh_channel_publication(new.channel_id);
  end if;
  return null;
end;
$$;

create trigger streams_after_change after insert or update or delete on public.streams
  for each row execute function private.streams_after_change();

create function private.channels_compute_publication()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
begin
  -- is_published can never be written directly.
  new.is_published := new.is_active and exists (
    select 1 from public.streams s where s.channel_id = new.id and s.is_published);
  return new;
end;
$$;

create trigger channels_compute_publication before insert or update on public.channels
  for each row execute function private.channels_compute_publication();

-- ---------------------------------------------------------------------------
-- Stream health history
-- ---------------------------------------------------------------------------

create table public.stream_health_logs (
  id                         bigint generated always as identity primary key,
  stream_id                  uuid not null references public.streams (id) on delete cascade,
  check_timestamp            timestamptz not null default now(),
  test_type                  public.health_test_type not null,
  outcome                    public.health_outcome not null,
  response_status            integer check (response_status is null or response_status between 100 and 599),
  playback_success           boolean not null,
  playback_duration_seconds  numeric(8, 2) check (playback_duration_seconds is null or playback_duration_seconds >= 0),
  detected_resolution        text check (detected_resolution is null or detected_resolution ~ '^[0-9]{1,5}x[0-9]{1,5}$'),
  detected_format            public.stream_format,
  video_frames_rendered      integer check (video_frames_rendered is null or video_frames_rendered >= 0),
  audio_decoded              boolean,
  reconnect_success          boolean,
  geo_vantage                char(2) check (geo_vantage is null or geo_vantage ~ '^[A-Z]{2}$'),
  runner_id                  text check (runner_id is null or length(runner_id) <= 200),
  status_before              public.stream_validation_status not null,
  status_after               public.stream_validation_status not null,
  error_message              text check (error_message is null or length(error_message) <= 2000),
  check (playback_success = (outcome = 'SUCCESS'))
);

create index stream_health_logs_stream_idx on public.stream_health_logs (stream_id, check_timestamp desc);
