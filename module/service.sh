#!/system/bin/sh
MODDIR=${0%/*}
ROOT=/data/adb/vmq_ksu
OLD_LISTENER=com.shinian.pay/com.shinian.pay.service.PayNotificationListenerService
COUNT=0
while [ "$(getprop sys.boot_completed)" != 1 ] && [ "$COUNT" -lt 180 ]; do
  sleep 1
  COUNT=$((COUNT + 1))
done
[ "$(getprop sys.boot_completed)" = 1 ] || exit 1
mkdir -p "$ROOT"
chmod 700 "$ROOT"
if [ ! -f "$ROOT/desired_state" ]; then
  printf 'running\n' >"$ROOT/desired_state"
fi
chmod 600 "$ROOT/desired_state"
if pm path com.shinian.pay >/dev/null 2>&1; then
  pm disable --user 0 "$OLD_LISTENER" >/dev/null 2>&1 || exit 1
fi
"$MODDIR/bin/vmqd" --root "$ROOT" --module "$MODDIR" >/dev/null 2>&1 &
exit 0
