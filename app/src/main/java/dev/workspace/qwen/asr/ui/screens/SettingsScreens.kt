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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
                    title = activeSection,
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
                subtitle = if (model.cpuOnly()) {
                    "ARM64 CPU · ${tuning.threads()} 线程 · 上下文 ${tuning.context()}"
                } else {
                    "后端 ${if (tuning.device() == "auto") "自动" else tuning.device()} · ${tuning.threads()} 线程 · 上下文 ${tuning.context()}"
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
            enabled = state.service.ready() && !state.service.live(),
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
}

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
