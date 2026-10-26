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

# 8) 通知使用权（NotificationListenerService）：系统把已授权的组件以冒号分隔存
#    在 secure settings 里。Root 直接并入列表后系统会自动绑定，用户无需去
#    「通知使用权」页面手动勾选；App 被重装/更新后经 `perms` 命令按需补回。
NL_COMPONENT="$PKG/$PKG.notification.AutoRunnerNotificationListener"
NL_LIST="$(settings get secure enabled_notification_listeners 2>/dev/null)"
# 读不到列表时不要写：空列表会被写成「只有本应用」，反而清掉其它 App 的授权。
if [ -z "$NL_LIST" ]; then
  log "notification listener list unreadable; fallback to allow_listener"
  cmd notification allow_listener "$NL_COMPONENT" >/dev/null 2>&1
else
  case "$NL_LIST" in
    *"$NL_COMPONENT"*) log "notification listener already enabled" ;;
    *)
      case "$NL_LIST" in
        "null") NL_NEW="$NL_COMPONENT" ;;
        *)      NL_NEW="$NL_LIST:$NL_COMPONENT" ;;
      esac
      settings put secure enabled_notification_listeners "$NL_NEW" 2>/dev/null
      # 部分 ROM 由 NotificationManagerService 的 allowlist 控制，再走一次 shell
      # 命令兜底；旧系统没有该子命令，失败忽略。
      cmd notification allow_listener "$NL_COMPONENT" >/dev/null 2>&1
      if settings get secure enabled_notification_listeners 2>/dev/null | grep -qF "$NL_COMPONENT"; then
        log "notification listener enabled"
      else
        log "notification listener NOT persisted (将在 App 侧重试)"
      fi
      ;;
  esac
fi

# 9) Android 15+：通知监听默认读不到短信验证码等「敏感通知」的正文，
#    需要单独允许这个 appop。旧系统上命令会失败，忽略即可。
set_op RECEIVE_SENSITIVE_NOTIFICATIONS

log "done"
