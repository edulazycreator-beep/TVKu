-- Installation registration and the server-authoritative 5-hour trial.
do $$
declare
  v_uuid    uuid := gen_random_uuid();
  v_uuid2   uuid := gen_random_uuid();
  v_uuid3   uuid := gen_random_uuid();
  v_secret  text := repeat('a1', 24);
  v_secret2 text := repeat('b2', 24);
  v_device  text := encode(extensions.digest('android-id-fixture', 'sha256'), 'hex');
  v_user    uuid := tests.make_auth_user('someone@test');
  v_e       jsonb;
  v_e2      jsonb;
begin
  -- Only Edge Functions (service_role) may register installations.
  perform tests.as_anon();
  perform tests.assert_raises(format('select public.register_installation(%L, %L)', v_uuid, v_secret),
    'permission denied', 'anon cannot call register_installation');
  perform tests.as_superuser();
  perform tests.as_user(v_user, 'aal2');
  perform tests.assert_raises(format('select public.get_entitlement(%L, %L)', v_uuid, v_secret),
    'permission denied', 'authenticated cannot call get_entitlement');
  perform tests.as_superuser();

  perform tests.as_service();
  perform tests.assert_raises(format('select public.register_installation(%L, %L)', v_uuid, 'short'),
    'invalid_secret', 'short secrets are rejected');

  v_e := public.register_installation(v_uuid, v_secret, v_device, '1.0.0', 'VALID');
  perform tests.assert_eq(v_e ->> 'status', 'TRIAL', 'trial starts at first registration');
  perform tests.assert_eq(v_e #>> '{trial,status}', 'ACTIVE', 'trial is active');
  perform tests.assert_eq((v_e #>> '{trial,remaining_seconds}')::integer, 18000, 'trial lasts exactly 18000 s of server time');
  perform tests.assert(((v_e #>> '{trial,expires_at}')::timestamptz - (v_e #>> '{trial,started_at}')::timestamptz) = interval '5 hours',
    'expires_at = started_at + 5 h');
  perform tests.assert((v_e ->> 'premium_features_unlocked')::boolean, 'premium features unlocked during trial');
  perform tests.assert((v_e ->> 'public_code') ~ '^TVK-[A-Z2-9]{4}-[A-Z2-9]{4}$', 'public code format');

  -- Idempotent retry with the same credentials.
  v_e2 := public.register_installation(v_uuid, v_secret, v_device, '1.0.1', null);
  perform tests.assert_eq(v_e2 ->> 'installation_id', v_e ->> 'installation_id', 'retry returns same installation');
  perform tests.assert_eq(v_e2 #>> '{trial,started_at}', v_e #>> '{trial,started_at}', 'retry does not restart trial');

  -- Someone who only knows the UUID cannot take over the installation.
  perform tests.assert_raises(format('select public.register_installation(%L, %L)', v_uuid, v_secret2),
    'installation_uuid_conflict', 'different secret for existing UUID rejected');
  perform tests.assert_raises(format('select public.get_entitlement(%L, %L)', v_uuid, v_secret2),
    'invalid_installation_credentials', 'wrong secret rejected');
  perform tests.assert_raises(format('select public.get_entitlement(%L, %L)', gen_random_uuid(), v_secret),
    'invalid_installation_credentials', 'unknown installation rejected');

  -- start_trial never restarts a trial.
  v_e2 := public.start_trial(v_uuid, v_secret);
  perform tests.assert_eq(v_e2 #>> '{trial,started_at}', v_e #>> '{trial,started_at}', 'start_trial is idempotent');
  perform tests.as_superuser();

  -- Simulate 4 h 59 m of server time.
  perform tests.age_trial(v_uuid, 4 * 3600 + 59 * 60);
  perform tests.as_service();
  v_e := public.get_entitlement(v_uuid, v_secret);
  perform tests.assert_eq(v_e ->> 'status', 'TRIAL', 'still in trial after 4h59m');
  perform tests.assert_eq((v_e #>> '{trial,remaining_seconds}')::integer, 60, '60 s remaining');
  perform tests.as_superuser();

  -- Past 5 hours: trial expired, user falls back to FREE (free channels stay available).
  perform tests.age_trial(v_uuid, 61);
  perform tests.as_service();
  v_e := public.get_entitlement(v_uuid, v_secret);
  perform tests.assert_eq(v_e ->> 'status', 'FREE', 'expired trial -> FREE');
  perform tests.assert_eq(v_e #>> '{trial,status}', 'EXPIRED', 'trial reported EXPIRED');
  perform tests.assert_eq((v_e #>> '{trial,remaining_seconds}')::integer, 0, 'no remaining time');
  perform tests.assert(not (v_e ->> 'premium_features_unlocked')::boolean, 'premium features locked');

  -- "Clear data": new UUID + new secret, same app-scoped device key -> no new trial.
  v_e2 := public.register_installation(v_uuid2, v_secret2, v_device, '1.0.0', null);
  perform tests.assert_eq(v_e2 ->> 'status', 'FREE', 'reinstall on same device does not get a new trial');
  perform tests.assert_eq(v_e2 #>> '{trial,expires_at}', v_e #>> '{trial,expires_at}', 'inherits original trial window');

  -- A different device gets its own trial; no device key also gets one.
  v_e2 := public.register_installation(v_uuid3, v_secret2, null, '1.0.0', null);
  perform tests.assert_eq(v_e2 ->> 'status', 'TRIAL', 'new device gets a trial');

  -- Malformed device key hash is rejected (raw identifiers must never be sent).
  perform tests.assert_raises(format('select public.register_installation(%L, %L, %L)', gen_random_uuid(), v_secret, 'raw-android-id'),
    'invalid_device_key_hash', 'raw device id rejected');

  -- Housekeeping marks stored statuses.
  perform tests.assert(((public.maintenance_expire_entitlements()) ->> 'trials_expired')::integer >= 1, 'maintenance expires trials');
  perform tests.as_superuser();

  -- Secrets are stored hashed only, and hashes are not readable through the API.
  perform tests.assert((select count(*) from public.installations where secret_hash = convert_to(v_secret, 'UTF8')) = 0,
    'secret not stored in clear');
  perform tests.as_user(tests.make_admin('adm-trial@test', 'owner'), 'aal2');
  perform tests.assert_raises('select secret_hash from public.installations', 'permission denied',
    'even admins cannot read secret hashes');
  perform tests.assert_raises('select device_key_hash from public.trial_entitlements', 'permission denied',
    'device key hashes not exposed');
  perform tests.assert_raises(format($q$update public.trial_entitlements set expires_at = now() + interval '1 year'$q$),
    'permission denied', 'nobody can modify trial expiry through the API');
  perform tests.as_superuser();

  -- Rate limiter.
  perform tests.as_service();
  perform tests.assert(public.consume_rate_limit('test:key', 2, 60), 'request 1 allowed');
  perform tests.assert(public.consume_rate_limit('test:key', 2, 60), 'request 2 allowed');
  perform tests.assert(not public.consume_rate_limit('test:key', 2, 60), 'request 3 limited');
end $$;
