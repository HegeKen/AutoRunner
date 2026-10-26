package com.autorunner.core.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusPayloadParserTest {

    /** 实机抓取：美团外卖（MiPush 场景化模板，无 param_v2 包裹）。 */
    private val scenePayload = """
        {"progressCount":2,"param_island":{"islandOrder":true,"dismissIsland":false,
        "highlightColor":"#FFCC33","bigIslandArea":{"imageTextInfoLeft":{"type":1,
        "textInfo":{"title":"已到店","showHighlightColor":false},"picInfo":{"type":1}},
        "textInfo":{"title":"21:07","content":"送达","showHighlightColor":true}}},
        "orderId":"3102357082368272496","enableFloat":true,"title":"预计21:07送达",
        "content":"川味特色面馆","scene":"foodDelivery","desc1":"商家已出餐，骑手已到店",
        "sequence":1792759690279,"protocol":1,"filterWhenNoPermission":true,"progress":50,
        "updatable":true,"colorDesc":"#FF8000","colorDescDark":"#FFCC33"}
    """.trimIndent()

    /** 官方《开发指南》示例结构（param_v2 + baseInfo）。 */
    private val paramV2Payload = """
        {"param_v2":{"protocol":1,"business":"taxi","enableFloat":true,"updatable":true,
        "islandTimeout":1800,
        "param_island":{"islandProperty":1,
        "bigIslandArea":{"imageTextInfoLeft":{"type":1,"textInfo":{"frontTitle":"充电中",
        "title":"24%","content":"剩5分钟"}}},
        "smallIslandArea":{"picInfo":{"type":1,"pic":"miui.focus.pic_imageText"}}},
        "baseInfo":{"title":"待取件","content":"安宁华庭2区8号底商店菜鸟驿站","type":2}}}
    """.trimIndent()

    @Test
    fun parsesSceneTemplate() {
        val payload = FocusPayloadParser.parse(scenePayload)!!
        assertEquals("预计21:07送达", payload.title)
        assertEquals("商家已出餐，骑手已到店 · 川味特色面馆", payload.detail)
        assertEquals(50, payload.progress)
        assertEquals("#FFCC33", payload.accentColor)
        assertEquals(3600, payload.timeoutSeconds)
        assertEquals("foodDelivery", payload.scene)
    }

    @Test
    fun parsesParamV2Template() {
        val payload = FocusPayloadParser.parse(paramV2Payload)!!
        assertEquals("待取件", payload.title)
        // baseInfo.content 与岛 A 区文案各取一段。
        assertEquals("安宁华庭2区8号底商店菜鸟驿站 · 充电中 24% 剩5分钟", payload.detail)
        assertNull(payload.progress)
        assertEquals(1800, payload.timeoutSeconds)
        assertEquals("taxi", payload.scene)
    }

    @Test
    fun fallsBackToIslandTextWhenNotificationBodyMissing() {
        val raw = """
            {"param_v2":{"param_island":{"bigIslandArea":{
            "textInfo":{"title":"2 分钟后到店","content":"取餐码 88"}}}}}
        """.trimIndent()
        val payload = FocusPayloadParser.parse(raw)!!
        assertEquals("2 分钟后到店 取餐码 88", payload.title)
        assertEquals("", payload.detail)
    }

    @Test
    fun readsProgressFromProgressComponent() {
        val raw = """
            {"param_v2":{"baseInfo":{"title":"下载中"},
            "param_island":{"progressTextInfo":{"progressInfo":{"progress":72}}}}}
        """.trimIndent()
        assertEquals(72, FocusPayloadParser.parse(raw)?.progress)
    }

    @Test
    fun rejectsPayloadWithoutAnyContent() {
        assertNull(FocusPayloadParser.parse("{}"))
        assertNull(FocusPayloadParser.parse("not json"))
        assertNull(FocusPayloadParser.parse(null))
        assertNull(FocusPayloadParser.parse(""))
    }

    @Test
    fun detectsCancelFlag() {
        assertEquals(true, FocusPayloadParser.isCancel("""{"param_v2":{"cancel":true}}"""))
        assertEquals(false, FocusPayloadParser.isCancel("""{"param_v2":{"cancel":false}}"""))
        assertEquals(false, FocusPayloadParser.isCancel("{}"))
        assertEquals(false, FocusPayloadParser.isCancel(null))
    }

    @Test
    fun clampsOutOfRangeProgress() {
        val raw = """{"title":"x","progress":250,"param_island":{}}"""
        assertEquals(100, FocusPayloadParser.parse(raw)?.progress)
    }
}
