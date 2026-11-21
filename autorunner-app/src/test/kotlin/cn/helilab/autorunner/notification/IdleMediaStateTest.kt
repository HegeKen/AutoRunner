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

    @Test
    fun progressExtrapolatesWhilePlaying() {
        val state = state(title = "晴天").copy(
            durationMs = 200_000L,
            positionMs = 50_000L,
            positionUpdatedAtElapsedMs = 1_000_000L,
            playbackSpeed = 1f,
        )
        // 10 秒后应为 60/200 = 0.3。
        assertEquals(0.3f, state.progressFractionAt(1_010_000L), 0.0001f)
    }

    @Test
    fun pausedProgressDoesNotAdvance() {
        val state = state(title = "晴天").copy(
            durationMs = 200_000L,
            positionMs = 50_000L,
            positionUpdatedAtElapsedMs = 1_000_000L,
            isPlaying = false,
            playbackSpeed = 0f,
        )
        assertEquals(0.25f, state.progressFractionAt(1_010_000L), 0.0001f)
    }

    @Test
    fun progressIsClampedAndHiddenWithoutDuration() {
        val state = state(title = "晴天").copy(
            durationMs = 10_000L,
            positionMs = 9_500L,
            positionUpdatedAtElapsedMs = 1_000_000L,
        )
        assertEquals(1f, state.progressFractionAt(1_010_000L), 0.0001f)
        assertEquals(0f, state.copy(durationMs = 0L).progressFractionAt(1_010_000L), 0.0001f)
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
