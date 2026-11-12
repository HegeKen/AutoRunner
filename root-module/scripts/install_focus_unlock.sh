#!/system/bin/sh
# 写入「超级岛解锁可用」标记。
#
# 解锁逻辑已随主 App 内置：AutoRunner 的 APK 同时是 LSPosed 模块，不再单独
# 安装 autorunner-xposed.apk。只要主 App 已安装，就写入标记，App 据此在没有
# 小米开放平台 APP_ID 的情况下也上报焦点通知。
#
# 该模块会：
#   * 在 com.xiaomi.xmsf 中让 AuthSession.getAuthError() 返回成功；
#   * 在 com.miui.systemui 中让 canShowFocusState / canShowFocusStateApp
#     对 cn.helilab.autorunner 返回允许。
#
# LSPosed 作用域仍需在管理器里勾选一次（系统界面 + 小米服务框架），
# 因为作用域配置属于 LSPosed 自己的数据库，不是本模块能安全代写的。
MODDIR=${0%/*}/..
PKG=cn.helilab.autorunner
ROOTDIR=/data/local/tmp/autorunner_root
MARKER="$ROOTDIR/focus_unlock"
mkdir -p "$ROOTDIR" 2>/dev/null

# 顺便安装 LSPosed 管理器，方便用户启用模块作用域（若尚未安装）。
LSP_MANAGER=/data/adb/modules/zygisk_lsposed/manager.apk
if [ -f "$LSP_MANAGER" ] && ! pm path org.lsposed.manager >/dev/null 2>&1; then
  echo "[focus_unlock] installing LSPosed manager"
  pm install -r "$LSP_MANAGER" >> "$ROOTDIR/service.log" 2>&1
fi

if pm path "$PKG" >/dev/null 2>&1; then
  echo "1" > "$MARKER"
  chmod 0644 "$MARKER"
  echo "[focus_unlock] marker written; enable the AutoRunner module in LSPosed with scopes: com.miui.systemui, com.xiaomi.xmsf"
else
  rm -f "$MARKER" 2>/dev/null
  echo "[focus_unlock] AutoRunner is not installed"
fi
