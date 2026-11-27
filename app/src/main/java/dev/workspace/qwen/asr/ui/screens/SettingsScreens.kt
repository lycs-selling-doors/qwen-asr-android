package dev.workspace.qwen.asr

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.math.BigDecimal
import java.util.Locale

@Composable
internal fun SettingsScreen(
    section: String,
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity,
    onOpenSection: (String) -> Unit,
    onGoHome: () -> Unit
) {
    AnimatedContent(
        targetState = section,
        transitionSpec = {
            if (targetState.isNotEmpty()) {
                (slideInHorizontally(tween(220)) { it / 5 } + fadeIn(tween(180))) togetherWith
                    fadeOut(tween(120))
            } else {
                fadeIn(tween(180)) togetherWith
                    (slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(120)))
            }
        },
        label = "settingsSection"
    ) { activeSection ->
        if (activeSection.isEmpty()) {
            SettingsHubScreen(
                state = state,
                model = model,
                activity = activity,
                onOpenSection = onOpenSection
            )
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                SubScreenTopBar(
                    title = if (activeSection == "推理") {
                        if (model.cpuOnly()) "推理参数" else "推理与硬件"
                    } else activeSection,
                    onBack = { onOpenSection("") }
                )
                PageColumn(modifier = Modifier.weight(1f)) {
                    when (activeSection) {
                        "首次配置" -> FirstRunContent(state, model, activity, onOpenSection, onGoHome)
                        "模型" -> ModelsSettingsContent(state, model, activity)
                        "推理" -> InferenceSettingsContent(state, model, activity)
                        "翻译" -> TranslationSettingsContent(state, model)
                        "音频" -> AudioSettingsContent(state, model)
                        "服务配置" -> GatewaySettingsContent(state, model, activity)
                        "诊断" -> DiagnosticsContent(state, model, activity)
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsHubScreen(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity,
    onOpenSection: (String) -> Unit
) {
    val qwen = LocalQwenColors.current
    val tuning = remember(state.backend, state.external) { DictationService.tuning(activity) }
    val gatewayConfig = remember(state.gateway, state.allowPrivateAudio) { GatewayConfig.read(activity) }
    val selectedCard = Models.CARDS.find { it.id() == state.selected }

    val modelSummary = when {
        state.external.isNotBlank() -> "外部引擎 · 剩余 ${formatBytes(state.available)}"
        state.installed.isEmpty() -> "未安装 · 剩余 ${formatBytes(state.available)}"
        else -> "${selectedCard?.title()?.replace("Qwen3-ASR-", "") ?: state.selected} · 剩余 ${formatBytes(state.available)}"
    }
    val translationModeLabel = when (state.translation) {
        "offline" -> "离线 ML Kit"
        "online" -> "联网翻译"
        else -> "仅听写"
    }
    val decoderLabel = when (state.audioDecoder) {
        "hardware" -> "硬件解码"
        "software" -> "软件解码"
        "pcm" -> "PCM / WAV 直读"
        else -> "自动"
    }
    val gatewayModeLabel = when (gatewayConfig.mode()) {
        "ALLOWLIST" -> "IP 白名单"
        "OPEN" -> "局域网全开"
        else -> "仅本机"
    }

    PageColumn {
        PageHeader(
            title = "设置",
            subtitle = "模型、推理、翻译与网络策略"
        )

        // Setup Highlight Banner when unconfigured
        if (!state.modelReady) {
            SoftCard(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)) {
                CardTitleRow(
                    title = "首次使用配置向导",
                    subtitle = "约 1 分钟完成设备检测、模型安装与短音频自检",
                    icon = AppIcons.Sparkles,
                    iconBg = MaterialTheme.colorScheme.primary,
                    iconFg = MaterialTheme.colorScheme.onPrimary
                )
                PrimaryButton(
                    label = "立即开始配置",
                    icon = AppIcons.Play,
                    modifier = Modifier.fillMaxWidth()
                ) { onOpenSection("首次配置") }
            }
        }

        SectionLabel("语音与翻译")
        InsetGroupCard {
            InsetSettingsItem(
                title = "语音模型",
                subtitle = modelSummary,
                icon = AppIcons.Storage,
                iconBg = qwen.iconTealBg,
                iconFg = qwen.iconTealFg,
                badge = if (!state.modelReady) ({
                    StatusPill("待安装", qwen.statusWarningSoft, qwen.statusWarning)
                }) else null,
                onClick = { onOpenSection("模型") }
            )
            InsetDivider()
            InsetSettingsItem(
                title = if (model.cpuOnly()) "推理参数" else "推理与硬件",
                subtitle = run {
                    val preset = state.performanceStatus?.let {
                        if (it.available()) performancePresetLabel(it.selectedPreset()) else "外部引擎"
                    } ?: "均衡"
                    val rtfEnabled = state.rtfStatus?.settings()?.enabled() ?: true
                    val rtfLabel = if (state.rtfStatus?.backend() == "external") "外部引擎"
                    else if (rtfEnabled) "RTF 开" else "RTF 关"
                    if (model.cpuOnly()) {
                        "ARM64 CPU · $preset · $rtfLabel"
                    } else {
                        "后端 ${if (tuning.device() == "auto") "自动" else tuning.device()} · $preset · $rtfLabel"
                    }
                },
                icon = AppIcons.Memory,
                iconBg = qwen.iconPurpleBg,
                iconFg = qwen.iconPurpleFg,
                onClick = { onOpenSection("推理") }
            )
            InsetDivider()
            InsetSettingsItem(
                title = "翻译与语言包",
                subtitle = "$translationModeLabel · ${languageName(state.source)} → ${languageName(state.target)}",
                icon = AppIcons.Translate,
                iconBg = qwen.iconBlueBg,
                iconFg = qwen.iconBlueFg,
                badge = if (state.translation == "offline" && state.missingLanguages.isNotEmpty()) ({
                    StatusPill("缺语言包", qwen.statusWarningSoft, qwen.statusWarning)
                }) else null,
                onClick = { onOpenSection("翻译") }
            )
            InsetDivider()
            InsetSettingsItem(
                title = "音频解码",
                subtitle = "$decoderLabel · 16 kHz 单声道 PCM",
                icon = AppIcons.GraphicEq,
                iconBg = qwen.iconAmberBg,
                iconFg = qwen.iconAmberFg,
                onClick = { onOpenSection("音频") }
            )
        }

        SectionLabel("网络与维护")
        InsetGroupCard {
            InsetSettingsItem(
                title = "网关与音频来源",
                subtitle = "端口 ${gatewayConfig.port()} · $gatewayModeLabel · " +
                    if (state.allowPrivateAudio) "允许内网音源" else "内网音源受限",
                icon = AppIcons.Lan,
                iconBg = qwen.iconIndigoBg,
                iconFg = qwen.iconIndigoFg,
                onClick = { onOpenSection("服务配置") }
            )
            InsetDivider()
            InsetSettingsItem(
                title = "设备诊断与自检",
                subtitle = "${model.deviceDetails()} · " + if (model.runtimePresent()) model.runtimeLabel() else "无内置运行时",
                icon = AppIcons.Speed,
                iconBg = qwen.iconRoseBg,
                iconFg = qwen.iconRoseFg,
                onClick = { onOpenSection("诊断") }
            )
        }
    }
}

private val setupSteps = listOf("设备检查", "安装语音模型", "语言与离线包", "短音频自检")

@Composable
private fun FirstRunContent(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity,
    onOpenSection: (String) -> Unit,
    onGoHome: () -> Unit
) {
    val step = state.setupStep.coerceIn(0, setupSteps.lastIndex)
    val qwen = LocalQwenColors.current

    SoftCard {
        CardTitleRow(
            title = "步骤 ${step + 1} / ${setupSteps.size} · ${setupSteps[step]}",
            subtitle = "配置进度会自动保存，可随时跳过并在设置中继续",
            icon = AppIcons.Sparkles,
            iconBg = qwen.iconTealBg,
            iconFg = qwen.iconTealFg
        )
        LinearProgressIndicator(
            progress = { (step + 1) / setupSteps.size.toFloat() },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
        )
    }

    when (step) {
        0 -> DiagnosticsContent(state, model, activity, selfTest = false)
        1 -> ModelsSettingsContent(state, model, activity)
        2 -> TranslationSettingsContent(state, model)
        3 -> {
            SoftCard {
                CardTitleRow(
                    title = "短音频端侧自检",
                    subtitle = "验证已安装模型与所选后端能否正确输出文本",
                    icon = AppIcons.CheckCircle,
                    iconBg = qwen.statusSuccessSoft,
                    iconFg = qwen.statusSuccess
                )
                Text(state.service.message(), style = MaterialTheme.typography.bodyMedium)
                if (state.service.starting()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape))
                }
                ActionRow {
                    PrimaryButton(
                        label = "运行短音频自检",
                        enabled = state.modelReady && !state.service.starting() && !state.service.ready(),
                        icon = AppIcons.Play
                    ) { activity.preload(true) }
                    SecondaryButton(label = "调整推理设置", icon = AppIcons.Tune) { onOpenSection("推理") }
                }
            }
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (step > 0) {
            SecondaryButton("上一步", modifier = Modifier.weight(1f)) { model.setup(step - 1) }
        }
        if (step < setupSteps.lastIndex) {
            PrimaryButton(
                label = "下一步",
                enabled = step != 1 || state.modelReady,
                modifier = Modifier.weight(1.4f)
            ) { model.setup(step + 1) }
        } else {
            PrimaryButton(
                label = "完成并开始听写",
                enabled = state.modelReady,
                icon = AppIcons.CheckCircle,
                modifier = Modifier.weight(1.4f)
            ) { onGoHome() }
        }
    }
    TextButton(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
        Text("稍后配置，先进入主页")
    }
}

@Composable
private fun ModelsSettingsContent(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity
) {
    var mobile by rememberSaveable { mutableStateOf(false) }
    var removal by remember { mutableStateOf<Models.Pair?>(null) }
    val busy = state.progress != null
    val qwen = LocalQwenColors.current

    SoftCard {
        CardTitleRow(
            title = "设备存储与下载",
            subtitle = "可用空间 ${formatBytes(state.available)} · 配套文件均强制 SHA-256 校验",
            icon = AppIcons.Storage,
            iconBg = qwen.iconTealBg,
            iconFg = qwen.iconTealFg
        )
        SettingSwitchRow(
            title = "允许使用移动网络下载",
            subtitle = "关闭时仅在 Wi-Fi 下拉取，自动支持断点续传",
            checked = mobile,
            onCheckedChange = { mobile = it }
        )
        if (state.legacyUnknown) {
            Surface(shape = RoundedCornerShape(14.dp), color = qwen.statusWarningSoft) {
                Text(
                    text = "检测到旧版 GGUF 文件但校验摘要不匹配，可重新导入清单内的配套文件。",
                    style = MaterialTheme.typography.bodySmall,
                    color = qwen.statusWarning,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }
        if (state.progress != null) {
            ProgressBanner(progress = state.progress, onCancel = { model.cancel() })
        }
    }

    Models.CARDS.forEach { card ->
        val pair = state.installed[card.id()]
        val isSelected = state.selected == card.id()
        val isSmall = card == Models.SMALL

        SoftCard {
            CardTitleRow(
                title = card.title(),
                subtitle = if (isSmall) "轻量推荐 · 速度快、功耗与内存占用更低" else "高质量档位 · 识别与断句更精准",
                icon = AppIcons.Memory,
                iconBg = if (isSmall) qwen.iconTealBg else qwen.iconPurpleBg,
                iconFg = if (isSmall) qwen.iconTealFg else qwen.iconPurpleFg,
                trailing = {
                    when {
                        pair != null && isSelected -> StatusPill("使用中", qwen.statusSuccessSoft, qwen.statusSuccess)
                        pair != null -> StatusPill("已安装", MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
                        isSmall -> StatusPill("推荐", MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CompactInfoChip("主模型 ${formatBytes(card.main().size())}", Modifier.weight(1f))
                CompactInfoChip("编码器 ${formatBytes(card.projector().size())}", Modifier.weight(1f))
                CompactInfoChip("共 ${formatBytes(card.size())}", Modifier.weight(1f))
            }

            if (pair == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryButton("下载模型", enabled = !busy, icon = AppIcons.Download, modifier = Modifier.weight(1f)) {
                        model.install(card, mobile = mobile)
                    }
                    SecondaryButton("本地导入", enabled = !busy, icon = AppIcons.Upload, modifier = Modifier.weight(1f)) {
                        activity.import(card)
                    }
                }
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(AppIcons.ShieldCheck, contentDescription = null, tint = qwen.statusSuccess, modifier = Modifier.size(16.dp))
                    Text(
                        text = if (pair.legacy()) "旧版资产已原地通过 SHA-256 校验" else "SHA-256 完整性校验通过",
                        style = MaterialTheme.typography.bodySmall,
                        color = qwen.statusSuccess
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TonalButton(
                        label = "设为当前模型",
                        enabled = !isSelected && !state.service.starting(),
                        icon = AppIcons.CheckCircle,
                        modifier = Modifier.weight(1f)
                    ) {
                        model.select(card)
                        model.notify("模型将在下次加载时切换")
                    }
                    SecondaryButton(
                        label = "卸载",
                        enabled = !busy && !state.service.starting() && state.runningModel != card.id(),
                        icon = AppIcons.Delete,
                        modifier = Modifier.weight(0.7f)
                    ) { removal = pair }
                }
            }
        }
    }

    removal?.let { pair ->
        AlertDialog(
            onDismissRequest = { removal = null },
            title = { Text("卸载 ${pair.card().title()}？") },
            text = { Text("将删除该档位的主模型与音频编码器，释放约 ${formatBytes(pair.card().size())} 存储空间。") },
            confirmButton = {
                TextButton(onClick = { removal = null; model.uninstall(pair) }) { Text("确认卸载") }
            },
            dismissButton = {
                TextButton(onClick = { removal = null }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun CompactInfoChip(text: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun InferenceSettingsContent(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity
) {
    val initial = remember { DictationService.tuning(activity) }
    var device by rememberSaveable { mutableStateOf(initial.device()) }
    var threads by rememberSaveable { mutableStateOf(initial.threads().toString()) }
    var batch by rememberSaveable { mutableStateOf(initial.batch().toString()) }
    var context by rememberSaveable { mutableStateOf(initial.context().toString()) }
    var output by rememberSaveable { mutableStateOf(initial.output().toString()) }
    var layers by rememberSaveable { mutableStateOf(initial.layers().toString()) }
    var external by rememberSaveable { mutableStateOf(state.external) }
    val qwen = LocalQwenColors.current

    val cpuOnly = model.cpuOnly()
    val options = linkedMapOf("auto" to "自动（优先已验证 HTP0，否则 CPU）", "CPU" to "CPU")
    val candidates = if (cpuOnly) emptyList() else state.devices?.devices().orEmpty()
    if (!cpuOnly) {
        (candidates + initial.device()).filter { it !in options }.forEach { name ->
            val tuning = NativeRuntime.Tuning(
                name,
                threads.toIntOrNull() ?: 0,
                batch.toIntOrNull() ?: 0,
                context.toIntOrNull() ?: 0,
                output.toIntOrNull() ?: 0,
                layers.toIntOrNull() ?: 0
            )
            options[name] = if (model.verified(state.selected, tuning)) "$name · 已通过自检" else "$name · 待验证"
        }
    }

    fun save() = model.saveInference(if (cpuOnly) "CPU" else device, listOf(threads, batch, context, output, layers), external)

    SoftCard {
        CardTitleRow(
            title = if (cpuOnly) "ARM64 CPU 推理引擎" else "计算后端",
            subtitle = if (cpuOnly) "面向天玑及通用 ARM64 SoC，自动匹配 ARMv8 / ARMv9 CPU 指令集"
            else "音频编码器固定 CPU，更改将在下次加载模型时生效",
            icon = AppIcons.Memory,
            iconBg = qwen.iconPurpleBg,
            iconFg = qwen.iconPurpleFg,
            trailing = { StatusPill("下次加载生效", qwen.statusInfoSoft, qwen.statusInfo, dotColor = null) }
        )

        if (!cpuOnly) {
            DropdownChoiceField(
                label = "语言模型解码设备",
                current = device,
                options = options,
                onSelect = { device = it }
            )
        }

        if (state.backend.isNotBlank()) {
            Text(
                text = "当前运行：${state.backend}",
                style = MaterialTheme.typography.bodySmall,
                color = qwen.statusSuccess
            )
        }

        if (!cpuOnly && candidates.isEmpty()) {
            SecondaryButton(
                label = "探测 GPU / NPU 加速设备",
                enabled = state.progress == null && !state.service.ready() && !state.service.starting(),
                icon = AppIcons.Refresh
            ) { model.enumerate() }
        }
        if (!cpuOnly && state.progress != null) ProgressBanner(progress = state.progress)
        if (!cpuOnly && state.devices != null) {
            Text(state.devices.lists(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        LabeledTextField(
            label = "外部 llama.cpp 地址（可选）",
            value = external,
            onValueChange = { external = it },
            placeholder = "留空使用内置端侧运行时"
        )
    }

    PerformancePresetCard(state = state, model = model)

    SoftCard {
        CardTitleRow(
            title = "高级参数",
            subtitle = "常驻单实例 · 同时处理单个音频片段",
            icon = AppIcons.Tune,
            iconBg = qwen.iconAmberBg,
            iconFg = qwen.iconAmberFg
        )
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabeledTextField("CPU 线程 (1–32)", threads, { threads = it }, modifier = Modifier.weight(1f))
            LabeledTextField("批尺寸 (32–2048)", batch, { batch = it }, modifier = Modifier.weight(1f))
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LabeledTextField("上下文 (512–8192)", context, { context = it }, modifier = Modifier.weight(1f))
            LabeledTextField("最大输出 (32–512)", output, { output = it }, modifier = Modifier.weight(1f))
        }
        if (!cpuOnly) {
            LabeledTextField("最大卸载层数 (1–99)", layers, { layers = it })
        }
    }

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton("保存设置", icon = AppIcons.CheckCircle, modifier = Modifier.weight(1f)) { save() }
        TonalButton(
            label = "保存并自检",
            enabled = !state.service.ready() && !state.service.starting() && (state.modelReady || external.isNotBlank()),
            icon = AppIcons.Play,
            modifier = Modifier.weight(1f)
        ) { if (save()) activity.preload(true) }
    }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(
            label = "释放模型",
            enabled = state.service.ready() && !state.service.live() && !state.recovering,
            icon = AppIcons.Stop,
            modifier = Modifier.weight(1f)
        ) { activity.release() }
        SecondaryButton("恢复默认", icon = AppIcons.Refresh, modifier = Modifier.weight(1f)) {
            model.resetInference()
            val defaults = DictationService.tuning(activity)
            device = defaults.device()
            threads = defaults.threads().toString()
            batch = defaults.batch().toString()
            context = defaults.context().toString()
            output = defaults.output().toString()
            layers = defaults.layers().toString()
            external = ""
        }
    }

    RtfWatchdogCard(state = state, model = model)
}

@Composable
private fun PerformancePresetCard(
    state: ScreenState,
    model: DictationModel
) {
    val qwen = LocalQwenColors.current
    val perf = state.performanceStatus ?: remember { model.performanceStatus() }
    val selected = perf.selectedPreset()
    val active = perf.activePreset()
    val isRunning = active.isNotBlank()
    val selectedLabel = performancePresetLabel(selected)
    val activeLabel = performancePresetLabel(active)

    val presetDescription = when (selected) {
        "foreground" -> "保护前台：CPU 线程上限 4、逻辑批上限 512、微批上限 256、启动优先级 nice +1、批量识别段间间隔 300 ms；适合分屏或与 Kikoeta 并行。"
        "full_speed" -> "全速：沿用高级线程与逻辑批、微批上限 512、继承系统优先级、批量段间间隔 0 ms；追求最高吞吐。"
        else -> "均衡（默认）：沿用高级线程与逻辑批、微批上限 256、启动优先级 nice +1、批量段间间隔 0 ms；兼顾吞吐与内存占用。"
    }
    val priorityText = when {
        !perf.available() -> "外部引擎"
        isRunning -> when {
            perf.nativeNiceAdjustment() > 0 -> "nice +${perf.nativeNiceAdjustment()}（本轮已应用）"
            active == "full_speed" -> "继承系统"
            else -> "继承系统（系统未授予 nice 调整）"
        }
        selected == "full_speed" -> "继承系统"
        else -> "nice +1（启动时应用）"
    }
    val bulkGapText = if (perf.bulkGapMillis() > 0) {
        "${perf.bulkGapMillis()} ms（仅批量任务，不计入 RTF）"
    } else {
        "0 ms（无段间等待）"
    }

    SoftCard {
        CardTitleRow(
            title = "性能与内存",
            subtitle = "全档固定禁用 Prompt Cache（--cache-ram 0），避免长音频内存膨胀",
            icon = AppIcons.ShieldCheck,
            iconBg = if (!perf.available()) MaterialTheme.colorScheme.surfaceContainer else qwen.iconTealBg,
            iconFg = if (!perf.available()) MaterialTheme.colorScheme.onSurfaceVariant else qwen.iconTealFg,
            trailing = {
                when {
                    !perf.available() -> StatusPill("外部不可用", qwen.statusWarningSoft, qwen.statusWarning, dotColor = null)
                    perf.reloadRequired() -> StatusPill("需重载生效", qwen.statusWarningSoft, qwen.statusWarning)
                    isRunning -> StatusPill("运行中", qwen.statusSuccessSoft, qwen.statusSuccess)
                    else -> StatusPill(selectedLabel, qwen.statusInfoSoft, qwen.statusInfo, dotColor = null)
                }
            }
        )

        if (!perf.available()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = qwen.statusWarningSoft
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Warning,
                        contentDescription = null,
                        tint = qwen.statusWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "当前使用外部 ASR 地址，本地性能与内存预设仅对内置 llama-server 生效。清空外部地址后自动恢复可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = qwen.statusWarning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        } else {
            PillTabSelector(
                selected = selected,
                options = listOf(
                    "foreground" to "保护前台",
                    "balanced" to "均衡",
                    "full_speed" to "全速"
                ),
                onSelect = { presetId ->
                    if (presetId != selected) model.savePerformancePreset(presetId)
                }
            )

            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    RtfStatusRow(
                        label = "当前预设",
                        value = if (selected == "balanced") "$selectedLabel（默认）" else selectedLabel,
                        emphasize = true
                    )
                    RtfStatusRow(
                        label = "进程状态",
                        value = when {
                            !isRunning -> "未加载 · 下次启动生效"
                            perf.reloadRequired() -> "运行中（$activeLabel）· 线程/微批/优先级待重载切换"
                            else -> "运行中（$activeLabel）· 已按当前预设启动"
                        }
                    )
                    RtfStatusRow(
                        label = "启动配置",
                        value = "${perf.nativeThreads()} 线程 · 批 ${perf.nativeBatch()} / 微批 ${perf.nativeMicroBatch()} · RAM 缓存 0 MiB"
                    )
                    RtfStatusRow(label = "启动优先级", value = priorityText)
                    RtfStatusRow(label = "批量间隔", value = bulkGapText)
                }
            }

            if (perf.reloadRequired()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = qwen.statusWarningSoft
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = AppIcons.Refresh,
                            contentDescription = null,
                            tint = qwen.statusWarning,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = "已选择「$selectedLabel」：批量段间间隔从下一次批量推理立即生效；线程、微批与启动优先级将在当前任务完成后释放并重新加载模型时生效。",
                            style = MaterialTheme.typography.bodySmall,
                            color = qwen.statusWarning,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Text(
                text = presetDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "保存预设后，文件转写、Kikoeta、OpenAI 与 DashScope 的批量段间间隔从下一次推理起生效（实时听写不插入段间等待，等待期间可随时取消且不计入 RTF）。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RtfWatchdogCard(
    state: ScreenState,
    model: DictationModel
) {
    val qwen = LocalQwenColors.current
    val rtfStatus = state.rtfStatus ?: remember { model.rtfStatus() }
    val isExternal = rtfStatus.backend() == "external"
    val rtfEnabled = rtfStatus.settings().enabled()
    val savedManual = rtfStatus.settings().manualLimit()
    val rtfKey = "${rtfStatus.modelId()}|${rtfStatus.backend()}"

    var manualMode by rememberSaveable(rtfKey) { mutableStateOf(savedManual != null) }
    var manualInput by rememberSaveable(rtfKey) {
        mutableStateOf(savedManual?.let(::formatRtfNumber) ?: "")
    }
    var manualError by remember(rtfKey) { mutableStateOf("") }

    LaunchedEffect(rtfKey, savedManual) {
        manualError = ""
        if (savedManual != null) {
            manualMode = true
            manualInput = formatRtfNumber(savedManual)
        } else {
            manualMode = false
            manualInput = ""
        }
    }

    val modelLabel = when {
        rtfStatus.modelId() == "external" -> "外部 ASR 引擎"
        rtfStatus.modelId().isBlank() -> "未安装模型"
        else -> Models.CARDS.find { it.id() == rtfStatus.modelId() }?.title() ?: rtfStatus.modelId()
    }
    val backendLabel = when (rtfStatus.backend()) {
        "external" -> "外部引擎 (external)"
        else -> rtfStatus.backend().ifBlank { "未确定" }
    }
    val backendScope = if (state.runningModel.isNotBlank()) "当前运行" else "下次加载目标"
    val modeLabel = when {
        isExternal -> "不可用"
        savedManual != null -> "手动"
        else -> "自动"
    }
    val effectiveSummary = when {
        isExternal -> "本地 RTF 重载不可用"
        savedManual != null -> "RTF > ${formatRtfNumber(rtfStatus.rtfLimit())}（无固定余量）"
        else -> {
            val overhead = formatSecondsLabel(rtfStatus.overheadSeconds())
            "RTF > ${String.format(Locale.ROOT, "%.2f", rtfStatus.rtfLimit())}（含 ${overhead}固定余量）"
        }
    }

    SectionLabel("稳定性保护")

    SoftCard {
        CardTitleRow(
            title = "RTF 失控预警",
            subtitle = "连续 3 次越界时扣留异常片段并重载本地模型，无需手动重启",
            icon = AppIcons.Speed,
            iconBg = if (isExternal || !rtfEnabled) MaterialTheme.colorScheme.surfaceContainer else qwen.iconRoseBg,
            iconFg = if (isExternal || !rtfEnabled) MaterialTheme.colorScheme.onSurfaceVariant else qwen.iconRoseFg,
            trailing = {
                when {
                    state.recovering -> StatusPill("恢复中", qwen.statusWarningSoft, qwen.statusWarning)
                    isExternal -> StatusPill("外部不可用", qwen.statusWarningSoft, qwen.statusWarning, dotColor = null)
                    rtfEnabled -> StatusPill("即时生效", qwen.statusSuccessSoft, qwen.statusSuccess, dotColor = null)
                    else -> StatusPill("已关闭", MaterialTheme.colorScheme.surfaceContainer, MaterialTheme.colorScheme.onSurfaceVariant, dotColor = null)
                }
            }
        )

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                RtfStatusRow(label = "实际后端", value = "$backendLabel · $backendScope")
                RtfStatusRow(label = "对应模型", value = modelLabel)
                RtfStatusRow(label = "阈值模式", value = modeLabel)
                RtfStatusRow(
                    label = "生效数值",
                    value = effectiveSummary,
                    emphasize = rtfEnabled && !isExternal
                )
                if (!rtfEnabled && !isExternal) {
                    Text(
                        text = "总开关已关闭：仍记录片段 RTF，停用越界计数与 RTF 重载；截断和进程故障仍自动恢复。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (isExternal) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                color = qwen.statusWarningSoft
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = AppIcons.Warning,
                        contentDescription = null,
                        tint = qwen.statusWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "当前已配置外部 ASR 地址（external），应用无法重载外部推理进程，本地 RTF 失控重载不可用。清空外部地址并切回内置运行时后自动恢复可用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = qwen.statusWarning,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        SettingSwitchRow(
            title = "启用 RTF 失控预警",
            subtitle = "本机总开关；关闭后仍记录片段 RTF，仅停用 RTF 越界计数、告警与 RTF 触发的重载",
            checked = rtfEnabled,
            onCheckedChange = { enabled ->
                model.saveRtfSettings(
                    modelId = rtfStatus.modelId(),
                    backend = rtfStatus.backend(),
                    enabled = enabled,
                    manualLimit = savedManual,
                    feedback = if (enabled) "RTF 失控预警已开启，下一次推理起生效"
                    else "RTF 失控预警已关闭（仍记录片段 RTF）"
                )
            }
        )

        if (!isExternal) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "预警值模式（按当前模型与 ${rtfStatus.backend()} 独立保存）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PillTabSelector(
                    selected = if (manualMode) "manual" else "auto",
                    options = listOf("auto" to "自动", "manual" to "手动"),
                    onSelect = { selectedMode ->
                        if (selectedMode == "auto") {
                            manualMode = false
                            manualError = ""
                            if (savedManual != null) {
                                model.saveRtfSettings(
                                    modelId = rtfStatus.modelId(),
                                    backend = rtfStatus.backend(),
                                    enabled = rtfEnabled,
                                    manualLimit = null,
                                    feedback = "已切回自动 RTF 预警，下一次推理起生效"
                                )
                            }
                        } else {
                            manualMode = true
                            manualError = ""
                            if (manualInput.isBlank() && savedManual != null) {
                                manualInput = formatRtfNumber(savedManual)
                            }
                        }
                    }
                )
            }

            if (!manualMode) {
                val fallbackRtf = if (rtfStatus.backend() == "CPU") "1.2" else "0.8"
                val overheadText = formatSecondsLabel(rtfStatus.overheadSeconds())
                Text(
                    text = "自动模式基于同配置最近 9 个健康片段（≥ 5 段且每段 ≥ 4 秒）的 RTF 中位数 × 3 动态设定，样本不足时暂用 ${rtfStatus.backend()} RTF $fallbackRtf，并附加固定 ${overheadText}余量。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LabeledTextField(
                    label = "手动 RTF 预警值（正数或小数）",
                    value = manualInput,
                    onValueChange = {
                        manualInput = it
                        if (manualError.isNotEmpty()) manualError = ""
                    },
                    placeholder = "例如 1.2",
                    supportingText = manualError.ifBlank {
                        "实测 RTF 严格大于该值才计为越界（如设 1.2，8 秒音频推理耗时超过 9.6 秒才计一次）"
                    },
                    isError = manualError.isNotBlank(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    PrimaryButton(
                        label = "保存预警值",
                        icon = AppIcons.CheckCircle,
                        modifier = Modifier.weight(1f)
                    ) {
                        val parsed = parseManualRtfLimit(manualInput)
                        if (parsed == null) {
                            manualError = "请输入大于 0 的有限正数，例如 0.8 或 1.2"
                        } else {
                            manualError = ""
                            model.saveRtfSettings(
                                modelId = rtfStatus.modelId(),
                                backend = rtfStatus.backend(),
                                enabled = rtfEnabled,
                                manualLimit = parsed,
                                feedback = "手动 RTF 预警值已保存，下一次推理起生效"
                            )
                        }
                    }
                    SecondaryButton(
                        label = "切回自动",
                        modifier = Modifier.weight(0.75f)
                    ) {
                        manualMode = false
                        manualError = ""
                        manualInput = ""
                        model.saveRtfSettings(
                            modelId = rtfStatus.modelId(),
                            backend = rtfStatus.backend(),
                            enabled = rtfEnabled,
                            manualLimit = null,
                            feedback = "已切回自动 RTF 预警，下一次推理起生效"
                        )
                    }
                }
            }
        }

        Text(
            text = "保存后无需重载模型，新设置从下一次推理尝试起生效并清零计数；已启动的恢复将继续完成。可在「日志」页查看 RTF 预警记录。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RtfStatusRow(
    label: String,
    value: String,
    emphasize: Boolean = false
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 64.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (emphasize) FontFamily.Monospace else FontFamily.Default,
            fontWeight = if (emphasize) FontWeight.SemiBold else FontWeight.Medium,
            color = if (emphasize) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun formatRtfNumber(value: Double): String =
    BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()

private fun formatSecondsLabel(seconds: Double): String =
    if (seconds == seconds.toLong().toDouble()) "${seconds.toLong()} 秒"
    else "${formatRtfNumber(seconds)} 秒"

@Composable
private fun TranslationSettingsContent(
    state: ScreenState,
    model: DictationModel
) {
    var source by rememberSaveable { mutableStateOf(state.source) }
    var target by rememberSaveable { mutableStateOf(state.target) }
    var mode by rememberSaveable { mutableStateOf(state.translation) }
    var url by rememberSaveable { mutableStateOf(model.modelPrefs().getString("translation", "")!!) }
    var id by rememberSaveable { mutableStateOf(model.modelPrefs().getString("model", "")!!) }
    var apiKey by remember { mutableStateOf("") }
    var mobile by rememberSaveable { mutableStateOf(false) }
    var acceptOnline by remember { mutableStateOf(false) }
    val qwen = LocalQwenColors.current

    fun save() {
        model.saveTranslation(mode, source, target, url, id, apiKey)
        apiKey = ""
    }

    SoftCard {
        CardTitleRow(
            title = "语种与翻译引擎",
            subtitle = "更改将在下次加载模型时生效",
            icon = AppIcons.Translate,
            iconBg = qwen.iconBlueBg,
            iconFg = qwen.iconBlueFg
        )
        PillTabSelector(
            selected = mode,
            options = listOf("offline" to "离线翻译", "online" to "联网翻译", "off" to "仅听写"),
            onSelect = { mode = it }
        )
        DropdownChoiceField(
            label = "源语言",
            current = source,
            options = languages.filterKeys { it != "简体中文" },
            onSelect = { source = it }
        )
        DropdownChoiceField(
            label = "目标语言",
            current = target,
            options = languages.filterKeys { it != "auto" && it != "zh" },
            onSelect = { target = it }
        )
        if (mode == "online") {
            LabeledTextField("OpenAI 兼容翻译地址", url, { url = it }, placeholder = "https://api.example.com/v1")
            LabeledTextField("翻译模型 ID", id, { id = it }, placeholder = "例如 qwen-plus")
            LabeledTextField("API Key（留空保留已存密钥）", apiKey, { apiKey = it }, password = true)
        }
        PrimaryButton("保存翻译设置", icon = AppIcons.CheckCircle, modifier = Modifier.fillMaxWidth()) {
            if (mode == "online") acceptOnline = true else save()
        }
    }

    SoftCard {
        CardTitleRow(
            title = "ML Kit 端侧离线语言包",
            subtitle = "安装后支持飞行模式全离线双语听写（日中经英语中转）",
            icon = AppIcons.Download,
            iconBg = qwen.iconTealBg,
            iconFg = qwen.iconTealFg,
            trailing = {
                if (state.missingLanguages.isEmpty()) StatusPill("已就绪", qwen.statusSuccessSoft, qwen.statusSuccess)
                else StatusPill("待下载", qwen.statusWarningSoft, qwen.statusWarning)
            }
        )
        Text(
            text = if (state.missingLanguages.isEmpty()) "当前语种所需离线语言包均已就绪。"
            else "待安装：${state.missingLanguages.joinToString("、", transform = ::languageName)}",
            style = MaterialTheme.typography.bodyMedium
        )
        SettingSwitchRow("允许使用移动网络下载语言包", checked = mobile, onCheckedChange = { mobile = it })
        if (state.progress != null) ProgressBanner(progress = state.progress, onCancel = { model.cancel() })
        PrimaryButton(
            label = "下载所选语言包",
            enabled = state.missingLanguages.isNotEmpty() && state.progress == null,
            icon = AppIcons.Download,
            modifier = Modifier.fillMaxWidth()
        ) { model.downloadLanguages(mobile) }
    }

    if (acceptOnline) {
        AlertDialog(
            onDismissRequest = { acceptOnline = false },
            title = { Text("开启联网翻译？") },
            text = { Text("启用后，应用会将转写文本发送到你配置的外部翻译 API。") },
            confirmButton = {
                TextButton(onClick = { acceptOnline = false; save() }) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { acceptOnline = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun AudioSettingsContent(
    state: ScreenState,
    model: DictationModel
) {
    val qwen = LocalQwenColors.current
    SoftCard {
        CardTitleRow(
            title = "音频文件解码器",
            subtitle = "文件转写与 Kikoeta 音轨统一重采样至 16 kHz PCM",
            icon = AppIcons.GraphicEq,
            iconBg = qwen.iconAmberBg,
            iconFg = qwen.iconAmberFg,
            trailing = { StatusPill("即时生效", qwen.statusSuccessSoft, qwen.statusSuccess, dotColor = null) }
        )
        DropdownChoiceField(
            label = "解码模式",
            current = state.audioDecoder,
            options = linkedMapOf(
                "auto" to "自动（优先最优解码器）",
                "hardware" to "系统硬件解码",
                "software" to "系统软件解码",
                "pcm" to "PCM / WAV 直读"
            ),
            onSelect = { model.saveAudioDecoder(it) }
        )
    }

    SoftCard {
        CardTitleRow(
            title = "采集规格说明",
            icon = AppIcons.Mic,
            iconBg = qwen.iconTealBg,
            iconFg = qwen.iconTealFg
        )
        Text("• 麦克风与播放内录均采用 16 kHz 单声道 16-bit PCM。", style = MaterialTheme.typography.bodyMedium)
        Text("• 实时听写每 3 秒分段并自动过滤静音；批量文件每 8 秒分段。", style = MaterialTheme.typography.bodyMedium)
        Text(
            text = "• 播放内录依赖系统录屏权限与音源应用的音频捕获许可。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun GatewaySettingsContent(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity
) {
    val stored = remember { GatewayConfig.read(activity) }
    var mode by rememberSaveable { mutableStateOf(stored.mode()) }
    var port by rememberSaveable { mutableStateOf(stored.port().toString()) }
    var bind by rememberSaveable { mutableStateOf(stored.bind()) }
    var ranges by rememberSaveable { mutableStateOf(stored.ranges()) }
    var kikoeta by rememberSaveable { mutableStateOf(stored.kikoeta()) }
    var openai by rememberSaveable { mutableStateOf(stored.openai()) }
    var dash by rememberSaveable { mutableStateOf(stored.dashscope()) }
    var alias by rememberSaveable { mutableStateOf(stored.alias()) }
    var allowPrivateAudio by rememberSaveable { mutableStateOf(state.allowPrivateAudio) }
    var lanOrigins by rememberSaveable { mutableStateOf(state.kikoetaLanSource) }
    var confirmOpen by remember { mutableStateOf(false) }
    val qwen = LocalQwenColors.current

    fun save() {
        if (!model.saveAudioFetchPolicy(allowPrivateAudio, lanOrigins)) return
        val config = GatewayConfig(port.toIntOrNull() ?: 0, bind.trim(), mode, ranges, kikoeta, openai, dash, alias)
        runCatching { config.policy() }.getOrElse {
            model.notify(it.message ?: "监听参数无效")
            return
        }
        activity.updateGateway(config)
        model.notify("监听与音频来源配置已提交")
    }

    SoftCard {
        CardTitleRow(
            title = "监听端口与访问范围",
            subtitle = "本机模式可交由 TLS 代理反代；局域网直连走 HTTP",
            icon = AppIcons.Lan,
            iconBg = qwen.iconIndigoBg,
            iconFg = qwen.iconIndigoFg
        )
        LabeledTextField("监听端口 (1024–65535)", port, { port = it })
        PillTabSelector(
            selected = mode,
            options = listOf("LOCAL" to "仅本机", "ALLOWLIST" to "IP 白名单", "OPEN" to "局域网全开"),
            onSelect = { mode = it }
        )
        if (mode != "LOCAL") {
            LabeledTextField("绑定接口 IP", bind, { bind = it }, placeholder = "例如 192.168.1.20")
        }
        if (mode == "ALLOWLIST") {
            LabeledTextField("允许的 IPv4 / IPv6 或 CIDR", ranges, { ranges = it }, placeholder = "192.168.1.0/24, 100.64.0.0/10")
        }
    }

    SoftCard {
        CardTitleRow(
            title = "启用协议",
            subtitle = "所有协议均独立鉴权",
            icon = AppIcons.Server,
            iconBg = qwen.iconBlueBg,
            iconFg = qwen.iconBlueFg
        )
        SettingSwitchRow("Kikoeta", "本地队列、缓存查询与 LRC 输出", kikoeta) { kikoeta = it }
        if (kikoeta) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Kikoeta 输出内容（即时生效）",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                PillTabSelector(
                    selected = state.kikoetaOutput,
                    options = listOf("source" to "仅原文", "bilingual" to "双语", "translation" to "仅译文"),
                    onSelect = { model.saveKikoetaOutput(it) }
                )
            }
        }
        SettingSwitchRow("OpenAI Audio", "/v1/models 与 /v1/audio/transcriptions", openai) { openai = it }
        SettingSwitchRow("百炼 DashScope 同步识别", "支持 Base64 与 HTTP(S) 音频链接", dash) { dash = it }
        if (dash) {
            LabeledTextField("百炼云模型别名（可选）", alias, { alias = it }, placeholder = "例如 qwen3-asr-flash")
        }
    }

    SoftCard {
        CardTitleRow(
            title = "音频下载与来源策略",
            subtitle = "自动支持公网、VPN Fake-IP (198.18.0.0/15)、系统代理与自定义端口",
            icon = AppIcons.ShieldCheck,
            iconBg = qwen.statusSuccessSoft,
            iconFg = qwen.statusSuccess,
            trailing = { StatusPill("即时生效", qwen.statusSuccessSoft, qwen.statusSuccess, dotColor = null) }
        )
        SettingSwitchRow(
            title = "允许局域网与内网音频来源",
            subtitle = "兼容自建 Kikoeru / NAS（192.168.x.x / 10.x.x.x / 172.16.x.x / Tailscale 100.64.x.x），本机回环与链路本地地址始终拦截",
            checked = allowPrivateAudio,
            onCheckedChange = { allowPrivateAudio = it }
        )
        if (!allowPrivateAudio) {
            LabeledTextField(
                label = "指定内网来源白名单（可选）",
                value = lanOrigins,
                onValueChange = { lanOrigins = it },
                placeholder = "http://192.168.1.50:8888",
                supportingText = "支持多个来源（逗号或空格分隔），仅填协议、主机和端口",
                singleLine = false,
                maxLines = 3
            )
        }
    }

    PrimaryButton(
        label = "应用配置",
        icon = AppIcons.CheckCircle,
        modifier = Modifier.fillMaxWidth()
    ) {
        if (mode == "OPEN") confirmOpen = true else save()
    }

    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("开启所选接口的全开访问？") },
            text = { Text("该接口可达的所有客户端都可发起请求（仍需协议密钥）。局域网监听使用 HTTP。") },
            confirmButton = {
                TextButton(onClick = { confirmOpen = false; save() }) { Text("确认开启") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("取消") }
            }
        )
    }
}

@Composable
private fun DiagnosticsContent(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity,
    selfTest: Boolean = true
) {
    val qwen = LocalQwenColors.current
    val cpuOnly = model.cpuOnly()
    val probe = state.devices
    val gpu = probe?.devices()?.filterNot { it.startsWith("HTP", true) }.orEmpty()
    val npu = probe?.devices()?.filter { it.startsWith("HTP", true) }.orEmpty()

    SoftCard {
        CardTitleRow(
            title = if (cpuOnly) "运行环境与自检" else "硬件与端侧运行时",
            subtitle = model.deviceDetails(),
            icon = AppIcons.Speed,
            iconBg = qwen.iconRoseBg,
            iconFg = qwen.iconRoseFg,
            trailing = {
                StatusPill(
                    label = if (model.runtimePresent()) (if (cpuOnly) "ARM64 CPU" else "已打包 b12470") else "缺失",
                    containerColor = if (model.runtimePresent()) qwen.statusSuccessSoft else MaterialTheme.colorScheme.errorContainer,
                    contentColor = if (model.runtimePresent()) qwen.statusSuccess else MaterialTheme.colorScheme.onErrorContainer
                )
            }
        )

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactInfoChip("可用存储 ${formatBytes(state.available)}", Modifier.weight(1f))
            CompactInfoChip("CPU ${if (model.runtimePresent()) "就绪" else "缺失"}", Modifier.weight(1f))
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (cpuOnly) {
                    Text(
                        text = "当前为通用 ARM64 CPU 版本（llama.cpp b12470），内置 ARMv8.0 – ARMv9.2 多指令集 CPU 后端，运行时自动选择最优指令集，无需设备探测与硬件卸载配置。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else if (probe != null) {
                    Text("GPU · " + gpu.joinToString("、").ifBlank { "未枚举到可用加速设备" }, style = MaterialTheme.typography.bodySmall)
                    Text("NPU · " + npu.joinToString("、").ifBlank { "未枚举到可用加速设备" }, style = MaterialTheme.typography.bodySmall)
                    Text(probe.lists(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text(
                        text = "点击「探测加速设备」枚举当前应用权限下可用的 GPU 与 Hexagon NPU。自动档将优先使用已验证的 HTP0，否则回退 CPU。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!cpuOnly) {
                SecondaryButton(
                    label = "探测加速设备",
                    enabled = state.progress == null && !state.service.starting() && !state.service.ready(),
                    icon = AppIcons.Refresh,
                    modifier = Modifier.weight(1f)
                ) { model.enumerate() }
            }
            if (selfTest) {
                PrimaryButton(
                    label = "短音频自检",
                    enabled = state.modelReady && !state.service.ready() && !state.service.starting(),
                    icon = AppIcons.Play,
                    modifier = Modifier.weight(1f)
                ) { activity.preload(true) }
            }
        }

        if (state.progress != null) ProgressBanner(progress = state.progress)
        if (selfTest && state.service.message().isNotBlank()) {
            Text(
                text = "当前引擎状态：${state.service.message()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (state.localCheckFailed) {
            TextButton(onClick = { activity.systemAppSettings() }) {
                Text("打开系统应用设置（电池优化豁免） →")
            }
        }
    }
}
