package com.autorunner.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.autorunner.ui.components.SectionCard
import com.autorunner.ui.components.StatusPill
import com.autorunner.ui.platform.PermissionStatus
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.AppRecording
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Link
import top.yukonga.miuix.kmp.icon.extended.Lock
import top.yukonga.miuix.kmp.icon.extended.Show
import top.yukonga.miuix.kmp.icon.extended.Theme
import top.yukonga.miuix.kmp.icon.extended.Tune
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.ui.theme.Dimens

/**
 * 设置页的二级分类。
 *
 * 设置项变多以后单页滚动很长，这里把可以独立成页的分组拆出来：`SettingsScreen`
 * 的落地页只展示分类入口，点进去才渲染具体控件（见 [SettingsCategoryMenu] 与
 * `SettingsScreen` 中的路由）。
 *
 * 顺序即落地页顺序；`title` / `summary` 同时用作二级页顶栏的标题与副标题。
 */
internal enum class SettingsPage(val title: String, val summary: String) {
    PERMISSIONS("权限与授权", "无障碍、悬浮窗、通知与电池白名单"),
    APPEARANCE("外观", "浅色 / 深色 / 跟随系统"),
    EXECUTION("执行", "默认执行模式、循环间隔、失败策略与功耗"),
    OVERLAY("悬浮窗", "悬浮球、显示开关与球位置"),
    RECORDING("录制微调", "点击抖动、长按阈值与坐标存储方式"),
    GAMEPAD("手柄模拟", "把 gamepad 按键映射为本机触摸"),
    STORAGE("存储与关于", "脚本目录、重置与应用信息"),
}

/**
 * 分类图标。
 *
 * 放在函数里而不是枚举构造参数里：MIUIX 的图标是 `MiuixIcons.Regular.X` 的扩展
 * 属性，在枚举初始化阶段求值没有必要，也不便于后续换成动态图标。
 */
@Composable
internal fun SettingsPage.icon(): ImageVector = when (this) {
    SettingsPage.PERMISSIONS -> MiuixIcons.Lock
    SettingsPage.APPEARANCE -> MiuixIcons.Theme
    SettingsPage.EXECUTION -> MiuixIcons.Tune
    SettingsPage.OVERLAY -> MiuixIcons.Show
    SettingsPage.RECORDING -> MiuixIcons.AppRecording
    SettingsPage.GAMEPAD -> MiuixIcons.Link
    SettingsPage.STORAGE -> MiuixIcons.Folder
}

/**
 * 落地页的分类列表。
 *
 * 每一项都是一个 [ArrowPreference]，与设置页其它入口行保持同一套 MIUIX 语义
 * （按下反馈 + 右侧箭头）；「权限与授权」额外用 [StatusPill] 提示待授权数量，
 * 这样即使没有进入二级页也能一眼看到还有几项权限没给。
 *
 * @param selected 当前二级页（宽屏时用于高亮左栏；窄屏的落地页传 `null`）。
 */
@Composable
internal fun SettingsCategoryMenu(
    permissionStatus: PermissionStatus,
    selected: SettingsPage?,
    onSelect: (SettingsPage) -> Unit,
    modifier: Modifier = Modifier,
    /** 渲染在分类列表上方（缺失权限摘要）。 */
    header: (@Composable () -> Unit)? = null,
) {
    val missing = permissionStatus.missingCore()
    val missingCount = missing.size

    header?.invoke()

    SectionCard(
        modifier = modifier,
        title = "设置分类",
        subtitle = "选择要调整的内容",
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            SettingsPage.entries.forEachIndexed { index, page ->
                ArrowPreference(
                    title = page.title,
                    // 「待授权 N 项」并入副标题：放在行尾药丸里在窄栏会被截断。
                    summary = if (page == SettingsPage.PERMISSIONS && missingCount > 0) {
                        "${page.summary} · 待授权 $missingCount 项"
                    } else {
                        page.summary
                    },
                    startAction = {
                        Icon(
                            imageVector = page.icon(),
                            contentDescription = null,
                            tint = if (page == selected) {
                                MiuixTheme.colorScheme.primary
                            } else {
                                MiuixTheme.colorScheme.onSurfaceVariantSummary
                            },
                        )
                    },
                    onClick = { onSelect(page) },
                    insideMargin = Dimens.PreferenceRowPadding,
                )
                if (index != SettingsPage.entries.lastIndex) {
                    HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                }
            }
        }
    }
}
