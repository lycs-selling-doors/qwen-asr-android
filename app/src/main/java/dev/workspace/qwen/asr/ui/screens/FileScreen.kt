package dev.workspace.qwen.asr

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
internal fun FileScreen(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity,
    onOpenModels: () -> Unit,
    onOpenTranslation: () -> Unit
) {
    var language by rememberSaveable { mutableStateOf(state.source) }
    var outputMode by rememberSaveable { mutableStateOf("source") }
    var viewMode by rememberSaveable { mutableStateOf("bilingual") }

    val qwen = LocalQwenColors.current
    val task = state.fileTask
    val cues = task.cues()
    val hasSelectedFile = state.selectedFileUri != null
    val isVideo = state.selectedFileMime.startsWith("video/", ignoreCase = true) ||
        state.selectedFileName.lowercase(Locale.ROOT).let {
            it.endsWith(".mp4") || it.endsWith(".mkv") || it.endsWith(".mov") ||
                it.endsWith(".webm") || it.endsWith(".avi") || it.endsWith(".3gp")
        }
    val busy = task.running() || (state.service.starting() && model.awaitingFileRun)
    val hasAnyTranslation = remember(cues) { cues.any { it.translation().isNotBlank() } }
    val effectiveViewMode = if (hasAnyTranslation) viewMode else "source"

    PageColumn {
        PageHeader(
            title = "文件转写",
            subtitle = "导入本地音视频，零拷贝提取音轨生成字幕"
        )

        if (!state.modelReady) {
            SoftCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
                CardTitleRow(
                    title = "尚未安装语音模型",
                    subtitle = "安装本地模型后即可离线转写音频与视频文件",
                    icon = AppIcons.Storage,
                    iconBg = MaterialTheme.colorScheme.primary,
                    iconFg = MaterialTheme.colorScheme.onPrimary
                )
                PrimaryButton("前往安装模型", icon = AppIcons.Download, modifier = Modifier.fillMaxWidth()) {
                    onOpenModels()
                }
            }
        }

        // File Picker / Selected Media Card
        if (!hasSelectedFile) {
            SoftCard(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 26.dp)) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(22.dp))
                            .background(qwen.iconTealBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = AppIcons.MediaFile,
                            contentDescription = null,
                            tint = qwen.iconTealFg,
                            modifier = Modifier.size(32.dp)
                        )
                    }
                    Text(
                        text = "选择音频或视频文件",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "支持直接读取手机本地视频音轨，无需提前转码或复制大文件",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    ActionRow {
                        listOf("MP4", "MKV", "MOV", "MP3", "M4A", "WAV", "FLAC").forEach { ext ->
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Text(
                                    text = ext,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                    PrimaryButton(
                        label = "从设备选择音视频",
                        enabled = !busy,
                        icon = AppIcons.Upload,
                        modifier = Modifier.fillMaxWidth()
                    ) { activity.pickMediaFile() }
                }
            }
        } else {
            SoftCard {
                CardTitleRow(
                    title = state.selectedFileName.ifBlank { "已选媒体文件" },
                    subtitle = buildList {
                        add(if (isVideo) "视频文件" else "音频文件")
                        if (state.selectedFileSize > 0) add(formatBytes(state.selectedFileSize))
                        if (state.selectedFileDurationMs > 0) add("时长 ${formatCueTime(state.selectedFileDurationMs)}")
                    }.joinToString(" · "),
                    icon = if (isVideo) AppIcons.VideoFile else AppIcons.AudioTrack,
                    iconBg = if (isVideo) qwen.iconPurpleBg else qwen.iconTealBg,
                    iconFg = if (isVideo) qwen.iconPurpleFg else qwen.iconTealFg,
                    trailing = {
                        StatusPill(
                            label = if (isVideo) "视频" else "音频",
                            containerColor = if (isVideo) qwen.iconPurpleBg else qwen.iconTealBg,
                            contentColor = if (isVideo) qwen.iconPurpleFg else qwen.iconTealFg,
                            dotColor = null
                        )
                    }
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    SecondaryButton(
                        label = "更换文件",
                        enabled = !busy,
                        icon = AppIcons.Upload,
                        modifier = Modifier.weight(1f)
                    ) { activity.pickMediaFile() }
                    SecondaryButton(
                        label = "移除",
                        enabled = !busy,
                        icon = AppIcons.Close,
                        modifier = Modifier.weight(0.7f)
                    ) {
                        model.clearSelectedFile()
                        activity.clearFileTask()
                    }
                }
            }
        }

        // Options & Progress Card
        if (hasSelectedFile || busy || task.message().isNotBlank() || task.error().isNotBlank()) {
            SoftCard {
                CardTitleRow(
                    title = "转写参数与进度",
                    subtitle = if (busy) task.message().ifBlank { "正在处理…" }
                    else "按 8 秒分段解码，实时流式输出字幕",
                    icon = AppIcons.Tune,
                    iconBg = qwen.iconBlueBg,
                    iconFg = qwen.iconBlueFg,
                    trailing = if (busy) ({
                        StatusPill("处理中", qwen.statusInfoSoft, qwen.statusInfo)
                    }) else null
                )

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "输出模式",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    PillTabSelector(
                        selected = outputMode,
                        options = listOf("source" to "仅原文", "bilingual" to "双语", "translation" to "仅译文"),
                        enabled = !busy,
                        onSelect = { outputMode = it }
                    )
                }

                DropdownChoiceField(
                    label = "识别源语言（仅对本次文件生效）",
                    current = language,
                    options = languages.filterKeys { it != "简体中文" },
                    enabled = !busy,
                    onSelect = { language = it }
                )

                if (outputMode != "source" && state.translation == "off") {
                    Surface(
                        onClick = onOpenTranslation,
                        shape = RoundedCornerShape(14.dp),
                        color = qwen.statusWarningSoft
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(AppIcons.Warning, contentDescription = null, tint = qwen.statusWarning, modifier = Modifier.size(18.dp))
                            Text(
                                text = "翻译当前处于「仅听写」状态，点此开启离线/联网翻译，或上方改选「仅原文」。",
                                style = MaterialTheme.typography.bodySmall,
                                color = qwen.statusWarning,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                if (busy) {
                    val progressFraction = if (task.durationMs() > 0L) {
                        (task.processedMs().toFloat() / task.durationMs()).coerceIn(0f, 1f)
                    } else null
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (state.service.starting() && !task.running()) "正在预热本地模型…"
                                else task.message().ifBlank { "正在转写…" },
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium
                            )
                            Text(
                                text = when {
                                    progressFraction != null -> String.format(
                                        Locale.ROOT,
                                        "%.0f%% · %s / %s",
                                        progressFraction * 100f,
                                        formatCueTime(task.processedMs()),
                                        formatCueTime(task.durationMs())
                                    )
                                    task.processedMs() > 0L -> "已处理 ${formatCueTime(task.processedMs())}"
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (progressFraction != null) {
                            LinearProgressIndicator(
                                progress = { progressFraction },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                            )
                        }
                    }
                    Button(
                        onClick = { activity.cancelFileTranscription() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = qwen.liveCoral,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(AppIcons.Stop, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("停止转写", style = MaterialTheme.typography.labelLarge)
                    }
                } else {
                    if (task.error().isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = task.error(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.fillMaxWidth().padding(12.dp)
                            )
                        }
                    } else if (task.message().isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = qwen.statusSuccessSoft
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(AppIcons.CheckCircle, contentDescription = null, tint = qwen.statusSuccess, modifier = Modifier.size(18.dp))
                                Text(
                                    text = task.message(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = qwen.statusSuccess
                                )
                            }
                        }
                    }

                    PrimaryButton(
                        label = if (state.service.ready()) "开始转写文件" else "预热模型并开始转写",
                        enabled = state.modelReady && hasSelectedFile && !state.service.starting(),
                        icon = AppIcons.Play,
                        modifier = Modifier.fillMaxWidth()
                    ) { activity.startFileTranscription(language, outputMode) }
                }
            }
        }

        // Streaming Results & Export Section
        if (cues.isNotEmpty()) {
            SoftCard {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "转写结果",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "已生成 ${cues.size} 条时间轴片段 · 支持长按自由选中",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (!busy) {
                        TextButton(onClick = { activity.clearFileTask() }) {
                            Text("清空结果")
                        }
                    }
                }

                if (hasAnyTranslation) {
                    PillTabSelector(
                        selected = viewMode,
                        options = listOf("bilingual" to "双语", "source" to "仅原文", "translation" to "仅译文"),
                        onSelect = { viewMode = it }
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TonalButton(
                        label = "复制",
                        icon = AppIcons.Copy,
                        modifier = Modifier.weight(1f)
                    ) {
                        activity.copy("转写文稿", Pcm.plainText(cues, effectiveViewMode))
                        model.notify("已复制全文到剪贴板")
                    }
                    SecondaryButton(
                        label = "SRT",
                        modifier = Modifier.weight(0.8f)
                    ) { activity.exportFileTranscript("srt", effectiveViewMode) }
                    SecondaryButton(
                        label = "LRC",
                        modifier = Modifier.weight(0.8f)
                    ) { activity.exportFileTranscript("lrc", effectiveViewMode) }
                    SecondaryButton(
                        label = "TXT",
                        modifier = Modifier.weight(0.8f)
                    ) { activity.exportFileTranscript("txt", effectiveViewMode) }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        cues.forEachIndexed { index, cue ->
                            val isLatest = task.running() && index == cues.lastIndex
                            Surface(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                color = if (isLatest) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                else MaterialTheme.colorScheme.surfaceContainerLow
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = "${formatCueTime(cue.startMs())} – ${formatCueTime(cue.endMs())}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = if (isLatest) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline
                                    )
                                    if (effectiveViewMode != "translation") {
                                        Text(
                                            text = cue.source(),
                                            fontSize = if (effectiveViewMode == "source") 16.sp else 14.sp,
                                            lineHeight = if (effectiveViewMode == "source") 24.sp else 21.sp,
                                            color = if (effectiveViewMode == "source") MaterialTheme.colorScheme.onSurface
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    if (effectiveViewMode != "source" && cue.translation().isNotBlank()) {
                                        Text(
                                            text = cue.translation(),
                                            fontSize = 17.sp,
                                            lineHeight = 25.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    } else if (effectiveViewMode == "translation") {
                                        Text(
                                            text = cue.source(),
                                            fontSize = 15.sp,
                                            lineHeight = 22.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
