# TVKu — Global Live TV

*Your World. Your Television.*

TVKu is an Android app for discovering and watching **verified, authorised, free-to-watch** television from around the world. Only streams that (1) passed a real Media3 playback test and (2) were approved by a human licensing review are ever shown to users.

> **Status:** early development. See [`docs/ARCHITECTURE_AND_ROADMAP.md`](docs/ARCHITECTURE_AND_ROADMAP.md) for the plan and current milestone. Nothing here is production-ready yet.

## Repository layout

| Path | Contents | Status |
|---|---|---|
| `backend/supabase/migrations/` | PostgreSQL schema, RLS, server-side trial / donation / premium / stream-health logic | ✅ M1 |
| `backend/supabase/seed/` | Countries, languages, categories, default config (no secrets) | ✅ M1 |
| `backend/tests/` | SQL test suite run against a real PostgreSQL | ✅ M1 |
| `scripts/database-import/` | Reference data generator | ✅ M1 |
| `app/`, `core/`, `feature/` | Android app (Kotlin, Compose, Media3) | ⏳ M2 |
| `backend/supabase/functions/` | Edge Functions | ⏳ M6 |
| `scripts/stream-*` | Discovery and validation pipeline | ⏳ M4–M5 |
| `admin-dashboard/` | Admin web dashboard | ⏳ M9 |

## Running the backend tests

Requires a PostgreSQL 15+ server you can create databases on. The suite creates (and drops) a database called `tvku_test`.

```bash
ADMIN_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres \
  backend/tests/run_sql_tests.sh
```

`backend/tests/sql/_supabase_stub.sql` emulates the Supabase roles and `auth` schema so migrations and RLS can be tested on plain PostgreSQL. It must never be applied to a real Supabase project.

## Legal

TVKu hosts no video. Discovery sources such as iptv-org are treated as leads, not as permission. See the architecture document for the licensing review process.
