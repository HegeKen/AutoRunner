#!/system/bin/sh
# 开机（late_start service）执行：等待系统就绪 → 启用无障碍 → 启动输入注入守护进程
MODDIR=${0%/*}
ROOTDIR=/data/local/tmp/autorunner_root
mkdir -p "$ROOTDIR" 2>/dev/null
chmod 0755 "$ROOTDIR" 2>/dev/null

# 1) 等待开机完成（settings 服务此时才可写）
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do
  sleep 2; i=$((i+1))
done
sleep 5

# 2) 默认启用 AutoRunner 的无障碍服务（幂等，保留其它已启用服务）
sh "$MODDIR/scripts/enable_accessibility.sh" >> "$ROOTDIR/service.log" 2>&1

# 2b) 默认授予其余所需权限（通知 / 悬浮窗 / 电池白名单 / 后台运行）
sh "$MODDIR/scripts/grant_permissions.sh" >> "$ROOTDIR/service.log" 2>&1

# 3) Root 输入注入守护进程
sh "$MODDIR/scripts/click_daemon.sh" >> "$ROOTDIR/daemon.log" 2>&1 &
echo $! > "$ROOTDIR/daemon.pid"
