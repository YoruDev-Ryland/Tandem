#!/bin/sh
# Container entrypoint: fix ownership of the data directory, then drop root and run the server as PUID:PGID.
set -e

PUID="${PUID:-1654}"
PGID="${PGID:-1654}"
DATA_DIR="${DATA_DIR:-/data}"
export DATA_DIR

mkdir -p "$DATA_DIR"
if [ "$(id -u)" = "0" ]; then
    # Only chown when needed: it is slow on a large library, and impossible on some network mounts.
    if [ "$(stat -c %u:%g "$DATA_DIR")" != "$PUID:$PGID" ]; then
        chown -R "$PUID:$PGID" "$DATA_DIR" || echo "warning: could not chown $DATA_DIR to $PUID:$PGID" >&2
    fi
    exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups /app/EmuSync.Server "$@"
fi
exec /app/EmuSync.Server "$@"
