#!/system/bin/sh
# 卸载时清理：停止守护进程、移除标记目录、把 AutoRunner 从无障碍列表移除
ROOTDIR=/data/local/tmp/autorunner_root
PKG=cn.helilab.autorunner
SVC="$PKG/$PKG.accessibility.AutoRunnerAccessibilityService"

[ -f "$ROOTDIR/daemon.pid" ] && kill "$(cat "$ROOTDIR/daemon.pid")" 2>/dev/null
pkill -f click_daemon.sh 2>/dev/null
rm -rf "$ROOTDIR"

LIST="$(settings get secure enabled_accessibility_services 2>/dev/null)"
NEW="$(echo "$LIST" | tr ':' '\n' | grep -v "^$SVC$" | paste -sd: -)"
[ -z "$NEW" ] && NEW="null"
settings put secure enabled_accessibility_services "$NEW" 2>/dev/null
echo "[uninstall] cleaned"
