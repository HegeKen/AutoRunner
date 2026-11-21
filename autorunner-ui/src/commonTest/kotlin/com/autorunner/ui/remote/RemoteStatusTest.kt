package com.autorunner.ui.remote

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `/api/status` 的解析与地址归一化。
 *
 * 服务端把所有数字都写成 JSON 字符串（`"8720"` 而不是 `8720`），所有可空项
 * 写成 `null`，因此这里用一份真实形状的载荷锁住「字符串数字 + null」的组合，
 * 避免以后把 `isLenient` 去掉后在真机上才暴露解析失败。
 */
class RemoteStatusTest {

    @Test
    fun parsesNumbersEncodedAsStrings() {
        val payload = """
            {
              "serverTimeMs": "1750000000000",
              "serverTime": "10:00",
              "serverUptimeMs": "90000",
              "stateVersion": "7",
              "longPollMs": "20000",
              "app": {
                "versionName": "1.2.3",
                "versionCode": "1203",
                "packageName": "cn.helilab.autorunner",
                "device": "Xiaomi 13",
                "android": "14",
                "sdk": "34",
                "themeMode": "SYSTEM"
              },
              "network": { "running": "true", "port": "8720", "addresses": ["192.168.1.5"] },
              "execution": {
                "state": "RUNNING",
                "stateLabel": "运行中",
                "scriptName": "每日任务",
                "completedLoops": "3",
                "totalLoops": "10",
                "loopFraction": "0.35",
                "currentActionIndex": "2",
                "totalActions": "8",
                "elapsedMs": "61000",
                "intervalTotalMs": "2000",
                "message": ""
              },
              "combo": null,
              "lastReport": {
                "scriptName": "旧任务",
                "outcome": "FAILED",
                "summary": "第 2 轮失败",
                "completedLoops": "1",
                "totalLoops": null,
                "elapsedLabel": "3 分 2 秒",
                "failureMessage": "找不到控件"
              },
              "scripts": [
                {
                  "id": "a",
                  "name": "每日任务",
                  "steps": "8",
                  "mode": "REPEAT",
                  "repeatCount": "10",
                  "totalLoops": "10",
                  "intervalMs": "2000",
                  "usesGamepad": "false",
                  "sizeBytes": "2048"
                }
              ],
              "environment": {
                "inputMode": "ACCESSIBILITY",
                "inputModeLabel": "无障碍",
                "accessibilityConnected": "true",
                "overlayPermission": "false",
                "rootAvailable": "false",
                "batteryPercent": "56",
                "batteryCharging": null,
                "screenWidth": "1080",
                "screenHeight": "2400",
                "unknownField": "ignored"
              }
            }
        """.trimIndent()

        val snapshot = assertNotNull(parseRemoteStatus(payload))
        assertEquals(1_203, snapshot.app.versionCode)
        assertEquals(34, snapshot.app.sdk)
        assertEquals(7L, snapshot.stateVersion)
        assertEquals(20_000L, snapshot.longPollMs)
        assertEquals(true, snapshot.network.running)
        assertEquals(8_720, snapshot.network.port)
        assertEquals(3, snapshot.execution.completedLoops)
        assertEquals(10, snapshot.execution.totalLoops)
        assertEquals(0.35f, snapshot.execution.loopFraction?.toFloat())
        assertEquals(61_000L, snapshot.execution.elapsedMs)
        assertNull(snapshot.combo)
        assertEquals("FAILED", snapshot.lastReport?.outcome)
        assertNull(snapshot.lastReport?.totalLoops)
        assertEquals(2_048L, snapshot.scripts.single().sizeBytes)
        assertEquals(56, snapshot.environment.batteryPercent)
        assertNull(snapshot.environment.batteryCharging)
        assertEquals(2, snapshot.environment.screenHeight / 1_200)
    }

    @Test
    fun rejectsNonStatusPayload() {
        assertNull(parseRemoteStatus("<html>404</html>"))
    }

    @Test
    fun parsesComboSlotRoundTime() {
        val payload = """
            {
              "combo": {
                "state": "RUNNING",
                "slots": [
                  {
                    "role": "A",
                    "state": "RUNNING",
                    "currentActionIndex": "0",
                    "totalActions": "2",
                    "loopElapsedMs": "65000"
                  }
                ]
              }
            }
        """.trimIndent()

        val slot = assertNotNull(parseRemoteStatus(payload)?.combo?.slots?.single())
        assertEquals(65_000L, slot.loopElapsedMs)
        assertEquals("动作 1/2", slot.phaseText())
        assertEquals("本轮 01:05", slot.roundTimeText())
    }

    @Test
    fun slotRoundTimeFollowsPhase() {
        val firstRoundWait = RemoteComboSlot(
            state = "RUNNING",
            startDelayTotalMs = 26 * 60_000,
            startDelayElapsedMs = 5 * 60_000 + 12_000,
        )
        assertEquals("等待首轮", firstRoundWait.phaseText())
        assertEquals("已等待 05:12 / 26:00", firstRoundWait.roundTimeText())

        val interval = RemoteComboSlot(
            state = "RUNNING",
            intervalTotalMs = 14 * 60_000,
            intervalElapsedMs = 3 * 60_000 + 58_000,
        )
        assertEquals("循环间隔", interval.phaseText())
        assertEquals("已等待 03:58 / 14:00", interval.roundTimeText())

        val running = RemoteComboSlot(
            state = "RUNNING",
            currentActionIndex = 1,
            totalActions = 8,
            loopElapsedMs = 4 * 60_000 + 21_000,
        )
        assertEquals("动作 2/8", running.phaseText())
        assertEquals("本轮 04:21", running.roundTimeText())

        val idle = RemoteComboSlot(state = "IDLE", description = "尚未开始")
        assertEquals("尚未开始", idle.phaseText())
        assertEquals("", idle.roundTimeText())
    }

