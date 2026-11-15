#!/usr/bin/env bash
# Applies every migration to a fresh database and runs the SQL test suite.
#
#   ADMIN_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/postgres \
#     backend/tests/run_sql_tests.sh
#
# Each tests/sql/test_*.sql file runs inside a transaction that is rolled back.
# Exit code is non-zero if migrations fail or any test fails.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKEND="$(cd "$HERE/.." && pwd)"
ADMIN_URL="${ADMIN_DATABASE_URL:?set ADMIN_DATABASE_URL to a superuser connection string}"
TEST_DB="${TEST_DB_NAME:-tvku_test}"
TEST_URL="$(python3 - "$ADMIN_URL" "$TEST_DB" <<'PY'
import sys, urllib.parse as u
p = u.urlsplit(sys.argv[1]); print(u.urlunsplit(p._replace(path="/" + sys.argv[2])))
PY
)"

PSQL=(psql -X -q -v ON_ERROR_STOP=1 --no-psqlrc)

echo "==> Recreating database $TEST_DB"
"${PSQL[@]}" "$ADMIN_URL" -c "drop database if exists $TEST_DB with (force)" -c "create database $TEST_DB"

echo "==> Supabase stub"
"${PSQL[@]}" "$TEST_URL" -f "$HERE/sql/_supabase_stub.sql"

echo "==> Migrations"
for f in "$BACKEND"/supabase/migrations/*.sql; do
  echo "    $(basename "$f")"
  "${PSQL[@]}" "$TEST_URL" -f "$f"
done

SEEDS=(reference_data.sql base_config.sql candidates_discovered.sql)
echo "==> Seed"
for s in "${SEEDS[@]}"; do "${PSQL[@]}" "$TEST_URL" -f "$BACKEND/supabase/seed/$s"; done

echo "==> Re-applying seed (idempotency)"
for s in "${SEEDS[@]}"; do "${PSQL[@]}" "$TEST_URL" -f "$BACKEND/supabase/seed/$s"; done

echo "==> Test helpers"
"${PSQL[@]}" "$TEST_URL" -f "$HERE/sql/_helpers.sql"

pass=0; fail=0; failed=()
for t in "$HERE"/sql/test_*.sql; do
  name="$(basename "$t" .sql)"
  if out="$("${PSQL[@]}" "$TEST_URL" -c "begin" -f "$t" -c "rollback" 2>&1)"; then
    echo "  PASS  $name"; pass=$((pass + 1))
  else
    echo "  FAIL  $name"; echo "$out" | sed 's/^/        /'; fail=$((fail + 1)); failed+=("$name")
  fi
done

echo
echo "SQL tests: $pass passed, $fail failed"
[[ $fail -eq 0 ]]
