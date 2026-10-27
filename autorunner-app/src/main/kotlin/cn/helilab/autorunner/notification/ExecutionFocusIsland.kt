package cn.helilab.autorunner.notification

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.provider.Settings
import com.autorunner.core.execution.ComboProgress
import com.autorunner.core.execution.ComboSlotProgress
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.util.currentTimeMillis
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import kotlin.math.roundToInt

/**
 * 「小米超级岛 / 焦点通知」上报。
 *
 * 超级岛不需要新权限或新 API：在普通通知的 extras 里放一个
 * `miui.focus.param` JSON 字符串即可。
 *
 * * 组合运行：`baseInfo` + `multiProgressInfo`（两段进度）+ 大岛 A/B 区各一张
 *   运行期生成的圆环位图；
 * * 单脚本运行：`baseInfo` + `progressInfo`（单条进度）+ 大岛 A 区一张圆环位图。
 *
 * `ticker` / `aodTitle` 兼容 OS2 状态栏与息屏兜底。只在
 * `notification_focus_protocol >= 3`（OS3 / HyperOS 超级岛）时上报；
 * `filterWhenNoPermission = true` 保证未开通焦点通知权限时不会多出一条普通通知。
 */
object ExecutionFocusIsland {

    /** 焦点通知使用独立 id，避免与前台服务通知互相覆盖。 */
    const val NOTIFICATION_ID = 0x4154

    /** 系统设置项：1/2 = 旧焦点通知，3 = OS3 超级岛。 */
    const val PROTOCOL_SETTING = "notification_focus_protocol"

    /** 小米开放平台分配的超级岛应用标识（清单 meta-data）。 */
    const val XMS_APP_ID_META = "com.xiaomi.xms.APP_ID"

    /** Root 模块写入的「已安装超级岛解锁模块」标记。 */
    const val ROOT_FOCUS_MARKER = "/data/local/tmp/autorunner_root/focus_unlock"

    /** 品牌主色，与 App 主题一致。 */
    private const val BRAND_COLOR = "#2655FF"

    /** 进度条未到达部分的颜色（半透明白，适配深浅色）。 */
    private const val UNREACHED_COLOR = "#33FFFFFF"

    /** 焦点通知 / 岛的兜底存活时间，避免进程被杀后永久残留。 */
    private const val TIMEOUT_MINUTES = 12 * 60

    private const val ISLAND_TIMEOUT_SECONDS = 12 * 60 * 60

    /** 当前系统的焦点通知协议版本；非小米设备通常为 0。 */
    fun focusProtocol(context: Context): Int = runCatching {
        Settings.System.getInt(context.contentResolver, PROTOCOL_SETTING, 0)
    }.getOrDefault(0)

    /**
     * 是否具备上报超级岛的最低条件：OS3 协议 + 已配置 XMS APP_ID。
     *
     * APP_ID 是服务端鉴权的必填项（官方文档明确「必须配置 App Id」）；缺少时
     * 即使协议是 3，通知也会在 `onAuthFailed` 后被系统移除，只会在通知栏留下
     * 一条无法上岛的普通通知。因此这里提前拦截。
     */
    fun supportsIsland(context: Context): Boolean =
        focusProtocol(context) >= 3 && (hasXmsAppId(context) || hasRootFocusUnlock())

    /**
     * Root 模块安装的「超级岛解锁」LSPosed 模块标记。
     *
     * 该模块绕过 XMSF 鉴权并让 SystemUI 对 AutoRunner 的焦点通知白名单返回允许；
     * 有它时不需要平台 APP_ID，root 用户也能上岛。
     */
    fun hasRootFocusUnlock(): Boolean =
        runCatching { File(ROOT_FOCUS_MARKER).isFile }.getOrDefault(false)

