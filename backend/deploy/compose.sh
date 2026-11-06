#!/usr/bin/env bash
set -euo pipefail
exec docker compose --env-file /etc/my-notes/production.env \
  --project-directory /opt/my-notes/backend \
  -f /opt/my-notes/backend/compose.vps.yaml "$@"
