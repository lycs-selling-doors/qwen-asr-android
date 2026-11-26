package dev.workspace.qwen.asr

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun LogsScreen(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity
) {
    var query by rememberSaveable { mutableStateOf("") }
    var level by rememberSaveable { mutableStateOf("全部") }
    var paused by rememberSaveable { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val qwen = LocalQwenColors.current

    val rows = remember(state.logs, query, level) {
        state.logs.filter {
            (level == "全部" || it.level() == level) &&
                it.display().contains(query, ignoreCase = true)
        }
    }
    val listState = rememberLazyListState()

    LaunchedEffect(rows.lastOrNull()?.id(), paused) {
        if (!paused && rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
    }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) paused = true
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Compact header with inline icon toolbar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "日志",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "${rows.size} 条脱敏事件",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalIconButton(
                onClick = { paused = !paused },
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = if (paused) qwen.statusWarningSoft else MaterialTheme.colorScheme.surface
                )
            ) {
                Icon(
                    imageVector = if (paused) AppIcons.Play else AppIcons.Pause,
                    contentDescription = if (paused) "恢复自动滚动" else "暂停自动滚动",
                    tint = if (paused) qwen.statusWarning else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(19.dp)
                )
            }
            FilledTonalIconButton(
                onClick = {
                    activity.copy("运行日志", rows.joinToString("\n") { it.display() })
                    model.notify("已复制 ${rows.size} 条日志")
                },
                enabled = rows.isNotEmpty(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Icon(AppIcons.Copy, contentDescription = "复制日志", modifier = Modifier.size(18.dp))
            }
            FilledTonalIconButton(
                onClick = { activity.exportLogs(rows.joinToString("\n") { it.display() }) },
                enabled = rows.isNotEmpty(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Icon(AppIcons.ExportFile, contentDescription = "导出日志", modifier = Modifier.size(18.dp))
            }
            FilledTonalIconButton(
                onClick = { showClearDialog = true },
                enabled = state.logs.isNotEmpty(),
                colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)
            ) {
                Icon(AppIcons.Delete, contentDescription = "清空日志", modifier = Modifier.size(18.dp))
            }
        }

        // Compact search bar
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("过滤关键词、组件或模型…") },
            leadingIcon = { Icon(AppIcons.Search, contentDescription = null, modifier = Modifier.size(19.dp)) },
            trailingIcon = if (query.isNotEmpty()) ({
                IconButton(onClick = { query = "" }) {
                    Icon(AppIcons.Close, contentDescription = "清除", modifier = Modifier.size(18.dp))
                }
            }) else null,
            singleLine = true,
            shape = CircleShape,
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedBorderColor = Color.Transparent,
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface
            )
        )

        // Level filter pills
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf("全部" to "全部", "ERROR" to "错误", "WARN" to "警告", "INFO" to "信息", "DEBUG" to "调试")
                .forEach { (key, title) ->
                    val selected = level == key
                    Surface(
                        onClick = { level = key },
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp)
                        )
                    }
                }
        }

        // Deep Console Surface
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            color = qwen.terminalBg
        ) {
            if (rows.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Terminal,
                            contentDescription = null,
                            tint = qwen.terminalMuted,
                            modifier = Modifier.size(30.dp)
                        )
                        Text(
                            text = if (query.isBlank() && level == "全部") "尚无运行事件" else "没有匹配的日志",
                            style = MaterialTheme.typography.bodyMedium,
                            color = qwen.terminalMuted
                        )
                    }
                }
            } else {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(rows, key = { it.id() }) { entry ->
                            ConsoleRow(entry = entry)
                        }
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清空运行日志？") },
            text = { Text("将清除内存缓冲区中的全部日志记录。") },
            confirmButton = {
                TextButton(onClick = { showClearDialog = false; model.clearLogs() }) { Text("清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun ConsoleRow(entry: RuntimeLog.Event) {
    val qwen = LocalQwenColors.current
    val timeText = remember(entry.time()) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date(entry.time()))
    }
    val accent = when (entry.level()) {
        "ERROR" -> Color(0xFFF97066)
        "WARN" -> Color(0xFFFBBF24)
        "INFO" -> Color(0xFF34D399)
        else -> Color(0xFF64748B)
    }
    val meta = listOf(entry.component(), entry.model(), entry.run())
        .filter { it.isNotBlank() }
        .joinToString(" · ")

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = qwen.terminalRow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(34.dp)
                    .align(Alignment.CenterVertically)
                    .clip(CircleShape)
                    .background(accent)
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = entry.level(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = accent
                    )
                    Text(
                        text = timeText,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = qwen.terminalMuted
                    )
                    if (meta.isNotBlank()) {
                        Text(
                            text = meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = qwen.terminalMuted,
                            maxLines = 1
                        )
                    }
                }
                Text(
                    text = entry.message(),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.level() == "ERROR") Color(0xFFFCA5A5) else qwen.terminalText
                )
            }
        }
    }
}
