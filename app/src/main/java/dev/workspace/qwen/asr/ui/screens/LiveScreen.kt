package dev.workspace.qwen.asr

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LiveScreen(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity,
    compact: Boolean,
    onConfigure: () -> Unit,
    onOpenTranslation: () -> Unit,
    onOpenModels: () -> Unit
) {
    var playback by rememberSaveable { mutableStateOf(false) }
    var viewMode by rememberSaveable { mutableStateOf("both") }
    var follow by rememberSaveable { mutableStateOf(true) }
    var showLanguageSheet by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val status = state.service
    val cues = status.cues()
    val qwen = LocalQwenColors.current
    val activeCard = Models.CARDS.find { it.id() == state.runningModel.ifBlank { state.selected } }

    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) follow = false
        }
    }
    LaunchedEffect(cues.size, follow) {
        if (cues.isEmpty()) follow = true
        if (follow && cues.isNotEmpty()) listState.animateScrollToItem(cues.lastIndex)
    }

    val modelBadgeText = when {
        state.external.isNotBlank() -> "外部引擎"
        !state.modelReady -> "未配置模型"
        else -> {
            val shortName = activeCard?.title().orEmpty().replace("Qwen3-ASR-", "").replace(" Q8_0", "")
            val hw = when {
                state.backend.contains("HTP", ignoreCase = true) -> "NPU"
                state.backend.contains("GPU", ignoreCase = true) -> "GPU"
                state.backend.isNotBlank() -> "CPU"
                else -> ""
            }
            listOf(shortName.ifBlank { "Qwen3" }, hw).filter { it.isNotBlank() }.joinToString(" · ")
        }
    }

    val shortLanguagePair = "${shortLang(state.source)} → ${shortLang(state.target)}"

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = if (compact) 6.dp else 10.dp),
        verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 10.dp)
    ) {
        // Minimal Studio Top Bar
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Surface(
                onClick = onOpenModels,
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    status.live() -> qwen.liveCoral
                                    status.ready() -> qwen.statusSuccess
                                    status.starting() -> qwen.statusInfo
                                    state.modelReady -> MaterialTheme.colorScheme.outline
                                    else -> qwen.statusWarning
                                }
                            )
                    )
                    Text(
                        text = modelBadgeText,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            Surface(
                onClick = { showLanguageSheet = true },
                shape = CircleShape,
                color = MaterialTheme.colorScheme.surface
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Translate,
                        contentDescription = "语言与翻译",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = shortLanguagePair,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        // Missing Offline Pack Slim Banner
        if (state.modelReady && state.translation == "offline" && state.missingLanguages.isNotEmpty()) {
            Surface(
                onClick = onOpenTranslation,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = qwen.statusWarningSoft
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Warning,
                        contentDescription = null,
                        tint = qwen.statusWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "缺少离线语言包（${state.missingLanguages.joinToString("、", transform = ::languageName)}），当前仅输出原文",
                        style = MaterialTheme.typography.bodySmall,
                        color = qwen.statusWarning,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "下载",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = qwen.statusWarning
                    )
                }
            }
        }

        // Seamless Immersive Transcript Stage
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            if (cues.isEmpty()) {
                StudioIdleStage(
                    state = state,
                    playback = playback,
                    onConfigure = onConfigure,
                    onOpenModels = onOpenModels
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Live waveform + view mode bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (status.live()) {
                            LiveWaveBars(color = qwen.liveCoral)
                        }
                        Text(
                            text = when {
                                status.live() -> status.liveMessage().ifBlank { "正在实时转写" }
                                else -> "共 ${cues.size} 句字幕"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (status.live()) qwen.liveCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        ViewModeSegmentedPill(selected = viewMode, onSelect = { viewMode = it })
                    }

                    SelectionContainer(modifier = Modifier.weight(1f)) {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(top = 2.dp, bottom = 56.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            itemsIndexed(cues, key = { _, cue -> cue.startMs() }) { index, cue ->
                                TranscriptCard(
                                    cue = cue,
                                    viewMode = viewMode,
                                    isLatest = index == cues.lastIndex
                                )
                            }
                        }
                    }
                }
            }

            // Floating jump-to-latest pill
            androidx.compose.animation.AnimatedVisibility(
                visible = cues.isNotEmpty() && !follow,
                enter = fadeIn() + slideInVertically { it / 2 },
                exit = fadeOut() + slideOutVertically { it / 2 },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 8.dp)
            ) {
                Surface(
                    onClick = { follow = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shadowElevation = 4.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(AppIcons.ArrowDownward, contentDescription = "回到最新", modifier = Modifier.size(16.dp))
                        Text("回到最新字幕", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Studio Floating Control Dock
        if (!state.modelReady) {
            PrimaryButton(
                label = if (state.checkingLegacy) "正在检测本地模型…" else "开始首次配置",
                enabled = state.progress == null && !state.checkingLegacy,
                icon = AppIcons.Sparkles,
                modifier = Modifier.fillMaxWidth(),
                onClick = onConfigure
            )
        } else {
            StudioControlDock(
                playback = playback,
                isLive = status.live(),
                isStarting = status.starting(),
                hasCues = cues.isNotEmpty(),
                onToggleSource = { if (!status.live()) playback = !playback },
                onToggleRecord = {
                    if (status.live()) activity.stopCapture() else activity.startCapture(playback)
                },
                onOverlay = { activity.overlay() },
                onExportSrt = { activity.exportSubtitles(viewMode == "source") }
            )
        }
    }

    // Quick Language & Mode Bottom Sheet
    if (showLanguageSheet) {
        QuickLanguageSheet(
            state = state,
            model = model,
            onDismiss = { showLanguageSheet = false },
            onMoreSettings = {
                showLanguageSheet = false
                onOpenTranslation()
            }
        )
    }
}

@Composable
private fun StudioIdleStage(
    state: ScreenState,
    playback: Boolean,
    onConfigure: () -> Unit,
    onOpenModels: () -> Unit
) {
    val qwen = LocalQwenColors.current
    val status = state.service
    val infinite = rememberInfiniteTransition(label = "idleRings")
    val ringScale by infinite.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "ringScale"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(120.dp),
            contentAlignment = Alignment.Center
        ) {
            val ringColor = when {
                status.live() -> qwen.liveCoral
                !state.modelReady -> MaterialTheme.colorScheme.secondary
                else -> MaterialTheme.colorScheme.primary
            }
            Box(
                modifier = Modifier
                    .size(112.dp)
                    .scale(if (status.live() || status.starting()) ringScale else 1f)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.10f))
            )
            Box(
                modifier = Modifier
                    .size(82.dp)
                    .clip(CircleShape)
                    .background(ringColor.copy(alpha = 0.16f))
            )
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center
            ) {
                if (status.starting() || state.checkingLegacy) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(26.dp),
                        strokeWidth = 2.5.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = when {
                            !state.modelReady -> AppIcons.Sparkles
                            status.live() -> AppIcons.GraphicEq
                            playback -> AppIcons.VolumeUp
                            else -> AppIcons.Mic
                        },
                        contentDescription = null,
                        tint = ringColor,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }

        Text(
            text = when {
                state.progress != null -> "正在准备模型资产"
                state.checkingLegacy -> "正在识别旧版模型"
                !state.modelReady -> "端侧离线听写与翻译"
                status.starting() -> "正在预热本地推理引擎"
                status.live() -> if (playback) "正在监听系统播放音频…" else "正在聆听麦克风…"
                else -> if (playback) "准备捕获播放音频" else "轻触下方按钮开始听写"
            },
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center
        )

        if (state.progress != null) {
            ProgressBanner(progress = state.progress)
        } else {
            Text(
                text = when {
                    !state.modelReady -> "安装一套 Qwen3-ASR 模型后，即可全程在手机本地完成语音识别、双语字幕与 API 服务。"
                    status.starting() -> status.message()
                    status.live() -> "识别到语音后将实时滚动展示双语字幕，支持长按复制和一键悬浮。"
                    else -> state.backend.ifBlank { "16 kHz 端侧语音识别 · 支持实时双语字幕与 SRT 导出" }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp)
            )
            if (!state.modelReady && !state.checkingLegacy) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TonalButton("浏览模型", icon = AppIcons.Storage, onClick = onOpenModels)
                    SecondaryButton("引导配置", onClick = onConfigure)
                }
            }
        }
    }
}

