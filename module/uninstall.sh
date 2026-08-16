#!/system/bin/sh
MODDIR=${0%/*}
ROOT=/data/adb/vmq_ksu
OLD_LISTENER=com.shinian.pay/com.shinian.pay.service.PayNotificationListenerService
LEGACY_STATE=$ROOT/legacy_listener_state
SUPERVISOR_PID=
LISTENER_PID=

if [ -f "$ROOT/state.json" ]; then
  SUPERVISOR_PID=$(sed -n 's/.*"supervisor":{"pid":\([0-9-]*\).*/\1/p' "$ROOT/state.json")
  LISTENER_PID=$(sed -n 's/.*"listenerPid":\([0-9-]*\).*/\1/p' "$ROOT/state.json")
fi

is_owned_process() {
  CHECK_PID=$1
  CHECK_ROLE=$2
  [ -n "$CHECK_PID" ] && [ "$CHECK_PID" -gt 0 ] 2>/dev/null || return 1
  CHECK_CMDLINE=$(cat "/proc/$CHECK_PID/cmdline" 2>/dev/null || true)
  case "$CHECK_ROLE:$CHECK_CMDLINE" in
    supervisor:*vmqd*"$ROOT"*) return 0 ;;
    listener:*com.vmq.ksu.Main*--root*"$ROOT"*) return 0 ;;
  esac
  return 1
}

terminate_owned_process() {
  TARGET_PID=$1
  TARGET_ROLE=$2
  is_owned_process "$TARGET_PID" "$TARGET_ROLE" || return 0
  kill -TERM "$TARGET_PID" 2>/dev/null || true
  COUNT=0
  while is_owned_process "$TARGET_PID" "$TARGET_ROLE" && [ "$COUNT" -lt 10 ]; do
    sleep 1
    COUNT=$((COUNT + 1))
  done
  if is_owned_process "$TARGET_PID" "$TARGET_ROLE"; then
    kill -KILL "$TARGET_PID" 2>/dev/null || true
    COUNT=0
    while is_owned_process "$TARGET_PID" "$TARGET_ROLE" && [ "$COUNT" -lt 5 ]; do
      sleep 1
      COUNT=$((COUNT + 1))
    done
  fi
  ! is_owned_process "$TARGET_PID" "$TARGET_ROLE"
}

if [ -x "$MODDIR/bin/vmqctl" ]; then
  "$MODDIR/bin/vmqctl" stop >/dev/null 2>&1 || true
fi
terminate_owned_process "$SUPERVISOR_PID" supervisor || exit 1
terminate_owned_process "$LISTENER_PID" listener || exit 1
if [ -f "$LEGACY_STATE" ] && pm path com.shinian.pay >/dev/null 2>&1; then
  LEGACY_STATUS=$(cat "$LEGACY_STATE")
  case "$LEGACY_STATUS" in
    enabled) pm enable --user 0 "$OLD_LISTENER" >/dev/null 2>&1 || exit 1 ;;
    disabled) pm disable --user 0 "$OLD_LISTENER" >/dev/null 2>&1 || exit 1 ;;
    default) pm default-state --user 0 "$OLD_LISTENER" >/dev/null 2>&1 || exit 1 ;;
    *) exit 1 ;;
  esac
fi
rm -rf "$ROOT"
