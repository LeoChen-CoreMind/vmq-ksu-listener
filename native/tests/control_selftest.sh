#!/system/bin/sh
set -eu
CTL=${VMQCTL_BIN:-/data/local/tmp/vmq-module/bin/vmqctl}
ROOT=${VMQ_ROOT:-/data/local/tmp/vmq-control}
MODULE=${VMQ_MODULE:-/data/local/tmp/vmq-module}
rm -rf "$ROOT"
mkdir -p "$ROOT"
export VMQ_ROOT="$ROOT"
assert_json() {
  OUTPUT=$($CTL "$@")
  LINE_COUNT=$(printf '%s\n' "$OUTPUT" | wc -l)
  test "$LINE_COUNT" -eq 1
  printf '%s' "$OUTPUT" | grep -q '^{"ok":'
}
assert_error_json() {
  OUTPUT=$($CTL "$@" 2>/dev/null || true)
  printf '%s' "$OUTPUT" | grep -q '^{"ok":false,"error":'
}
VMQ_CONFIG_JSON=$(cat "$MODULE/defaults/config.json")
export VMQ_CONFIG_JSON
assert_json config-set
assert_error_json config-set extra
unset VMQ_CONFIG_JSON
VMQ_CONFIG_B64=$(printf '%s' "$(cat "$MODULE/defaults/config.json")" | base64 | tr -d '\n')
export VMQ_CONFIG_B64
assert_json config-set
unset VMQ_CONFIG_B64
assert_json config-get
assert_json status
assert_json test-connection
assert_json test-parser
assert_json logs
assert_error_json logs extra
assert_json clear-logs
assert_json start
grep -q '^description=V免签通知监听服务正在运行$' "$MODULE/module.prop"
sleep 1
assert_json restart
sleep 2
assert_json stop
grep -q '^description=V免签通知监听服务已停止运行$' "$MODULE/module.prop"
sleep 1
assert_json status
grep -q '"pid":-1' "$ROOT/state.json"
grep -q '"listenerPid":-1' "$ROOT/state.json"
echo "PASS: vmqctl commands return one JSON object, lifecycle is PID-scoped, and module status metadata is dynamic"
