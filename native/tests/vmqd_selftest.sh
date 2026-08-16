#!/system/bin/sh
set -eu
BIN=${VMQD_BIN:-/data/adb/modules/vmq_ksu/bin/vmqd}
ROOT=${VMQD_ROOT:-/data/local/tmp/vmqd-selftest}
MODULE=${VMQD_MODULE:-/data/adb/modules/vmq_ksu}
RUNTIME_MODULE=${VMQD_RUNTIME_MODULE:-${ROOT}-module}
rm -rf "$ROOT"
rm -rf "$RUNTIME_MODULE"
mkdir -p "$ROOT"
mkdir -p "$RUNTIME_MODULE"
cp "$MODULE/defaults/config.json" "$ROOT/config.json"
cp "$MODULE/module.prop" "$RUNTIME_MODULE/module.prop"
ln -s "$MODULE/dex" "$RUNTIME_MODULE/dex"
SUPERVISOR=
cleanup() {
  if [ -n "${SUPERVISOR:-}" ]; then
    kill "$SUPERVISOR" 2>/dev/null || true
    wait "$SUPERVISOR" 2>/dev/null || true
  fi
  rm -rf "$RUNTIME_MODULE"
}
trap cleanup EXIT INT TERM
"$BIN" --root "$ROOT" --module "$RUNTIME_MODULE" >/dev/null 2>&1 &
SUPERVISOR=$!
sleep 1
test -f "$ROOT/state.json"
grep -q '^description=V免签通知监听服务正在运行$' "$RUNTIME_MODULE/module.prop"
grep -q '^id=vmq_ksu$' "$RUNTIME_MODULE/module.prop"
if "$BIN" --root "$ROOT" --module "$RUNTIME_MODULE" >/dev/null 2>&1; then
  echo "second supervisor unexpectedly acquired lock" >&2
  exit 1
fi
CHILD=$(sed -n 's/.*"listenerPid":\([0-9-]*\).*/\1/p' "$ROOT/state.json")
test -n "$CHILD" && test "$CHILD" -gt 0
kill -9 "$CHILD"
sleep 2
NEW_CHILD=$(sed -n 's/.*"listenerPid":\([0-9-]*\).*/\1/p' "$ROOT/state.json")
test -n "$NEW_CHILD" && test "$NEW_CHILD" -gt 0 && test "$NEW_CHILD" != "$CHILD"
kill "$SUPERVISOR"
wait "$SUPERVISOR" || true
SUPERVISOR=
test -f "$ROOT/state.json"
FINAL=$(sed -n 's/.*"listenerPid":\([0-9-]*\).*/\1/p' "$ROOT/state.json")
test "$FINAL" = "-1"
FINAL_SUPERVISOR=$(sed -n 's/.*"supervisor":{"pid":\([0-9-]*\).*/\1/p' "$ROOT/state.json")
test "$FINAL_SUPERVISOR" = "-1"
grep -q '^description=V免签通知监听服务已停止运行$' "$RUNTIME_MODULE/module.prop"

ORPHAN_ROOT="${ROOT}-orphan"
rm -rf "$ORPHAN_ROOT"
mkdir -p "$ORPHAN_ROOT"
cp "$MODULE/defaults/config.json" "$ORPHAN_ROOT/config.json"
printf 'running\n' >"$ORPHAN_ROOT/desired_state"
"$BIN" --root "$ORPHAN_ROOT" --module "$RUNTIME_MODULE" >/dev/null 2>&1 &
SUPERVISOR=$!
sleep 1
ORPHAN_CHILD=$(sed -n 's/.*"listenerPid":\([0-9-]*\).*/\1/p' "$ORPHAN_ROOT/state.json")
test -n "$ORPHAN_CHILD" && test "$ORPHAN_CHILD" -gt 0
kill -9 "$SUPERVISOR"
wait "$SUPERVISOR" 2>/dev/null || true
SUPERVISOR=
sleep 2
if kill -0 "$ORPHAN_CHILD" 2>/dev/null; then
  echo "listener survived supervisor SIGKILL" >&2
  exit 1
fi
rm -rf "$ORPHAN_ROOT"
echo "PASS: vmqd lock, child restart, graceful cleanup, parent-death cleanup, and module status metadata"
