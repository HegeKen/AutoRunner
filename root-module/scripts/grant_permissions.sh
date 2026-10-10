#!/system/bin/sh
# 默认授予 AutoRunner 运行所需的全部权限（Root 场景）。
# 无障碍服务由 enable_accessibility.sh 负责；这里处理其余权限/appop。
#
# 本脚本会在两个时机执行：
#   * 开机时由 service.sh 调用；
#   * App 检测到权限缺失时，经守护进程的 `perms` 命令按需调用
#     ——因为 App 被重装/更新后，运行时权限与 appop 会被系统重置，
#     只靠开机授予会出现「模块在、权限却没有」的情况。
PKG=cn.helilab.autorunner
log() { echo "[grant] $1"; }

# appops 设置后回读校验：部分 ROM 上命令返回 0 但并未真正写入，
# 因此只信回读结果。参数：op 编号或名称
set_op() {
  appops set "$PKG" "$1" allow 2>/dev/null
  if appops get "$PKG" "$1" 2>/dev/null | grep -q "allow"; then
    log "appop $1 allowed"
  else
    log "appop $1 NOT applied"
  fi
}

# 1) 通知（Android 13+ 的运行时权限）
if pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null; then
  if dumpsys package "$PKG" 2>/dev/null | grep -q "android.permission.POST_NOTIFICATIONS: granted=true"; then
    log "POST_NOTIFICATIONS granted"
  else
    log "POST_NOTIFICATIONS not persisted (将在 App 侧按需重试)"
  fi
fi

# 2) 悬浮窗：属于 appop，不是运行时权限
set_op SYSTEM_ALERT_WINDOW

# 2b) MIUI / HyperOS 专有 appop（普通 ROM 上 appops 会直接报错，忽略即可）
#   10008 —— 自启动
#   10021 —— 后台弹出界面（MIUI 2019 起默认拒绝，不授予则后台无法启动界面）
set_op 10008
set_op 10021

# 3) 忽略电池优化（deviceidle 白名单）
dumpsys deviceidle whitelist "+$PKG" >/dev/null 2>&1 && log "battery whitelist added"

# 4) 允许后台运行（部分 ROM 会冻结后台任务）
set_op RUN_IN_BACKGROUND
set_op RUN_ANY_IN_BACKGROUND

# 5) 前台服务（长时间执行 + 悬浮面板通知）
set_op START_FOREGROUND

# 6) Android 9 及以下导出模块需要写公共下载目录（10+ 用 MediaStore，无需权限）
pm grant "$PKG" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null
pm grant "$PKG" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null

# 7) 撤销系统的"未使用应用休眠"，避免脚本被限制
am set-inactive "$PKG" false >/dev/null 2>&1
log "done"
