package com.autorunner.ui.state

/**
 * AutoRunner 外壳的顶层导航。
 *
 * 外壳在状态中只保存单个 destination，而不引入导航库：
 * 只有四个页面加一条常驻的导航栏/导航轨，这样做更简单，也更容易适配（§5.2）。
 */
sealed interface Destination {

    /** 首页：已录制的脚本库。 */
    data object Scripts : Destination

    /** 脚本编辑器；`scriptId == null` 表示新建一个空脚本。 */
    data class Editor(val scriptId: String? = null) : Destination

    /** 录制会话：实时事件流 + 开始/停止。 */
    data object Record : Destination

    /** 设置、权限与手柄配置。 */
    data object Settings : Destination

    companion object {
        /** 导航栏 / 导航轨上可达的页面。 */
        val topLevel: List<Destination> = listOf(Scripts, Record, Settings)

        fun labelOf(destination: Destination): String = when (destination) {
            Scripts -> "脚本"
            is Editor -> "编辑"
            Record -> "录制"
            Settings -> "设置"
        }
    }
}
