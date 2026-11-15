-- TVKu — categories and default application configuration.
-- Safe to re-run. Contains no secrets and no real phone numbers.

insert into public.categories (slug, name, sort_order)
select v.slug, v.name, v.sort_order
  from (values
    ('news', 'News', 10), ('sports', 'Sports', 20), ('entertainment', 'Entertainment', 30),
    ('movies', 'Movies', 40), ('music', 'Music', 50), ('documentary', 'Documentary', 60),
    ('kids', 'Kids', 70), ('education', 'Education', 80), ('religious', 'Religious', 90),
    ('general', 'General', 100)
  ) as v(slug, name, sort_order)
on conflict (slug) do update set name = excluded.name, sort_order = excluded.sort_order;

insert into public.app_config (config_key, config_value, is_public, description) values
  ('whatsapp_admin_number',            'null'::jsonb,      true,  'WhatsApp number for donation confirmation: digits only, international format without +. Set in the admin dashboard.'),
  ('donation_currency',                '"USD"'::jsonb,     true,  'Default donation currency (ISO 4217).'),
  ('donation_suggested_amounts',       '[2, 5, 10, 25]'::jsonb, true, 'Suggested donation amounts in the default currency.'),
  ('donation_instructions',            '""'::jsonb,        true,  'Payment instructions shown on the donation screen.'),
  ('donation_min_amount',              '1'::jsonb,         true,  'Minimum donation amount.'),
  ('donation_max_amount',              '100000'::jsonb,    false, 'Maximum donation amount accepted by create_donation.'),
  ('donation_max_pending_per_installation', '3'::jsonb,    false, 'Maximum simultaneous PENDING donations per installation.'),
  ('premium_default_days',             '30'::jsonb,        false, 'Default premium validity after an approved donation.'),
  ('trial_duration_seconds',           '18000'::jsonb,     true,  'Premium trial length (5 hours). Fixed by product rule.'),
  ('stream_offline_failure_threshold', '5'::jsonb,         false, 'Consecutive transient failures before a stream becomes OFFLINE.'),
  ('stream_min_playback_seconds',      '60'::jsonb,        false, 'Minimum observed playback for a Tier B verification.'),
  ('maintenance_mode',                 'false'::jsonb,     true,  'When true the app shows a maintenance notice.'),
  ('min_supported_version_code',       '1'::jsonb,         true,  'Oldest Android versionCode allowed to run.'),
  ('feature_flags',                    '{}'::jsonb,        true,  'Remote feature flags.')
on conflict (config_key) do nothing;
