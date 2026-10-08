# AutoRunner Root Bridge（Magisk / KernelSU 模块）

给有 Root 的用户提供两条能力，二选一或同时使用：

1. **开机自动启用 AutoRunner 的无障碍服务** —— Root 直接写 `secure settings`，
   绕过 MIUI/HyperOS 的「受限设置」不让第三方 App 打开无障碍开关的问题；
2. **Root 模式输入注入桥** —— 由 root shell 调用 `input`（具备 `INJECT_EVENTS`），
   在无障碍服务不可用/被限制的设备上也能做点击、长按、滑动、按键、文本输入。

## 安装

**方式 A：刷入 zip（推荐）**

```bash
./build.sh                                  # 生成 autorunner_root-v1.0.0.zip
# 然后在 Magisk / KernelSU 管理器里「从本地安装」该 zip
```

**方式 B：手动放置（不需要管理器，永远可用）**

```bash
adb push root-module /data/local/tmp/autorunner_root_src
adb shell su -c 'mkdir -p /data/adb/modules/autorunner_root && \
  cp -af /data/local/tmp/autorunner_root_src/. /data/adb/modules/autorunner_root/ && \
  rm -f /data/adb/modules/autorunner_root/build.sh && \
  chmod 0755 /data/adb/modules/autorunner_root/service.sh'
# 重启生效
```

卸载：管理器里移除模块后重启，或 `su -c 'sh /data/adb/modules/autorunner_root/uninstall.sh'`。

## 它做了什么

| 文件 | 作用 |
|---|---|
| `service.sh` | 开机等到 `sys.boot_completed` 后：启用无障碍服务 + 启动输入注入守护进程 |
| `scripts/enable_accessibility.sh` | 幂等地把 `cn.helilab.autorunner/...AutoRunnerAccessibilityService` 追加进 `enabled_accessibility_services`（保留其它已启用服务），并置 `accessibility_enabled=1` |
| `scripts/click_daemon.sh` | 监听命令文件，执行 `input tap/long/swipe/key/text`；收到 `a11y` 命令时即时补回无障碍条目（按需自愈，无轮询） |
| `uninstall.sh` | 停守护进程、删标记目录、从无障碍列表移除本 App |

标记目录 `/data/local/tmp/autorunner_root/`（root 可写、普通 App 只读）：

- `mode`：模块存在标记 —— App 可据此显示「Root 增强已启用」；
- `daemon.pid` / `daemon.log` / `service.log`：排障用。

> **无障碍按需自愈**：系统「强停应用」（`am force-stop`、Gradle `installDebug` 等）会
> unbind 无障碍服务并重写 `enabled_accessibility_services`，把本 App 的条目移除；仅靠开机
> 补写会导致强停后无障碍一直缺失到下次重启。守护进程**不做任何轮询**（避免周期性 spawn
> `settings` 的耗电），而是由 App 在启动/回前台检测到条目缺失时，经命令文件下发一条
> `a11y`，守护进程在既有的约 0.2s 循环里即时调用 `enable_accessibility.sh` 幂等补回。

## Root 注入协议（App 侧对接）

App 往**自己的私有目录**写命令文件：`/data/data/cn.helilab.autorunner/files/root_input.cmd`
（0700 私有目录，只有 AutoRunner 与 root 能写；守护进程读后立即清空，并拒绝符号链接）。

每行一条命令：

```
tap 540 1200
long 540 1200 800
swipe 200 1800 1000 400 300
key KEYCODE_BACK
text hello
a11y
```

建议 App 侧实现一个 `InputBackend`：检测到 `/data/local/tmp/autorunner_root/mode` 存在且
无障碍未连接时，走该文件投递手势；否则仍用无障碍 `dispatchGesture`。

## 安全说明

- 只有 AutoRunner 能下发指令（命令文件位于其私有目录，第三方 App 无写权限）；
- 守护进程只接受上述白名单命令，未知命令仅记日志；
- 模块不修改任何 system 分区文件，不注入系统进程，仅一个常驻 shell 循环
  （约 0.2s 一轮处理注入命令，无常驻轮询）；
- `uninstall.sh` 会还原无障碍服务列表（移除本 App，保留其它服务）。
