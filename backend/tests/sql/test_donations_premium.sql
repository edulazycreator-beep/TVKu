-- Donation flow, admin approval, premium lifecycle and audit trail.
do $$
declare
  v_uuid    uuid := gen_random_uuid();
  v_secret  text := repeat('c3', 24);
  v_admin   uuid := tests.make_admin('donadmin@test', 'admin');
  v_rev     uuid := tests.make_admin('donrev@test', 'reviewer');
  v_user    uuid := tests.make_auth_user('donor@test');
  v_inst    uuid;
  v_d1      jsonb;
  v_d2      jsonb;
  v_r       jsonb;
  v_r2      jsonb;
  v_e       jsonb;
  v_n       integer;
begin
  perform tests.as_service();
  v_inst := (public.register_installation(v_uuid, v_secret, null, '1.0.0', null) ->> 'installation_id')::uuid;
  perform tests.as_superuser();
  perform tests.age_trial(v_uuid, 18001);  -- trial over

  perform tests.as_service();
  perform tests.assert_raises(format('select public.create_donation(%L, %L, 0, %L)', v_uuid, v_secret, 'USD'),
    'invalid_amount', 'zero amount rejected');
  perform tests.assert_raises(format('select public.create_donation(%L, %L, 5.555, %L)', v_uuid, v_secret, 'USD'),
    'invalid_amount', 'more than 2 decimals rejected');
  perform tests.assert_raises(format('select public.create_donation(%L, %L, 5, %L)', v_uuid, v_secret, 'US'),
    'invalid_currency', 'bad currency rejected');

  v_d1 := public.create_donation(v_uuid, v_secret, 5, 'usd', 'bank_transfer');
  perform tests.assert((v_d1 ->> 'donation_reference') ~ '^TVKD-[0-9]{6}-[A-Z2-9]{6}$', 'donation reference format');
  perform tests.assert_eq(v_d1 ->> 'currency', 'USD', 'currency normalised');

  -- Creating a donation (and opening WhatsApp) never activates premium.
  v_e := public.get_entitlement(v_uuid, v_secret);
  perform tests.assert_eq(v_e ->> 'status', 'PENDING_DONATION', 'pending donation status');
  perform tests.assert(not (v_e ->> 'premium_features_unlocked')::boolean, 'pending donation does not unlock premium');
  perform tests.assert_eq(v_e ->> 'pending_donation_reference', v_d1 ->> 'donation_reference', 'reference echoed');

  v_d2 := public.create_donation(v_uuid, v_secret, 10, 'USD', null);
  perform public.create_donation(v_uuid, v_secret, 1, 'USD', null);
  perform tests.assert_raises(format('select public.create_donation(%L, %L, 1, %L)', v_uuid, v_secret, 'USD'),
    'too_many_pending_donations', 'pending donations are capped');
  perform tests.as_superuser();

  -- Only admins with MFA can approve.
  perform tests.as_user(v_user, 'aal2');
  perform tests.assert_raises(format('select public.admin_approve_donation(%L)', v_d1 ->> 'donation_id'),
    'forbidden', 'normal user cannot approve');
  perform tests.assert_raises(format($q$update public.donations set status = 'APPROVED' where id = %L$q$, v_d1 ->> 'donation_id'),
    'permission denied', 'normal user cannot update donations');
  perform tests.assert_raises(format($q$insert into public.premium_entitlements (installation_id, entitlement_type, starts_at, expires_at) values (%L, 'ADMIN_GRANT', now(), now() + interval '1 year')$q$, v_inst),
    'permission denied', 'normal user cannot create entitlements');
  select count(*) into v_n from public.donations;
  perform tests.assert_eq(v_n, 0::integer, 'normal user cannot read donations');
  perform tests.as_superuser();

  perform tests.as_user(v_rev, 'aal2');
  perform tests.assert_raises(format('select public.admin_approve_donation(%L)', v_d1 ->> 'donation_id'),
    'forbidden', 'reviewer cannot approve donations');
  perform tests.as_superuser();

  perform tests.as_user(v_admin, 'aal1');
  perform tests.assert_raises(format('select public.admin_approve_donation(%L)', v_d1 ->> 'donation_id'),
    'forbidden', 'admin without MFA cannot approve');
  perform tests.as_superuser();

  -- Approve with default validity (30 days).
  perform tests.as_user(v_admin, 'aal2');
  v_r := public.admin_approve_donation((v_d1 ->> 'donation_id')::uuid, null, null, 'WA-msg-2026-11-15', 'Transfer seen');
  perform tests.assert_eq((v_r ->> 'idempotent_replay')::boolean, false, 'first approval');
  perform tests.assert((v_r ->> 'expires_at')::timestamptz between now() + interval '29 days 23 hours' and now() + interval '30 days 1 hour',
    'default 30 day validity');

  -- Approval is idempotent.
  v_r2 := public.admin_approve_donation((v_d1 ->> 'donation_id')::uuid, 90);
  perform tests.assert_eq((v_r2 ->> 'idempotent_replay')::boolean, true, 'second approval is a replay');
  perform tests.assert_eq(v_r2 ->> 'entitlement_id', v_r ->> 'entitlement_id', 'same entitlement returned');
  select count(*) into v_n from public.premium_entitlements where installation_id = v_inst;
  perform tests.assert_eq(v_n, 1::integer, 'no duplicate entitlement');

  perform tests.assert_raises(format('select public.admin_reject_donation(%L, %L)', v_d1 ->> 'donation_id', 'late'),
    'donation_not_pending', 'approved donation cannot be rejected');
  perform tests.assert_raises(format('select public.admin_reject_donation(%L, %L)', v_d2 ->> 'donation_id', ''),
    'reason_required', 'rejection needs a reason');
  perform public.admin_reject_donation((v_d2 ->> 'donation_id')::uuid, 'No payment received');
  perform tests.as_superuser();

  perform tests.as_service();
  v_e := public.get_entitlement(v_uuid, v_secret);
  perform tests.assert_eq(v_e ->> 'status', 'PREMIUM', 'approved donation -> PREMIUM');
  perform tests.assert((v_e ->> 'premium_features_unlocked')::boolean, 'premium unlocked');
  perform tests.as_superuser();

  -- Extend and revoke.
  perform tests.as_user(v_admin, 'aal2');
  perform tests.assert_raises(format('select public.admin_extend_premium(%L, 10, now() + interval %L)', v_inst, '1 day'),
    'exactly_one_of', 'extend needs exactly one mode');
  v_r := public.admin_extend_premium(v_inst, 10, null, 'Loyal supporter');
  perform tests.assert((v_r ->> 'expires_at')::timestamptz > now() + interval '39 days', 'extension adds to current expiry');
  perform tests.assert_raises(format('select public.admin_revoke_premium(%L, %L)', v_inst, ''),
    'reason_required', 'revocation needs a reason');
  perform public.admin_revoke_premium(v_inst, 'Chargeback');
  perform tests.as_superuser();

  perform tests.as_service();
  v_e := public.get_entitlement(v_uuid, v_secret);
  perform tests.assert_eq(v_e ->> 'status', 'PENDING_DONATION', 'revoked premium with another pending donation');
  perform tests.as_superuser();
  update public.donations set status = 'CANCELLED' where installation_id = v_inst and status = 'PENDING';
  perform tests.as_service();
  v_e := public.get_entitlement(v_uuid, v_secret);
  perform tests.assert_eq(v_e ->> 'status', 'REVOKED', 'revoked premium -> REVOKED');
  perform tests.as_superuser();

  -- Admin cannot approve a donation tied to their own account.
  insert into public.users (auth_user_id) values (v_admin);
  update public.installations set user_id = (select id from public.users where auth_user_id = v_admin) where id = v_inst;
  update public.donations set status = 'PENDING', reviewed_at = null, reviewed_by = null where id = (v_d2 ->> 'donation_id')::uuid;
  perform tests.as_user(v_admin, 'aal2');
  perform tests.assert_raises(format('select public.admin_approve_donation(%L)', v_d2 ->> 'donation_id'),
    'self_approval_forbidden', 'admins cannot approve their own donations');
  perform tests.as_superuser();

  -- Audit trail.
  select count(*) into v_n from public.admin_audit_logs
   where admin_id = v_admin and action in ('approve_donation', 'reject_donation', 'extend_premium', 'revoke_premium');
  perform tests.assert_eq(v_n, 4::integer, 'every donation/premium action is audited');
  perform tests.assert_raises('delete from public.admin_audit_logs', 'append-only', 'audit log cannot be deleted');
  perform tests.assert_raises($q$update public.admin_audit_logs set action = 'x'$q$, 'append-only', 'audit log cannot be edited');

  -- Expired premium shows as EXPIRED.
  perform tests.as_service();
  v_uuid := gen_random_uuid();
  v_inst := (public.register_installation(v_uuid, v_secret, null, null, null) ->> 'installation_id')::uuid;
  perform tests.as_superuser();
  insert into public.premium_entitlements (installation_id, entitlement_type, starts_at, expires_at, granted_by)
  values (v_inst, 'ADMIN_GRANT', now() - interval '40 days', now() - interval '10 days', v_admin);
  perform tests.age_trial(v_uuid, 18001);
  perform tests.as_service();
  perform tests.assert_eq(public.get_entitlement(v_uuid, v_secret) ->> 'status', 'EXPIRED', 'lapsed premium -> EXPIRED');
end $$;
