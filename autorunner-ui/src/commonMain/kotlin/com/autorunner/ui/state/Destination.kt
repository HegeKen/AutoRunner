package com.autorunner.ui.state

/**
 * Top level navigation of the AutoRunner shell.
 *
 * The shell keeps a single destination in state instead of pulling in a
 * navigation library: with four screens and a persistent navigation bar/rail
 * that is both simpler and easier to make adaptive (§5.2).
 */
sealed interface Destination {

    /** Home: the recorded script library. */
    data object Scripts : Destination

    /** Script editor; `scriptId == null` starts a new empty script. */
    data class Editor(val scriptId: String? = null) : Destination

    /** Recording session: live event feed + start/stop. */
    data object Record : Destination

    /** Settings, permissions and gamepad configuration. */
    data object Settings : Destination

    companion object {
        /** Destinations reachable from the navigation bar / rail. */
        val topLevel: List<Destination> = listOf(Scripts, Record, Settings)

        fun labelOf(destination: Destination): String = when (destination) {
            Scripts -> "脚本"
            is Editor -> "编辑"
            Record -> "录制"
            Settings -> "设置"
        }
    }
}
