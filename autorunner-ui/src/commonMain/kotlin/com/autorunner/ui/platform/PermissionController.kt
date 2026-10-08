package com.autorunner.ui.platform

import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Show

/**
 * AutoRunner 需要的权限，按引导流程申请的先后顺序排列。
 *
 * 标题、用途说明与图标跟枚举一起定义，设置页各处直接读取，无需再各自维护一份 when 映射。
 */
enum class AutoRunnerPermission(
    val title: String,
    val summary: String,
    val icon: ImageVector,
) {
    /** `BIND_ACCESSIBILITY_SERVICE`——录制与回放所必需。 */
    ACCESSIBILITY(
        title = "无障碍服务",
        summary = "BIND_ACCESSIBILITY_SERVICE：录制触摸与回放手势",
        icon = MiuixIcons.Lock,
    ),

    /** `SYSTEM_ALERT_WINDOW`——悬浮面板所必需。 */
    OVERLAY(
        title = "悬浮窗权限",
        summary = "SYSTEM_ALERT_WINDOW：显示悬浮控制面板",
        icon = MiuixIcons.Show,
    ),

    /** `POST_NOTIFICATIONS`（API 33+）——前台通知所必需。 */
    NOTIFICATION(
        title = "通知权限",
        summary = "POST_NOTIFICATIONS：前台服务与执行进度通知",
        icon = MiuixIcons.Report,
    ),
}

/** AutoRunner 关心的所有特殊权限的快照。 */
data class PermissionStatus(
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationGranted: Boolean = true,
    /** 为 `true` 时应用已豁免电池优化。 */
    val batteryOptimisationIgnored: Boolean = false,
    /** 当前设备厂商，用于厂商专属引导。 */
    val manufacturer: String = "",
    /** 为 `true` 时表示已知该 ROM 需要额外步骤（MIUI、ColorOS 等）。 */
    val requiresOemGuidance: Boolean = false,
) {
    /** 核心功能集所需的全部权限是否都已授予。 */
    val corePermissionsGranted: Boolean
        get() = accessibilityEnabled && overlayGranted && notificationGranted

    fun isGranted(permission: AutoRunnerPermission): Boolean = when (permission) {
        AutoRunnerPermission.ACCESSIBILITY -> accessibilityEnabled
        AutoRunnerPermission.OVERLAY -> overlayGranted
        AutoRunnerPermission.NOTIFICATION -> notificationGranted
    }

    /** 缺失的核心权限，用于渲染引导横幅。 */
    fun missingCore(): List<AutoRunnerPermission> = AutoRunnerPermission.entries.filterNot { isGranted(it) }
}

/** 厂商专属设置向导的一步（见 §6.4.2）。 */
data class OemGuidanceStep(
    val title: String,
    val detail: String,
)

/**
 * Android 层实现的权限接口。
 *
 * 共享 UI 从不直接触碰 `Settings.canDrawOverlays` 或
 * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION`，只向本控制器发起请求；
 * 这让 `commonMain` 不含平台类型（也让页面可在 desktop 目标上预览）。
 */
interface PermissionController {

    val status: StateFlow<PermissionStatus>

    /** 重新读取所有权限标志（从设置页返回后调用）。 */
    fun refresh()

    /** 打开系统无障碍服务列表。 */
    fun openAccessibilitySettings()

    /** 通过专用设置页申请 `SYSTEM_ALERT_WINDOW`。 */
    fun requestOverlayPermission()

    /** 申请 `POST_NOTIFICATIONS`（API 33 以下为空操作）。 */
    fun requestNotificationPermission()

    /** 打开电池优化豁免对话框。 */
    fun requestIgnoreBatteryOptimisations()

    /** 打开本应用的系统详情页（受限 ROM 的兜底方案）。 */
    fun openAppDetailsSettings()

    /** 针对当前设备的厂商专属指引。 */
    fun oemGuidance(): List<OemGuidanceStep>
}

/**
 * Android 层通过存储访问框架（SAF）实现的导入/导出接口。
 * desktop 默认实现仅支持剪贴板。
 */
interface ScriptTransferController {

    /** 拉起系统选择器；载荷经 [onPicked] 回传。 */
    var onPicked: ((fileName: String?, content: String) -> Unit)?

    /** 拉起系统文件选择器；无法打开时返回 `false`。 */
    fun pickScriptFile(): Boolean

    /** 通过系统创建文档流程将 [content] 写出为 [fileName]。 */
    fun exportScript(fileName: String, content: String)

    /** 将 [content] 以纯文本形式分享（SAF 不可用时的兜底）。 */
    fun shareScript(fileName: String, content: String)

    /** 将文本复制到系统剪贴板。 */
    fun copyToClipboard(label: String, text: String)

    /** 显示平台风格的 toast/snackbar 消息。 */
    fun notify(message: String)
}
