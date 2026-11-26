package dev.workspace.qwen.asr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DictationService extends Service {
    static final String START = "start";
    static final String STOP = "stop";
    static final String RELEASE = "release";
    static final String PROBE = "probe";
    static final String GATEWAY_START = "gateway_start";
    static final String GATEWAY_STOP = "gateway_stop";
    static final String GATEWAY_SAVE = "gateway_save";
    static final String MIC = "mic";
    static final String PLAYBACK = "playback";
    static final String STOP_LIVE = "stop_live";
    static final String OVERLAY = "overlay";
    static final String PROJECTION_DATA = "projection_data";
    static final String PREFS = "dictation";
    private static final String CHANNEL = "dictation";

    record Status(String message, boolean starting, boolean ready, boolean live,
                  String liveMessage, List<Pcm.Cue> cues, int change) {}
    static volatile Status status = new Status("模型未加载", false, false, false, "", List.of(), 0);
    record Gateway(List<String> addresses, String error) {}
    static volatile Gateway gateway = new Gateway(List.of(), "");
    private static volatile Wire activeWire;
    private static volatile Jobs activeJobs;
    private static volatile Inference activeEngine;

    static String backend() { var engine = activeEngine; return engine == null ? "" : engine.backend(); }
    static String modelId() { var engine = activeEngine; return engine == null ? "" : engine.modelId(); }
    static long requestCount() { var wire = activeWire; return wire == null ? 0 : wire.requests(); }
    static int gatewayQueue() { var wire = activeWire; return wire == null ? 0 : wire.pending(); }
    static int inferenceQueue() { var engine = activeEngine; return engine == null ? 0 : engine.pending(); }
    static long taskCount() { var jobs = activeJobs; return jobs == null ? 0 : jobs.pending(); }

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService initializer = Executors.newSingleThreadExecutor();
    private Inference engine;
    private volatile Inference loading;
    private volatile boolean destroyed;
    private Jobs jobs;
    private Wire wire;
    private LiveCapture capture;
    private MediaProjection projection;
    private android.widget.TextView overlay;
    private final List<Pcm.Cue> cues = new ArrayList<>();

    static android.content.SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE);
    }

    static NativeRuntime.Tuning tuning(Context context) {
        var prefs = prefs(context);
        return new NativeRuntime.Tuning(prefs.getString("device", "auto"),
                prefs.getInt("threads", Math.min(6, Runtime.getRuntime().availableProcessors())),
                prefs.getInt("batch", 512), prefs.getInt("context", 2048), prefs.getInt("output", 256),
                prefs.getInt("layers", 99));
    }

    static Inference.Config config(Context context) {
        var prefs = prefs(context);
        String mode = prefs.getString("translationMode", "offline");
        return new Inference.Config(prefs.getString("asr", ""), prefs.getString("translation", ""),
                prefs.getString("model", ""), mode.equals("online") ? Secrets.get(context, "key", false) : "",
                prefs.getString("source", "auto"), prefs.getString("target", "简体中文"),
                mode, prefs.getString("modelId", ""), tuning(context));
    }

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "听写服务", NotificationManager.IMPORTANCE_LOW));
        cues.addAll(status.cues());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) return START_NOT_STICKY;
        try {
            switch (intent.getAction()) {
                case START -> startEngine(false);
                case PROBE -> startEngine(true);
                case GATEWAY_START -> updateGateway(GatewayConfig.read(this), true);
                case GATEWAY_SAVE -> updateGateway(GatewayConfig.from(intent), false);
                case RELEASE -> releaseEngine();
                case GATEWAY_STOP -> {
                    closeGateway();
                    if (engine == null && !status.starting() && (jobs == null || jobs.pending() == 0)) stopSelf();
                }
                case STOP -> {
                    update("服务未启动", false, false, false, "");
                    stopSelf();
                }
                case MIC -> startCapture(false, null);
                case PLAYBACK -> startCapture(true, intent.getParcelableExtra(PROJECTION_DATA, Intent.class));
                case OVERLAY -> toggleOverlay();
                case STOP_LIVE -> {
                    if (capture != null) {
                        capture.stopInput();
                        update(status.message(), false, true, true, "正在处理最后一段");
                    }
                }
            }
        } catch (Exception error) {
            if (!status.ready()) {
                update(message(error), false, false, false, "");
                if (wire == null) stopSelf();
            } else {
                closeProjection();
                foreground(0);
                update(status.message(), false, true, false, message(error));
            }
        }
        return START_NOT_STICKY;
    }

    private void ensureJobs() throws java.io.IOException {
        if (jobs != null) return;
        jobs = new Jobs(getApplicationContext());
        activeJobs = jobs;
    }

    private void updateGateway(GatewayConfig config, boolean start) {
        try {
            var policy = config.policy();
            if (start || wire != null) {
                foreground(captureType);
                ensureJobs();
                if (wire == null) wire = new Wire(this, jobs, policy);
                else wire.rebind(policy);
                activeWire = wire;
                gateway = new Gateway(wire.addresses(), "");
                RuntimeLog.add("INFO", "网关", "", "", "监听已启用，端口 " + policy.port());
            } else {
                gateway = new Gateway(List.of(), "");
            }
            config.save(this);
        } catch (Exception error) {
            gateway = new Gateway(wire == null ? List.of() : wire.addresses(), message(error));
            RuntimeLog.add("ERROR", "网关", "", "", "无法应用监听配置");
        }
        if (wire == null && engine == null && !status.starting()) stopSelf();
    }

    private void closeGateway() {
        if (wire != null) wire.close();
        wire = null;
        activeWire = null;
        gateway = new Gateway(List.of(), "");
    }

    private void releaseEngine() {
        if (engine == null || capture != null) return;
        if ((wire != null && wire.busy()) || !jobs.detachIfIdle()) {
            update("仍有请求或任务在处理，请稍后释放模型", false, true, status.live(), status.liveMessage());
            return;
        }
        engine.close();
        engine = null;
        activeEngine = null;
        update("模型已释放", false, false, false, "");
        if (wire == null) stopSelf();
    }

    private void startEngine(boolean probe) throws java.io.IOException {
        if (engine != null || status.starting()) return;
        foreground(0);
        ensureJobs();
        Inference.Config settings = config(this);
        update(probe ? "正在加载模型并运行短音频自检…" : "正在连接或加载模型…", true, false, false, "");
        initializer.execute(() -> {
            Inference candidate = null;
            try {
                if (settings.asrUrl().isBlank()) new Models(this).migrateLegacy();
                candidate = new Inference(this, settings);
                loading = candidate;
                if (destroyed) {
                    candidate.close();
                    return;
                }
                candidate.awaitReady();
                if (probe) {
                    try (var sample = getAssets().open("probe-en.pcm")) {
                        String result = candidate.transcribe(Wire.readBounded(sample, 320_000), "en", false);
                        if (!result.toLowerCase(java.util.Locale.ROOT).matches("(?s).*\\bspeech\\b.*")) {
                            throw new java.io.IOException("短音频自检未识别出预期关键词");
                        }
                    }
                    if (settings.asrUrl().isBlank()) {
                        NativeRuntime.mark(this, candidate.modelId(), candidate.tuning(), true);
                    }
                    RuntimeLog.add("INFO", "自检", "", candidate.modelId(), "短音频识别通过");
                }
                Inference ready = candidate;
                main.post(() -> publish(ready, probe));
            } catch (Exception error) {
                if (candidate != null) {
                    if (settings.asrUrl().isBlank()) NativeRuntime.mark(this, candidate.modelId(), candidate.tuning(), false);
                    candidate.close();
                }
                RuntimeLog.add("ERROR", "推理", "", "", "模型加载或自检失败（" + error.getClass().getSimpleName() + "）");
                main.post(() -> {
                    if (destroyed) return;
                    loading = null;
                    update(message(error), false, false, false, "");
                    if (wire == null) stopSelf();
                });
            }
        });
    }

    private void publish(Inference ready, boolean probe) {
        if (destroyed) {
            ready.close();
            return;
        }
        loading = null;
        engine = ready;
        activeEngine = ready;
        jobs.setEngine(ready);
        update(probe ? "短音频自检通过 · " + ready.backend() : "模型已就绪 · " + ready.backend(), false, true, false, "");
        monitorEngine(ready);
    }

    private void monitorEngine(Inference current) {
        main.postDelayed(() -> {
            if (destroyed || engine != current) return;
            if (current.alive()) {
                monitorEngine(current);
                return;
            }
            if (capture != null) {
                captureSession++;
                capture.close();
                capture = null;
            }
            closeProjection();
            foreground(0);
            engine = null;
            activeEngine = null;
            jobs.setEngine(null);
            NativeRuntime.mark(this, current.modelId(), current.tuning(), false);
            current.close();
            RuntimeLog.add("ERROR", "推理", "", current.modelId(), "运行时提前退出");
            update("运行时已退出，请重试或切换 CPU", false, false, false, "");
            if (wire == null) stopSelf();
        }, 3000);
    }

    private int captureType;
    private long captureSession;

    private void startCapture(boolean playback, Intent permission) throws Exception {
        if (capture != null || engine == null || !status.ready()) return;
        if (playback && permission == null) throw new IllegalArgumentException("未授权捕获播放音频");
        foreground(playback ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                : ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        if (playback) {
            projection = getSystemService(MediaProjectionManager.class).getMediaProjection(android.app.Activity.RESULT_OK, permission);
            if (projection == null) throw new IllegalStateException("无法获取音频捕获会话");
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() {
                    if (capture != null) capture.stopInput();
                }
            }, main);
        }
        long session = ++captureSession;
        LiveCapture next = new LiveCapture(this, engine, projection, new LiveCapture.Listener() {
            @Override public void source(Pcm.Cue cue) {
                main.post(() -> {
                    if (destroyed || captureSession != session) return;
                    cues.add(cue);
                    update(status.message(), false, true, true, status.liveMessage());
                });
            }

            @Override public void translated(Pcm.Cue cue) {
                main.post(() -> {
                    if (destroyed || captureSession != session) return;
                    for (int i = cues.size() - 1; i >= 0; i--) {
                        if (cues.get(i).startMs() == cue.startMs()) {
                            cues.set(i, cue);
                            update(status.message(), false, true, true, status.liveMessage());
                            break;
                        }
                    }
                });
            }

            @Override public void status(String message) {
                main.post(() -> {
                    if (!destroyed && captureSession == session) update(status.message(), false, true, true, message);
                });
            }

            @Override public void finished() {
                main.post(() -> {
                    if (destroyed || captureSession != session) return;
                    capture = null;
                    closeProjection();
                    foreground(0);
                    update(status.message(), false, true, false,
                            status.liveMessage().startsWith("正在") ? "采集结束" : status.liveMessage());
                });
            }
        });
        capture = next;
        cues.clear();
        update(status.message(), false, true, true, playback ? "正在采集播放音频" : "正在采集麦克风");
    }

    private void foreground(int type) {
        Intent show = new Intent(this, MainActivity.class);
        Intent stop = new Intent(this, DictationService.class).setAction(STOP);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("Qwen 听写")
                .setContentText("Kikoeta 本机听写与实时字幕")
                .setContentIntent(PendingIntent.getActivity(this, 0, show, PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, "停止服务",
                        PendingIntent.getService(this, 1, stop, PendingIntent.FLAG_IMMUTABLE)).build())
                .setOngoing(true).build();
        int local = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
        startForeground(1, notification, local | type);
        captureType = type;
    }

    private void closeProjection() {
        if (projection == null) return;
        MediaProjection previous = projection;
        projection = null;
        previous.stop();
    }

    private void update(String message, boolean starting, boolean ready, boolean live, String liveMessage) {
        status = new Status(message, starting, ready, live, liveMessage, List.copyOf(cues), status.change() + 1);
        if (overlay != null) {
            if (!live) removeOverlay();
            else if (!cues.isEmpty()) {
                Pcm.Cue last = cues.get(cues.size() - 1);
                overlay.setText(last.translation().isBlank() ? last.source() : last.source() + "\n" + last.translation());
            }
        }
    }

    private void toggleOverlay() {
        if (overlay != null) {
            removeOverlay();
            return;
        }
        if (!status.live() || !android.provider.Settings.canDrawOverlays(this)) return;
        var text = new android.widget.TextView(this);
        text.setText("正在等待语音…");
        text.setBackgroundColor(0xff101821);
        text.setTextColor(0xffffffff);
        text.setTextSize(18);
        text.setMaxLines(6);
        float density = getResources().getDisplayMetrics().density;
        text.setPadding((int) (18 * density), (int) (10 * density), (int) (18 * density), (int) (10 * density));
        text.setGravity(android.view.Gravity.CENTER);
        var params = new android.view.WindowManager.LayoutParams(-1, -2,
                android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                android.graphics.PixelFormat.TRANSLUCENT);
        params.gravity = android.view.Gravity.BOTTOM;
        params.y = (int) (72 * density);
        params.alpha = 0.78f;
        try {
            getSystemService(android.view.WindowManager.class).addView(text, params);
            overlay = text;
            update(status.message(), status.starting(), status.ready(), status.live(), status.liveMessage());
        } catch (RuntimeException error) {
            update(status.message(), false, true, true, "无法显示悬浮字幕");
        }
    }

    private void removeOverlay() {
        if (overlay == null) return;
        getSystemService(android.view.WindowManager.class).removeView(overlay);
        overlay = null;
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    @Override public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        removeOverlay();
        if (capture != null) capture.close();
        closeProjection();
        if (wire != null) closeGateway();
        if (jobs != null) jobs.close();
        activeJobs = null;
        activeEngine = null;
        if (engine != null) engine.close();
        if (loading != null) loading.close();
        initializer.shutdownNow();
        if (status.ready() || status.starting()) update("服务未启动", false, false, false, "");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
