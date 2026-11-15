-- TVKu — installations, users, admins, trials, donations, premium, favorites,
-- configuration, audit log and rate limiting.

-- ---------------------------------------------------------------------------
-- Accounts
-- ---------------------------------------------------------------------------

create table public.users (
  id            uuid primary key default gen_random_uuid(),
  auth_user_id  uuid not null unique references auth.users (id) on delete cascade,
  display_name  text check (display_name is null or length(display_name) between 1 and 80),
  email         text check (email is null or length(email) <= 320),
  created_at    timestamptz not null default now()
);

create table public.admin_users (
  auth_user_id  uuid primary key references auth.users (id) on delete cascade,
  role          public.admin_role not null,
  is_active     boolean not null default true,
  created_at    timestamptz not null default now(),
  created_by    uuid references auth.users (id) on delete set null
);

-- ---------------------------------------------------------------------------
-- Installations
-- ---------------------------------------------------------------------------
-- installation_uuid : random UUID generated on the device (public identifier)
-- secret_hash       : SHA-256 of a random secret generated on the device and
--                     sent once at registration. The UUID alone is never a
--                     credential (it appears in WhatsApp messages).
-- device_key_hash   : optional SHA-256 of the app-scoped Android ID, computed on
--                     the device. Used only to stop the 5-hour trial from
--                     restarting after "clear data". Never a raw identifier.

create table public.installations (
  id                   uuid primary key default gen_random_uuid(),
  installation_uuid    uuid not null unique,
  public_code          text not null unique check (public_code ~ '^TVK-[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}$'),
  secret_hash          bytea not null check (length(secret_hash) = 32),
  device_key_hash      text check (device_key_hash is null or device_key_hash ~ '^[a-f0-9]{64}$'),
  user_id              uuid references public.users (id) on delete set null,
  first_registered_at  timestamptz not null default now(),
  last_seen_at         timestamptz not null default now(),
  app_version          text check (app_version is null or length(app_version) <= 32),
  platform             text not null default 'android' check (platform = 'android'),
  integrity_verdict    text check (integrity_verdict is null or integrity_verdict in ('VALID', 'INVALID', 'UNAVAILABLE')),
  blocked_at           timestamptz,
  block_reason         text check (block_reason is null or length(block_reason) <= 1000),
  created_at           timestamptz not null default now()
);

create index installations_device_key_idx on public.installations (device_key_hash) where device_key_hash is not null;
create index installations_user_idx       on public.installations (user_id) where user_id is not null;
create index installations_last_seen_idx  on public.installations (last_seen_at desc);

-- ---------------------------------------------------------------------------
-- Trial (5 hours, server time)
-- ---------------------------------------------------------------------------
-- The stored status is maintained by private.maintenance_expire_entitlements();
-- the authoritative value is always computed from expires_at vs now().

create table public.trial_entitlements (
  id               uuid primary key default gen_random_uuid(),
  installation_id  uuid not null unique references public.installations (id) on delete cascade,
  device_key_hash  text check (device_key_hash is null or device_key_hash ~ '^[a-f0-9]{64}$'),
  started_at       timestamptz not null,
  expires_at       timestamptz not null,
  status           public.trial_status not null default 'ACTIVE' check (status <> 'NOT_STARTED'),
  inherited_from   uuid references public.trial_entitlements (id) on delete set null,
  created_at       timestamptz not null default now(),
  check (expires_at > started_at)
);

create index trial_entitlements_device_key_idx on public.trial_entitlements (device_key_hash, started_at)
  where device_key_hash is not null;
create index trial_entitlements_active_idx on public.trial_entitlements (expires_at) where status = 'ACTIVE';

-- ---------------------------------------------------------------------------
-- Donations (manual verification via WhatsApp)
-- ---------------------------------------------------------------------------

create table public.donations (
  id                  uuid primary key default gen_random_uuid(),
  installation_id     uuid not null references public.installations (id) on delete restrict,
  user_id             uuid references public.users (id) on delete set null,
  donation_reference  text not null unique check (donation_reference ~ '^TVKD-[0-9]{6}-[A-HJ-NP-Z2-9]{6}$'),
  amount              numeric(12, 2) not null check (amount > 0),
  currency            char(3) not null check (currency ~ '^[A-Z]{3}$'),
  payment_method      text check (payment_method is null or length(payment_method) <= 64),
  proof_reference     text check (proof_reference is null or length(proof_reference) <= 500),
  status              public.donation_status not null default 'PENDING',
  reviewed_by         uuid references auth.users (id) on delete set null,
  reviewed_at         timestamptz,
  review_notes        text check (review_notes is null or length(review_notes) <= 2000),
  created_at          timestamptz not null default now(),
  updated_at          timestamptz not null default now(),
  check (status not in ('APPROVED', 'REJECTED') or reviewed_at is not null)
);

