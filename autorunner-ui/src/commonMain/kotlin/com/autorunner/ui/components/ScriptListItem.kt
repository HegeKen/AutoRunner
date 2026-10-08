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
 * Script library card.
 *
 * Three fixed rows so every entry has the same rhythm:
 *
 * 1. the script name (plus the gamepad type badge when the script drives a gamepad),
 * 2. the existing description (step count / execution mode / creation date),
 * 3. the per-script actions as icon buttons — run, edit, duplicate, export, share,
 *    rename, delete — instead of hiding them behind a "more" sheet.
 *
 * Tapping the card itself opens the editor. The actions use uniform boxes (see
 * [RowAction]) so the glyphs always line up.
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
            // 1 — title (+ gamepad type when the script drives a gamepad)
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

            // 2 — description
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

            // 3 — actions
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
 * Fixed size action of a card row.
 *
 * @param onClick `null` renders a non interactive indicator that still occupies
 *   exactly the same box, which is what keeps a row of actions aligned.
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
 * Read-only row describing a single [ActionStep]; used by the editor list, the
 * recording feed and the floating panel's step preview.
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
 * Title of the page currently being rendered.
 *
 * A secondary page (see the settings categories) already shows its own title in
 * the top app bar, so a section card repeating the same words reads as a double
 * title. [SectionCard] compares its title against this value and drops the header
 * when they match.
 *
 * 仅限本模块内部使用：这是「页面级」信息，只有设置页的二级路由需要提供它，
 * 不对外暴露成全局 API（`internal` 限制了可见范围）。
 */
internal val LocalPageTitle = androidx.compose.runtime.compositionLocalOf { "" }

/** Titled card used to group sections on every screen. */
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
