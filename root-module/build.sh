#!/usr/bin/env bash
# 打包成可刷入的 zip。
#   ./build.sh                   → ./autorunner_root-v1.0.0.zip
#   ./build.sh /path/to/out.zip  → 输出到指定路径（供 Gradle 集成调用）
# 版本号取自仓库根目录的 gradle.properties（单一来源）。
set -euo pipefail
cd "$(dirname "$0")"
ID=autorunner_root

# 版本来自根 gradle.properties 的单一来源；打包时注入，不改写仓库内的 module.prop。
GRADLE_PROPS="$(cd .. && pwd)/gradle.properties"
VERSION_NAME=$(grep '^autorunner.versionName=' "$GRADLE_PROPS" | cut -d= -f2)
VERSION_CODE=$(grep '^autorunner.versionCode=' "$GRADLE_PROPS" | cut -d= -f2)
if [ -z "$VERSION_NAME" ] || [ -z "$VERSION_CODE" ]; then
  echo "! 无法从 $GRADLE_PROPS 读取 autorunner.versionName / autorunner.versionCode" >&2
  exit 1
fi
VER="v$VERSION_NAME"

# 输出路径：可选参数，默认写到当前目录；统一转成绝对路径，便于在临时目录里打包。
OUT="${1:-$ID-$VER.zip}"
mkdir -p "$(dirname "$OUT")"
OUT="$(cd "$(dirname "$OUT")" && pwd)/$(basename "$OUT")"

STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT

# 注入 version / versionCode 行（awk 可移植，规避 macOS 与 GNU 的 sed -i 差异）
awk -v v="$VER" -v c="$VERSION_CODE" '
  /^version=/ { print "version=" v; next }
  /^versionCode=/ { print "versionCode=" c; next }
  { print }' module.prop > "$STAGE/module.prop"

cp customize.sh service.sh uninstall.sh "$STAGE/"
cp -R scripts "$STAGE/"

# Magisk / KernelSU 通用最小安装器
mkdir -p "$STAGE/META-INF/com/google/android"
cat > "$STAGE/META-INF/com/google/android/update-binary" <<'UB'
#!/sbin/sh
umask 022
OUTFD=$2
ZIPFILE=$3
[ -z "$ZIPFILE" ] && ZIPFILE=$0
TMPDIR=/dev/tmp_autorunner
rm -rf "$TMPDIR"; mkdir -p "$TMPDIR"
command -v unzip >/dev/null 2>&1 || abort "! unzip not found"
unzip -o "$ZIPFILE" -d "$TMPDIR" >/dev/null 2>&1 || abort "! unzip failed"
export TMPDIR
. "$TMPDIR/customize.sh"
exit 0
UB
echo '#MAGISK' > "$STAGE/META-INF/com/google/android/updater-script"
chmod 0755 "$STAGE/META-INF/com/google/android/update-binary"

( cd "$STAGE" && zip -qr9 "$OUT" . )

echo "built: $OUT"
unzip -l "$OUT" | tail -5