    @Test
    fun liveRoundTimeOnlyAdvancesWhileDispatching() {
        fun liveSlot(slot: RemoteComboSlot) = assertNotNull(
            RemoteStatusSnapshot(combo = RemoteCombo(state = "RUNNING", slots = listOf(slot)))
                .liveAfter(10_000)
                .combo
                ?.slots
                ?.single(),
        )

        val dispatching = liveSlot(
            RemoteComboSlot(
                state = "RUNNING",
                currentActionIndex = 0,
                totalActions = 2,
                loopElapsedMs = 1_000,
            ),
        )
        assertEquals(11_000L, dispatching.loopElapsedMs)

        // 轮间等待只推进等待时长，本轮时长停在上一轮的结果上。
        val waiting = liveSlot(
            RemoteComboSlot(
                state = "RUNNING",
                loopElapsedMs = 9_000,
                intervalTotalMs = 60_000,
                intervalElapsedMs = 5_000,
            ),
        )
        assertEquals(9_000L, waiting.loopElapsedMs)
        assertEquals(15_000L, waiting.intervalElapsedMs)
    }

    @Test
    fun derivesReadTimeoutFromServerPollCap() {
        // 新服务端上报 20 秒挂起：读超时留 15 秒余量，一条死连接最迟 35 秒被发现，
        // 而不是像以前固定 70 秒那样把「连接中断」拖很久。
        assertEquals(35_000, remoteReadTimeoutMs(20_000))
        // 旧服务端没有 longPollMs：按 55 秒兜底，仍是原来的 70 秒。
        assertEquals(70_000, remoteReadTimeoutMs(0))
        // 异常值夹到合理区间，避免对方给一个荒唐的上限把监控页卡死。
        assertEquals(16_000, remoteReadTimeoutMs(1))
        assertEquals(75_000, remoteReadTimeoutMs(10 * 60_000))
    }

    @Test
    fun normalizesHostInput() {
        assertEquals("http://192.168.1.5:8720/", buildRemoteStatusUrl("192.168.1.5", "8720"))
        assertEquals("http://192.168.1.5:9000/", buildRemoteStatusUrl("192.168.1.5:9000", "8720"))
        assertEquals("http://192.168.1.5:8720/", buildRemoteStatusUrl("http://192.168.1.5:8720/", "1234"))
        assertEquals("http://mypc.local:8720/", buildRemoteStatusUrl(" mypc.local ", "8720"))
        assertNull(buildRemoteStatusUrl("", "8720"))
        assertNull(buildRemoteStatusUrl("192.168.1.5", "70000"))
    }

    @Test
    fun extrapolatesRunningElapsedAndClampsWaitTime() {
        val snapshot = RemoteStatusSnapshot(
            serverUptimeMs = 90_000,
            execution = RemoteExecution(
                state = "RUNNING",
                elapsedMs = 5_000,
                intervalElapsedMs = 59_000,
                intervalTotalMs = 60_000,
            ),
        )

        val live = snapshot.liveAfter(2_000)
        assertEquals(92_000, live.serverUptimeMs)
        assertEquals(7_000, live.execution.elapsedMs)
        assertEquals("00:07", live.execution.elapsedLabel)
        assertEquals(60_000, live.execution.intervalElapsedMs)
    }

    @Test
    fun pausedExecutionDoesNotAdvance() {
        val snapshot = RemoteStatusSnapshot(
            execution = RemoteExecution(state = "PAUSED", elapsedMs = 5_000, elapsedLabel = "00:05"),
        )

        val live = snapshot.liveAfter(3_000)
        assertEquals(5_000, live.execution.elapsedMs)
        assertEquals("00:05", live.execution.elapsedLabel)
    }

    @Test
    fun comboSlotsAdvanceAndRebuildWaitDescription() {
        val snapshot = RemoteStatusSnapshot(
            combo = RemoteCombo(
                state = "RUNNING",
                elapsedMs = 60_000,
                slots = listOf(
                    RemoteComboSlot(
                        role = "A",
                        state = "RUNNING",
                        elapsedMs = 60_000,
                        intervalTotalMs = 14 * 60_000,
                    ),
                    RemoteComboSlot(
                        role = "B",
                        state = "RUNNING",
                        elapsedMs = 60_000,
                        startDelayTotalMs = 26 * 60_000,
                    ),
                ),
            ),
        )

        val live = snapshot.liveAfter(60_000)
        val combo = assertNotNull(live.combo)
        assertEquals(120_000, combo.elapsedMs)
        assertEquals("02:00", combo.elapsedLabel)
        assertEquals("循环间隔 1/14 分钟", combo.slots[0].description)
        assertEquals("等待首轮 1/26 分钟", combo.slots[1].description)
    }

    @Test
    fun formatsProgressAndDuration() {
        assertEquals("第 4 / 10 轮", loopProgressText(3, 10))
        assertEquals("已完成 10 轮", loopProgressText(10, 10))
        assertEquals("已运行 5 轮 · 无限循环", loopProgressText(5, null))
        assertEquals("1 分 5 秒", formatDuration(65_000))
        assertEquals("2 小时 0 分", formatDuration(7_200_000))
    }
}