    /** 是否已在清单里配置 `com.xiaomi.xms.APP_ID`。 */
    fun hasXmsAppId(context: Context): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(
            context.packageName,
            PackageManager.GET_META_DATA,
        )
        !info.metaData?.getString(XMS_APP_ID_META).isNullOrBlank()
    }.getOrDefault(false)

    /**
     * 生成 `miui.focus.param` 的 JSON 负载。
     *
     * @param progress 组合运行的聚合进度（循环数 / 运行时长）。
     * @param combo 两个槽位的实时进度。
     * @param nowEpochMs 当前墙钟时间；测试可注入，生产使用默认值。
     */
    fun buildPayload(
        progress: ExecutionProgress,
        combo: ComboProgress,
        nowEpochMs: Long = currentTimeMillis(),
    ): String {
        val elapsedMs = combo.liveElapsedMs(nowEpochMs)
        val elapsed = ExecutionProgress.formatDuration(elapsedMs)
        val loops = progress.completedLoops
        val slotA = combo.slot(ComboSlotRole.A)
        val slotB = combo.slot(ComboSlotRole.B)
        val percentA = percentOf(slotA, nowEpochMs)
        val percentB = percentOf(slotB, nowEpochMs)
        val overall = ((percentA + percentB) / 2.0).roundToInt().coerceIn(0, 100)
        val countA = slotA?.progress?.completedLoops ?: 0
        val countB = slotB?.progress?.completedLoops ?: 0
        val progressTitle = "A$percentA% · B$percentB%"
        val loopText = "循环 $loops 次"
        val runtimeText = "已运行 $elapsed"
        val summary = "$loopText · $runtimeText"
        val islandTitle = "循环$loops"
        val islandFront = "A$countA/B$countB"

        return buildJsonObject {
            put(
                "param_v2",
                buildJsonObject {
                    put("protocol", 1)
                    put("business", "autorunner_combo")
                    // 首次出现不强制展开，后续更新也不自动弹开，避免打断用户。
                    put("islandFirstFloat", false)
                    put("enableFloat", false)
                    put("updatable", true)
                    put("timeout", TIMEOUT_MINUTES)
                    put("filterWhenNoPermission", true)
                    put("ticker", "组合运行 · $summary")
                    put("aodTitle", "组合运行 · $elapsed")

                    put(
                        "baseInfo",
                        buildJsonObject {
                            put("type", 2)
                            put("title", "组合运行")
                            put("subTitle", loopText)
                            put("content", runtimeText)
                            put("colorTitle", BRAND_COLOR)
                            put("colorSubTitle", BRAND_COLOR)
                            put("showDivider", true)
                            put("showContentDivider", true)
                        },
                    )

                    put(
                        "multiProgressInfo",
                        buildJsonObject {
                            put("title", progressTitle)
                            put("progress", overall)
                            put("color", BRAND_COLOR)
                            // 1 个中间节点 => 进度条被均分成两段，对应 A/B 两个脚本。
                            put("points", 1)
                        },
                    )

                    put(
                        "param_island",
                        buildJsonObject {
                            put("islandProperty", 1)
                            put("islandOrder", true)
                            put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
                            put("highlightColor", BRAND_COLOR)
                            put(
                                "bigIslandArea",
                                buildJsonObject {
                                    put(
                                        "imageTextInfoLeft",
                                        buildJsonObject {
                                            put("type", 1)
                                            put(
                                                "picInfo",
                                                buildJsonObject {
                                                    put("type", 1)
                                                    put("pic", RING_A_PIC_KEY)
                                                },
                                            )
                                            // 圆环内已有 A 标识，A 区不再重复放文字；文字信息
                                            // 仍完整保留在展开通知的标题/摘要里。
                                            put(
                                                "textInfo",
                                                buildJsonObject {
                                                    put("showHighlightColor", false)
                                                },
                                            )
                                        },
                                    )
                                    // 超级岛原生模板只有一个进度组件，无法并排画两个
                                    // 独立进度圈；这里在 B 区放一张运行时生成的
                                    // 「A/B 双环」位图，每个环分别表示一个脚本的当前
                                    // 循环进度。展开通知仍保留原生两段进度条。
                                    put(
                                        "picInfo",
                                        buildJsonObject {
                                            put("type", 1)
                                            put("pic", RING_B_PIC_KEY)
                                        },
                                    )
                                },
                            )
                            put(
                                "smallIslandArea",
                                buildJsonObject {
                                    put("picInfo", buildJsonObject { put("type", 1) })
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
    }

    /** 大岛 A/B 区各一张进度圈位图。 */
    const val RING_A_PIC_KEY = "miui.focus.pic_combo_ring_a"
    const val RING_B_PIC_KEY = "miui.focus.pic_combo_ring_b"

    /** 单脚本运行时大岛 A 区的进度圈位图。 */
    const val RING_SINGLE_PIC_KEY = "miui.focus.pic_single_ring"

    /**
     * 单脚本运行的 `miui.focus.param` 负载。
     *
     * 使用「进度组件 2」（`progressInfo` 不传图标）画一条整段进度条，大岛 A 区
     * 放一张运行时生成的百分比圆环位图；标题直接用脚本名，与前台通知一致。
     */
    fun buildSinglePayload(progress: ExecutionProgress): String {
        val percent = singlePercent(progress)
        val scriptName = progress.scriptName.ifBlank { "AutoRunner" }
        val loopText = "循环 ${progress.loopLabel}"
        val runtimeText = "已运行 ${progress.elapsedLabel}"

        return buildJsonObject {
            put(
                "param_v2",
                buildJsonObject {
                    put("protocol", 1)
                    put("business", "autorunner_single")
                    put("islandFirstFloat", false)
                    put("enableFloat", false)
                    put("updatable", true)
                    put("timeout", TIMEOUT_MINUTES)
                    put("filterWhenNoPermission", true)
                    put("ticker", "$scriptName · $loopText · $runtimeText")
                    put("aodTitle", "$scriptName · $percent%")

                    put(
                        "baseInfo",
                        buildJsonObject {
                            put("type", 2)
                            put("title", scriptName)
                            put("subTitle", loopText)
                            put("content", runtimeText)
                            put("colorTitle", BRAND_COLOR)
                            put("colorSubTitle", BRAND_COLOR)
                            put("showDivider", true)
                            put("showContentDivider", true)
                        },
                    )

                    put(
                        "progressInfo",
                        buildJsonObject {
                            put("progress", percent)
                            put("colorProgress", BRAND_COLOR)
                            put("colorProgressEnd", BRAND_COLOR)
                        },
                    )

                    put(
                        "param_island",
                        buildJsonObject {
                            put("islandProperty", 1)
                            put("islandOrder", true)
                            put("islandTimeout", ISLAND_TIMEOUT_SECONDS)
                            put("highlightColor", BRAND_COLOR)
                            put(
                                "bigIslandArea",
                                buildJsonObject {
                                    put(
                                        "imageTextInfoLeft",
                                        buildJsonObject {
                                            put("type", 1)
                                            put(
                                                "picInfo",
                                                buildJsonObject {
                                                    put("type", 1)
                                                    put("pic", RING_SINGLE_PIC_KEY)
                                                },
                                            )
                                            put(
                                                "textInfo",
                                                buildJsonObject {
                                                    put("title", scriptName)
                                                    put("content", loopText)
                                                    put("showHighlightColor", false)
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                            put(
                                "smallIslandArea",
                                buildJsonObject {
                                    put("picInfo", buildJsonObject { put("type", 1) })
                                },
                            )
                        },
                    )
                },
            )
        }.toString()
    }

    /** 单脚本进度：优先首轮等待 / 循环间隔，其次当前循环动作完成度。 */
    fun singlePercent(progress: ExecutionProgress): Int {
        val fraction = when {
            progress.startDelayTotalMs > 0L &&
                progress.startDelayElapsedMs < progress.startDelayTotalMs ->
                progress.startDelayElapsedMs.toFloat() / progress.startDelayTotalMs

            progress.intervalTotalMs > 0L &&
                progress.intervalElapsedMs < progress.intervalTotalMs ->
                progress.intervalElapsedMs.toFloat() / progress.intervalTotalMs

            else -> progress.actionFraction ?: progress.loopFraction ?: 0f
        }
        return (fraction * 100f).roundToInt().coerceIn(0, 100)
    }

    /** 单脚本进度圈：中心显示百分比数字。 */
    fun buildSingleRingBitmap(percent: Int): Bitmap =
        buildRingBitmap(percent, percent.toString(), BRAND_COLOR)

    /**
     * 生成单个任务的进度圈。
     *
     * 原生大岛的 A/B 区各只能放一个图文组件，没有「两个独立进度」字段；
     * 因此 A 区、B 区各放一张运行时生成的圆环位图，分别表示脚本 A / B。
     * 展开通知里仍保留原生两段进度条和完整文字。
     */
    fun buildRingBitmap(
        percent: Int,
        label: String,
        colorHex: String,
        sizePx: Int = 192,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val stroke = sizePx * 0.11f
        val radius = sizePx * 0.38f
        val cx = sizePx / 2f
        val cy = sizePx / 2f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor(UNREACHED_COLOR)
        }
        val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            strokeCap = Paint.Cap.ROUND
            color = Color.parseColor(colorHex)
        }
        canvas.drawArc(rect, -90f, 360f, false, track)
        canvas.drawArc(rect, -90f, 360f * percent / 100f, false, arc)

        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            // 三位数百分比（100%）比 A/B 更宽，缩小字号保证不压到圆环。
            textSize = sizePx * if (label.length >= 3) 0.24f else 0.32f
            color = Color.WHITE
        }
        val metrics = labelPaint.fontMetrics
        val labelY = cy - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(label, cx, labelY, labelPaint)
        return bitmap
    }

    private fun percentOf(slot: ComboSlotProgress?, nowEpochMs: Long): Int =
        ((slot?.cycleFractionAt(nowEpochMs) ?: 0f) * 100f).roundToInt().coerceIn(0, 100)
}
