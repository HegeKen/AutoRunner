#!/system/bin/sh
# 卸载时清理：停止守护进程、移除标记、把 AutoRunner 从无障碍与通知使用权列表移除，
# 并卸载随 root 模块安装的超级岛解锁 LSPosed 模块。
ROOTDIR=/data/local/tmp/autorunner_root
PKG=cn.helilab.autorunner
SVC="$PKG/$PKG.accessibility.AutoRunnerAccessibilityService"
NL_SVC="$PKG/$PKG.notification.AutoRunnerNotificationListener"

[ -f "$ROOTDIR/daemon.pid" ] && kill "$(cat "$ROOTDIR/daemon.pid")" 2>/dev/null
pkill -f click_daemon.sh 2>/dev/null
rm -rf "$ROOTDIR"

LIST="$(settings get secure enabled_accessibility_services 2>/dev/null)"
NEW="$(echo "$LIST" | tr ':' '\n' | grep -v "^$SVC$" | paste -sd: -)"
[ -z "$NEW" ] && NEW="null"
settings put secure enabled_accessibility_services "$NEW" 2>/dev/null

# 通知使用权与 Android 15+ 的敏感通知 appop 一并撤销。
# 列表读不到时保持原样，避免把其它 App 的授权一起清掉。
NL_LIST="$(settings get secure enabled_notification_listeners 2>/dev/null)"
if [ -n "$NL_LIST" ]; then
  NL_NEW="$(echo "$NL_LIST" | tr ':' '\n' | grep -v "^$NL_SVC$" | paste -sd: -)"
  [ -z "$NL_NEW" ] && NL_NEW="null"
  settings put secure enabled_notification_listeners "$NL_NEW" 2>/dev/null
fi
appops set "$PKG" RECEIVE_SENSITIVE_NOTIFICATIONS default 2>/dev/null

# 超级岛解锁 LSPosed 模块（若用户没有单独保留）
pm uninstall cn.helilab.autorunner.xposed >/dev/null 2>&1
rm -f /data/local/tmp/autorunner_root/focus_unlock 2>/dev/null

echo "[uninstall] cleaned"
