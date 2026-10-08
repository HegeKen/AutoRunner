# 调试与实机部署

本文是 AutoRunner 的开发期调试手册：如何把 debug 包直接编到实机/模拟器、如何在没有 IDE 的情况下
定位问题，以及本项目在 MIUI / HyperOS 上踩过的坑与对策。

---

## 1. 环境准备

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home   # 需要 JDK 21
export PATH=$PATH:~/Library/Android/sdk/platform-tools                        # adb
export GRADLE_USER_HOME=/tmp/autorunner-gh      # 仅当 ~/.gradle 不可写（沙箱/只读 HOME）时
```

* `GRADLE_USER_HOME` 指向可写目录后，依赖缓存与 wrapper 发行版都会放在那里；
  项目 wrapper 已是 `gradle-9.4.1-bin.zip`，首次需要能联网下载（或把它预热进该目录）。
* debug 包使用仓库内的 `autorunner-app/debug.keystore`（storePassword `android`），
  因此同一台机器上多次安装不会因签名变化要求卸载。

## 2. 编译与安装

```bash
# 只编译 APK
./gradlew :autorunner-app:assembleDebug
# 产物：autorunner-app/build/outputs/apk/debug/autorunner-app-debug.apk

# 编译并直接装到当前唯一/首个设备（推荐日常使用）
./gradlew :autorunner-app:installDebug

# 多设备（手机 + 平板模拟器）时用 adb 指定序列号
adb devices
APK=autorunner-app/build/outputs/apk/debug/autorunner-app-debug.apk
for D in $(adb devices | awk 'NR>1 && $2=="device"{print $1}'); do
  adb -s "$D" install -r "$APK" && adb -s "$D" shell am force-stop cn.helilab.autorunner
done

# 改过 applicationId 之后，旧包要显式卸载（否则两个应用并存）
adb uninstall com.autorunner.app
```

单测（不需要设备，跑在 JVM）：

```bash
./gradlew :autorunner-core:desktopTest :autorunner-gamepad:desktopTest :autorunner-ui:desktopTest
```

## 3. 启动到指定页面

```bash
adb shell am start -n cn.helilab.autorunner/.MainActivity --es destination scripts
#   destination: scripts | record | settings | editor
adb shell am start -n cn.helilab.autorunner/.MainActivity --es destination editor --es scriptId "测试"
```

应用是 `singleTop`：已在前台时再 `am start` 会走 `onNewIntent` 切换页面，不必先杀进程。
（若发现页面没切换，先 `am force-stop` 再启动，排除 ROM 复用任务栈的干扰。）

## 4. 调试通道（debug 广播）

release 包不含该通道（manifest 占位符 `debugReceiverEnabled`）。

```bash
B="adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND -n cn.helilab.autorunner/.debug.DebugCommandReceiver --es command"
$B state          # 打印无障碍连接、悬浮窗、脚本数量、执行状态（自动机友好）
$B run            # 按"列表 ▶"的同一条路径运行最近脚本
$B stop           # 停止执行
$B pause          # 暂停/继续
$B tap --es x 540 --es y 1200     # 通过无障碍注入一次点击（脚本调试用）
$B record_start / record_stop     # 直接开始/结束录制
$B overlay_show / overlay_hide / overlay_expand / overlay_collapse
$B import                         # 拉起导入文件选择器（等价点击右下"导入"）
$B flush / pick_action            # 刷新脚本缓存 / 进入"从实际操作录入"
```

例：

```bash
adb shell am broadcast -a cn.helilab.autorunner.DEBUG_COMMAND \
  -n cn.helilab.autorunner/.debug.DebugCommandReceiver --es command state
