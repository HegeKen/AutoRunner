package cn.helilab.autorunner.permission

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import cn.helilab.autorunner.accessibility.AccessibilityServiceHolder
import cn.helilab.autorunner.accessibility.AutoRunnerAccessibilityService
import com.autorunner.core.platform.RootInputBackend
import com.autorunner.ui.platform.AutoRunnerPermission
import com.autorunner.ui.platform.OemGuidanceStep
import com.autorunner.ui.platform.PermissionController
import com.autorunner.ui.platform.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 权限表面的 Android 实现（§6.4.2、§9）。
 *
 * 特殊权限（`SYSTEM_ALERT_WINDOW`、无障碍）无法通过运行时 API 授予，
 * 因此控制器只是*引导*用户前往正确的系统页面，并在应用回到前台时
 * 重新读取状态。厂商 ROM 会把这些页面藏在不同的位置，
 * 因此针对具体厂商的提示放在 [oemGuidance] 中。
 */
class AndroidPermissionController(
    private val context: Context,
    private val requestPermissionLauncher: ((Array<String>) -> Unit)? = null,
    /** Root 注入后端：装了模块时用它按需补回被系统移除的无障碍条目。 */
    private val rootInputBackend: RootInputBackend? = null,
) : PermissionController {

    private val _status = MutableStateFlow(readStatus())

    override val status: StateFlow<PermissionStatus> = _status.asStateFlow()

    /** 本轮「缺失」是否已下发过修复请求，避免反复触发。 */
    private var repairRequested = false

    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    override fun refresh() {
        val status = readStatus()
        _status.value = status
        requestAccessibilityRepairIfNeeded(status)
    }

    /**
     * Root 模式下，系统「强停应用」会把无障碍条目从列表移除。模块守护进程不再
     * 轮询，因此这里在检测到「缺失 + 模块已装」时按需下发一条修复请求，稍后重读
     * 一次状态刷新 UI。同一轮缺失只请求一次，避免形成新的轮询。
     */
    private fun requestAccessibilityRepairIfNeeded(status: PermissionStatus) {
        if (status.accessibilityEnabled) {
            repairRequested = false
            return
        }
        val backend = rootInputBackend ?: return
        if (!backend.moduleInstalled || repairRequested) return
        repairRequested = true
        if (backend.repairAccessibility()) {
            // 给守护进程（约 0.2s 一轮）与系统一点时间生效后再重读。
            mainHandler.postDelayed(
                { _status.value = readStatus() },
                REPAIR_RECHECK_DELAY_MS,
            )
        } else {
            repairRequested = false
        }
    }

    override fun openAccessibilitySettings() {
        val intents = listOf(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS),
            // 某些 ROM 只在“已安装服务”页面暴露服务列表。
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").setData(
                Uri.parse("package:${context.packageName}"),
            ),
        )
        launchFirstAvailable(intents, Settings.ACTION_ACCESSIBILITY_SETTINGS)
    }

    override fun requestOverlayPermission() {
        val intents = listOf(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION),
            // MIUI 把应用权限保存在独立的“应用详情”页面中。
            appDetailsIntent(),
        )
        launchFirstAvailable(intents, Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
    }

    override fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            refresh()
            return
        }
        requestPermissionLauncher?.invoke(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    override fun requestIgnoreBatteryOptimisations() {
        val intents = listOf(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
        )
        launchFirstAvailable(intents, Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
    }

    override fun openAppDetailsSettings() {
        launchFirstAvailable(listOf(appDetailsIntent()), Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    override fun oemGuidance(): List<OemGuidanceStep> {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val restrictedSettings = restrictedSettingsStep()
        val vendor = when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") ||
                manufacturer.contains("poco") -> listOf(
                OemGuidanceStep("自启动", "设置 → 应用设置 → 应用管理 → AutoRunner → 自启动：允许"),
                OemGuidanceStep("省电策略", "设置 → 应用设置 → 应用管理 → AutoRunner → 省电策略：无限制"),
                OemGuidanceStep("后台弹出界面", "设置 → 应用设置 → 权限管理 → 后台弹出界面：允许"),
                OemGuidanceStep("悬浮窗", "设置 → 应用设置 → 权限管理 → 显示悬浮窗：允许，并关闭「每次使用时询问」"),
            )

            manufacturer.contains("oppo") || manufacturer.contains("oneplus") ||
                manufacturer.contains("realme") -> listOf(
                OemGuidanceStep("自启动", "设置 → 应用管理 → AutoRunner → 允许自启动、允许关联启动、允许后台活动"),
                OemGuidanceStep("省电", "设置 → 电池 → 更多设置 → 应用耗电管理 → AutoRunner → 允许后台运行"),
                OemGuidanceStep("悬浮窗", "设置 → 应用管理 → AutoRunner → 权限管理 → 悬浮窗：允许"),
            )

            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> listOf(
                OemGuidanceStep("自启动", "设置 → 应用与权限 → 权限管理 → 自启动 → AutoRunner：允许"),
                OemGuidanceStep("后台运行", "设置 → 电池 → 后台高耗电 → AutoRunner：允许"),
                OemGuidanceStep("悬浮窗", "设置 → 应用与权限 → 权限管理 → 悬浮窗 → AutoRunner：允许"),
            )

            manufacturer.contains("huawei") || manufacturer.contains("honor") -> listOf(
                OemGuidanceStep("启动管理", "设置 → 应用 → 应用启动管理 → AutoRunner → 手动管理：全部允许"),
                OemGuidanceStep("电池优化", "设置 → 电池 → 更多电池设置 → 忽略电池优化：AutoRunner 允许"),
            )

            else -> emptyList()
        }
        return restrictedSettings?.let { listOf(it) + vendor } ?: vendor
    }

    /**
     * Android 13+ 会阻止为来自未知来源安装的应用启用无障碍服务
     * （“受限设置”）。没有这一步，设置中的开关看起来可用，
     * 但服务永远不会绑定——这正是“已授权却一直提示未授权”的症状。
     */
    private fun restrictedSettingsStep(): OemGuidanceStep? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
        val installer = runCatching {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        }.getOrNull()
        val trustedInstaller = installer == "com.android.vending" ||
            installer == "com.xiaomi.market" ||
            installer == "com.google.android.packageinstaller" ||
            installer == "com.android.packageinstaller"
        if (trustedInstaller && installer != null) return null
        return OemGuidanceStep(
            title = "允许受限设置（Android 13+ 必做）",
            detail = "系统设置 → 应用管理 → AutoRunner → 右上角「⋮」→「允许受限设置」，" +
                "否则无障碍服务开关会被系统静默拦截，AutoRunner 会一直提示未授权。",
        )
    }

    /** 重新读取 Android 平台能报告的每一个标志位。 */
    private fun readStatus(): PermissionStatus {
        val overlay = runCatching { Settings.canDrawOverlays(context) }.getOrDefault(false)
        val notifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }
        val battery = runCatching {
            val manager = context.getSystemService(android.os.PowerManager::class.java)
            manager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)

        val manufacturer = Build.MANUFACTURER ?: ""
        return PermissionStatus(
            accessibilityEnabled = AccessibilityServiceHolder.isConnected ||
                isAccessibilityServiceEnabled(),
            overlayGranted = overlay,
            notificationGranted = notifications,
            batteryOptimisationIgnored = battery,
            manufacturer = manufacturer,
            requiresOemGuidance = oemGuidance().isNotEmpty(),
        )
    }

    /**
     * 从 `Settings.Secure` 与实时的 [AccessibilityManager] 列表中读取已启用的无障碍服务。
     *
     * 原始设置把条目存为 `package/.relative.Class` 或
     * `package/full.Class.Name`，因此与 `ComponentName.flattenToString()`
     * 做简单的字符串比较会漏掉缩写形式——这导致即使服务正在运行，
     * 应用仍报告“未授予”。这里对两种形式都做了归一化，
     * 并把已绑定的服务实例视为凭据。
     */
    private fun isAccessibilityServiceEnabled(): Boolean {
        if (AccessibilityServiceHolder.isConnected) return true

        val expected = runCatching {
            ComponentName(context, AutoRunnerAccessibilityService::class.java)
        }.getOrNull() ?: return false

        // 1) AccessibilityManager 维护的实时列表
        val live = runCatching {
            val manager = context.getSystemService(AccessibilityManager::class.java)
            manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                ?.any { info ->
                    val serviceInfo = info.resolveInfo?.serviceInfo ?: return@any false
                    serviceInfo.packageName == expected.packageName &&
                        serviceInfo.name == expected.className
                } ?: false
        }.getOrDefault(false)
        if (live) return true

        // 2) 持久化的 secure 设置
        return runCatching {
            val raw = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            ) ?: return false
            raw.split(':').any { entry ->
                matchesAccessibilityComponent(entry, expected.packageName, expected.className)
            }
        }.getOrDefault(false)
    }

    private fun appDetailsIntent() = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.parse("package:${context.packageName}"),
    )

    private fun launchFirstAvailable(intents: List<Intent>, fallbackAction: String) {
        for (intent in intents) {
            val resolved = runCatching {
                if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }.getOrElse { false }
            if (resolved) return
        }
        runCatching {
            context.startActivity(
                Intent(fallbackAction).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        refresh()
    }

    /** 设置页面使用的可读权限名称。 */
    fun labelOf(permission: AutoRunnerPermission): String = when (permission) {
        AutoRunnerPermission.ACCESSIBILITY -> "无障碍服务"
        AutoRunnerPermission.OVERLAY -> "悬浮窗权限"
        AutoRunnerPermission.NOTIFICATION -> "通知权限"
    }

    private companion object {
        /** 下发修复请求后等待守护进程/系统生效，再重读状态刷新 UI。 */
        const val REPAIR_RECHECK_DELAY_MS = 800L
    }
}

/**
 * 将 `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` 中以 `:` 分隔的条目
 * 逐个与期望的服务组件比对。同时接受 `package/.Relative`、
 * `package/full.Name`（以及 `package/shortName`）几种写法。
 *
 * 用 String 参数而非 [ComponentName]，便于在 JVM 单测中直接覆盖。
 */
internal fun matchesAccessibilityComponent(
    entry: String,
    expectedPackage: String,
    expectedClass: String,
): Boolean {
    val trimmed = entry.trim()
    if (trimmed.isEmpty()) return false
    val slash = trimmed.indexOf('/')
    if (slash <= 0) return false
    val packageName = trimmed.substring(0, slash)
    val rawClass = trimmed.substring(slash + 1)
    if (packageName != expectedPackage) return false
    val className = when {
        rawClass.startsWith(".") -> packageName + rawClass
        rawClass.contains('.') -> rawClass
        else -> "$packageName.$rawClass"
    }
    return className == expectedClass
}
