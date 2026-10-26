package cn.helilab.autorunner.accessibility

/**
 * 窗口类型（与 `AccessibilityWindowInfo` 的常量一一对应，但不直接依赖 Android
 * 类型，便于在 JVM 单测里覆盖判定逻辑）。
 */
internal enum class DialogWindowType {
    APPLICATION,
    INPUT_METHOD,
    SYSTEM,
    ACCESSIBILITY_OVERLAY,
    MAGNIFICATION_OVERLAY,
    OTHER,
}

/**
 * 一次窗口扫描得到的、用于判定「是否为需要退出挂机的确认弹窗」的最小信息。
 *
 * @param isNewWindow 该窗口 id 不在进入挂机时的基线集合里。
 * @param isFullscreen 根节点铺满（≥90%）屏幕；游戏主界面通常是全屏，确认框通常不是。
 * @param confirmTexts 命中「确定 / 同意 / 购买」等确认类文案的可见文本。
 * @param titleTexts 命中「提示 / 公告 / 更新」等弹窗标题类文案的可见文本。
 */
internal data class DialogSignal(
    val packageName: String,
    val className: String,
    val windowId: Int,
    val windowType: DialogWindowType,
    val isFullscreen: Boolean,
    val isNewWindow: Boolean,
    val confirmTexts: List<String>,
    val titleTexts: List<String>,
)

/**
 * 纯函数：这次窗口信号是否应当触发「退出黑屏挂机」。
 *
 * 判定刻意保守，只认**挂机开始后新出现的应用窗口**（`Dialog` / `PopupWindow` /
 * 全屏广告 Activity 都会产生新窗口）或**前台包名发生变化**的窗口：这样游戏主界面
 * 里常驻的「确定 / 取消」按钮不会误报。在此前提下：
 *
 * * 非全屏窗口 + 任一确认类文案（或标题类文案）→ 确认框；
 * * 全屏窗口 + 至少两个确认类文案 + 标题类文案 → 全屏弹窗 / 广告页；
 * * 前台包名变成别的应用 + 至少两个确认类文案 → 支付页 / 浏览器 / 系统跳转。
 *
 * 系统窗口（通知栏、音量条、输入法）一律忽略：它们由用户在系统层操作，
 * 且正文里可能出现「确定」等字样，误报代价太高。
 *
 * @param baselinePackage 进入挂机时前台应用的包名；未知时为 `null`。
 */
internal fun isHangDialogCandidate(
    signal: DialogSignal,
    ownPackage: String,
    baselinePackage: String?,
): Boolean {
    if (signal.packageName.isEmpty() || signal.packageName == ownPackage) return false
    if (signal.windowType != DialogWindowType.APPLICATION) return false
    if (signal.className.contains("Toast", ignoreCase = true)) return false
    if (signal.className.contains("InputMethod", ignoreCase = true)) return false
    if (signal.className.contains("SoftInput", ignoreCase = true)) return false

    val confirmCount = signal.confirmTexts.count { it.isNotBlank() }
    val hasTitle = signal.titleTexts.any { it.isNotBlank() }
    val packageChanged = baselinePackage != null && signal.packageName != baselinePackage
    // 只关心挂机后新出现的窗口；前台包名变了也算（窗口 id 可能复用）。
    if (!signal.isNewWindow && !packageChanged) return false

    return when {
        // 典型的小弹窗：取消 + 确定，或网页 alert 的单个「确定」。
        !signal.isFullscreen && confirmCount >= 1 -> true
        // 按钮可能是自绘 / 无文字，标题命中也能说明它是提示框。
        !signal.isFullscreen && hasTitle -> true
        // 全屏广告 / 公告页：需要更完整的「标题 + 双按钮」证据。
        signal.isFullscreen && confirmCount >= 2 && hasTitle -> true
        // 前台被换成了别的应用（支付页 / 浏览器跳转）：也要求双按钮，
        // 避免用户主动切到新应用时被其主页上的单个「关闭」按钮误判。
        packageChanged && confirmCount >= 2 -> true
        else -> false
    }
}

/** 确认 / 接受类文案（中英文）。 */
internal fun isConfirmText(text: String): Boolean {
    if (text.isBlank()) return false
    if (ChineseConfirmWords.any { text.contains(it) }) return true
    return ConfirmWordPattern.containsMatchIn(text)
}

/** 弹窗标题类文案（中英文）。 */
internal fun isDialogTitleText(text: String): Boolean {
    if (text.isBlank()) return false
    if (ChineseTitleWords.any { text.contains(it) }) return true
    return TitleWordPattern.containsMatchIn(text)
}

private val ChineseConfirmWords = listOf(
    "确定", "确认", "同意", "允许", "继续", "重试", "知道了", "我知道了",
    "好的", "取消", "关闭", "购买", "支付", "领取", "立即",
)

private val ChineseTitleWords = listOf(
    "提示", "警告", "注意", "公告", "更新", "通知", "授权", "权限", "登录",
    "验证", "实名", "支付", "购买", "订单", "网络", "断开", "异常", "错误",
)

/** 英文按单词边界匹配，避免 `ok` 命中 `TikTok`、`close` 命中 `closed-loop`。 */
private val ConfirmWordPattern = Regex(
    "(?<![A-Za-z])(ok|yes|confirm|agree|allow|continue|retry|cancel|close|buy|pay)(?![A-Za-z])",
    RegexOption.IGNORE_CASE,
)

private val TitleWordPattern = Regex(
    "(?<![A-Za-z])(notice|warning|alert|update|permission|login|error|failed|retry)(?![A-Za-z])",
    RegexOption.IGNORE_CASE,
)
