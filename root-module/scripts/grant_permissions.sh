#!/system/bin/sh
# 默认授予 AutoRunner 运行所需的全部权限（Root 场景）。
# 无障碍服务由 enable_accessibility.sh 负责；这里处理其余权限/appop。
PKG=cn.helilab.autorunner
log() { echo "[grant] $1"; }

# 1) 通知（Android 13+ 的运行时权限）
pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null && log "POST_NOTIFICATIONS granted"

# 2) 悬浮窗：属于 appop，不是运行时权限
appops set "$PKG" SYSTEM_ALERT_WINDOW allow 2>/dev/null && log "SYSTEM_ALERT_WINDOW allowed"

# 3) 忽略电池优化（deviceidle 白名单）
dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 && log "battery whitelist added"

# 4) 允许后台运行（部分 ROM 会冻结后台任务）
appops set "$PKG" RUN_IN_BACKGROUND allow 2>/dev/null && log "RUN_IN_BACKGROUND allowed"
appops set "$PKG" RUN_ANY_IN_BACKGROUND allow 2>/dev/null && log "RUN_ANY_IN_BACKGROUND allowed"

# 5) 前台服务（长时间执行 + 悬浮面板通知）
appops set "$PKG" START_FOREGROUND allow 2>/dev/null && log "START_FOREGROUND allowed"

# 6) Android 9 及以下导出模块需要写公共下载目录（10+ 用 MediaStore，无需权限）
pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null
pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null

# 7) 撤销系统的"未使用应用休眠"，避免脚本被限制
am set-inactive "$PKG" false >/dev/null 2>&1
log "done"
