#!/system/bin/sh
DATA_DIR=/data/adb/vmq_ksu
OLD_LISTENER=com.shinian.pay/com.shinian.pay.service.PayNotificationListenerService
LEGACY_STATE=$DATA_DIR/legacy_listener_state
ABI=$(getprop ro.product.cpu.abi)
API=$(getprop ro.build.version.sdk)
[ "$ABI" = arm64-v8a ] || abort "V免签通知监听需要 arm64-v8a，当前为 $ABI"
[ "$API" = 35 ] || abort "V免签通知监听需要 Android API 35，当前为 $API"
[ -x /system/bin/app_process64 ] || abort "缺少 /system/bin/app_process64"
[ -r /system/framework/framework.jar ] || abort "缺少 /system/framework/framework.jar"
[ -f "$MODPATH/dex/vmq-listener.dex" ] || abort "缺少通知监听 DEX"
[ -f "$MODPATH/bin/vmqd" ] || abort "缺少 arm64 服务守护程序"
chmod 755 "$MODPATH/bin/vmqd" "$MODPATH/bin/vmqctl" || abort "无法设置可执行文件权限"
mkdir -p "$DATA_DIR" || abort "无法创建数据目录 $DATA_DIR"
chmod 700 "$DATA_DIR" || abort "无法保护数据目录 $DATA_DIR"
if [ ! -f "$DATA_DIR/config.json" ]; then
  cp "$MODPATH/defaults/config.json" "$DATA_DIR/config.json" || abort "无法创建 config.json"
fi
chmod 600 "$DATA_DIR/config.json" || abort "无法保护 config.json"
for DATA_FILE in desired_state state.json state.json.lock vmq.log vmq.log.1 queue.jsonl queue.jsonl.lock dedup.json dedup.json.lock; do
  [ ! -e "$DATA_DIR/$DATA_FILE" ] || chmod 600 "$DATA_DIR/$DATA_FILE" || abort "无法保护 $DATA_FILE"
done
if ! CLASSPATH="$MODPATH/dex/vmq-listener.dex" /system/bin/app_process64 /system/bin \
  com.vmq.ksu.ControlMain --root "$DATA_DIR" --module "$MODPATH" config-get >/dev/null; then
  abort "现有 config.json 配置无效"
fi
chmod 755 "$MODPATH/service.sh" "$MODPATH/action.sh" "$MODPATH/uninstall.sh" || abort "无法设置脚本权限"
chmod 644 "$MODPATH/dex/vmq-listener.dex" "$MODPATH/module.prop" "$MODPATH/sepolicy.rule" || abort "无法设置模块文件权限"
find "$MODPATH/webroot" -type d -exec chmod 755 {} \; || abort "无法设置控制面板目录权限"
find "$MODPATH/webroot" -type f -exec chmod 644 {} \; || abort "无法设置控制面板文件权限"
if pm path com.shinian.pay >/dev/null 2>&1; then
  if [ ! -f "$LEGACY_STATE" ]; then
    PACKAGE_STATE=$(dumpsys package com.shinian.pay 2>/dev/null | sed -n '/User 0:/,/User 10:/p')
    case "$PACKAGE_STATE" in
      *"disabledComponents:"*"com.shinian.pay.service.PayNotificationListenerService"*) printf 'disabled\n' >"$LEGACY_STATE" ;;
      *"enabledComponents:"*"com.shinian.pay.service.PayNotificationListenerService"*) printf 'enabled\n' >"$LEGACY_STATE" ;;
      *) printf 'default\n' >"$LEGACY_STATE" ;;
    esac
    chmod 600 "$LEGACY_STATE" || abort "无法保护旧监听器状态"
  fi
  pm disable --user 0 "$OLD_LISTENER" >/dev/null || abort "无法禁用旧 APK 通知监听器"
fi
ui_print "V免签通知监听安装完成，请在 KernelSU 控制面板中进行配置"
