#!/usr/bin/env bash
#
# Swaps a new fix-gateway jar into the running service, with automatic rollback.
#
#   deploy.sh <path-to-new-jar> <release-id>
#
# Run on the gateway instance as the service user (ubuntu), normally by the
# GitHub Actions workflow (.github/workflows/deploy-gateway.yml). Only the jar
# changes: config, certs/, data/ (FIX sequence numbers) and logs/ are never
# touched.
#
# Layout under APP_DIR:
#   fix-gateway.jar          symlink -> releases/fix-gateway-<release-id>.jar
#                            (the systemd unit's ExecStart path, unchanged)
#   releases/                one jar per deploy, last KEEP_RELEASES kept
#   logs/deploy.log          one line per deploy outcome
#
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/aurify-fixgateway}"
SERVICE="${SERVICE:-aurify-fixgateway}"
HEALTH_URL="${HEALTH_URL:-http://127.0.0.1:8080/actuator/health}"
GRPC_PORT="${GRPC_PORT:-9090}"
HEALTH_TIMEOUT_SECONDS="${HEALTH_TIMEOUT_SECONDS:-120}"
KEEP_RELEASES="${KEEP_RELEASES:-5}"

NEW_JAR="${1:?usage: deploy.sh <path-to-new-jar> <release-id>}"
RELEASE_ID="${2:?usage: deploy.sh <path-to-new-jar> <release-id>}"

LINK="$APP_DIR/fix-gateway.jar"
RELEASES="$APP_DIR/releases"
TARGET="$RELEASES/fix-gateway-$RELEASE_ID.jar"

log() { printf '%s [deploy %s] %s\n' "$(date -u +%FT%TZ)" "$RELEASE_ID" "$*"; }
record() { mkdir -p "$APP_DIR/logs"; log "$*" >> "$APP_DIR/logs/deploy.log"; }

# One deploy at a time - a second push while one is rolling out waits here.
exec 9>"$APP_DIR/.deploy.lock"
flock -w 600 9 || { log "another deploy is still running"; exit 1; }

# A truncated upload must never become the running jar.
if ! python3 -c 'import sys, zipfile; sys.exit(zipfile.ZipFile(sys.argv[1]).testzip() is not None)' "$NEW_JAR"; then
    log "new jar $NEW_JAR is not a valid archive - nothing changed"
    rm -f -- "$NEW_JAR"
    exit 1
fi

mkdir -p "$RELEASES"

# First run only: the live jar is a plain file. Keep it as a release so there
# is something to roll back to, then turn the path into a symlink.
if [[ -f "$LINK" && ! -L "$LINK" ]]; then
    legacy="$RELEASES/fix-gateway-pre-pipeline-$(date -u +%Y%m%d%H%M%S).jar"
    cp -p "$LINK" "$legacy"
    ln -sfn "$legacy" "$LINK.tmp" && mv -Tf "$LINK.tmp" "$LINK"
    log "converted $LINK to a symlink (previous jar kept as $(basename "$legacy"))"
fi

PREVIOUS="$(readlink -f "$LINK" 2>/dev/null || true)"

mv -f "$NEW_JAR" "$TARGET"

switch_to() {
    ln -sfn "$1" "$LINK.tmp"
    mv -Tf "$LINK.tmp" "$LINK"   # rename is atomic: the path is never missing
}

healthy() {
    local deadline=$((SECONDS + HEALTH_TIMEOUT_SECONDS))
    while (( SECONDS < deadline )); do
        if systemctl is-active --quiet "$SERVICE" \
            && curl -fsS -m 3 "$HEALTH_URL" 2>/dev/null | grep -q '"status":"UP"' \
            && (exec 3<>"/dev/tcp/127.0.0.1/$GRPC_PORT") 2>/dev/null; then
            return 0
        fi
        sleep 2
    done
    return 1
}

log "switching $LINK -> $(basename "$TARGET") (was $(basename "${PREVIOUS:-none}"))"
switch_to "$TARGET"
sudo systemctl restart "$SERVICE"

if healthy; then
    log "healthy: actuator UP and gRPC port $GRPC_PORT open"
    record "OK $(basename "$TARGET")"
else
    log "NOT healthy after ${HEALTH_TIMEOUT_SECONDS}s - last service log lines:"
    sudo journalctl -u "$SERVICE" -n 60 --no-pager || true

    if [[ -n "$PREVIOUS" && -f "$PREVIOUS" && "$PREVIOUS" != "$TARGET" ]]; then
        log "rolling back to $(basename "$PREVIOUS")"
        switch_to "$PREVIOUS"
        sudo systemctl restart "$SERVICE"
        if healthy; then
            log "rollback healthy"
            record "FAILED $(basename "$TARGET") - rolled back to $(basename "$PREVIOUS")"
        else
            log "rollback is NOT healthy either - check the service by hand"
            record "FAILED $(basename "$TARGET") - rollback to $(basename "$PREVIOUS") also unhealthy"
        fi
    else
        log "no previous release to roll back to"
        record "FAILED $(basename "$TARGET") - no rollback available"
    fi
    exit 1
fi

# Keep the newest KEEP_RELEASES jars, never deleting the live or previous one.
current="$(readlink -f "$LINK")"
ls -1t "$RELEASES"/fix-gateway-*.jar 2>/dev/null | tail -n +$((KEEP_RELEASES + 1)) | while read -r old; do
    [[ "$old" == "$current" || "$old" == "$PREVIOUS" ]] && continue
    rm -f -- "$old" && log "pruned $(basename "$old")"
done

log "done"
