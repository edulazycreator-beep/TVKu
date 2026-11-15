-- App configuration, admin accounts and favorites.
do $$
declare
  v_owner uuid := tests.make_admin('cfg-owner@test', 'owner');
  v_admin uuid := tests.make_admin('cfg-admin@test', 'admin');
  v_u1    uuid := tests.make_auth_user('fav1@test');
  v_u2    uuid := tests.make_auth_user('fav2@test');
  v_ch    uuid := tests.make_channel('fav-channel');
  v_row1  uuid;
  v_row2  uuid;
  v_n     integer;
begin
  -- Anonymous clients see public keys only.
  perform tests.as_anon();
  select count(*) into v_n from public.app_config where config_key = 'whatsapp_admin_number';
  perform tests.assert_eq(v_n, 1::integer, 'public config visible');
  select count(*) into v_n from public.app_config where config_key = 'premium_default_days';
  perform tests.assert_eq(v_n, 0::integer, 'private config hidden');
  perform tests.assert_raises($q$update public.app_config set config_value = '"1"' where config_key = 'whatsapp_admin_number'$q$,
    'permission denied', 'anon cannot change config');
  perform tests.as_superuser();

  -- No real number is shipped by default.
  perform tests.assert_eq((select config_value from public.app_config where config_key = 'whatsapp_admin_number'),
                          'null'::jsonb, 'WhatsApp number not hardcoded');

  perform tests.as_user(v_admin, 'aal2');
  perform tests.assert_raises($q$select public.admin_set_config('whatsapp_admin_number', '"+62 812"')$q$,
    'e164', 'WhatsApp number must be E.164 digits');
  perform public.admin_set_config('whatsapp_admin_number', '"6281234567890"');
  perform tests.assert_raises($q$select public.admin_set_config('trial_duration_seconds', '36000')$q$,
    'fixed_by_product_rule', 'trial duration cannot be changed');
  perform tests.assert_raises($q$select public.admin_set_config('premium_default_days', '"thirty"')$q$,
    'config_type_mismatch', 'config type is enforced');
  perform tests.assert_raises($q$select public.admin_set_config('no_such_key', '1')$q$,
    'unknown_config_key', 'unknown keys rejected');
  perform tests.assert_raises(format('select public.admin_set_admin_role(%L, %L, true)', v_u1, 'admin'),
    'forbidden', 'only owners manage admins');
  perform tests.as_superuser();

  perform tests.as_user(v_owner, 'aal2');
  perform public.admin_set_admin_role(v_u1, 'reviewer', true);
  perform tests.assert_raises(format('select public.admin_set_admin_role(%L, %L, false)', v_owner, 'owner'),
    'cannot_change_own_admin_role', 'owner cannot demote themselves');
  perform tests.assert((public.admin_overview() ->> 'total_channels')::integer >= 1, 'overview works for admins');
  perform tests.as_superuser();

  perform tests.as_user(v_u2, 'aal2');
  perform tests.assert_raises('select public.admin_overview()', 'forbidden', 'overview requires admin');
  perform tests.as_superuser();

  -- Favorites are private per user.
  insert into public.users (auth_user_id) values (v_u1) returning id into v_row1;
  insert into public.users (auth_user_id) values (v_u2) returning id into v_row2;
  perform tests.as_user(v_u1);
  insert into public.user_favorites (user_id, channel_id) values (v_row1, v_ch);
  perform tests.assert_raises(format('insert into public.user_favorites (user_id, channel_id) values (%L, %L)', v_row2, v_ch),
    'row-level security', 'cannot write another user''s favorites');
  perform tests.as_superuser();
  perform tests.as_user(v_u2);
  select count(*) into v_n from public.user_favorites;
  perform tests.assert_eq(v_n, 0::integer, 'cannot read another user''s favorites');
  select count(*) into v_n from public.users;
  perform tests.assert_eq(v_n, 1::integer, 'users see only their own profile');
  perform tests.as_superuser();
end $$;
