#!/usr/bin/env bash
#
# Rehearses deploy/deploy.sh in a throwaway directory with stand-ins for
# systemctl, sudo and journalctl, so the swap / rollback / prune logic is
# proven on a real Linux filesystem (real symlinks, real zip files) before the
# workflow lets it near the gateway. Runs in CI before every deploy.
#
# A release whose id contains "bad" starts but never becomes healthy.
#
set -Euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
WORK="$(mktemp -d)"
trap 'kill "${LISTENER_PID:-}" 2>/dev/null; rm -rf "$WORK"' EXIT

export APP_DIR="$WORK/app" STATE="$WORK/state"
export HEALTH_URL="http://127.0.0.1:1/actuator/health" HEALTH_TIMEOUT_SECONDS=4 KEEP_RELEASES=2
export GRPC_PORT=19090
mkdir -p "$WORK/bin" "$APP_DIR/data" "$APP_DIR/certs"

# Stand-in for the gRPC port being open.
python3 -m http.server "$GRPC_PORT" --bind 127.0.0.1 >/dev/null 2>&1 &
LISTENER_PID=$!
for _ in $(seq 50); do (exec 3<>"/dev/tcp/127.0.0.1/$GRPC_PORT") 2>/dev/null && break; sleep 0.1; done

cat > "$WORK/bin/sudo" <<'EOF'
#!/usr/bin/env bash
exec "$@"
EOF
cat > "$WORK/bin/systemctl" <<'EOF'
#!/usr/bin/env bash
case "$1" in
  restart) [[ "$(readlink -f "$APP_DIR/fix-gateway.jar")" == *bad* ]] && echo down > "$STATE" || echo up > "$STATE" ;;
  is-active) [[ "$(cat "$STATE" 2>/dev/null)" == up ]] ;;
esac
EOF
cat > "$WORK/bin/curl" <<'EOF'
#!/usr/bin/env bash
[[ "$(cat "$STATE" 2>/dev/null)" == up ]] && echo '{"status":"UP"}' || exit 22
EOF
cat > "$WORK/bin/journalctl" <<'EOF'
#!/usr/bin/env bash
echo "(journal)"
EOF
chmod +x "$WORK/bin/"*
export PATH="$WORK/bin:$PATH"

FAILURES=0
pass() { echo "  ok   $*"; }
fail() {
    echo "  FAIL $*"
    FAILURES=$((FAILURES + 1))
    # Shows as an annotation on the run, readable without opening the log.
    [[ -n "${GITHUB_ACTIONS:-}" ]] && echo "::error title=deploy rehearsal::FAIL $* | $(tail -n 3 "$WORK/out.log" 2>/dev/null | tr '\n' ' ')"
    return 0
}
check() { local what="$1"; shift; if "$@"; then pass "$what"; else fail "$what"; fi; }

jar() { python3 -c 'import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], "w"); z.writestr("v", sys.argv[2]); z.close()' "$1" "$2"; }
live() { readlink "$APP_DIR/fix-gateway.jar"; }
deploy() { jar "$WORK/in.jar" "$2"; bash "$HERE/deploy.sh" "$WORK/in.jar" "$1" > "$WORK/out.log" 2>&1; }

jar "$APP_DIR/fix-gateway.jar" original
echo up > "$STATE"
echo "seq=42" > "$APP_DIR/data/seqnums"

echo "1) first deploy converts the plain jar into a symlink"
deploy 1-good v1; rc=$?
check "exit 0" test "$rc" -eq 0
check "fix-gateway.jar is a symlink" test -L "$APP_DIR/fix-gateway.jar"
check "points at release 1-good" test "$(live)" = "$APP_DIR/releases/fix-gateway-1-good.jar"
check "original jar kept as a release" compgen -G "$APP_DIR/releases/fix-gateway-pre-pipeline-*.jar" >/dev/null

echo "2) second good deploy"
deploy 2-good v2; rc=$?
check "exit 0" test "$rc" -eq 0
check "points at release 2-good" test "$(live)" = "$APP_DIR/releases/fix-gateway-2-good.jar"

echo "3) unhealthy deploy rolls back"
deploy 3-bad v3; rc=$?
check "exit 1" test "$rc" -eq 1
check "rolled back to 2-good" test "$(live)" = "$APP_DIR/releases/fix-gateway-2-good.jar"
check "service healthy again" test "$(cat "$STATE")" = up
check "deploy.log records the rollback" grep -q "FAILED fix-gateway-3-bad.jar - rolled back to fix-gateway-2-good.jar" "$APP_DIR/logs/deploy.log"

echo "4) corrupt upload is refused without touching the service"
printf 'PK\003\004truncated' > "$WORK/in.jar"
bash "$HERE/deploy.sh" "$WORK/in.jar" 4-corrupt > "$WORK/out.log" 2>&1; rc=$?
check "exit 1" test "$rc" -eq 1
check "still on 2-good" test "$(live)" = "$APP_DIR/releases/fix-gateway-2-good.jar"
check "no release file created" test ! -e "$APP_DIR/releases/fix-gateway-4-corrupt.jar"

echo "5) good deploy prunes old releases but keeps current and previous"
deploy 5-good v5; rc=$?
check "exit 0" test "$rc" -eq 0
check "points at release 5-good" test "$(live)" = "$APP_DIR/releases/fix-gateway-5-good.jar"
check "previous release 2-good kept" test -f "$APP_DIR/releases/fix-gateway-2-good.jar"
check "oldest release 1-good pruned" test ! -e "$APP_DIR/releases/fix-gateway-1-good.jar"

echo "6) update.sh installs a jar copied onto the server by hand"
cp "$HERE/deploy.sh" "$APP_DIR/deploy.sh"
jar "$WORK/manual.jar" v6
bash "$HERE/update.sh" "$WORK/manual.jar" > "$WORK/out.log" 2>&1; rc=$?
check "exit 0" test "$rc" -eq 0
check "points at a manual-* release" bash -c '[[ "$(readlink "$APP_DIR/fix-gateway.jar")" == "$APP_DIR"/releases/fix-gateway-manual-*.jar ]]'
check "the copied jar itself is left in place" test -f "$WORK/manual.jar"

echo "7) update.sh --rollback returns to the previous successful release"
before="$(readlink "$APP_DIR/fix-gateway.jar")"
# Taken now: the rollback installs a copy, and pruning may then remove the original.
want="$(sha256sum < "$APP_DIR/releases/fix-gateway-5-good.jar")"
bash "$HERE/update.sh" --rollback > "$WORK/out.log" 2>&1; rc=$?
check "exit 0" test "$rc" -eq 0
check "no longer on the manual release" test "$(readlink "$APP_DIR/fix-gateway.jar")" != "$before"
check "running the same jar content as 5-good" test "$(sha256sum < "$(readlink "$APP_DIR/fix-gateway.jar")")" = "$want"

echo "8) update.sh --status runs"
bash "$HERE/update.sh" --status > "$WORK/out.log" 2>&1; rc=$?
check "exit 0" test "$rc" -eq 0

echo "9) nothing outside the jar was touched"
check "data/ intact" test "$(cat "$APP_DIR/data/seqnums")" = "seq=42"
check "certs/ still there" test -d "$APP_DIR/certs"

if (( FAILURES > 0 )); then
    echo "deploy.sh rehearsal: $FAILURES check(s) failed - last run output:"
    cat "$WORK/out.log"
    exit 1
fi
echo "deploy.sh rehearsal: all checks passed"
