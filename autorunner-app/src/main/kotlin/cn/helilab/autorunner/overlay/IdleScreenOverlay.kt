package cn.helilab.autorunner.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import cn.helilab.autorunner.AppGraph
import cn.helilab.autorunner.AutoRunnerApplication
import cn.helilab.autorunner.notification.FocusNotification
import cn.helilab.autorunner.notification.FocusNotificationFeed
import cn.helilab.autorunner.notification.IdleMediaMonitor
import cn.helilab.autorunner.notification.IdleMediaState
import cn.helilab.autorunner.notification.IdleNotification
import cn.helilab.autorunner.notification.IdleNotificationFeed
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * 黑屏挂机覆盖层。
 *
 * 用一层全屏纯黑窗口模拟熄屏，同时让 display 保持 ON：
 * * `FLAG_KEEP_SCREEN_ON` 阻止系统超时熄屏；
 * * `FLAG_NOT_TOUCHABLE` 让脚本注入的触摸继续穿透到下层游戏
 *   （display 为 OFF 时 InputDispatcher 会直接丢弃注入的触摸）；
 * * 黑色背景上绘制一块缓慢反弹的发光边框文字面板：挂机标题、**当前时间**、
 *   操作提示、**右上角电量 / 充电状态**，有音乐播放时再加上媒体信息。
 *   面板位置持续变化以避免 AMOLED 长时间显示同一位置的像素造成烧屏；
 * * 叠一层 [IdleNotificationView]，挂机期间新到的手机通知以只读卡片显示；
 * * 媒体控制按钮位于独立的、只有按钮大小的可触摸窗口（[IdleMediaControlWindow]）
 *   中，跟随面板移动。黑屏层本身仍不可触摸，按钮矩形以外的触摸继续穿透。
 *
 * 整屏纯黑，不再为悬浮球预留透明区域：退出挂机统一走「长按音量减键」，
 * 悬浮球与菜单在挂机期间不可见也不可操作。
 */
