package dev.workspace.qwen.asr

import android.app.Application
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.net.HttpURLConnection
import java.net.URI

internal data class ScreenState(
    val service: DictationService.Status = DictationService.status,
    val gateway: DictationService.Gateway = DictationService.gateway,
    val requests: Long = 0,
    val connectionsQueued: Int = 0,
    val inferenceQueued: Int = 0,
    val jobs: Long = 0,
    val backend: String = "",
    val runningModel: String = "",
    val installed: Map<String, Models.Pair> = emptyMap(),
    val selected: String = "",
    val external: String = "",
    val legacyUnknown: Boolean = false,
    val checkingLegacy: Boolean = false,
    val available: Long = 0,
    val progress: Models.Progress? = null,
    val languages: Set<String> = emptySet(),
    val source: String = "auto",
    val target: String = "简体中文",
    val translation: String = "offline",
    val audioDecoder: String = "auto",
    val allowPrivateAudio: Boolean = true,
    val kikoetaLanSource: String = "",
    val kikoetaOutput: String = "source",
    val devices: NativeRuntime.Devices? = null,
    val logs: List<RuntimeLog.Event> = emptyList(),
    val localCheckFailed: Boolean = false,
    val setupStep: Int = 0,
    val notice: String = ""
) {
    val modelReady get() = external.isNotBlank() || selected in installed
    val missingLanguages get() = Translator.Offline.required(source, target).filterNot { it in languages }
}

