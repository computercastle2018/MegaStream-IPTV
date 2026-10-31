#!/usr/bin/env bash
set -euo pipefail
umask 077
base=/opt/megastream
stamp=$(date -u +%Y%m%dT%H%M%SZ)
backup="$base/backups/desktop-$stamp"
stage="$base/app-desktop-$stamp"
mkdir -m 700 "$backup" "$stage"
tar -czf "$backup/app.tar.gz" -C "$base/app" .
mariadb-dump --single-transaction --skip-lock-tables megastream > "$backup/megastream.sql"
test -s "$backup/megastream.sql"
tar -xzf "$base/megastream-desktop-server-3.0.16.tar.gz" -C "$stage"
test -s "$stage/MegaStream.Server.dll"
swapped=false
rollback() {
  if "$swapped"; then
    systemctl stop megastream || true
    if test -d "$base/app"; then mv "$base/app" "$backup/failed-app"; fi
    mv "$backup/live-app" "$base/app"
    systemctl start megastream
    echo 'DEPLOYMENT_ROLLED_BACK' >&2
  fi
}
trap rollback ERR
systemctl stop megastream
mv "$base/app" "$backup/live-app"
swapped=true
mv "$stage" "$base/app"
systemctl start megastream
for attempt in {1..20}; do
  if curl --fail --silent --max-time 3 -H 'Host: localhost' http://127.0.0.1:5080/health/ready > "$backup/health.json"; then
    echo "DEPLOYMENT_READY backup=$backup"
    exit 0
  fi
  sleep 1
done
exit 1
