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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
internal fun ServiceScreen(
    state: ScreenState,
    model: DictationModel,
    activity: MainActivity,
    onOpenGatewayConfig: () -> Unit
) {
    val addresses = state.gateway.addresses()
    val running = addresses.isNotEmpty()
    val config = GatewayConfig.read(activity)
    val base = addresses.firstOrNull() ?: "http://127.0.0.1:${config.port()}"
    val qwen = LocalQwenColors.current
    var selectedTab by rememberSaveable { mutableStateOf("kikoeta") }

    val modeLabel = when (config.mode()) {
        "ALLOWLIST" -> "IP 白名单"
        "OPEN" -> "局域网全开"
        else -> "仅本机"
    }

    val enabledTabs = buildSet {
        if (config.kikoeta()) add("kikoeta")
        if (config.openai()) add("openai")
        if (config.dashscope()) add("dash")
    }

    val audioPolicyNote = if (state.allowPrivateAudio) {
        "已自动支持公网、VPN Fake-IP、系统代理、局域网及 Tailscale 音源"
    } else if (state.kikoetaLanSource.isNotBlank()) {
        "支持公网、VPN Fake-IP、系统代理 + 已配置的白名单内网音源"
    } else {
        "支持公网、VPN Fake-IP 与系统代理（内网音源已关闭）"
    }

    PageColumn {
        PageHeader(
            title = "服务",
            subtitle = "本地语音识别 API 网关"
        )

        // Hero Gateway Status Banner
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(28.dp),
            color = Color.Transparent
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (running) Brush.linearGradient(listOf(qwen.heroStart, qwen.heroEnd))
                        else Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.surface,
                                MaterialTheme.colorScheme.surface
                            )
                        )
                    )
                    .padding(20.dp)
            ) {
                val fg = if (running) Color.White else MaterialTheme.colorScheme.onSurface
                val fgMuted = if (running) Color.White.copy(alpha = 0.72f) else MaterialTheme.colorScheme.onSurfaceVariant

                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(if (running) Color(0xFF34D399) else MaterialTheme.colorScheme.outline)
                                )
                                Text(
                                    text = if (running) "API 网关运行中" else "API 网关未开启",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = fg
                                )
                            }
                            Text(
                                text = "端口 ${config.port()} · $modeLabel",
                                style = MaterialTheme.typography.bodySmall,
                                color = fgMuted
                            )
                        }
                        Switch(
                            checked = running,
                            onCheckedChange = { activity.gateway(it) },
                            colors = if (running) SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF002B1F),
                                checkedTrackColor = Color(0xFF34D399)
                            ) else SwitchDefaults.colors()
                        )
                    }

                    if (running) {
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White.copy(alpha = 0.10f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                SelectionContainer(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = addresses.joinToString("\n"),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.White
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        activity.copy("网关地址", addresses.first())
                                        model.notify("已复制网关地址")
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        AppIcons.Copy,
                                        contentDescription = "复制地址",
                                        tint = Color.White,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }

                    if (state.gateway.error().isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.errorContainer
                        ) {
                            Text(
                                text = state.gateway.error(),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    // Embedded live metrics bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        HeroMetric("已认证请求", state.requests.toString(), fg, fgMuted, Modifier.weight(1f))
                        HeroMetric("排队连接", (state.connectionsQueued + state.inferenceQueued).toString(), fg, fgMuted, Modifier.weight(1f))
                        HeroMetric("批量任务", state.jobs.toString(), fg, fgMuted, Modifier.weight(1f))
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { model.testGateway() },
                            enabled = running,
                            shape = RoundedCornerShape(14.dp),
                            colors = if (running) ButtonDefaults.buttonColors(
                                containerColor = Color.White.copy(alpha = 0.16f),
                                contentColor = Color.White
                            ) else ButtonDefaults.filledTonalButtonColors(),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        ) {
                            Icon(AppIcons.CheckCircle, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("连通测试", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = onOpenGatewayConfig,
                            shape = RoundedCornerShape(14.dp),
                            colors = if (running) ButtonDefaults.buttonColors(
                                containerColor = Color.White,
                                contentColor = Color(0xFF0E1B24)
                            ) else ButtonDefaults.buttonColors(),
                            modifier = Modifier.weight(1f).heightIn(min = 44.dp)
                        ) {
                            Icon(AppIcons.Tune, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("端口与音源", style = MaterialTheme.typography.labelLarge)
                        }
                    }

                    if (state.localCheckFailed) {
                        TextButton(
                            onClick = { activity.systemAppSettings() },
                            colors = ButtonDefaults.textButtonColors(contentColor = if (running) Color(0xFFFBBF24) else MaterialTheme.colorScheme.error)
                        ) {
                            Text("本机连接异常？前往系统设置豁免电池优化 →")
                        }
                    }
                }
            }
        }

        // Compact Model Runtime Bar
        SoftCard {
            CardTitleRow(
                title = "端侧推理引擎",
                subtitle = state.backend.ifBlank { state.service.message() },
                icon = AppIcons.Memory,
                iconBg = qwen.iconPurpleBg,
                iconFg = qwen.iconPurpleFg,
                trailing = {
                    StatusPill(
                        label = when {
                            state.service.starting() -> "预热中"
                            state.service.ready() -> "已常驻"
                            else -> "待机"
                        },
                        containerColor = when {
                            state.service.ready() -> qwen.statusSuccessSoft
                            state.service.starting() -> qwen.statusInfoSoft
                            else -> MaterialTheme.colorScheme.surfaceContainer
                        },
                        contentColor = when {
                            state.service.ready() -> qwen.statusSuccess
                            state.service.starting() -> qwen.statusInfo
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            )
            if (state.runningModel.isNotBlank()) {
                Text(
                    text = "模型 ID：${state.runningModel}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (state.service.starting()) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(5.dp)
                        .clip(CircleShape)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                PrimaryButton(
                    label = "预热模型",
                    enabled = state.modelReady && !state.service.starting() && !state.service.ready(),
                    icon = AppIcons.Play,
                    modifier = Modifier.weight(1f)
                ) { activity.preload() }
                SecondaryButton(
                    label = "释放显存/内存",
                    enabled = state.service.ready() && !state.service.live(),
                    icon = AppIcons.Stop,
                    modifier = Modifier.weight(1f)
                ) { activity.release() }
            }
        }

        // Tabbed Protocol Inspector (replaces 3 giant identical stacked cards)
        SectionLabel("接入协议与凭据")

        PillTabSelector(
            selected = selectedTab,
            options = listOf("kikoeta" to "Kikoeta", "openai" to "OpenAI", "dash" to "百炼 DashScope"),
            accentDots = enabledTabs,
            onSelect = { selectedTab = it }
        )

        val current = when (selectedTab) {
            "openai" -> ProtocolSpec(
                name = "OpenAI Audio 协议",
                desc = "兼容 /v1/models 与 /v1/audio/transcriptions（multipart 上传，支持 json / text）",
                enabled = config.openai(),
                authLabel = "Bearer Token",
                url = "$base/v1",
                secretKey = "openaiKey",
                example = "curl -H \"Authorization: Bearer \$QWEN_OPENAI_KEY\" $base/v1/models",
                footnote = "单次同步上传上限 8 MiB / 5 分钟"
            )
            "dash" -> ProtocolSpec(
                name = "阿里云百炼 DashScope",
                desc = "同步多模态语音识别接口，支持 Base64 Data URI 与 HTTP(S) 音频链接",
                enabled = config.dashscope(),
                authLabel = "Bearer API Key",
                url = base + AudioApi.DASH_PATH,
                secretKey = "dashKey",
                example = "curl -H \"Authorization: Bearer \$QWEN_DASH_KEY\" -H \"Content-Type: application/json\" -d @request.json $base${AudioApi.DASH_PATH}",
                footnote = "模型别名：${config.alias().ifBlank { "未映射，直接使用本地模型 ID" }}"
            )
            else -> ProtocolSpec(
                name = "Kikoeta 原生听写协议",
                desc = "支持批量音轨任务、事件轮询、结果缓存与私有 LRC 字幕下载",
                enabled = config.kikoeta(),
                authLabel = "Basic · admin",
                url = "$base/api/v1/health",
                secretKey = "password",
                example = "curl -u admin $base/api/v1/health",
                footnote = audioPolicyNote
            )
        }

        SoftCard {
            CardTitleRow(
                title = current.name,
                subtitle = current.desc,
                trailing = {
                    StatusPill(
                        label = if (current.enabled) current.authLabel else "未启用",
                        containerColor = if (current.enabled) qwen.statusSuccessSoft else MaterialTheme.colorScheme.surfaceContainer,
                        contentColor = if (current.enabled) qwen.statusSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                        dotColor = if (current.enabled) qwen.statusSuccess else null
                    )
                }
            )

            if (current.enabled) {
                if (selectedTab == "kikoeta") {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "Kikoeta 输出文本（即时生效）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        PillTabSelector(
                            selected = state.kikoetaOutput,
                            options = listOf("source" to "仅原文", "bilingual" to "双语", "translation" to "仅译文"),
                            onSelect = { model.saveKikoetaOutput(it) }
                        )
                        Text(
                            text = when (state.kikoetaOutput) {
                                "bilingual" -> "每行同步输出「原文 / 译文」，需开启翻译"
                                "translation" -> "仅输出翻译后的目标语言字幕，需开启翻译"
                                else -> "跳过翻译步骤，直接输出 Qwen3-ASR 识别原文 LRC，速度更快且免离线翻译包"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = qwen.terminalBg
                ) {
                    SelectionContainer {
                        Text(
                            text = current.url,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = qwen.terminalText,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
                }

                Text(
                    text = current.footnote,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SecondaryButton(
                        label = "地址",
                        icon = AppIcons.Copy,
                        modifier = Modifier.weight(1f)
                    ) {
                        activity.copy("API 地址", current.url)
                        model.notify("已复制接口地址")
                    }
                    TonalButton(
                        label = "密钥",
                        icon = AppIcons.Key,
                        modifier = Modifier.weight(1f)
                    ) {
                        val secret = model.secret(current.secretKey)
                        if (secret.isNotBlank()) {
                            activity.copy("API 凭据", secret, secret = true)
                            model.notify("已复制加密凭据")
                        }
                    }
                    SecondaryButton(
                        label = "示例",
                        icon = AppIcons.Code,
                        modifier = Modifier.weight(1f)
                    ) {
                        activity.copy("调用示例", current.example)
                        model.notify("已复制 cURL 示例")
                    }
                }
            } else {
                Text(
                    text = "此协议当前已关闭，可在「端口与音源」中随时开启。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TonalButton("前往开启", icon = AppIcons.Tune, onClick = onOpenGatewayConfig)
            }
        }
    }
}

@Composable
private fun HeroMetric(
    label: String,
    value: String,
    fg: Color,
    fgMuted: Color,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = fg
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = fgMuted
        )
    }
}

private data class ProtocolSpec(
    val name: String,
    val desc: String,
    val enabled: Boolean,
    val authLabel: String,
    val url: String,
    val secretKey: String,
    val example: String,
    val footnote: String
)
