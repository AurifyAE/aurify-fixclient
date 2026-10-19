#!/usr/bin/env bash
#
# Installs a FIX gateway build on this server - run it yourself, as ubuntu.
#
#   update.sh                    newest build published by GitHub Actions
#   update.sh gateway-42-abc123  a specific build (tag from the Releases page)
#   update.sh ./fix-gateway.jar  a jar you copied onto the server yourself
#   update.sh --rollback         back to the jar that ran before this one
#   update.sh --status           what is running, and the last deploys
#
# Downloads come from the public GitHub Releases of the repo and are checked
# against their published SHA-256. The swap itself (health check, automatic
# rollback, pruning) is deploy.sh.
#
set -Eeuo pipefail

REPO="${REPO:-AurifyAE/aurify-fixclient}"
APP_DIR="${APP_DIR:-/opt/aurify-fixgateway}"
SERVICE="${SERVICE:-aurify-fixgateway}"
BASE="https://github.com/$REPO/releases"
LINK="$APP_DIR/fix-gateway.jar"
RELEASES="$APP_DIR/releases"

die() { echo "update.sh: $*" >&2; exit 1; }

if [[ $EUID -eq 0 ]]; then
    die "run as the service user (ubuntu), not with sudo - deploy.sh calls sudo itself"
fi

live_jar() { readlink -f "$LINK" 2>/dev/null || echo "$LINK"; }

status() {
    echo "Running:  $(basename "$(live_jar)")"
    echo "Service:  $(systemctl is-active "$SERVICE" 2>/dev/null || true)"
    echo "Health:   $(curl -fsS -m 3 http://127.0.0.1:8080/actuator/health 2>/dev/null || echo unreachable)"
    echo
    echo "Releases on this server (newest first):"
    ls -1t "$RELEASES"/fix-gateway-*.jar 2>/dev/null | xargs -r -n1 basename | sed 's/^/  /' || true
    echo
    echo "Last deploys:"
    tail -n 10 "$APP_DIR/logs/deploy.log" 2>/dev/null | sed 's/^/  /' || echo "  (none yet)"
}

# Runs deploy.sh on a jar already on this server.
install_jar() {
    local jar="$1" id="$2"
    [[ -f "$APP_DIR/deploy.sh" ]] || die "$APP_DIR/deploy.sh is missing - run update.sh with no arguments once to fetch it"
    mkdir -p "$RELEASES"
    local incoming="$RELEASES/.incoming-$id.jar"
    cp -- "$jar" "$incoming"
    bash "$APP_DIR/deploy.sh" "$incoming" "$id"
}

from_github() {
    local tag="${1:-}"
    if [[ -z "$tag" ]]; then
        # /releases/latest redirects to /releases/tag/<newest tag>
        tag="$(curl -fsSLI -o /dev/null -w '%{url_effective}' "$BASE/latest")"
        tag="${tag##*/}"
        [[ "$tag" == gateway-* ]] || die "could not find the latest gateway build on $BASE (got '$tag')"
    fi

    if [[ "$(live_jar)" == *"fix-gateway-$tag.jar" ]]; then
        echo "$tag is already running - nothing to do."
        exit 0
    fi

    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' EXIT

    echo "Downloading $tag ..."
    for file in fix-gateway.jar fix-gateway.jar.sha256 deploy.sh update.sh; do
        curl -fsSL --retry 3 -o "$tmp/$file" "$BASE/download/$tag/$file" \
            || die "could not download $file for $tag - check the tag on $BASE"
    done
    (cd "$tmp" && sha256sum -c --quiet fix-gateway.jar.sha256) \
        || die "checksum mismatch for $tag - download is corrupt, nothing changed"

    # Keep the scripts in step with the build they came with.
    install -m 755 "$tmp/deploy.sh" "$APP_DIR/deploy.sh"

    install_jar "$tmp/fix-gateway.jar" "$tag"

    # Replaced last, by rename: this script is still running from the old file.
    install -m 755 "$tmp/update.sh" "$APP_DIR/update.sh.new" && mv -f "$APP_DIR/update.sh.new" "$APP_DIR/update.sh"
}

rollback() {
    local current previous=""
    current="$(live_jar)"
    # The newest successful deploy, other than the one running now.
    while read -r name; do
        if [[ -f "$RELEASES/$name" && "$RELEASES/$name" != "$current" ]]; then
            previous="$RELEASES/$name"
            break
        fi
    done < <(grep -oE '\] OK fix-gateway-[^ ]+\.jar' "$APP_DIR/logs/deploy.log" 2>/dev/null | sed 's/^\] OK //' | tac)

    [[ -n "$previous" ]] || die "no earlier successful release found in $APP_DIR/logs/deploy.log"
    echo "Rolling back from $(basename "$current") to $(basename "$previous")"
    local id="${previous##*/fix-gateway-}"
    install_jar "$previous" "rollback-$(date -u +%Y%m%d%H%M%S)-${id%.jar}"
}

case "${1:-}" in
    --status|-s)   status ;;
    --rollback|-r) rollback ;;
    --help|-h)     sed -n '3,14p' "$0" | sed 's/^# \{0,1\}//' ;;
    "")            from_github ;;
    *.jar)         [[ -f "$1" ]] || die "no such file: $1"
                   install_jar "$1" "manual-$(date -u +%Y%m%d%H%M%S)" ;;
    gateway-*)     from_github "$1" ;;
    *)             die "unknown argument '$1' - see update.sh --help" ;;
esac
