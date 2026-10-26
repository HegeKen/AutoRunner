package com.autorunner.core.notification

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 从 `miui.focus.param` 负载里抽出的展示字段。
 *
 * 小米超级岛 / 焦点通知把整条通知的内容塞在通知 extras 的一个 JSON 字符串里，
 * 字段随模板（`baseInfo` / `chatInfo` / `progressInfo` …）和下发方式
 * （MIPUSH 场景化模板 / 客户端 `param_v2`）变化很大。黑屏挂机上只需要
 * 「一行主文案 + 一行补充说明 + 可选进度」，因此这里只保留这四个字段，
 * 其余结构不解析、不持有。
 *
 * @param title 主文案，来自 `baseInfo.title` / 场景化 `title` / 大岛 B 区。
 * @param detail 补充说明，最多两段，用 ` · ` 连接。
 * @param progress 进度百分比（0..100），没有进度时为 `null`。
 * @param accentColor 强调色（`#RRGGBB`），用于进度条。
 * @param timeoutSeconds 岛自动消失时间（秒），来自 `param_v2.islandTimeout`。
 * @param scene 业务场景标识，仅用于日志排查。
 */
data class FocusPayload(
    val title: String,
    val detail: String,
    val progress: Int?,
    val accentColor: String?,
    val timeoutSeconds: Int,
    val scene: String?,
)

/**
 * `miui.focus.param` 的最小解析器。
 *
 * 同时兼容两种真实形态：
 *
 * 1. **场景化模板**（MiPush `scene=foodDelivery` 等）：字段直接位于根对象，
 *    如 `title` / `content` / `desc1` / `progress` / `colorDesc`。
 * 2. **`param_v2` 模板**：字段位于 `param_v2` 下，正文在
 *    `baseInfo` / `hintInfo` / `progressInfo` 等组件里。
 *
 * 大岛文案（`param_island.bigIslandArea.*`）作为兜底，避免只有岛数据、
 * 没有通知正文时出现空白。
 */
object FocusPayloadParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(raw: String?): FocusPayload? {
        if (raw.isNullOrBlank()) return null
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return null
        // 光有 param_island 没有可读文案时也要继续尝试岛文案，因此这里不提前返回 null。
        val v2 = root.obj("param_v2")
        val scope = v2 ?: root
        val island = root.obj("param_island") ?: scope.obj("param_island")

        val baseInfo = scope.obj("baseInfo")
        val hintInfo = scope.obj("hintInfo")

        val islandB = island.obj("bigIslandArea")?.obj("textInfo")
        val islandA = island.obj("bigIslandArea")?.obj("imageTextInfoLeft")?.obj("textInfo")

        val title = sequenceOf(
            baseInfo.str("title"),
            scope.str("title"),
            islandB.str("title")?.let { joinNonBlank(it, islandB.str("content")) },
            hintInfo.str("title"),
        ).firstNonBlank()

        val detail = buildList {
            scope.str("desc1")?.let(::add)
            scope.str("content")?.let(::add)
            baseInfo.str("content")?.let(::add)
            islandA?.let { a ->
                joinNonBlank(a.str("frontTitle"), a.str("title"), a.str("content"))?.let(::add)
            }
        }.distinct().take(2).joinToString(" · ")

        val progress = sequenceOf(
            root.int("progress"),
            island.int("progress"),
            island.obj("progressTextInfo")?.obj("progressInfo").int("progress"),
        ).filterNotNull().firstOrNull()?.coerceIn(0, 100)

        val accent = sequenceOf(
            island.str("highlightColor"),
            root.str("colorDesc"),
            root.str("colorDescDark"),
        ).filterNotNull().firstOrNull()

        val timeout = sequenceOf(
            scope.int("islandTimeout"),
            scope.int("timeout")?.let { it * 60 }, // timeout 单位是分钟
        ).filterNotNull().firstOrNull() ?: DEFAULT_TIMEOUT_SECONDS

        val scene = sequenceOf(scope.str("scene"), scope.str("business"))
            .filterNotNull().firstOrNull()

        if (title.isNullOrBlank() && detail.isBlank() && progress == null) return null
        return FocusPayload(
            title = title.orEmpty(),
            detail = detail,
            progress = progress,
            accentColor = accent,
            timeoutSeconds = timeout.coerceAtLeast(1),
            scene = scene,
        )
    }

    /** 该 JSON 是否明确表示「直接结束」；调用方据此跳过展示。 */
    fun isCancel(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val root = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return false
        val scope = root.obj("param_v2") ?: root
        return scope.bool("cancel") == true
    }

    private fun joinNonBlank(vararg parts: String?): String? =
        parts.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" ")

    private fun Sequence<String?>.firstNonBlank(): String? = firstOrNull { !it.isNullOrBlank() }

    private fun JsonObject?.obj(key: String): JsonObject? = this?.get(key) as? JsonObject

    private fun JsonObject?.str(key: String): String? {
        val primitive = this?.get(key) as? JsonPrimitive ?: return null
        if (!primitive.isString) return null
        return primitive.content.trim().takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun JsonObject?.int(key: String): Int? =
        (this?.get(key) as? JsonPrimitive)?.intOrNull

    private fun JsonObject?.bool(key: String): Boolean? =
        (this?.get(key) as? JsonPrimitive)?.booleanOrNull

    /** FAQ：`islandTimeout` 不传时系统按 1 小时兜底。 */
    private const val DEFAULT_TIMEOUT_SECONDS = 60 * 60
}
