package cn.helilab.autorunner.notification

import kotlin.test.Test
import kotlin.test.assertEquals

class IdleMediaStateTest {

    @Test
    fun displayTextCombinesTitleAndArtist() {
        val state = state(title = "晴天", artist = "周杰伦")
        assertEquals("♫ 晴天 · 周杰伦", state.displayText)
    }

    @Test
    fun displayTextOmitsEmptyArtist() {
        val state = state(title = "晴天", artist = "")
        assertEquals("♫ 晴天", state.displayText)
    }

    @Test
    fun displayTextFallsBackToAppLabelWhenTitleIsMissing() {
        val state = state(title = "", appLabel = "网易云音乐")
        assertEquals("♫ 网易云音乐", state.displayText)
    }

    @Test
    fun displayTextFallsBackToPlaceholderWhenEverythingIsMissing() {
        val state = state(title = "", appLabel = "", artist = "")
        assertEquals("♫ 正在播放", state.displayText)
    }

    private fun state(
        title: String,
        appLabel: String = "音乐",
        artist: String = "",
    ) = IdleMediaState(
        packageName = "com.example.player",
        appLabel = appLabel,
        title = title,
        artist = artist,
        isPlaying = true,
        canSkipPrevious = true,
        canSkipNext = true,
        canTogglePlayPause = true,
    )
}