class IdleScreenOverlay(
    private val context: Context,
    private val windowManager: WindowManager? = context.getSystemService(WindowManager::class.java),
    private val windowType: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
) {

    private var view: FrameLayout? = null

    private var hintView: DvdHintView? = null

    private var notificationView: IdleNotificationView? = null

    /** 本层注册到 [IdleNotificationFeed] 的观察者；收起时按身份撤销。 */
    private var feedObserver: ((IdleNotification) -> Unit)? = null

    /** 本层注册到 [FocusNotificationFeed] 的观察者；收起时按身份撤销。 */
    private var focusObserver: ((List<FocusNotification>) -> Unit)? = null

    /** 跟随文字面板的媒体按钮窗口；只在有音乐播放时添加。 */
    private var mediaControlWindow: IdleMediaControlWindow? = null

    /** 通过「通知使用权」读取媒体会话的监听器。 */
    private var mediaMonitor: IdleMediaMonitor? = null

    /** 电量 / 充电状态监听器。 */
    private var batteryMonitor: IdleBatteryMonitor? = null

    /** 通知看板是否已就绪（排障与测试用）。 */
    val hasNotificationLayer: Boolean get() = notificationView != null

    /** 媒体控制按钮当前是否可见（排障与测试用）。 */
    val hasMediaControls: Boolean get() = mediaControlWindow?.isShowing == true

    val isShowing: Boolean get() = view != null

    /** 媒体会话监听状态，输出到 `hang_state` 调试命令。 */
    fun debugMediaState(): String = mediaMonitor?.debugDescription() ?: "inactive"

    /** 添加覆盖层；失败（无悬浮窗权限等）时返回 `false`。 */
    fun show(): Boolean {
        if (view != null) return true
        val manager = windowManager ?: return false
        return runCatching {
            val root = FrameLayout(context)
            val hint = DvdHintView(context) { bounds ->
                mediaControlWindow?.moveTo(bounds)
            }
            root.addView(
                hint,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            val notifications = IdleNotificationView(context)
            root.addView(
                notifications,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
            manager.addView(root, buildLayoutParams())
            view = root
            hintView = hint
            notificationView = notifications
            mediaControlWindow = IdleMediaControlWindow(
                context = context,
                windowManager = manager,
                windowType = windowType,
                onAction = ::handleMediaAction,
            )
            // 黑屏层可见期间才接收通知；收起时立即断开，避免后台持有通知内容。
            val observer: (IdleNotification) -> Unit = { notifications.add(it) }
            feedObserver = observer
            IdleNotificationFeed.observer = observer
            // 焦点通知（超级岛）状态常驻在文字面板里；与 8 秒卡片用独立开关。
            if (AppGraph.containerOrNull?.settingsRepository?.current?.idleFocusDisplay == true) {
                val focusObserver: (List<FocusNotification>) -> Unit = { hint.setFocus(it) }
                this.focusObserver = focusObserver
                FocusNotificationFeed.observer = focusObserver
            }
            // 电量是粘性广播：start() 会同步回调一次当前状态。
            val battery = IdleBatteryMonitor(context) { state -> hint.setBattery(state) }
            batteryMonitor = battery
            runCatching { battery.start() }.onFailure { error ->
                Log.w(AutoRunnerApplication.TAG, "battery monitor start failed", error)
            }
            // 媒体会话同样只在挂机期间读取；start() 内部会吞掉缺少授权的异常，
            // 失败只是没有媒体按钮，不影响黑屏层本身。
            val monitor = IdleMediaMonitor(context) { state -> applyMediaState(state) }
            mediaMonitor = monitor
            runCatching { monitor.start() }.onFailure { error ->
                Log.w(AutoRunnerApplication.TAG, "media session monitor start failed", error)
            }
            true
        }.onFailure { error ->
            Log.w(AutoRunnerApplication.TAG, "idle screen overlay attach failed (type=$windowType)", error)
        }.getOrDefault(false)
    }

    fun hide() {
        val current = view
        view = null
        hintView = null
        notificationView = null
        // 先停监听再撤窗口：避免撤窗口过程中状态回调又添加按钮窗口。
        batteryMonitor?.stop()
        batteryMonitor = null
        mediaMonitor?.stop()
        mediaMonitor = null
        mediaControlWindow?.hide()
        mediaControlWindow = null
        if (feedObserver != null && IdleNotificationFeed.observer === feedObserver) {
            IdleNotificationFeed.observer = null
        }
        feedObserver = null
        if (focusObserver != null && FocusNotificationFeed.observer === focusObserver) {
            FocusNotificationFeed.observer = null
        }
        focusObserver = null
        if (current != null) {
            runCatching { windowManager?.removeViewImmediate(current) }
        }
    }

    /** 媒体会话状态变化：同步文字面板，并按需添加 / 移除按钮窗口。 */
    private fun applyMediaState(state: IdleMediaState?) {
        val hint = hintView ?: return
        hint.setMedia(state)
        val controls = mediaControlWindow ?: return
        if (state == null) {
            controls.hide()
        } else {
            // setMedia() 已算出新的按钮行位置；先缓存位置再添加窗口，避免首帧闪到 (0, 0)。
            hint.actionBounds()?.let(controls::moveTo)
            controls.show(state)
        }
    }

    private fun handleMediaAction(action: IdleMediaAction) {
        val monitor = mediaMonitor ?: return
        when (action) {
            IdleMediaAction.PREVIOUS -> monitor.skipPrevious()
            IdleMediaAction.TOGGLE_PLAY_PAUSE -> monitor.togglePlayPause()
            IdleMediaAction.NEXT -> monitor.skipNext()
        }
    }

    private fun buildLayoutParams(): WindowManager.LayoutParams =
        WindowManager.LayoutParams(
            // 显式铺满物理屏：MATCH_PARENT 会落在导航条之上，底部留出一条
            // 透出游戏的亮边，黑屏效果就不完整了。
            context.resources.displayMetrics.widthPixels,
            context.resources.displayMetrics.heightPixels,
            windowType,
            // 触摸穿透是黑屏挂机成立的前提：脚本的点击 / 滑动必须落到下层游戏。
            // KEEP_SCREEN_ON 让 display 保持 ON，注入的触摸才会被正常投递。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            // 整屏纯黑，不透明格式即可。
            PixelFormat.OPAQUE,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 1f
            // 与悬浮球保持一致：铺满含 cutout 的物理屏，避免打孔屏下出现偏移 / letterbox。
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            } else {
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
}

/**
 * 纯黑背景 + 发光圆角边框 + DVD 待机 Logo 式反弹文字面板。
 *
 * 面板沿随机斜向匀速移动，碰到屏幕四边按轴反向。速度刻意取低
 * （约 18dp/s），既能看清提示，又不会因为移动过快分散注意力。
 *
 * 面板内部从上到下依次是：标题 + **右上角电量 / 充电标识**、**当前时间
 * （每秒刷新）**、操作提示；有音乐播放时追加媒体标题 / 歌手（超长截断）与
 * 一行按钮占位。按钮本身由 [IdleMediaControlWindow] 单独绘制，这里只负责
 * 计算并上报它该出现的位置。
 */
@SuppressLint("ViewConstructor")
private class DvdHintView(
    context: Context,
    private val onActionBoundsChanged: (IdleActionBounds) -> Unit,
) : View(context) {

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(210, 235, 235, 235)
        textSize = sp(24f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    private val clockPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(236, 250, 250, 250)
        textSize = sp(31f)
        // 等宽字体让秒数跳动时文字面板宽度保持稳定，不会左右抖动。
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 170, 170, 170)
        textSize = sp(13f)
    }

    private val mediaPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(216, 150, 205, 255)
        textSize = sp(14f)
    }

    /** 焦点通知主文案：比媒体行更亮、更粗，作为「岛」状态的第一眼信息。 */
    private val focusTitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 250, 250, 250)
        textSize = sp(15f)
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }

    /** 焦点通知补充说明（骑手状态、商家名等）。 */
    private val focusDetailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(190, 205, 205, 205)
        textSize = sp(12.5f)
    }

    /** 进度条底槽；强调色填充由每条通知的 `highlightColor` 决定。 */
    private val focusProgressTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(40, 255, 255, 255)
    }

    private val focusProgressFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = DEFAULT_FOCUS_ACCENT
    }

    private val batteryTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(215, 225, 225, 225)
        textSize = sp(11f)
    }

    private val batteryFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(235, 225, 225, 225)
    }

    private val boltPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // 闪电画在绿色 / 红色电量填充之上，用深色保证对比度。
        color = Color.argb(235, 10, 24, 16)
    }

    /** 面板内部极淡的底色，让发光边框看起来像一块悬浮玻璃；仍然接近纯黑。 */
    private val frameFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(16, 40, 92, 122)
    }

    private val frameGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(220, 96, 210, 255)
    }

    private val frameRect = RectF()

    private val boltPath = Path()

    private val title = "AutoRunner 挂机中"

    private val subtitleLines = listOf(
        "屏幕保亮  ·  触摸穿透",
        "长按音量减键退出黑屏",
    )

    private val clockFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var clockText = clockFormatter.format(Date())
    private var lastClockSecond = -1L

    private var mediaState: IdleMediaState? = null

    /** 已按内容宽度截断的媒体行，避免超长歌名每帧重复测量。 */
    private var mediaLineText: String? = null

    /** 当前有效的焦点通知（超级岛）状态，按最近更新排序。 */
    private var focusState: List<FocusNotification> = emptyList()

    private var batteryState: IdleBatteryState? = null

    private val gap = dp(10f)
    private val lineGap = dp(3f)

    /** 焦点通知块内部的间距：主/副文案、进度条。 */
    private val focusLineGap = dp(2f)
    private val focusProgressGap = dp(5f)
    private val focusProgressHeight = dp(3f)
    /**
     * 碰撞边界：以发光边框本体（最内层亮线）的外沿为准，而不是旧版 24dp 的
     * 安全留白。核心描边宽约 1.6dp，这里只保留半个描边宽度，贴边时亮线正好
     * 落在屏幕边缘；外层泛光会被屏幕裁掉，这是「边框即碰撞位置」的预期效果。
     */
    private val collisionInset = dp(0.8f)
    private val speedPxPerSecond = dp(18f)
    private val actionWidth = dp(IdleMediaControlView.WIDTH_DP)
    private val actionHeight = dp(IdleMediaControlView.HEIGHT_DP)

    private val framePadding = dp(16f)
    private val frameRadius = dp(18f)

    /** 面板最大宽度：音乐名再长也不会把面板撑到整屏，超出部分以省略号截断。 */
    private val maxPanelWidth = dp(430f)

    private val headerBatteryGap = dp(10f)
    private val batteryTextGap = dp(5f)
    private val batteryBodyWidth = dp(18f)
    private val batteryBodyHeight = dp(10f)
    private val batteryNubWidth = dp(2.5f)
    private val batteryNubHeight = dp(5f)
    private val batteryStroke = dp(1.2f)

    private val batteryOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = batteryStroke
        color = Color.argb(205, 220, 220, 220)
    }

    private val batteryNubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(205, 220, 220, 220)
    }

    /** 多层描边模拟泛光：由外到内逐层变窄、变亮。 */
    private val glowWidths = floatArrayOf(dp(11f), dp(7f), dp(4f), dp(1.6f))
    private val glowAlphas = intArrayOf(14, 28, 52, 220)

    private var contentWidth = 0f
    private var contentHeight = 0f
    private var frameWidth = 0f
    private var frameHeight = 0f
    private var posX = -1f
    private var posY = -1f
    private var velocityX = 0f
    private var velocityY = 0f
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            val previous = lastFrameNanos
            lastFrameNanos = frameTimeNanos
            refreshClock()
            if (previous != 0L) {
                // 限制单帧步长：卡顿或灭屏恢复时面板不会瞬移。
                val deltaSeconds = ((frameTimeNanos - previous) / 1_000_000_000f)
                    .coerceIn(0f, 0.05f)
                advance(deltaSeconds)
                invalidate()
            }
            if (isAttachedToWindow) {
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        lastFrameNanos = 0L
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    override fun onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        lastFrameNanos = 0L
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        measurePanel()
        if (posX < 0f || posY < 0f) {
            resetPosition()
        } else {
            clampPosition()
        }
        publishActionBounds()
        invalidate()
    }

    /** 更新媒体播放状态；由 [IdleScreenOverlay] 在主线程调用。 */
    fun setMedia(state: IdleMediaState?) {
        if (state == mediaState) return
        mediaState = state
        measurePanel()
        clampPosition()
        publishActionBounds()
        invalidate()
    }

    /** 更新电量 / 充电状态；由 [IdleBatteryMonitor] 在主线程调用。 */
    fun setBattery(state: IdleBatteryState) {
        if (state == batteryState) return
        batteryState = state
        measurePanel()
        clampPosition()
        publishActionBounds()
        invalidate()
    }

    /** 更新焦点通知（超级岛）状态；由 [FocusNotificationFeed] 在主线程回调。 */
    fun setFocus(state: List<FocusNotification>) {
        if (state == focusState) return
        focusState = state
        measurePanel()
        clampPosition()
        publishActionBounds()
        invalidate()
    }

    /** 按钮行当前应出现的位置；没有媒体或尚未完成首次布局时为 `null`。 */
    fun actionBounds(): IdleActionBounds? {
        if (mediaState == null || frameWidth <= 0f || posX < 0f || posY < 0f) return null
        return IdleActionBounds(
            left = posX + framePadding + (contentWidth - actionWidth) / 2f,
            top = posY + framePadding + contentHeight - actionHeight,
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.BLACK)
        if (frameWidth <= 0f || frameHeight <= 0f) return

        drawFrame(canvas)

        var cursor = posY + framePadding
        val header = headerHeight()
        drawHeader(canvas, cursor, header)
        cursor += header + gap

        drawCentered(canvas, clockText, clockPaint, cursor)
        cursor += lineHeight(clockPaint) + gap

        if (focusState.isNotEmpty()) {
            cursor = drawFocusBlock(canvas, cursor)
            cursor += gap
        }

        subtitleLines.forEachIndexed { index, line ->
            drawCentered(canvas, line, subtitlePaint, cursor)
            cursor += lineHeight(subtitlePaint)
            if (index != subtitleLines.lastIndex) cursor += lineGap
        }

        val media = mediaState
        if (media != null) {
            cursor += gap
            // mediaLineText 已在测量阶段按 contentWidth 截断，直接绘制。
            drawCenteredRaw(canvas, mediaLineText ?: media.displayText, mediaPaint, cursor)
            // 再往下是按钮行的位置，由独立的可触摸窗口绘制，这里留空。
        }
    }

    /** 逐帧检查秒数；只在时间文本真正变化时重新测量，避免每帧格式化。 */
    private fun refreshClock() {
        val second = System.currentTimeMillis() / 1000L
        if (second == lastClockSecond) return
        lastClockSecond = second
        val text = clockFormatter.format(Date(second * 1000L))
        if (text == clockText) {
            invalidate()
            return
        }
        clockText = text
        if (width > 0 && height > 0) {
            measurePanel()
            clampPosition()
            publishActionBounds()
        }
        invalidate()
    }

    /**
     * 重新测量内容与面板。
     *
     * 内容宽度上限同时受屏幕可用宽度与 [maxPanelWidth] 约束；音乐行只取
     * 「截断后的宽度」参与测量，因此超长歌名不会把边框撑大，而是在边框内
     * 以省略号结尾。
     */
    private fun measurePanel() {
        if (width <= 0 || height <= 0) {
            contentWidth = 0f
            contentHeight = 0f
            frameWidth = 0f
            frameHeight = 0f
            return
        }
        val maxFrameWidth = min(
            (width - collisionInset * 2f).coerceAtLeast(1f),
            maxPanelWidth,
        )
        val maxContentWidth = (maxFrameWidth - framePadding * 2f).coerceAtLeast(1f)
        val media = mediaState
        val mediaWidth = media?.let { mediaPaint.measureText(it.displayText) } ?: 0f
        val focusWidth = focusState.take(MAX_FOCUS_ENTRIES).maxOfOrNull { item ->
            maxOf(
                focusTitlePaint.measureText(focusTitleText(item)),
                focusDetailPaint.measureText(item.detail),
            )
        } ?: 0f
        val headerWidth = titlePaint.measureText(title) +
            if (batteryState != null) headerBatteryGap + batteryWidth() else 0f
        val desiredWidth = maxOf(
            clockPaint.measureText(clockText),
            subtitleLines.maxOf { subtitlePaint.measureText(it) },
            min(mediaWidth, maxContentWidth),
            min(focusWidth, maxContentWidth),
            if (media != null) actionWidth else 0f,
            min(headerWidth, maxContentWidth),
        )
        contentWidth = min(desiredWidth, maxContentWidth)
        mediaLineText = media?.displayText?.let { ellipsize(it, mediaPaint, contentWidth) }
        contentHeight = measureContentHeight(hasMedia = media != null)
        frameWidth = contentWidth + framePadding * 2f
        frameHeight = contentHeight + framePadding * 2f
    }

    private fun measureContentHeight(hasMedia: Boolean): Float {
        var height = headerHeight()
        height += gap + lineHeight(clockPaint)
        if (focusState.isNotEmpty()) {
            height += gap + focusBlockHeight()
        }
        height += gap + subtitleLines.size * lineHeight(subtitlePaint) +
            (subtitleLines.size - 1) * lineGap
        if (hasMedia) {
            height += gap + lineHeight(mediaPaint) + gap + actionHeight
        }
        return height
    }

    private fun headerHeight(): Float =
        max(lineHeight(titlePaint), batteryBodyHeight + batteryStroke * 2f)

    private fun batteryWidth(): Float {
        val state = batteryState ?: return 0f
        return batteryTextPaint.measureText(state.label) +
            batteryTextGap + batteryBodyWidth + batteryNubWidth
    }

    /** 绘制圆角发光边框；多层描边由外到内叠加出泛光。 */
    private fun drawFrame(canvas: Canvas) {
        frameRect.set(posX, posY, posX + frameWidth, posY + frameHeight)
        canvas.drawRoundRect(frameRect, frameRadius, frameRadius, frameFillPaint)
        for (index in glowWidths.indices) {
            frameGlowPaint.strokeWidth = glowWidths[index]
            frameGlowPaint.color = Color.argb(glowAlphas[index], 96, 210, 255)
            canvas.drawRoundRect(frameRect, frameRadius, frameRadius, frameGlowPaint)
        }
    }

    /**
     * 顶栏：左侧标题（空间不足时截断），右侧电量百分比 + 电池图标。
     * 电池内部按电量填充；充电时填充变绿并叠加闪电标识。
     */
    private fun drawHeader(canvas: Canvas, top: Float, height: Float) {
        val left = posX + framePadding
        val battery = batteryState
        val reserved = if (battery != null) batteryWidth() + headerBatteryGap else 0f
        val titleWidth = (contentWidth - reserved).coerceAtLeast(1f)
        val displayTitle = ellipsize(title, titlePaint, titleWidth)
        val titleLineHeight = lineHeight(titlePaint)
        val baseline = top + (height - titleLineHeight) / 2f - titlePaint.fontMetrics.ascent
        canvas.drawText(displayTitle, left, baseline, titlePaint)
        if (battery != null) {
            drawBattery(canvas, right = left + contentWidth, top = top, height = height)
        }
    }

    private fun drawBattery(canvas: Canvas, right: Float, top: Float, height: Float) {
        val state = batteryState ?: return
        val label = state.label
        val labelWidth = batteryTextPaint.measureText(label)
        val totalWidth = labelWidth + batteryTextGap + batteryBodyWidth + batteryNubWidth
        val left = right - totalWidth
        val centerY = top + height / 2f
        val metrics = batteryTextPaint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, left, baseline, batteryTextPaint)

        val bodyRight = right - batteryNubWidth
        val bodyLeft = bodyRight - batteryBodyWidth
        val bodyTop = centerY - batteryBodyHeight / 2f
        val bodyBottom = centerY + batteryBodyHeight / 2f
        canvas.drawRoundRect(
            bodyLeft,
            bodyTop,
            bodyRight,
            bodyBottom,
            dp(2.5f),
            dp(2.5f),
            batteryOutlinePaint,
        )
        canvas.drawRoundRect(
            bodyRight,
            centerY - batteryNubHeight / 2f,
            right,
            centerY + batteryNubHeight / 2f,
            dp(1f),
            dp(1f),
            batteryNubPaint,
        )

        val inset = batteryStroke + dp(0.8f)
        val innerLeft = bodyLeft + inset
        val innerRight = bodyRight - inset
        val ratio = if (state.levelPercent in 0..100) state.levelPercent / 100f else 0f
        val fillRight = innerLeft + (innerRight - innerLeft) * ratio
        batteryFillPaint.color = when {
            state.charging -> Color.argb(255, 110, 231, 160)
            state.levelPercent in 0..15 -> Color.argb(255, 248, 113, 113)
            else -> Color.argb(235, 225, 225, 225)
        }
        if (fillRight > innerLeft) {
            canvas.drawRoundRect(
                innerLeft,
                bodyTop + inset,
                fillRight,
                bodyBottom - inset,
                dp(1.2f),
                dp(1.2f),
                batteryFillPaint,
            )
        }
        if (state.charging) {
            drawChargingBolt(canvas, (bodyLeft + bodyRight) / 2f, centerY)
        }
    }

    /** 电池内部的充电闪电标识。 */
    private fun drawChargingBolt(canvas: Canvas, centerX: Float, centerY: Float) {
        boltPath.reset()
        boltPath.moveTo(centerX + dp(0.8f), centerY - dp(4.2f))
        boltPath.lineTo(centerX - dp(2.2f), centerY + dp(0.5f))
        boltPath.lineTo(centerX - dp(0.1f), centerY + dp(0.5f))
        boltPath.lineTo(centerX - dp(0.9f), centerY + dp(4.2f))
        boltPath.lineTo(centerX + dp(2.4f), centerY - dp(0.5f))
        boltPath.lineTo(centerX + dp(0.2f), centerY - dp(0.5f))
        boltPath.close()
        canvas.drawPath(boltPath, boltPaint)
    }

    /** 在内容区内水平居中绘制一行文字；超宽时以省略号截断到内容宽度。 */
    private fun drawCentered(canvas: Canvas, text: String, paint: Paint, top: Float) {
        drawCenteredRaw(canvas, ellipsize(text, paint, contentWidth), paint, top)
    }

    /** 绘制已经截断 / 确定宽度的文字，不再重复做省略号计算。 */
    private fun drawCenteredRaw(canvas: Canvas, text: String, paint: Paint, top: Float) {
        val x = posX + framePadding + (contentWidth - paint.measureText(text)) / 2f
        canvas.drawText(text, x, top - paint.fontMetrics.ascent, paint)
    }

    private fun lineHeight(paint: Paint): Float =
        paint.fontMetrics.descent - paint.fontMetrics.ascent + dp(1f)

    private fun ellipsize(text: String, paint: Paint, maxWidth: Float): String {
        if (text.isEmpty() || maxWidth <= 0f) return text
        if (paint.measureText(text) <= maxWidth) return text
        val ellipsis = "…"
        var end = text.length
        while (end > 0 && paint.measureText(text, 0, end) + paint.measureText(ellipsis) > maxWidth) {
            end--
        }
        return text.substring(0, end).trimEnd() + ellipsis
    }

    private fun resetPosition() {
        val maxX = horizontalMax()
        val maxY = verticalMax()
        posX = ((width - frameWidth) / 2f).coerceIn(collisionInset, maxX)
        posY = ((height - frameHeight) / 2f).coerceIn(collisionInset, maxY)
        // 随机斜向：两个轴速度分量大小相同，碰到边缘就按该轴反向。
        velocityX = speedPxPerSecond * if (Random.nextBoolean()) 1f else -1f
        velocityY = speedPxPerSecond * if (Random.nextBoolean()) 1f else -1f
    }

    private fun clampPosition() {
        if (frameWidth <= 0f || frameHeight <= 0f) return
        posX = posX.coerceIn(collisionInset, horizontalMax())
        posY = posY.coerceIn(collisionInset, verticalMax())
    }

    private fun advance(deltaSeconds: Float) {
        if (frameWidth <= 0f || frameHeight <= 0f) return
        val maxX = horizontalMax()
        val maxY = verticalMax()
        posX += velocityX * deltaSeconds
        posY += velocityY * deltaSeconds
        if (posX <= collisionInset) {
            posX = collisionInset
            velocityX = abs(velocityX)
        } else if (posX >= maxX) {
            posX = maxX
            velocityX = -abs(velocityX)
        }
        if (posY <= collisionInset) {
            posY = collisionInset
            velocityY = abs(velocityY)
        } else if (posY >= maxY) {
            posY = maxY
            velocityY = -abs(velocityY)
        }
        publishActionBounds()
    }

    /** 焦点通知在面板里的总高度；与 [drawFocusBlock] 的推进严格一致。 */
    private fun focusBlockHeight(): Float {
        var height = 0f
        focusState.take(MAX_FOCUS_ENTRIES).forEachIndexed { index, item ->
            if (index > 0) height += gap
            height += lineHeight(focusTitlePaint)
            if (item.detail.isNotEmpty()) {
                height += focusLineGap + lineHeight(focusDetailPaint)
            }
            if (item.progress != null) {
                height += focusProgressGap + focusProgressHeight
            }
        }
        return height
    }

    /** 主文案固定为「应用名 · 标题」；应用名让多条岛状态可以区分来源。 */
    private fun focusTitleText(item: FocusNotification): String =
        if (item.title.isBlank()) item.appLabel else "${item.appLabel} · ${item.title}"

    /** 绘制焦点通知块并返回绘制结束后的光标位置。 */
    private fun drawFocusBlock(canvas: Canvas, top: Float): Float {
        var cursor = top
        focusState.take(MAX_FOCUS_ENTRIES).forEachIndexed { index, item ->
            if (index > 0) cursor += gap
            drawCentered(canvas, focusTitleText(item), focusTitlePaint, cursor)
            cursor += lineHeight(focusTitlePaint)
            if (item.detail.isNotEmpty()) {
                cursor += focusLineGap
                drawCentered(canvas, item.detail, focusDetailPaint, cursor)
                cursor += lineHeight(focusDetailPaint)
            }
            item.progress?.let { progress ->
                cursor += focusProgressGap
                drawProgressBar(canvas, cursor, progress, item.accentColor)
                cursor += focusProgressHeight
            }
        }
        return cursor
    }

    /** 一条细进度条；没有 `highlightColor` 时用面板的默认蓝色。 */
    private fun drawProgressBar(canvas: Canvas, top: Float, progress: Int, accent: Int?) {
        val left = posX + framePadding
        val radius = focusProgressHeight / 2f
        canvas.drawRoundRect(
            left,
            top,
            left + contentWidth,
            top + focusProgressHeight,
            radius,
            radius,
            focusProgressTrackPaint,
        )
        val ratio = progress.coerceIn(0, 100) / 100f
        if (ratio <= 0f) return
        focusProgressFillPaint.color = accent ?: DEFAULT_FOCUS_ACCENT
        canvas.drawRoundRect(
            left,
            top,
            left + contentWidth * ratio,
            top + focusProgressHeight,
            radius,
            radius,
            focusProgressFillPaint,
        )
    }

    private fun publishActionBounds() {
        if (mediaState == null) return
        actionBounds()?.let(onActionBoundsChanged)
    }

    private fun horizontalMax(): Float =
        (width - frameWidth - collisionInset).coerceAtLeast(collisionInset)

    private fun verticalMax(): Float =
        (height - frameHeight - collisionInset).coerceAtLeast(collisionInset)

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    private fun sp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

    private companion object {
        /** 面板里最多同时展示的岛状态条数；再多会挤占时钟与媒体行。 */
        const val MAX_FOCUS_ENTRIES = 2

        /** 焦点通知没有 `highlightColor` 时的进度条颜色（与边框泛光同色）。 */
        val DEFAULT_FOCUS_ACCENT: Int = Color.argb(255, 96, 210, 255)
    }
}
