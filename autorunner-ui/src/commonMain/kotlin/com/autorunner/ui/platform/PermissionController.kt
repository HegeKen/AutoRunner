package com.autorunner.ui.platform

import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.StateFlow
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Report
import top.yukonga.miuix.kmp.icon.extended.Show

/**
 * Permissions AutoRunner needs, in the order the onboarding flow asks for them.
 *
 * 标题、用途说明与图标跟枚举一起定义，设置页各处直接读取，无需再各自维护一份 when 映射。
 */
enum class AutoRunnerPermission(
    val title: String,
    val summary: String,
    val icon: ImageVector,
) {
    /** `BIND_ACCESSIBILITY_SERVICE` — required for recording and replay. */
    ACCESSIBILITY(
        title = "无障碍服务",
        summary = "BIND_ACCESSIBILITY_SERVICE：录制触摸与回放手势",
        icon = MiuixIcons.Lock,
    ),

    /** `SYSTEM_ALERT_WINDOW` — required for the floating panel. */
    OVERLAY(
        title = "悬浮窗权限",
        summary = "SYSTEM_ALERT_WINDOW：显示悬浮控制面板",
        icon = MiuixIcons.Show,
    ),

    /** `POST_NOTIFICATIONS` (API 33+) — required for the foreground notification. */
    NOTIFICATION(
        title = "通知权限",
        summary = "POST_NOTIFICATIONS：前台服务与执行进度通知",
        icon = MiuixIcons.Report,
    ),
}

/** Snapshot of every special permission AutoRunner cares about. */
data class PermissionStatus(
    val accessibilityEnabled: Boolean = false,
    val overlayGranted: Boolean = false,
    val notificationGranted: Boolean = true,
    /** `true` when the app is exempt from battery optimisation. */
    val batteryOptimisationIgnored: Boolean = false,
    /** Manufacturer of the current device, used for ROM specific guidance. */
    val manufacturer: String = "",
    /** `true` when the ROM is known to need extra steps (MIUI, ColorOS, …). */
    val requiresOemGuidance: Boolean = false,
) {
    /** Every permission that is mandatory for the core feature set. */
    val corePermissionsGranted: Boolean
        get() = accessibilityEnabled && overlayGranted && notificationGranted

    fun isGranted(permission: AutoRunnerPermission): Boolean = when (permission) {
        AutoRunnerPermission.ACCESSIBILITY -> accessibilityEnabled
        AutoRunnerPermission.OVERLAY -> overlayGranted
        AutoRunnerPermission.NOTIFICATION -> notificationGranted
    }

    /** Missing mandatory permissions, used to render the onboarding banner. */
    fun missingCore(): List<AutoRunnerPermission> = AutoRunnerPermission.entries.filterNot { isGranted(it) }
}

/** One step of the vendor specific setup wizard (see §6.4.2). */
data class OemGuidanceStep(
    val title: String,
    val detail: String,
)

/**
 * Permission surface implemented by the Android layer.
 *
 * The shared UI never touches `Settings.canDrawOverlays` or
 * `Settings.ACTION_MANAGE_OVERLAY_PERMISSION` directly; it only asks this
 * controller, which keeps `commonMain` free of platform types (and makes the
 * screens previewable on the desktop target).
 */
interface PermissionController {

    val status: StateFlow<PermissionStatus>

    /** Re-reads every permission flag (call after returning from settings). */
    fun refresh()

    /** Opens the system accessibility service list. */
    fun openAccessibilitySettings()

    /** Requests `SYSTEM_ALERT_WINDOW` through the dedicated settings screen. */
    fun requestOverlayPermission()

    /** Requests `POST_NOTIFICATIONS` (no-op below API 33). */
    fun requestNotificationPermission()

    /** Opens the battery optimisation exemption dialog. */
    fun requestIgnoreBatteryOptimisations()

    /** Opens this app's system details page (fallback for locked down ROMs). */
    fun openAppDetailsSettings()

    /** Vendor specific instructions for the current device. */
    fun oemGuidance(): List<OemGuidanceStep>
}

/**
 * Import/export surface implemented by the Android layer with the Storage
 * Access Framework. The desktop default only supports the clipboard.
 */
interface ScriptTransferController {

    /** Launches the system picker; the payload is delivered to [onPicked]. */
    var onPicked: ((fileName: String?, content: String) -> Unit)?

    /** Launches the system file picker; `false` when it could not be opened. */
    fun pickScriptFile(): Boolean

    /** Writes [content] as [fileName] through the system create-document flow. */
    fun exportScript(fileName: String, content: String)

    /** Shares [content] as plain text (fallback when SAF is unavailable). */
    fun shareScript(fileName: String, content: String)

    /** Copies text to the system clipboard. */
    fun copyToClipboard(label: String, text: String)

    /** Shows a platform toast/snackbar style message. */
    fun notify(message: String)
}
