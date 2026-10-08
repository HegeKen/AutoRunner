package cn.helilab.autorunner.input

import kotlin.test.Test
import kotlin.test.assertEquals

class RootInputProtocolTest {

    // ---- rootInputArgs: 协议行 → input 子命令 ----

    @Test
    fun longPressBecomesStationarySwipe() {
        assertEquals(
            "swipe 100 200 100 200 500",
            rootInputArgs("long 100 200 500"),
        )
    }

    @Test
    fun tapLinePassesThroughUnchanged() {
        val line = "tap 320 640"
        assertEquals(line, rootInputArgs(line))
    }

    @Test
    fun swipeLinePassesThroughUnchanged() {
        val line = "swipe 10 20 30 40 150"
        assertEquals(line, rootInputArgs(line))
    }

    @Test
    fun shortLongLineIsNotConverted() {
        // 少于 4 段的 "long ..." 不是合法协议行，应原样返回而不是生成残缺 swipe。
        val line = "long 100 200"
        assertEquals(line, rootInputArgs(line))
    }

    @Test
    fun longKeywordWithWrongPositionIsNotConverted() {
        val line = "swipe 100 200 500 extra"
        assertEquals(line, rootInputArgs(line))
    }

    // ---- rootPx: 归一化坐标 → 像素 ----

    @Test
    fun nonNormalisedValueIsRoundedAsIs() {
        val value = rootPx(123.4f, horizontal = true, normalised = false) { 1080 to 2400 }
        assertEquals(123, value)
    }

    @Test
    fun normalisedHorizontalUsesWidth() {
        val value = rootPx(0.5f, horizontal = true, normalised = true) { 1080 to 2400 }
        assertEquals(540, value)
    }

    @Test
    fun normalisedVerticalUsesHeight() {
        val value = rootPx(0.5f, horizontal = false, normalised = true) { 1080 to 2400 }
        assertEquals(1200, value)
    }

    @Test
    fun normalisedValueRoundsToNearestPixel() {
        // 0.333 * 1000 = 333.0；0.334 * 1000 = 334.0（含四舍五入边界）
        val metrics = { 1000 to 1000 }
        assertEquals(500, rootPx(0.5004f, horizontal = true, normalised = true, metrics))
        assertEquals(499, rootPx(0.4994f, horizontal = true, normalised = true, metrics))
    }

    @Test
    fun metricsNotConsultedWhenNotNormalised() {
        // normalised=false 时不应触碰 metrics（否则在未授权 root 的设备上也可能抛异常）。
        val value = rootPx(7f, horizontal = true, normalised = false) {
            error("metrics must not be called")
        }
        assertEquals(7, value)
    }
}
