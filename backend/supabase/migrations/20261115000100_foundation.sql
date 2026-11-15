-- TVKu — foundation: extensions, schemas, enum types.
--
-- Conventions used by every migration:
--   * public  : tables exposed through PostgREST, always protected by RLS.
--   * private : helper functions and internal tables; never exposed by the API.
--   * Every SECURITY DEFINER function sets search_path = '' and fully
--     qualifies every object it touches.

create schema if not exists extensions;
create extension if not exists pgcrypto with schema extensions;
create extension if not exists pg_trgm  with schema extensions;

create schema if not exists private;
revoke all on schema private from public;
-- RLS policies call a few private helpers, so API roles need USAGE on the
-- schema. EXECUTE is granted function by function.
grant usage on schema private to anon, authenticated, service_role;

-- Supabase grants EXECUTE on new functions to PUBLIC/anon/authenticated by
-- default. TVKu grants execute explicitly instead.
alter default privileges in schema public  revoke execute on functions from public;
alter default privileges in schema private revoke execute on functions from public;

-- ---------------------------------------------------------------------------
-- Enum types
-- ---------------------------------------------------------------------------

-- Technical health of a single stream, driven only by recorded checks
-- (plus manual REJECTED / REMOVED / PENDING_REVIEW decisions by admins).
create type public.stream_validation_status as enum (
  'DISCOVERED',      -- imported from a discovery source, untouched
  'PENDING_REVIEW',  -- queued for testing / could not be tested yet
  'TESTING',         -- passed automated probe, waiting for playback test
  'VERIFIED',        -- passed real Media3 playback test (>= 60 s + reconnect)
  'DEGRADED',        -- verified before, currently failing intermittently
  'GEO_RESTRICTED',  -- blocked from the validator's vantage point
  'OFFLINE',         -- repeatedly failing
  'REJECTED',        -- unusable (unsupported format) or rejected by an admin
  'REMOVED'          -- removed by an admin (e.g. takedown)
);

-- Legal / licensing status of a stream source. Independent from health.
-- Public accessibility alone is NOT a permission, so there is deliberately no
-- "publicly reachable" value here.
create type public.stream_permission_status as enum (
  'UNREVIEWED',             -- nobody has reviewed the source yet
  'OFFICIAL_PUBLIC_SOURCE', -- broadcaster itself offers the stream free to the public
  'EXPLICIT_PERMISSION',    -- written permission from the rights holder
  'DENIED',                 -- reviewed, not allowed
  'TAKEDOWN'                -- takedown request received
);

create type public.stream_format as enum ('HLS', 'DASH', 'PROGRESSIVE', 'UNKNOWN');

create type public.trial_status as enum ('NOT_STARTED', 'ACTIVE', 'EXPIRED');

-- Overall status reported to the app.
create type public.premium_status as enum (
  'FREE', 'TRIAL', 'PENDING_DONATION', 'PREMIUM', 'EXPIRED', 'REVOKED'
);

create type public.entitlement_status as enum ('ACTIVE', 'EXPIRED', 'REVOKED');
create type public.entitlement_type   as enum ('DONATION', 'ADMIN_GRANT');
create type public.donation_status    as enum ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED');

-- Declaration order matters: comparisons use it (owner > admin > reviewer).
create type public.admin_role as enum ('reviewer', 'admin', 'owner');

create type public.health_test_type as enum ('TIER_A_PROBE', 'TIER_B_PLAYBACK', 'MANUAL_RECHECK');

create type public.health_outcome as enum (
  'SUCCESS',
  'TRANSIENT_FAILURE',  -- DNS hiccup, timeout, 5xx, CDN outage
  'PERMANENT_FAILURE',  -- 404/410, broken manifest, missing segments
  'GEO_BLOCKED',        -- 403/451 with geo indication from the vantage point
  'UNSUPPORTED'         -- format or codec that Media3 cannot decode
);

-- Shared trigger: maintain updated_at.
create function private.touch_updated_at()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  new.updated_at := now();
  return new;
end;
$$;
