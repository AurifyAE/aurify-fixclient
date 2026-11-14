#!/usr/bin/env bash
#
# Runs the fake LP (com.aurify.fixclient.loadtest.FakeLp) next to the gateway,
# for load tests. It answers orders only for LP accounts whose trading session
# points at it (the seeded LT-LP-* accounts), and by default listens on
# 127.0.0.1, so nothing outside this machine can reach it.
#
#   fake-lp.sh install [release-tag]   download fake-lp.jar (default: the installed gateway's release)
#   fake-lp.sh start [-Dfakelp.x=y ...] start in the background; settings as in FakeLpConfig
#   fake-lp.sh stop                    stop, printing the run summary to the log
#   fake-lp.sh status                  running or not, and the current run's order count
#   fake-lp.sh logs                    follow the log
#
# Example: fake-lp.sh start -Dfakelp.profile=fxcubic -Dfakelp.fillDelayMs=80
#
# The libraries come from the installed gateway jar, unpacked fresh on every
# start, so the fake LP always runs on the same QuickFIX/J and dictionaries as
# the gateway it trades with.
set -Eeuo pipefail

APP_DIR="${APP_DIR:-/opt/aurify-fixgateway}"
REPO="${REPO:-AurifyAE/aurify-fixclient}"
BASE="https://github.com/$REPO/releases"
DIR="${FAKE_LP_DIR:-$APP_DIR/fake-lp}"
PIDFILE="$DIR/fake-lp.pid"
LOG="$DIR/fake-lp.log"
JAVA="${JAVA:-java}"
MAIN="com.aurify.fixclient.loadtest.FakeLp"

die() { echo "fake-lp: $*" >&2; exit 1; }

# Java on Windows (Git Bash, for local runs) wants ';' and Windows paths.
SEP=":"
winpath() { printf '%s' "$1"; }
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) SEP=";"; winpath() { cygpath -w "$1"; } ;;
esac

gateway_jar() { readlink -f "$APP_DIR/fix-gateway.jar" 2>/dev/null || true; }
installed_tag() { gateway_jar | sed -n 's#.*/fix-gateway-\(.*\)\.jar$#\1#p'; }
running() { [ -f "$PIDFILE" ] && kill -0 "$(cat "$PIDFILE")" 2>/dev/null; }

unpack_libs() {
    local jar="$1" into="$2"
    if command -v unzip >/dev/null 2>&1; then
        (cd "$into" && unzip -q -o "$jar" 'BOOT-INF/lib/*' 'BOOT-INF/classes/*.xml')
    elif command -v python3 >/dev/null 2>&1; then
        python3 - "$jar" "$into" <<'PY'
import sys, zipfile
jar, into = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(jar) as z:
    for name in z.namelist():
        if name.startswith("BOOT-INF/lib/") or (name.startswith("BOOT-INF/classes/") and name.endswith(".xml")):
            z.extract(name, into)
PY
    else
        die "needs unzip or python3 to unpack the gateway's libraries"
    fi
}

cmd_install() {
    local tag="${1:-$(installed_tag)}"
    [ -n "$tag" ] || die "cannot tell which gateway release is installed; pass the release tag"
    mkdir -p "$DIR"
    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN
    for file in fake-lp.jar fake-lp.jar.sha256; do
        curl -fsSL --retry 3 -o "$tmp/$file" "$BASE/download/$tag/$file" \
            || die "release $tag has no $file (it predates the fake LP?); pass a newer tag"
    done
    (cd "$tmp" && sha256sum -c --quiet fake-lp.jar.sha256) || die "checksum mismatch for $tag, nothing changed"
    mv "$tmp/fake-lp.jar" "$DIR/fake-lp.jar"
    echo "$tag" > "$DIR/RELEASE"
    echo "fake-lp: installed from $tag"
}

cmd_start() {
    running && die "already running (pid $(cat "$PIDFILE")); stop it first"
    [ -f "$DIR/fake-lp.jar" ] || die "not installed: run '$0 install' first"
    local jar
    jar="$(gateway_jar)"
    [ -f "$jar" ] || die "no gateway jar at $APP_DIR/fix-gateway.jar"
    local built_for installed
    built_for="$(cat "$DIR/RELEASE" 2>/dev/null || true)"
    installed="$(installed_tag)"
    if [ -n "$built_for" ] && [ -n "$installed" ] && [ "$built_for" != "$installed" ]; then
        echo "fake-lp: note: fake-lp.jar is from $built_for, the gateway is $installed ('$0 install' to match)"
    fi

    rm -rf "$DIR/lib" && mkdir -p "$DIR/lib"
    unpack_libs "$jar" "$DIR/lib"
    local report="$DIR/orders-$(date +%Y%m%d-%H%M%S).csv"
    local cp
    cp="$(winpath "$DIR/fake-lp.jar")$SEP$(winpath "$DIR/lib/BOOT-INF/classes")$SEP$(winpath "$DIR/lib/BOOT-INF/lib")/*"

    echo "---- $(date -u +%FT%TZ) start $*" >> "$LOG"
    # shellcheck disable=SC2086
    nohup "$JAVA" ${FAKE_LP_JAVA_OPTS:-} -cp "$cp" "-Dfakelp.reportFile=$(winpath "$report")" "$@" "$MAIN" >> "$LOG" 2>&1 &
    echo $! > "$PIDFILE"

    for _ in $(seq 1 60); do
        if tail -n 20 "$LOG" | grep -q "\[fake-lp\] .* on port"; then
            tail -n 20 "$LOG" | grep "\[fake-lp\] .* on port"
            echo "fake-lp: running (pid $(cat "$PIDFILE")), orders -> $report"
            return 0
        fi
        running || { tail -n 30 "$LOG"; rm -f "$PIDFILE"; die "exited during start; log above"; }
        sleep 0.5
    done
    die "did not report listening within 30s; see $LOG"
}

cmd_stop() {
    if ! running; then
        rm -f "$PIDFILE"
        echo "fake-lp: not running"
        return 0
    fi
    local pid
    pid="$(cat "$PIDFILE")"
    kill "$pid"
    for _ in $(seq 1 20); do
        kill -0 "$pid" 2>/dev/null || break
        sleep 0.5
    done
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid"
    rm -f "$PIDFILE"
    grep "\[fake-lp\] received" "$LOG" | tail -n 1 || true
    echo "fake-lp: stopped"
}

cmd_status() {
    if running; then
        local report
        report="$(ls -t "$DIR"/orders-*.csv 2>/dev/null | head -n 1)"
        echo "fake-lp: running (pid $(cat "$PIDFILE"))"
        grep "\[fake-lp\] .* on port" "$LOG" | tail -n 1
        [ -n "$report" ] && echo "orders written so far: $(( $(wc -l < "$report") > 0 ? $(wc -l < "$report") - 1 : 0 )) in $report (written in batches of 100)"
    else
        echo "fake-lp: not running"
    fi
}

case "${1:-}" in
    install) shift; cmd_install "$@" ;;
    start) shift; cmd_start "$@" ;;
    stop) cmd_stop ;;
    status) cmd_status ;;
    logs) tail -n 50 -f "$LOG" ;;
    *) sed -n '3,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
