package com.autorunner.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Copy
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.Edit
import top.yukonga.miuix.kmp.icon.extended.Play
import top.yukonga.miuix.kmp.icon.extended.Rename
import top.yukonga.miuix.kmp.icon.extended.Share
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.model.ActionStep
import com.autorunner.core.model.ExecutionMode
import com.autorunner.core.model.GamepadMode
import com.autorunner.core.model.displayLabel
import com.autorunner.core.model.labelFor
import com.autorunner.core.script.ScriptRecord
import com.autorunner.ui.theme.Dimens

/**
 * 脚本库卡片。
 *
 * 固定三行，让每个条目保持同样的节奏：
 *
 * 1. 脚本名（脚本驱动手柄时附带手柄类型徽标），
 * 2. 已有描述（步数 / 执行模式 / 创建日期），
 * 3. 脚本级操作图标按钮 —— 运行、编辑、创建副本、导出、分享、
 *    重命名、删除 —— 而不是藏进「更多」面板里。
 *
 * 点按卡片本身打开编辑器。操作按钮使用统一的方框（见 [RowAction]），
 * 保证图标始终对齐。
 */
@Composable
fun ScriptListItem(
    record: ScriptRecord,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: () -> Unit,
    onRun: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null,
    onDuplicate: (() -> Unit)? = null,
    onExport: (() -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onRename: (() -> Unit)? = null,
    onDelete: (() -> Unit)? = null,
) {
    Card(
        modifier = modifier,
        insideMargin = PaddingValues(0.dp),
        onClick = onClick,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            // 1 — 标题（脚本驱动手柄时附带手柄类型）
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = record.name,
                    modifier = Modifier.weight(1f),
                    style = MiuixTheme.textStyles.title4,
                    color = if (selected) {
                        MiuixTheme.colorScheme.primary
                    } else {
                        MiuixTheme.colorScheme.onSurface
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (record.execution.mode == ExecutionMode.REPEAT) {
                    TagPill(
                        text = if (record.execution.isInfinite) {
                            "无限循环"
                        } else {
                            "重复 ${record.execution.repeatCount} 次"
                        },
                    )
                }
                if (record.script.usesGamepad) {
                    TagPill(text = record.script.info.gamepadMode.displayName)
                }
            }

            // 2 — 描述
            Text(
                text = buildString {
                    append(record.summary())
                    if (record.script.info.createdAt.isNotBlank()) {
                        append(" · ")
                        append(com.autorunner.core.util.isoDatePart(record.script.info.createdAt))
                    }
                },
                modifier = Modifier.padding(top = 4.dp),
                style = MiuixTheme.textStyles.footnote1,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            // 3 — 操作按钮
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                onRun?.let { RowAction(MiuixIcons.Play, "运行", MiuixTheme.colorScheme.primary, it) }
                onEdit?.let {
                    RowAction(MiuixIcons.Edit, "编辑", MiuixTheme.colorScheme.onSurfaceVariantActions, it)
                }
                onDuplicate?.let {
                    RowAction(MiuixIcons.Copy, "创建副本", MiuixTheme.colorScheme.onSurfaceVariantActions, it)
                }
                onExport?.let {
                    RowAction(MiuixIcons.Download, "导出 .arscript", MiuixTheme.colorScheme.onSurfaceVariantActions, it)
                }
                onShare?.let {
                    RowAction(MiuixIcons.Share, "分享或复制", MiuixTheme.colorScheme.onSurfaceVariantActions, it)
                }
                onRename?.let {
                    RowAction(MiuixIcons.Rename, "重命名", MiuixTheme.colorScheme.onSurfaceVariantActions, it)
                }
                onDelete?.let { RowAction(MiuixIcons.Delete, "删除", MiuixTheme.colorScheme.error, it) }
            }
        }
    }
}

/**
 * 卡片行中固定尺寸的操作按钮。
 *
 * @param onClick 传 `null` 时渲染为不可交互的占位图标，但占据完全相同的方框，
 *   一排操作按钮正是靠这一点保持对齐。
 */
@Composable
fun RowAction(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    onClick: (() -> Unit)?,
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = tint,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * 描述单个 [ActionStep] 的只读行；编辑器列表、录制动作流与悬浮面板的
 * 步骤预览共用此实现。
 */
@Composable
fun ActionStepRow(
    index: Int,
    step: ActionStep,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    /**
     * 脚本的手柄类型：手柄动作的按键按它显示品牌命名（DualSense → × / ○ / □ / △）。
     * 录制流不会产生手柄动作，因此默认值只影响其它调用方。
     */
    gamepadMode: GamepadMode = GamepadMode.Default,
) {
    BasicComponent(
        modifier = modifier,
        title = "${index + 1}. ${step.displayLabel(gamepadMode)}",
        titleColor = if (selected) {
            top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor(color = MiuixTheme.colorScheme.primary)
        } else {
            top.yukonga.miuix.kmp.basic.BasicComponentDefaults.titleColor()
        },
        // 自定义名称存在时，原始 label 作为副标题展示，便于区分
        summary = if (!step.name.isNullOrBlank()) {
            "${step.labelFor(gamepadMode)} · 延迟 ${step.delay}ms"
        } else {
            "延迟 ${step.delay}ms"
        },
        endActions = {
            TagPill(text = step.typeName)
            trailing?.invoke()
        },
        onClick = onClick,
        insideMargin = Dimens.CompactRowPadding,
    )
}

/**
 * 当前渲染页面的标题。
 *
 * 二级页面（见设置页的分类入口）自身已在顶栏显示标题，若分区卡片再重复
 * 同样的文字就会出现双重标题。[SectionCard] 会把自身标题与该值比较，
 * 相同时省略表头。
 *
 * 仅限本模块内部使用：这是「页面级」信息，只有设置页的二级路由需要提供它，
 * 不对外暴露成全局 API（`internal` 限制了可见范围）。
 */
internal val LocalPageTitle = androidx.compose.runtime.compositionLocalOf { "" }

/** 用于在各页面分组分区的带标题卡片。 */
@Composable
fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable () -> Unit,
) {
    val pageTitle = LocalPageTitle.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (title.isNotBlank() && title != pageTitle) {
            Column(
                modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = title,
                    style = MiuixTheme.textStyles.title4,
                    color = MiuixTheme.colorScheme.onBackground,
                )
                subtitle?.let {
                    Text(
                        text = it,
                        style = MiuixTheme.textStyles.footnote1,
                        color = MiuixTheme.colorScheme.onBackgroundVariant,
                    )
                }
            }
        }
        Card(insideMargin = PaddingValues(0.dp)) {
            content()
        }
    }
}