```

## 5. 实机取日志

```bash
adb logcat -c && adb logcat -d | grep -i autorunner | tail -50    # 应用日志（tag: AutoRunner）
adb logcat -d | grep -B 5 -A 40 "FATAL EXCEPTION"                 # 崩溃堆栈
adb shell dumpsys accessibility | grep -A 3 "Bound services"      # 无障碍是否真的绑定
adb shell dumpsys window | grep -m1 mCurrentFocus                 # 当前前台窗口
```

**注意（本会话实测）**：部分 MIUI/HyperOS 版本会**完全屏蔽第三方应用的 `Log.i/Log.w`**，
`logcat` 里一条 `AutoRunner` 都看不到。因此关键路径都写了**文件诊断**，可直接读：

```bash
adb shell su -c 'cat /data/data/cn.helilab.autorunner/files/root_probe.txt'   # Root 探测每个 su 路径的结果
adb shell run-as cn.helilab.autorunner ls files/                              # 无 root 时看诊断文件清单
```

## 6. MIUI / HyperOS 常见坑与对策

| 现象 | 原因 | 对策 |
|---|---|---|
| `adb shell input tap` 无效、`settings put` 报 `SecurityException` | MIUI 拒绝 `INJECT_EVENTS` / `WRITE_SECURE_SETTINGS` | 用第 4 节的调试广播；或已 root 时 `su -c "input tap x y"` |
| 应用日志为空 | ROM 屏蔽第三方日志 | 看文件诊断（`files/*.txt`）与 `dumpsys` |
| `am force-stop` 后无障碍"未授予" | 强停会 unbind 并重写 `enabled_accessibility_services`，把本 App 条目移除 | 装 root-module 后，App 在启动/回前台检测到缺失时经命令桥下发 `a11y`，守护进程即时补回；否则重新在系统设置里开启 |
| Android 13+ 无法打开无障碍开关 | "受限设置"限制侧载应用 | 设置 → 无障碍 → 应用信息 → 允许受限设置 |
| 系统反复清掉无障碍条目 | ROM 重写 `enabled_accessibility_services` | 用 `root-module` 开机补写 + App 按需触发自愈（见第 8 节） |
| 运行按钮点了没反应 | 无障碍/悬浮窗/Root 三者都不可用 | `$B state` 看具体缺什么，界面也会给出对应提示 |

## 7. 屏幕尺寸、朝向与动画（模拟器）

```bash
D=emulator-5554
adb -s $D shell cmd window user-rotation lock 0     # 平板原生（横屏）；1=竖屏；free=跟随传感器
adb -s $D shell wm size 2560x1280                   # 模拟"手机横屏"几何（800×400dp @320dpi）
adb -s $D shell wm size reset                       # 还原
adb -s $D shell wm density reset
adb -s $D shell settings put global window_animation_scale 0      # 关动画，截图/断言更稳
adb -s $D shell settings put global transition_animation_scale 0
adb -s $D shell settings put global animator_duration_scale 0
```

无头截图（CI/脚本里很有用）：

```bash
adb -s $D exec-out screencap -p > /tmp/shot.png
adb -s $D shell uiautomator dump /sdcard/ui.xml && adb -s $D shell cat /sdcard/ui.xml | tr '>' '\n' | grep -o 'text="[^"]*"'
```

## 8. Root 模式与 Magisk/KernelSU 模块

```bash
# 设备是否可用 root
adb shell su -c id                       # 期望 uid=0(root) ...
adb shell su -c 'settings get secure enabled_accessibility_services'

# 安装/更新模块（KernelSU；Magisk 用管理器"从本地安装"同一 zip）
cd root-module && ./build.sh             # 产出 autorunner_root-v1.0.0.zip，并同步进 App assets
adb push autorunner_root-v1.0.0.zip /sdcard/Download/
adb shell su -c 'ksud module install /sdcard/Download/autorunner_root-v1.0.0.zip'
# KernelSU 会先放进 modules_update，**重启后**才执行 service.sh
adb shell su -c 'ls /data/adb/modules_update/autorunner_root/scripts/'

# 模块运行痕迹
adb shell su -c 'cat /data/local/tmp/autorunner_root/service.log'   # 开机自动授权/启用无障碍的日志
adb shell su -c 'tail -5 /data/local/tmp/autorunner_root/daemon.log' # root 输入注入日志（[daemon] tap X Y）
adb shell su -c 'appops get cn.helilab.autorunner SYSTEM_ALERT_WINDOW'
adb shell su -c 'dumpsys deviceidle whitelist | grep cn.helilab'
```

App 侧：**设置 → 执行 → 模拟输入方式** 可切换「无障碍服务 / Root 注入」，
未装模块时打开开关会把内置模块导出到 `Downloads/autorunner_root-v1.0.0.zip` 并提示安装。

## 9. 录制/运行的调试要点

* **运行**：列表点「运行」→ 回到桌面并显示悬浮窗（此时未执行）→ 用户打开目标页面 →
  **单击悬浮球**开始（图标变停止），或长按球展开面板。跑完自动结束、不返回 App。
* **录制**：点「开始录制」→ 回桌面 → 单击悬浮球开始采集；采集期间有三个停止入口：
  屏幕常驻「停止录制」胶囊、悬浮球（单击）、通知栏「停止录制」。
* **点击穿透**：采集层是全屏最上层窗口，镜像注入时会被它自己吃掉。实现上注入期间会把采集层
  置为 `FLAG_NOT_TOUCHABLE`，注入结束立即恢复——调试"点了没反应"时先确认这段逻辑没被短路。
* **大标题折叠**：MIUIX 的 `ScrollBehavior` 需要滚动容器同时挂
  `.overScrollVertical()` 与 `.nestedScroll(scrollBehavior.nestedScrollConnection)`
  （顺序不能反），并且要绑在**滚动容器自己**或它的共同祖先上（设置页三个容器分属不同分支，
  必须各自绑定）。
* **弹层宿主**：`OverlayDialog` / `OverlayListPopup` 等依赖 `Scaffold` 的 `MiuixPopupHost`。
  悬浮控制面板是 `WindowManager` 覆盖层、**不在 Scaffold 内**，所以面板里不能使用这些弹层组件
  （单位选择因此用 `AppSegmentedChoice`）。

## 10. 常用自检清单

```bash
$B state                              # 应用自述状态
adb shell dumpsys package cn.helilab.autorunner | grep -m1 versionName
adb shell pm list packages | grep helilab
adb shell su -c 'cat /data/data/cn.helilab.autorunner/files/root_probe.txt'
```

构建侧若看到 `compileSdk 37` 的"未经测试"提示：`gradle.properties` 里的
`android.suppressUnsupportedCompileSdk=36,37,37.0` 已压制（用 37 是为了解决 AndroidX 依赖冲突）。
`--warning-mode all` 下剩下的 multi-string 依赖弃用警告来自 AGP 自身（`lint-gradle` / `aapt2`），
不是本仓库脚本，等 AGP 适配 Gradle 10 即可。
