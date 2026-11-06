#!/usr/bin/env bash
set -euo pipefail
umask 077
exec 9>/run/lock/my-notes-backup.lock
flock -n 9 || exit 0
backup_dir=/var/backups/my-notes
install -d -m 700 "$backup_dir"
archive="$backup_dir/my-notes-$(date -u +%Y%m%dT%H%M%SZ).dump"
trap 'rm -f "${archive}.partial"' EXIT
/usr/local/sbin/my-notes-compose exec -T postgres \
  sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' > "${archive}.partial" < /dev/null
test -s "${archive}.partial"
/usr/local/sbin/my-notes-compose exec -T postgres pg_restore --list < "${archive}.partial" > /dev/null
mv "${archive}.partial" "$archive"
# Retain successful archives for 14 days; never remove unrelated files.
find "$backup_dir" -maxdepth 1 -type f -name 'my-notes-????????T??????Z.dump' -mtime +13 -delete
printf 'Backup completed: %s\n' "$archive"
