package com.autorunner.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.autorunner.core.script.ScriptRecord
import com.autorunner.ui.adaptive.AutoRunnerWindowSize
import com.autorunner.ui.components.AppButtonTone
import com.autorunner.ui.components.ConfirmDialog
import com.autorunner.ui.components.InputDialog
import com.autorunner.ui.components.MessageDialog
import com.autorunner.ui.components.PageScaffold
import com.autorunner.ui.components.ScriptListItem
import com.autorunner.ui.theme.Dimens
import com.autorunner.ui.viewmodel.ScriptListViewModel

/**
 * Script library — the home screen (§6.5).
 *
 * Phone: a single list that navigates into the editor.
 * Tablet: the same list, but the shell renders it next to the editor
 * (List-Detail pattern, §5.3), which is why [onSelect] is separate from
 * [onOpen].
 */
@Composable
fun ScriptListScreen(
    viewModel: ScriptListViewModel,
    windowSize: AutoRunnerWindowSize,
    onOpen: (String) -> Unit,
    onCreate: () -> Unit,
    onImport: () -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    onSelect: (String) -> Unit = onOpen,
) {
    // List-Detail（列表 + 编辑器并排）时外壳不再渲染顶栏，列表必须自带顶栏，
    // 否则矮窗口（横屏）里中间列表会顶进状态栏被遮挡。未分栏时外壳顶栏已就位，
    // 这里不再重复渲染，避免叠出两条标题。
    if (windowSize.useListDetail) {
        PageScaffold(
            windowSize = windowSize,
            title = "脚本",
            subtitle = "无障碍自动化录制与回放",
            modifier = modifier,
        ) {
            ScriptListContent(
                viewModel = viewModel,
                windowSize = windowSize,
                onOpen = onOpen,
                onRun = onRun,
                selectedId = selectedId,
                onSelect = onSelect,
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        ScriptListContent(
            viewModel = viewModel,
            windowSize = windowSize,
            onOpen = onOpen,
            onRun = onRun,
            selectedId = selectedId,
            onSelect = onSelect,
            modifier = modifier,
        )
    }
}

/**
 * 脚本库列表主体：搜索框 + 列表 + 行内弹窗。由 [ScriptListScreen] 按布局决定
 * 是否包进带顶栏的 [PageScaffold]。
 */
@Composable
private fun ScriptListContent(
    viewModel: ScriptListViewModel,
    windowSize: AutoRunnerWindowSize,
    onOpen: (String) -> Unit,
    onRun: (String) -> Unit,
    modifier: Modifier = Modifier,
    selectedId: String? = null,
    onSelect: (String) -> Unit = onOpen,
) {
    val scripts by viewModel.visibleScripts.collectAsState()
    val query by viewModel.query.collectAsState()
    val message by viewModel.message.collectAsState()
    val busy by viewModel.busy.collectAsState()

    var deleteTarget by remember { mutableStateOf<ScriptRecord?>(null) }
    var deleteVisible by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<ScriptRecord?>(null) }
    var renameVisible by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.ScreenPadding, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = query,
                onValueChange = viewModel::setQuery,
                label = "搜索脚本",
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }

        if (scripts.isEmpty()) {
            EmptyScriptsState(
                hasQuery = query.isNotBlank(),
                busy = busy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Dimens.ScreenPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Dimens.ScreenPadding,
                    end = Dimens.ScreenPadding,
                    bottom = Dimens.ScreenPadding + 72.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Dimens.ItemSpacing),
            ) {
                items(scripts, key = { it.id }) { record ->
                    Card(insideMargin = PaddingValues(0.dp)) {
                        ScriptListItem(
                            record = record,
                            selected = record.id == selectedId,
                            onClick = { if (windowSize.useListDetail) onSelect(record.id) else onOpen(record.id) },
                            onRun = { onRun(record.id) },
                            onEdit = { onOpen(record.id) },
                            onDuplicate = { viewModel.duplicate(record.id) },
                            onExport = { viewModel.export(record.id) },
                            onShare = { viewModel.share(record.id) },
                            onRename = {
                                renameTarget = record
                                renameText = record.name
                                renameVisible = true
                            },
                            onDelete = {
                                deleteTarget = record
                                deleteVisible = true
                            },
                        )
                    }
                }
            }
        }
    }

    // -------------------------------------------------------------- dialogs
    //
    // 危险操作统一走 ConfirmDialog + Danger 语气（此前删除脚本用主色、清空全部用
    // 危险色，同一个破坏性动作给出两种风险暗示）。显隐由 show 驱动，记录本身留到
    // 退场动画结束后再清空，避免动画播到一半数据源被置空。
    ConfirmDialog(
        show = deleteVisible,
        title = "删除脚本",
        message = deleteTarget?.let { "「${it.name}」将被永久删除，该操作无法撤销。" }.orEmpty(),
        confirmLabel = "删除",
        confirmTone = AppButtonTone.Danger,
        onConfirm = {
            deleteTarget?.let { viewModel.delete(it.id) }
            deleteVisible = false
        },
        onDismiss = { deleteVisible = false },
        onDismissFinished = { deleteTarget = null },
    )

    InputDialog(
        show = renameVisible,
        title = "重命名脚本",
        summary = renameTarget?.let { "为「${it.name}」输入新的脚本名称。" },
        value = renameText,
        onValueChange = { renameText = it },
        label = "脚本名称",
        confirmEnabled = renameText.isNotBlank(),
        onConfirm = {
            renameTarget?.let { viewModel.rename(it.id, renameText) }
            renameVisible = false
        },
        onDismiss = { renameVisible = false },
        onDismissFinished = { renameTarget = null },
    )

    MessageDialog(
        message = message,
        onDismiss = viewModel::dismissMessage,
    )
}

/** Friendly empty state explaining how to get the first script. */
@Composable
private fun EmptyScriptsState(
    hasQuery: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Card(
            modifier = Modifier.widthIn(max = Dimens.MaxContentWidth),
            insideMargin = Dimens.EmptyStatePadding,
        ) {
            Text(
                text = when {
                    hasQuery -> "没有匹配的脚本"
                    busy -> "正在读取脚本目录…"
                    else -> "还没有任何脚本"
                },
                style = MiuixTheme.textStyles.title4,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = if (hasQuery) {
                    "试试其它关键字，或清空搜索框。"
                } else {
                    "点击右下角的 + 新建空白脚本，或导入 .arscript 文件；也可以前往「录制」页录制一段操作，" +
                        "也可以从文件导入已有的 .arscript 脚本。"
                },
                modifier = Modifier.padding(top = 8.dp),
                style = MiuixTheme.textStyles.body2,
                color = MiuixTheme.colorScheme.onSurfaceSecondary,
            )
        }
    }
}
