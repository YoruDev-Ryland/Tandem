#!/bin/sh
# Container entrypoint: fix ownership of the data directory, then drop root and run the server as PUID:PGID.
set -e

PUID="${PUID:-1654}"
PGID="${PGID:-1654}"
DATA_DIR="${DATA_DIR:-/data}"
export DATA_DIR

# Running the server as root is almost never wanted; require an explicit opt-in.
if { [ "$PUID" = "0" ] || [ "$PGID" = "0" ]; } && [ "${ALLOW_ROOT:-}" != "true" ]; then
    echo "error: PUID/PGID 0 would run the server as root. Set ALLOW_ROOT=true if you really want that." >&2
    exit 1
fi

mkdir -p "$DATA_DIR"
if [ "$(id -u)" = "0" ]; then
    # Only chown when needed: it is slow on a large library, and impossible on some network mounts.
    if [ "$(stat -c %u:%g "$DATA_DIR")" != "$PUID:$PGID" ]; then
        chown -R "$PUID:$PGID" "$DATA_DIR" || echo "warning: could not chown $DATA_DIR to $PUID:$PGID" >&2
    fi
    exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups /app/Tandem.Server "$@"
fi
exec /app/Tandem.Server "$@"
