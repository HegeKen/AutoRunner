#!/system/bin/sh
# Root 模式输入注入桥。
#
# 协议（App 往命令文件里按行追加，守护进程读取后清空并执行）：
#   tap X Y
#   long X Y DURATION_MS
#   swipe X1 Y1 X2 Y2 DURATION_MS
#   key KEYCODE
#   text STRING
#   a11y            （幂等补回无障碍服务条目，供 App 在检测到缺失时按需触发）
#   perms           （按需重新授予通知 / 悬浮窗等权限：App 重装或更新后这些
#                     授权会被系统重置，不能只等下次开机）
#
# 安全模型：
#   * 命令文件位于 AutoRunner 的私有目录（0700，只有该 App 与 root 可读写），
#     因此只有 AutoRunner 能下发指令，第三方 App 无法伪造；
#   * 拒绝符号链接，读取后立即清空，避免残留指令被重放；
#   * 所有输入由 root shell 调用 input 执行，具备 INJECT_EVENTS 权限
#     （这正是 MIUI/adb shell 下被拒绝的能力）。
PKG=cn.helilab.autorunner
CMDFILE=/data/data/$PKG/files/root_input.cmd
ROOTDIR=/data/local/tmp/autorunner_root
SCRIPT_DIR=${0%/*}
mkdir -p "$ROOTDIR" 2>/dev/null

# 无障碍自愈（按需）：开机补写由 service.sh 负责；系统「强停应用」（am force-stop、
# Gradle installDebug 等）会 unbind 并重写 enabled_accessibility_services，把本服务
# 从列表移除。守护进程本身不做任何轮询（避免周期性 spawn settings 的耗电），而是等
# App 检测到缺失时经命令文件下发一条 `a11y`，在这里即时补回。
echo "[daemon] started $(date) cmd=$CMDFILE"

while true; do
  # 等待命令文件出现且非空
  if [ ! -e "$CMDFILE" ] || [ -L "$CMDFILE" ] || [ ! -s "$CMDFILE" ]; then
    sleep 0.2
    continue
  fi

  # 读走内容并立即清空（先读后清，避免半行）
  CONTENT="$(cat "$CMDFILE" 2>/dev/null)"
  : > "$CMDFILE" 2>/dev/null

  echo "$CONTENT" | while IFS= read -r line; do
    [ -z "$line" ] && continue
    set -- $line
    case "$1" in
      tap)
        [ -n "$2" ] && [ -n "$3" ] && input tap "$2" "$3" && echo "[daemon] tap $2 $3" ;;
      long)
        [ -n "$2" ] && [ -n "$3" ] && input swipe "$2" "$3" "$2" "$3" "${4:-800}" && echo "[daemon] long $2 $3 ${4:-800}" ;;
      swipe)
        [ -n "$4" ] && input swipe "$2" "$3" "$4" "$5" "${6:-300}" && echo "[daemon] swipe $2 $3 $4 $5 ${6:-300}" ;;
      key)
        [ -n "$2" ] && input keyevent "$2" && echo "[daemon] key $2" ;;
      text)
        shift
        input text "$*" && echo "[daemon] text $*" ;;
      a11y)
        sh "$SCRIPT_DIR/enable_accessibility.sh" >> "$ROOTDIR/service.log" 2>&1 && \
          echo "[daemon] accessibility restored on demand at $(date)" ;;
      perms)
        sh "$SCRIPT_DIR/grant_permissions.sh" >> "$ROOTDIR/service.log" 2>&1 && \
          echo "[daemon] permissions re-granted on demand at $(date)" ;;
      *)
        echo "[daemon] unknown command: $1" ;;
    esac
  done
  sleep 0.1
done