@Composable
private fun TranscriptCard(
    cue: Pcm.Cue,
    viewMode: String,
    isLatest: Boolean
) {
    val containerColor = if (isLatest) MaterialTheme.colorScheme.surface
    else MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = containerColor
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isLatest) {
                Box(
                    modifier = Modifier
                        .width(3.5.dp)
                        .heightIn(min = 38.dp)
                        .align(Alignment.CenterVertically)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                )
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = formatCueTime(cue.startMs()),
                    style = MaterialTheme.typography.labelSmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (isLatest) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                if (viewMode != "translation") {
                    Text(
                        text = cue.source(),
                        color = if (viewMode == "source") MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = if (viewMode == "source") 21.sp else 15.sp,
                        lineHeight = if (viewMode == "source") 30.sp else 22.sp,
                        fontWeight = if (viewMode == "source") FontWeight.Medium else FontWeight.Normal
                    )
                }
                if (viewMode != "source" && cue.translation().isNotBlank()) {
                    Text(
                        text = cue.translation(),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 22.sp,
                        lineHeight = 32.sp,
                        fontWeight = if (isLatest) FontWeight.SemiBold else FontWeight.Medium
                    )
                } else if (viewMode == "translation") {
                    Text(
                        text = cue.source(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 15.sp,
                        lineHeight = 22.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewModeSegmentedPill(
    selected: String,
    onSelect: (String) -> Unit
) {
    val modes = remember { listOf("both" to "双语", "source" to "原文", "translation" to "译文") }
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            modes.forEach { (key, label) ->
                val active = key == selected
                Surface(
                    onClick = { onSelect(key) },
                    shape = CircleShape,
                    color = if (active) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                    contentColor = if (active) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LiveWaveBars(color: Color) {
    val transition = rememberInfiniteTransition(label = "wave")
    val bar1 by transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "b1")
    val bar2 by transition.animateFloat(0.85f, 0.25f, infiniteRepeatable(tween(520), RepeatMode.Reverse), label = "b2")
    val bar3 by transition.animateFloat(0.35f, 0.95f, infiniteRepeatable(tween(380), RepeatMode.Reverse), label = "b3")
    val bar4 by transition.animateFloat(0.75f, 0.30f, infiniteRepeatable(tween(480), RepeatMode.Reverse), label = "b4")
    Canvas(modifier = Modifier.size(width = 20.dp, height = 16.dp)) {
        val levels = floatArrayOf(bar1, bar2, bar3, bar4)
        val barWidth = 3.dp.toPx()
        val gap = (size.width - barWidth * levels.size) / (levels.size - 1)
        levels.forEachIndexed { i, level ->
            val h = (size.height * level).coerceAtLeast(3.dp.toPx())
            val x = i * (barWidth + gap)
            val y = (size.height - h) / 2f
            drawRoundRect(
                color = color,
                topLeft = Offset(x, y),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f)
            )
        }
    }
}

@Composable
private fun StudioControlDock(
    playback: Boolean,
    isLive: Boolean,
    isStarting: Boolean,
    hasCues: Boolean,
    onToggleSource: () -> Unit,
    onToggleRecord: () -> Unit,
    onOverlay: () -> Unit,
    onExportSrt: () -> Unit
) {
    val qwen = LocalQwenColors.current
    val fabColor by animateColorAsState(
        targetValue = if (isLive) qwen.liveCoral else MaterialTheme.colorScheme.primary,
        animationSpec = tween(220),
        label = "fabColor"
    )
    val fabRadius by animateDpAsState(
        targetValue = if (isLive) 22.dp else 34.dp,
        animationSpec = tween(220),
        label = "fabRadius"
    )

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Source Toggle Pill
            Surface(
                onClick = onToggleSource,
                enabled = !isLive,
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (playback) AppIcons.VolumeUp else AppIcons.Mic,
                        contentDescription = "切换音源",
                        tint = if (isLive) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = if (playback) "播放内录" else "麦克风",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isLive) "采集中" else "点击切换",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Center: Hero Record / Stop Button
            Surface(
                onClick = onToggleRecord,
                enabled = !isStarting,
                shape = RoundedCornerShape(fabRadius),
                color = fabColor,
                contentColor = Color.White,
                shadowElevation = 3.dp,
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (isStarting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 2.5.dp,
                            color = Color.White
                        )
                    } else {
                        Icon(
                            imageVector = if (isLive) AppIcons.Stop else AppIcons.Play,
                            contentDescription = if (isLive) "结束采集" else "开始听写",
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // Right: Overlay + Export SRT icon tools
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = onOverlay,
                    enabled = isLive,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Icon(AppIcons.Overlay, contentDescription = "悬浮字幕", modifier = Modifier.size(20.dp))
                }
                FilledTonalIconButton(
                    onClick = onExportSrt,
                    enabled = hasCues,
                    modifier = Modifier.size(48.dp),
                    colors = IconButtonDefaults.filledTonalIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer
                    )
                ) {
                    Icon(AppIcons.ExportFile, contentDescription = "导出 SRT", modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickLanguageSheet(
    state: ScreenState,
    model: DictationModel,
    onDismiss: () -> Unit,
    onMoreSettings: () -> Unit
) {
    var source by remember { mutableStateOf(state.source) }
    var target by remember { mutableStateOf(state.target) }
    var mode by remember { mutableStateOf(state.translation) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("语言与翻译模式", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = onMoreSettings) {
                    Text("详细配置")
                }
            }

            PillTabSelector(
                selected = mode,
                options = listOf("offline" to "离线翻译", "online" to "联网翻译", "off" to "仅听写"),
                onSelect = { mode = it }
            )

            DropdownChoiceField(
                label = "识别源语言",
                current = source,
                options = languages.filterKeys { it != "简体中文" },
                onSelect = { source = it }
            )

            DropdownChoiceField(
                label = "翻译目标语言",
                current = target,
                options = languages.filterKeys { it != "auto" && it != "zh" },
                onSelect = { target = it }
            )

            Text(
                text = "保存后将在下次加载模型时生效。如需配置联网接口或下载离线包，请点击右上角「详细配置」。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            PrimaryButton(
                label = "应用语言设置",
                icon = AppIcons.CheckCircle,
                modifier = Modifier.fillMaxWidth()
            ) {
                val url = model.modelPrefs().getString("translation", "")!!
                val id = model.modelPrefs().getString("model", "")!!
                if (mode == "online" && (url.isBlank() || id.isBlank())) {
                    onMoreSettings()
                } else {
                    model.saveTranslation(mode, source, target, url, id, "")
                    onDismiss()
                }
            }
        }
    }
}

private fun shortLang(code: String): String = when (code) {
    "auto" -> "自动"
    "简体中文" -> "简中"
    else -> languageName(code)
}
