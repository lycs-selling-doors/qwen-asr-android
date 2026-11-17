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
    static final String MIC = "mic";
    static final String PLAYBACK = "playback";
    static final String STOP_LIVE = "stop_live";
    static final String OVERLAY = "overlay";
    static final String PROJECTION_DATA = "projection_data";
    static final String PREFS = "dictation";
    private static final String CHANNEL = "dictation";

    record Status(String message, boolean starting, boolean ready, boolean live,
                  String liveMessage, List<Pcm.Cue> cues, int change) {}
    static volatile Status status = new Status("服务未启动", false, false, false, "", List.of(), 0);
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

    static Inference.Config config(Context context) {
        var prefs = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String password = prefs.getString("password", "");
        if (password.isEmpty()) {
            password = Inference.newPassword();
            prefs.edit().putString("password", password).apply();
        }
        return new Inference.Config(prefs.getString("asr", ""), prefs.getString("translation", ""),
                prefs.getString("model", ""), prefs.getString("key", ""),
                prefs.getString("target", "简体中文"), password);
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
                case START -> startEngine();
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
                stopSelf();
            } else {
                closeProjection();
                foreground(0);
                update(status.message(), false, true, false, message(error));
            }
        }
        return START_NOT_STICKY;
    }

    private void startEngine() throws java.io.IOException {
        if (engine != null || status.starting()) return;
        foreground(0);
        Inference.Config settings = config(this);
        jobs = new Jobs(this);
        wire = new Wire(settings.password(), jobs);
        update("正在连接或加载模型…", true, false, false, "");
        initializer.execute(() -> {
            Inference candidate = null;
            try {
                if (!settings.translationUrl().isBlank()) Inference.baseUrl(settings.translationUrl());
                candidate = new Inference(this, settings);
                loading = candidate;
                if (destroyed) {
                    candidate.close();
                    return;
                }
                candidate.awaitReady();
                Inference ready = candidate;
                main.post(() -> publish(ready));
            } catch (Exception error) {
                if (candidate != null) candidate.close();
                main.post(() -> {
                    if (destroyed) return;
                    loading = null;
                    update(message(error), false, false, false, "");
                    stopSelf();
                });
            }
        });
    }

    private void publish(Inference ready) {
        if (destroyed) {
            ready.close();
            return;
        }
        loading = null;
        engine = ready;
        jobs.setEngine(ready);
        update("服务已启动 · 127.0.0.1:2370", false, true, false, "");
    }

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
        LiveCapture next = new LiveCapture(engine, projection, new LiveCapture.Listener() {
            @Override public void source(Pcm.Cue cue) {
                main.post(() -> {
                    if (destroyed) return;
                    cues.add(cue);
                    update(status.message(), false, true, true, status.liveMessage());
                });
            }

            @Override public void translated(Pcm.Cue cue) {
                main.post(() -> {
                    if (destroyed) return;
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
                    if (!destroyed) update(status.message(), false, true, true, message);
                });
            }

            @Override public void finished() {
                main.post(() -> {
                    if (destroyed) return;
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
        removeOverlay();
        if (capture != null) capture.close();
        closeProjection();
        if (wire != null) wire.close();
        if (jobs != null) jobs.close();
        if (engine != null) engine.close();
        if (loading != null) loading.close();
        initializer.shutdownNow();
        if (status.ready() || status.starting()) update("服务未启动", false, false, false, "");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
