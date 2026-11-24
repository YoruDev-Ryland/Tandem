#!/bin/sh
# Installed once per admin command name (list-users, create-user, reset-password, change-email, revoke-device,
# restore-backup) as a symlink in /usr/local/bin, so `docker exec tandem create-user me@example.com --admin` works.
# The command name is this script's own name ($0); it runs the server binary in admin mode as PUID:PGID, never as
# root, so files it creates under /data belong to the same user as the server's.
set -e

PUID="${PUID:-1654}"
PGID="${PGID:-1654}"
DATA_DIR="${DATA_DIR:-/data}"
export DATA_DIR
command="$(basename "$0")"

if [ "$(id -u)" = "0" ]; then
    exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups /app/Tandem.Server "$command" "$@"
fi
exec /app/Tandem.Server "$command" "$@"
