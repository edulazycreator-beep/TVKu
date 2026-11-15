-- TVKu — enum values for official embedded players (YouTube).
-- Kept in its own migration: PostgreSQL only allows a new enum value to be
-- used after the transaction that added it has committed.

alter type public.stream_format    add value if not exists 'YOUTUBE_EMBED';
alter type public.health_test_type add value if not exists 'EMBED_STATUS_CHECK';
