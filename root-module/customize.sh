#!/sbin/sh
# AutoRunner Root Bridge —— 安装逻辑（Magisk / KernelSU 通用）
SKIPUNZIP=1

MODID=autorunner_root
: "${MODPATH:=/data/adb/modules/$MODID}"

ui_print() { echo "$1"; } 2>/dev/null || true

ui_print "*******************************"
ui_print " AutoRunner Root Bridge"
ui_print "*******************************"

# 模块文件来自安装器解包目录（本模块自带 update-binary → TMPDIR），
# 若由 Manager 直接安装则文件已在 $MODPATH。
# 关键：模块声明了 SKIPUNZIP=1（不接受管理器自动解包），必须自己把载荷解出来。
# Magisk/KernelSU 会给出 $ZIPFILE；自带 update-binary 刷入时给出 $TMPDIR。
if [ -n "${ZIPFILE:-}" ] && [ -f "${ZIPFILE:-}" ] && command -v unzip >/dev/null 2>&1; then
  unzip -o "$ZIPFILE" -x 'META-INF/*' -d "$MODPATH" >/dev/null 2>&1
elif [ -n "${TMPDIR:-}" ] && [ -f "$TMPDIR/module.prop" ]; then
  cp -af "$TMPDIR"/. "$MODPATH"/ 2>/dev/null
else
  ui_print "! 无法定位模块载荷（ZIPFILE/TMPDIR 均不可用）"
fi

# 双保险：若载荷已就地存在（管理器已解包），上面的分支不会破坏它
for f in service.sh uninstall.sh scripts; do
  if [ -e "$MODPATH/$f" ] || { [ -n "${TMPDIR:-}" ] && [ -e "$TMPDIR/$f" ]; }; then
    [ -e "$MODPATH/$f" ] || cp -af "$TMPDIR/$f" "$MODPATH/" 2>/dev/null
  else
    ui_print "! 缺少 $f（载荷未解出）"
  fi
done

rm -rf "$MODPATH/META-INF" "$MODPATH/build.sh" "$MODPATH/README.md" 2>/dev/null

chmod 0755 "$MODPATH" 2>/dev/null
chmod 0755 "$MODPATH/service.sh" "$MODPATH/uninstall.sh" 2>/dev/null
chmod 0755 "$MODPATH/scripts" 2>/dev/null
chmod 0755 "$MODPATH"/scripts/*.sh 2>/dev/null

# 标记目录：root 可写、App 只读，用于让 App 探测「Root 桥可用」
ROOTDIR=/data/local/tmp/autorunner_root
mkdir -p "$ROOTDIR"
chmod 0755 "$ROOTDIR"
echo "$MODID" > "$ROOTDIR/mode"
chmod 0644 "$ROOTDIR/mode"

ui_print "- 已安装到 $MODPATH"
ui_print "- 重启后生效：开机自动启用无障碍服务 + 启动输入注入守护进程"
ui_print "- 卸载：删除模块并重启，或执行 uninstall.sh"
exit 0
