package com.autorunner.ui.format

/**
 * 循环间隔 / 首轮延迟控件使用的单位，也用于把估算总时长格式化为同一单位。
 *
 * 脚本格式只存毫秒；单位纯粹影响用户的输入与阅读方式，
 * 「每 5 分钟」不必写成 300000。
 */
enum class IntervalUnit(val label: String, internal val perUnitMs: Long, val maxUnits: Float, val steps: Int) {
    MILLIS("毫秒", 1L, 5_000f, 49),
    SECONDS("秒", 1_000L, 300f, 59),
    MINUTES("分", 60_000L, 60f, 59),
    ;

    fun unitsOf(millis: Long): Float = (millis.toFloat() / perUnitMs).coerceIn(0f, maxUnits)

    fun toMillis(units: Float): Long = (units * perUnitMs).toLong().coerceAtLeast(0L)

    /**
     * 把毫秒值吸附为本单位的整数格：切换单位时避免出现 `2.3333333 min` 这种
     * 与整数输入框互相矛盾的显示。结果同时受本单位上限（[maxUnits]）约束。
     *
     * 取整用「四舍五入」的整数写法，避免浮点误差；纯函数，便于单测覆盖。
     */
    fun snap(millis: Long): Long {
        if (millis <= 0L) return 0L
        val rounded = ((millis * 2 + perUnitMs) / (perUnitMs * 2))
            .coerceIn(0L, maxUnits.toLong())
        return rounded * perUnitMs
    }

    fun format(units: Float): String = when (this) {
        MILLIS -> "${units.toInt()} ms"
        SECONDS -> "${units.toInt()} s"
        MINUTES -> "${trimTrailingZero(units)} min"
    }

    /** 用于展示「预计总时长」等较长时长的单位名称。 */
    val durationLabel: String
        get() = when (this) {
            MILLIS -> "毫秒"
            SECONDS -> "秒"
            MINUTES -> "分钟"
        }

    /**
     * 把毫秒时长按本单位格式化：毫秒保持整数；秒 / 分钟最多保留两位小数，
     * 末尾的 0 会被去掉（例如 `28 分钟`、`28.1 分钟`、`0.05 分钟`）。
     *
     * 两位小数保证「2.9 秒」在「分钟」单位下不会变成没有意义的 `0 分钟`。
     */
    fun formatDuration(millis: Long): String {
        if (millis <= 0L) return "0 $durationLabel"
        if (this == MILLIS) return "$millis 毫秒"
        val hundredths = kotlin.math.round(millis.toDouble() / perUnitMs * 100.0).toLong()
        val whole = hundredths / 100
        val fraction = hundredths % 100
        val text = when {
            fraction == 0L -> whole.toString()
            fraction % 10 == 0L -> "$whole.${fraction / 10}"
            else -> "$whole.${fraction.toString().padStart(2, '0')}"
        }
        return "$text $durationLabel"
    }

    /** 选取能让存储值保持可读的最大单位。 */
    companion object {
        fun forValue(millis: Long): IntervalUnit = when {
            millis <= 0L -> SECONDS
            millis % 60_000L == 0L -> MINUTES
            millis >= 1_000L -> SECONDS
            else -> MILLIS
        }

        private fun trimTrailingZero(units: Float): String {
            val whole = units.toInt()
            return if (units == whole.toFloat()) whole.toString() else units.toString()
        }
    }
}

