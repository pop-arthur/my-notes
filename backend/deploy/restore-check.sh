#!/usr/bin/env bash
set -euo pipefail
umask 077
archive=${1:?Usage: my-notes-restore-check /var/backups/my-notes/archive.dump}
test -s "$archive"
# This script never restores over the application database.
temporary_db="my_notes_restore_$(date -u +%Y%m%d%H%M%S)_$$"
compose=/usr/local/sbin/my-notes-compose
cleanup() { "$compose" exec -T postgres dropdb -U postgres --if-exists "$temporary_db" < /dev/null; }
trap cleanup EXIT
"$compose" exec -T postgres createdb -U postgres -O my_notes "$temporary_db" < /dev/null
"$compose" exec -T postgres pg_restore -U postgres --dbname="$temporary_db" \
  --no-owner --role=my_notes --exit-on-error --single-transaction < "$archive"
"$compose" exec -T postgres psql -X -v ON_ERROR_STOP=1 -U postgres -d "$temporary_db" \
  -c 'SELECT version, success FROM flyway_schema_history ORDER BY installed_rank;' \
  -c 'SELECT count(*) AS users FROM users;' \
  -c 'SELECT count(*) AS tasks FROM tasks;' \
  -c 'SELECT count(*) AS sessions FROM spring_session;' < /dev/null
printf 'Restore verified in temporary database: %s (removed on exit)\n' "$temporary_db"
