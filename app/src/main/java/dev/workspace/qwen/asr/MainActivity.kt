package dev.workspace.qwen.asr

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.provider.Settings
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val model by viewModels<DictationModel>()
    private val audioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) launchCapture() else model.notify("麦克风权限未授权，可以重新选择开始")
    }
    private val playbackPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val grant = result.data
        if (result.resultCode == RESULT_OK && grant != null) send(DictationService.PLAYBACK) {
            putExtra(DictationService.PROJECTION_DATA, grant)
        } else model.notify("播放音频捕获未授权")
    }
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (Settings.canDrawOverlays(this) && DictationService.status.live()) send(DictationService.OVERLAY)
        else if (!Settings.canDrawOverlays(this)) model.notify("悬浮窗权限未授权")
    }
    private val importMain = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            model.importMain = uri
            importProjector.launch(arrayOf("application/octet-stream", "*/*"))
        } else model.clearImport()
    }
    private val importProjector = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val card = Models.CARDS.find { it.id() == model.importCard }
        val main = model.importMain
        if (uri != null && card != null && main != null) model.install(card, main, uri)
        model.clearImport()
    }
    private val exportSrt = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-subrip")) {
        export(it, model.exportSrt)
    }
    private val exportLog = registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) {
        export(it, model.exportLog)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                model.state.collect { ui ->
                    if (!ui.service.starting() && model.awaitingCapture &&
                        ui.service.change() > model.captureAfterChange) {
                        model.awaitingCapture = false
                        if (ui.service.ready()) requestCapture(model.capturePlayback)
                    }
                }
            }
        }
        setContent { DictationApp(model, this) }
    }

    private fun send(action: String, data: Intent.() -> Unit = {}) {
        val intent = Intent(this, DictationService::class.java).setAction(action).apply(data)
        try {
            if (action in listOf(DictationService.START, DictationService.PROBE, DictationService.GATEWAY_START)) {
                startForegroundService(intent)
            } else startService(intent)
        } catch (error: IllegalStateException) {
            model.awaitingCapture = false
            model.notify("系统暂时无法启动服务，请在应用保持前台时重试")
        } catch (error: SecurityException) {
            model.awaitingCapture = false
            model.notify("系统拒绝启动服务，请检查应用权限")
        }
    }

    internal fun startCapture(playback: Boolean) {
        if (playback && getSystemService(MediaProjectionManager::class.java) == null) {
            model.notify("设备不支持播放音频捕获")
            return
        }
        if (DictationService.status.ready()) requestCapture(playback)
        else {
            model.capturePlayback = playback
            model.captureAfterChange = DictationService.status.change()
            model.awaitingCapture = true
            send(DictationService.START)
        }
    }

    private fun requestCapture(playback: Boolean) {
        model.capturePlayback = playback
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) launchCapture()
        else audioPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun launchCapture() {
        if (!model.capturePlayback) send(DictationService.MIC)
        else {
            val manager = getSystemService(MediaProjectionManager::class.java)
            if (manager == null) model.notify("设备不支持播放音频捕获")
            else try {
                playbackPermission.launch(manager.createScreenCaptureIntent())
            } catch (error: RuntimeException) {
                model.notify("系统无法启动播放音频捕获")
            }
        }
    }

    internal fun stopCapture() = send(DictationService.STOP_LIVE)
    internal fun preload(probe: Boolean = false) = send(if (probe) DictationService.PROBE else DictationService.START)
    internal fun release() = send(DictationService.RELEASE)
    internal fun gateway(on: Boolean) = send(if (on) DictationService.GATEWAY_START else DictationService.GATEWAY_STOP)
    internal fun updateGateway(config: GatewayConfig) = send(DictationService.GATEWAY_SAVE) { config.put(this) }

    internal fun overlay() {
        if (Settings.canDrawOverlays(this)) send(DictationService.OVERLAY)
        else overlayPermission.launch(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))
    }

    internal fun import(card: Models.Card) {
        model.importCard = card.id()
        importMain.launch(arrayOf("application/octet-stream", "*/*"))
    }

    internal fun exportSubtitles(sourceOnly: Boolean) {
        val cues = DictationService.status.cues()
        model.exportSrt = Pcm.srt(if (sourceOnly) cues.map { Pcm.Cue(it.startMs(), it.endMs(), it.source(), "") } else cues)
        exportSrt.launch("Qwen字幕.srt")
    }

    internal fun exportLogs(value: String) {
        model.exportLog = value
        exportLog.launch("Qwen运行日志.txt")
    }

    private fun export(uri: Uri?, value: String) {
        if (uri == null) return
        lifecycleScope.launch {
            val success = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    requireNotNull(contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(value.toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            model.notify(if (success) "已导出" else "写入文件失败")
        }
    }

    internal fun copy(label: String, value: String, secret: Boolean = false) {
        if (value.isBlank()) return
        val clip = ClipData.newPlainText(label, value)
        if (secret) clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true)
        }
        getSystemService(ClipboardManager::class.java).setPrimaryClip(clip)
    }

    internal fun systemAppSettings() = startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri()))
    private fun packageUri() = Uri.parse("package:$packageName")
}
