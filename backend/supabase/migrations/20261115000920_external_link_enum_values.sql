-- TVKu — enum values for "watch on the official platform" links.
-- Separate migration: new enum values must be committed before use.

alter type public.stream_format    add value if not exists 'EXTERNAL_LINK';
alter type public.health_test_type add value if not exists 'LINK_CHECK';