create index donations_status_idx       on public.donations (status, created_at desc);
create index donations_installation_idx on public.donations (installation_id, created_at desc);

create trigger donations_touch before update on public.donations
  for each row execute function private.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Premium entitlements
-- ---------------------------------------------------------------------------

create table public.premium_entitlements (
  id                uuid primary key default gen_random_uuid(),
  installation_id   uuid references public.installations (id) on delete cascade,
  user_id           uuid references public.users (id) on delete cascade,
  entitlement_type  public.entitlement_type not null,
  donation_id       uuid unique references public.donations (id) on delete restrict,
  starts_at         timestamptz not null,
  expires_at        timestamptz not null,
  status            public.entitlement_status not null default 'ACTIVE',
  granted_by        uuid references auth.users (id) on delete set null,
  revoked_at        timestamptz,
  revoked_by        uuid references auth.users (id) on delete set null,
  revoke_reason     text check (revoke_reason is null or length(revoke_reason) <= 1000),
  created_at        timestamptz not null default now(),
  updated_at        timestamptz not null default now(),
  check (installation_id is not null or user_id is not null),
  check (expires_at > starts_at),
  check ((entitlement_type = 'DONATION') = (donation_id is not null)),
  check ((status = 'REVOKED') = (revoked_at is not null))
);

create index premium_installation_idx on public.premium_entitlements (installation_id, expires_at desc);
create index premium_user_idx         on public.premium_entitlements (user_id, expires_at desc) where user_id is not null;
create index premium_active_idx       on public.premium_entitlements (expires_at) where status = 'ACTIVE';

create trigger premium_touch before update on public.premium_entitlements
  for each row execute function private.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Favorites synchronised for signed-in users
-- ---------------------------------------------------------------------------

create table public.user_favorites (
  user_id     uuid not null references public.users (id) on delete cascade,
  channel_id  uuid not null references public.channels (id) on delete cascade,
  created_at  timestamptz not null default now(),
  primary key (user_id, channel_id)
);

-- ---------------------------------------------------------------------------
-- Application configuration
-- ---------------------------------------------------------------------------

create table public.app_config (
  id            smallint generated always as identity primary key,
  config_key    text not null unique check (config_key ~ '^[a-z][a-z0-9_]{1,62}$'),
  config_value  jsonb not null,
  is_public     boolean not null default false,  -- readable by the app without auth
  description   text,
  updated_at    timestamptz not null default now(),
  updated_by    uuid references auth.users (id) on delete set null
);

create trigger app_config_touch before update on public.app_config
  for each row execute function private.touch_updated_at();

-- ---------------------------------------------------------------------------
-- Admin audit log (append-only)
-- ---------------------------------------------------------------------------

create table public.admin_audit_logs (
  id           bigint generated always as identity primary key,
  admin_id     uuid,          -- auth.users id; null for system actions
  action       text not null check (length(action) between 1 and 100),
  target_type  text not null check (length(target_type) between 1 and 60),
  target_id    text,
  details      jsonb not null default '{}'::jsonb,
  created_at   timestamptz not null default now()
);

create index admin_audit_logs_target_idx on public.admin_audit_logs (target_type, target_id, created_at desc);
create index admin_audit_logs_admin_idx  on public.admin_audit_logs (admin_id, created_at desc);

create function private.audit_logs_append_only()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'admin_audit_logs is append-only' using errcode = '42501';
end;
$$;

create trigger admin_audit_logs_append_only before update or delete on public.admin_audit_logs
  for each row execute function private.audit_logs_append_only();

-- ---------------------------------------------------------------------------
-- Rate limiting (fixed window), used by Edge Functions
-- ---------------------------------------------------------------------------

create table private.rate_limits (
  bucket_key     text primary key check (length(bucket_key) <= 200),
  window_start   timestamptz not null,
  request_count  integer not null
);
