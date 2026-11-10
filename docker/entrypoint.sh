#!/bin/sh
# Starts Hisaab. Runs as root only long enough to make the data and backup folders writable (volumes and
# bind mounts are often created root-owned), then drops to the unprivileged "hisaab" user.
set -e
mkdir -p "$STORAGE_DIR" "$BACKUP_DIR"
chown -R hisaab:hisaab "$STORAGE_DIR" "$BACKUP_DIR" 2>/dev/null || true
exec setpriv --reuid=hisaab --regid=hisaab --init-groups java $JAVA_OPTS -jar /app/app.jar