internal class DictationModel(private val app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val models = Models(app)
    private val prefs = DictationService.prefs(app)
    private val mutable = MutableStateFlow(ScreenState())
    val state = mutable.asStateFlow()
    private var operation: Job? = null

    var awaitingCapture: Boolean
        get() = saved["awaitingCapture"] ?: false
        set(value) { saved["awaitingCapture"] = value }
    var captureAfterChange: Int
        get() = saved["captureAfterChange"] ?: -1
        set(value) { saved["captureAfterChange"] = value }
    var capturePlayback: Boolean
        get() = saved["capturePlayback"] ?: false
        set(value) { saved["capturePlayback"] = value }
    var importCard: String
        get() = saved["importCard"] ?: ""
        set(value) { saved["importCard"] = value }
    var importMain: Uri?
        get() = saved["importMain"]
        set(value) { saved["importMain"] = value }
    var exportSrt = ""
    var exportLog = ""

    init {
        refresh()
        viewModelScope.launch {
            if (models.hasLegacyFiles()) {
                mutable.update { it.copy(checkingLegacy = true) }
                val found = runCatching { runInterruptible(Dispatchers.IO) { models.migrateLegacy() } }.getOrNull()
                mutable.update { it.copy(checkingLegacy = false, legacyUnknown = found == null && models.hasLegacyFiles()) }
                refresh()
            }
            languages()
        }
        viewModelScope.launch {
            while (true) {
                mutable.update {
                    it.copy(
                        service = DictationService.status,
                        gateway = DictationService.gateway,
                        requests = DictationService.requestCount(),
                        connectionsQueued = DictationService.gatewayQueue(),
                        inferenceQueued = DictationService.inferenceQueue(),
                        jobs = DictationService.taskCount(),
                        backend = DictationService.backend(),
                        runningModel = DictationService.modelId(),
                        logs = RuntimeLog.snapshot()
                    )
                }
                delay(350)
            }
        }
    }

    private fun refresh() {
        val present = Models.CARDS.mapNotNull { card -> models.installed(card)?.let { card.id() to it } }.toMap()
        mutable.update {
            it.copy(
                installed = present,
                selected = prefs.getString("modelId", "")!!.ifBlank {
                    present.keys.firstOrNull() ?: Models.SMALL.id()
                },
                available = app.filesDir.usableSpace,
                setupStep = prefs.getInt("setupStep", 0),
                external = prefs.getString("asr", "")!!,
                source = prefs.getString("source", "auto")!!,
                target = prefs.getString("target", "简体中文")!!,
                translation = prefs.getString("translationMode", "offline")!!,
                audioDecoder = prefs.getString("audioDecoder", "auto")!!,
                allowPrivateAudio = prefs.getBoolean("allowPrivateAudio", true),
                kikoetaLanSource = prefs.getString("kikoetaLanSource", "")!!,
                kikoetaOutput = prefs.getString("kikoetaOutput", "source")!!
            )
        }
    }

    private suspend fun languages() {
        runCatching { runInterruptible(Dispatchers.IO) { Translator.Offline.installed() } }
            .onSuccess { set -> mutable.update { it.copy(languages = set) } }
    }

    fun notify(message: String) { mutable.update { it.copy(notice = message) } }
    fun clearImport() { importMain = null; importCard = "" }
    fun clearLogs() { RuntimeLog.clear(); mutable.update { it.copy(logs = emptyList()) } }

    fun setup(step: Int) {
        prefs.edit().putInt("setupStep", step).apply()
        refresh()
    }

    fun select(card: Models.Card) {
        prefs.edit().putString("modelId", card.id()).apply()
        refresh()
    }

    fun saveAudioDecoder(decoder: String) {
        prefs.edit().putString("audioDecoder", decoder).apply()
        refresh()
        notify("解码器设置已应用于下一次文件解码")
    }

    fun saveKikoetaOutput(mode: String) {
        val normalized = when (mode) {
            "source", "bilingual", "translation" -> mode
            else -> "source"
        }
        prefs.edit().putString("kikoetaOutput", normalized).apply()
        refresh()
        val label = when (normalized) {
            "bilingual" -> "双语（原文 / 译文）"
            "translation" -> "仅译文"
            else -> "仅原文（跳过翻译）"
        }
        notify("Kikoeta 输出已设为：$label")
    }

    fun saveAudioFetchPolicy(allowPrivate: Boolean, rawOrigins: String): Boolean {
        val trimmed = rawOrigins.trim()
        if (trimmed.isNotBlank()) {
            val origins = runCatching { AudioFetch.parseOrigins(trimmed) }.getOrElse {
                notify("内网来源白名单需填写有效的 HTTP(S) 地址")
                return false
            }
            if (origins.any { it.rawQuery != null || it.path !in listOf("", "/") }) {
                notify("内网来源白名单仅填写协议、主机和端口，例如 http://192.168.1.50:8888")
                return false
            }
        }
        prefs.edit()
            .putBoolean("allowPrivateAudio", allowPrivate)
            .putString("kikoetaLanSource", trimmed)
            .apply()
        refresh()
        return true
    }

    fun install(card: Models.Card, main: Uri? = null, projector: Uri? = null, mobile: Boolean = false) {
        runTask(if (main == null) "连接下载服务器…" else "正在导入配套文件…", "模型配套文件已通过 SHA-256 校验") {
            models.install(card, main, projector, mobile) { progress ->
                mutable.update { it.copy(progress = progress) }
            }
            if (prefs.getString("modelId", "")!!.isBlank() || mutable.value.installed.isEmpty()) {
                prefs.edit().putString("modelId", card.id()).apply()
            }
        }
    }

    fun uninstall(pair: Models.Pair) {
        if (DictationService.status.starting() || DictationService.modelId() == pair.card().id()) {
            notify("请先释放正在使用的模型")
            return
        }
        runTask("正在卸载…", "模型已卸载") {
            models.uninstall(pair)
            if (prefs.getString("modelId", "") == pair.card().id()) prefs.edit().remove("modelId").apply()
        }
    }

    fun cancel() { operation?.cancel() }

    fun downloadLanguages(mobile: Boolean) {
        val pending = mutable.value.missingLanguages
        runTask("正在下载端侧语言包…", "离线语言包已就绪") {
            pending.forEachIndexed { i, language ->
                mutable.update {
                    it.copy(
                        progress = Models.Progress(
                            "下载离线语言包 · " + languageName(language),
                            i.toLong(),
                            pending.size.toLong(),
                            0
                        )
                    )
                }
                Translator.Offline.download(language, mobile)
            }
        }
    }

    fun enumerate() = runTask("正在枚举运行时设备…", "设备枚举完成") {
        val devices = NativeRuntime.probe(app)
        mutable.update { it.copy(devices = devices) }
    }

    private fun runTask(working: String, done: String, block: () -> Unit) {
        if (operation?.isCompleted == false) {
            notify("已有配置任务在运行")
            return
        }
        mutable.update { it.copy(progress = Models.Progress(working, 0, 0, 0)) }
        operation = viewModelScope.launch {
            try {
                runInterruptible(Dispatchers.IO, block)
                notify(done)
            } catch (cancelled: CancellationException) {
                if ("语言包" in working) notify("已停止等待，系统可能继续下载离线包")
                else notify("已取消，下载断点已保留")
                throw cancelled
            } catch (error: Exception) {
                val message = if ("语言包" in working) "端侧语言包下载失败（" + error.javaClass.simpleName + "）"
                else error.message?.takeIf { "http" !in it.lowercase() && "://" !in it } ?: "操作失败"
                notify(message)
            } finally {
                mutable.update { it.copy(progress = null) }
                refresh()
            }
            languages()
        }
    }

    fun saveInference(device: String, numbers: List<String>, external: String): Boolean {
        val keys = listOf("threads", "batch", "context", "output", "layers")
        val ranges = listOf(1..32, 32..2048, 512..8192, 32..512, 1..99)
        val values = numbers.map { it.toIntOrNull() ?: 0 }
        if (values.zip(ranges).any { (value, range) -> value !in range } ||
            values[1] > values[2] || values[3] >= values[2]
        ) {
            notify("请检查线程、批尺寸、上下文、输出和卸载层范围")
            return false
        }
        val url = runCatching { external.trim().ifBlank { "" }.let { if (it.isBlank()) it else Inference.baseUrl(it) } }
            .getOrElse { notify("请填写有效的外部引擎地址"); return false }
        val settings = prefs.edit().putString("device", device).putString("asr", url)
        keys.zip(values).forEach { (key, value) -> settings.putInt(key, value) }
        settings.apply()
        refresh()
        notify("已保存，下次加载模型时生效")
        return true
    }

    fun resetInference() {
        prefs.edit().remove("device").remove("threads").remove("batch").remove("context")
            .remove("output").remove("layers").remove("asr").apply()
        refresh()
        notify("已恢复默认，下次加载模型时生效")
    }

    fun saveTranslation(mode: String, source: String, target: String, url: String, id: String, key: String) {
        try {
            val base = if (url.isBlank()) "" else Inference.baseUrl(url)
            if (mode == "online" && (base.isBlank() || id.isBlank())) {
                notify("联网翻译需要 API 地址和模型 ID")
                return
            }
            val endpoint = if (base.isBlank()) null else URI.create(base)
            val hasKey = key.isNotBlank() || (mode == "online" && Secrets.get(app, "key", false).isNotBlank())
            if (hasKey && endpoint?.scheme == "http" && endpoint.host !in listOf("localhost", "127.0.0.1", "[::1]")) {
                notify("使用远程 API Key 时，翻译地址须使用 HTTPS")
                return
            }
            if (key.isNotBlank()) Secrets.set(app, "key", key.trim())
            prefs.edit().putString("translationMode", mode).putString("source", source).putString("target", target)
                .putString("translation", base).putString("model", id.trim()).apply()
            refresh()
            notify("已保存，下次加载模型时生效")
        } catch (error: Exception) {
            notify("无法保存翻译设置，请检查 API 地址或本机安全存储")
        }
    }

    fun testGateway() {
        val address = mutable.value.gateway.addresses().firstOrNull() ?: return
        val config = GatewayConfig.read(app)
        viewModelScope.launch {
            val code = runCatching {
                runInterruptible(Dispatchers.IO) {
                    val openai = !config.kikoeta() && config.openai()
                    val dash = !config.kikoeta() && !openai && config.dashscope()
                    if (!config.kikoeta() && !openai && !dash) return@runInterruptible 0
                    val path = if (dash) AudioApi.DASH_PATH else if (openai) "/v1/models" else "/api/v1/health"
                    val connection = URI.create(address + path).toURL().openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
                    try {
                        val secret = Secrets.get(app, if (dash) "dashKey" else if (openai) "openaiKey" else "password", true)
                        val auth = if (dash || openai) "Bearer " + secret else "Basic " +
                            android.util.Base64.encodeToString(("admin:" + secret).toByteArray(), android.util.Base64.NO_WRAP)
                        connection.setRequestProperty("Authorization", auth)
                        connection.connectTimeout = 3000
                        connection.readTimeout = 4000
                        if (dash) {
                            connection.requestMethod = "POST"
                            connection.doOutput = true
                            connection.setFixedLengthStreamingMode(2)
                            connection.outputStream.use { it.write("{}".toByteArray()) }
                        }
                        connection.responseCode
                    } finally {
                        connection.disconnect()
                    }
                }
            }.getOrDefault(-1)
            val connected = code == 200 || (!config.kikoeta() && !config.openai() && config.dashscope() && code == 400)
            mutable.update { it.copy(localCheckFailed = code == -1) }
            notify(
                when {
                    connected -> "本机连通测试通过"
                    code == 0 -> "请先启用至少一种协议"
                    code > 0 -> "连通测试返回 HTTP " + code
                    else -> "本机连接失败，请检查网关监听与系统后台限制"
                }
            )
        }
    }

    fun secret(name: String) = runCatching { Secrets.get(app, name, true) }
        .getOrElse { notify("无法读取本机安全存储"); "" }

    fun verified(card: String, tuning: NativeRuntime.Tuning) = NativeRuntime.verified(app, card, tuning)
    fun modelPrefs() = prefs
    fun runtimePresent() = NativeRuntime.executable(app).canExecute()
    fun deviceDetails() = "Android " + Build.VERSION.SDK_INT + " · " + Build.SUPPORTED_ABIS.joinToString(" / ")
}

internal val languages = linkedMapOf(
    "auto" to "自动检测",
    "简体中文" to "简体中文",
    "zh" to "中文",
    "ja" to "日语",
    "en" to "英语",
    "ko" to "韩语",
    "es" to "西班牙语",
    "fr" to "法语",
    "de" to "德语"
)

internal fun languageName(code: String) = languages[code] ?: code
