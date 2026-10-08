#!/system/bin/sh
# 幂等地把 AutoRunner 的无障碍服务加入 enabled_accessibility_services。
# Root 直接写 secure settings，可绕过 MIUI「受限设置」无法开启开关的问题。
PKG=cn.helilab.autorunner
SVC="$PKG/$PKG.accessibility.AutoRunnerAccessibilityService"
LIST="$(settings get secure enabled_accessibility_services 2>/dev/null)"

case "$LIST" in
  *"$SVC"*) echo "[enable_a11y] already enabled: $LIST" ;;
  *)
    case "$LIST" in
      ""|"null") NEW="$SVC" ;;
      *)         NEW="$LIST:$SVC" ;;
    esac
    settings put secure enabled_accessibility_services "$NEW" && \
      echo "[enable_a11y] enabled -> $NEW" || echo "[enable_a11y] FAILED to write list"
    ;;
esac

# 无障碍总开关
settings put secure accessibility_enabled 1 && echo "[enable_a11y] accessibility_enabled=1"
# MIUI 的「受限设置」只影响 UI 开关，secure settings 由 root 写入后系统即生效
