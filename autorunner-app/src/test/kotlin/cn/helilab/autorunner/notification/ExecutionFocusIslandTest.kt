package cn.helilab.autorunner.notification

import com.autorunner.core.execution.ComboProgress
import com.autorunner.core.execution.ComboSlotProgress
import com.autorunner.core.model.ComboSlotRole
import com.autorunner.core.model.ExecutionProgress
import com.autorunner.core.model.ExecutionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ExecutionFocusIslandTest {

    private fun slot(
        role: ComboSlotRole,
        completedLoops: Int,
        intervalElapsedMs: Long,
    ) = ComboSlotProgress(
        role = role,
        scriptId = role.name,
        scriptName = role.name,
        state = ExecutionState.RUNNING,
        progress = ExecutionProgress(
            state = ExecutionState.RUNNING,
            completedLoops = completedLoops,
            totalLoops = 10,
            intervalTotalMs = 6_000L,
            intervalElapsedMs = intervalElapsedMs,
        ),
        loopDurationMs = 4_000L,
        cycleDurationMs = 10_000L,
    )

    @Test
    fun buildsATwoSegmentIslandPayloadForTheCombo() {
        val combo = ComboProgress(
            slots = listOf(
                slot(ComboSlotRole.A, completedLoops = 3, intervalElapsedMs = 6_000L),
                slot(ComboSlotRole.B, completedLoops = 2, intervalElapsedMs = 4_000L),
            ),
            state = ExecutionState.RUNNING,
            // 125 秒 = 02:05
            elapsedMs = 125_000L,
        )
        val progress = ExecutionProgress(
            state = ExecutionState.RUNNING,
            completedLoops = 5,
            totalLoops = 20,
        )

        val payload = ExecutionFocusIsland.buildPayload(progress, combo, nowEpochMs = 0L)
        val root = Json.parseToJsonElement(payload).jsonObject
        val v2 = root.obj("param_v2")
        val baseInfo = v2.obj("baseInfo")
        val multi = v2.obj("multiProgressInfo")
        val island = v2.obj("param_island")
        val big = island.obj("bigIslandArea")

        assertEquals("组合运行", baseInfo.str("title"))
        assertEquals("循环 5 次", baseInfo.str("subTitle"))
        assertEquals("已运行 02:05", baseInfo.str("content"))

        // A 100%、B 80% → 平均 90%，两段进度条用 1 个中间节点分隔。
        assertEquals(90, multi["progress"]!!.jsonPrimitive.int)
        assertEquals(1, multi["points"]!!.jsonPrimitive.int)
        assertEquals("A100% · B80%", multi.str("title"))

        // 原生岛没有两个独立的进度字段：A/B 区各引用一张运行时生成的双环位图。
        assertEquals(
            ExecutionFocusIsland.RING_A_PIC_KEY,
            big.obj("imageTextInfoLeft").obj("picInfo").str("pic"),
        )
        assertEquals(
            ExecutionFocusIsland.RING_B_PIC_KEY,
            big.obj("picInfo").str("pic"),
        )
    }

    private fun JsonObject.obj(key: String): JsonObject = this[key]!!.jsonObject

    private fun JsonObject.str(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun buildsASingleProgressIslandPayloadForOneScript() {
        val progress = ExecutionProgress(
            state = ExecutionState.RUNNING,
            scriptName = "GTA OL",
            completedLoops = 3,
            totalLoops = 10,
            // 第 2 / 4 个动作进行中 → 50%
            currentActionIndex = 1,
            totalActions = 4,
        )

        val payload = ExecutionFocusIsland.buildSinglePayload(progress)
        val root = Json.parseToJsonElement(payload).jsonObject
        val v2 = root.obj("param_v2")
        val baseInfo = v2.obj("baseInfo")
        val island = v2.obj("param_island")
        val big = island.obj("bigIslandArea")
        val imageText = big.obj("imageTextInfoLeft")

        assertEquals("GTA OL", baseInfo.str("title"))
        assertEquals("循环 3 / 10", baseInfo.str("subTitle"))
        assertEquals("已运行 00:00", baseInfo.str("content"))
        assertEquals(50, v2.obj("progressInfo")["progress"]!!.jsonPrimitive.int)
        assertEquals(
            ExecutionFocusIsland.RING_SINGLE_PIC_KEY,
            imageText.obj("picInfo").str("pic"),
        )
        assertEquals("GTA OL", imageText.obj("textInfo").str("title"))
    }
}
