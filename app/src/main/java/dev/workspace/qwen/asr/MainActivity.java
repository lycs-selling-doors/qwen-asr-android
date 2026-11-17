package dev.workspace.qwen.asr;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;

public final class MainActivity extends Activity {
    private static final int INK = 0xff142b2a;
    private static final int SUBDUED = 0xff486661;
    private static final int PAPER = 0xfff1f6f2;
    private static final int GREEN = 0xff1e6b55;
    private static final int LIGHT = 0xffdceae2;
    private static final int PICK_MODEL = 101;
    private static final int PICK_PROJECTOR = 102;
    private static final int CAPTURE_PLAYBACK = 103;
    private static final int EXPORT_SRT = 104;
    private static final int AUDIO_PERMISSION = 105;
    private static final int NOTIFICATIONS = 106;
    private static final int OVERLAY = 107;

    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout root;
    private LinearLayout settings;
    private TextView status;
    private TextView liveStatus;
    private TextView caption;
    private ScrollView captionBox;
    private TextView models;
    private Button engine;
    private Button mic;
    private Button playback;
    private Button stop;
    private Button overlay;
    private Button export;
    private Button importModel;
    private Button importProjector;
    private EditText asr;
    private EditText translation;
    private EditText model;
    private EditText key;
    private EditText target;
    private boolean playbackRequested;
    private boolean importing;
    private String subtitleExport = "";
    private int rendered = -1;

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            main.postDelayed(this, 300);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if (state != null) playbackRequested = state.getBoolean("playbackRequested");
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        ScrollView page = new ScrollView(this);
        root = column();
        root.setBackgroundColor(PAPER);
        root.setPadding(dp(24), dp(22), dp(24), dp(28));
        page.addView(root);
        setContentView(page);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(android.view.WindowInsets.Type.systemBars()
                    | android.view.WindowInsets.Type.displayCutout());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });

        TextView name = label("Qwen 听写", 32, INK);
        name.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        root.addView(name);
        status = label("", 14, SUBDUED);
        add(root, status, 5);
        engine = button("启动服务", true, () -> {
            if (DictationService.status.ready() || DictationService.status.starting()) {
                command(DictationService.STOP, null);
            } else {
                startEngine();
            }
        });
        add(root, engine, 12);

        add(root, section("实时字幕"), 22);
        liveStatus = label("选择音源开始", 14, SUBDUED);
        add(root, liveStatus, 5);
        LinearLayout inputs = row();
        mic = button("麦克风", false, () -> requestCapture(false));
        playback = button("播放音频", false, () -> requestCapture(true));
        pair(inputs, mic, playback);
        add(root, inputs, 9);
        caption = label("识别到的字幕会显示在这里", 17, INK);
        caption.setPadding(dp(16), dp(13), dp(16), dp(15));
        caption.setTextIsSelectable(true);
        captionBox = new ScrollView(this);
        captionBox.setFillViewport(true);
        captionBox.setNestedScrollingEnabled(true);
        captionBox.setBackground(background(0xfffcfefa, LIGHT, 14));
        captionBox.addView(caption);
        LinearLayout.LayoutParams captionSize = new LinearLayout.LayoutParams(-1, dp(168));
        captionSize.topMargin = dp(11);
        root.addView(captionBox, captionSize);
        LinearLayout actions = row();
        stop = button("结束采集", false, () -> command(DictationService.STOP_LIVE, null));
        overlay = button("悬浮字幕", false, this::toggleOverlay);
        pair(actions, stop, overlay);
        add(root, actions, 9);
        export = button("导出 SRT", false, this::export);
        add(root, export, 5);

        add(root, section("Kikoeta 连接"), 20);
        Inference.Config config = DictationService.config(this);
        String address = "本地连接  127.0.0.1:2370\n账号  " + Wire.USER;
        TextView connection = label(address, 14, INK);
        connection.setLineSpacing(dp(3), 1);
        connection.setTextIsSelectable(true);
        add(root, connection, 6);
        Button password = button("显示密码", false, null);
        password.setOnClickListener(view -> {
            boolean hidden = connection.getText().length() == address.length();
            connection.setText(hidden ? address + "\n密码  " + config.password() : address);
            password.setText(hidden ? "隐藏密码" : "显示密码");
        });
        add(root, password, 5);

        Button showSettings = button("模型与翻译设置", false,
                () -> settings.setVisibility(settings.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        add(root, showSettings, 15);
        settings = column();
        settings.setVisibility(View.GONE);
        root.addView(settings);
        models = label("", 14, SUBDUED);
        add(settings, models, 8);
        LinearLayout imports = row();
        importModel = button("导入 GGUF", false, () -> pick(PICK_MODEL));
        importProjector = button("导入 mmproj", false, () -> pick(PICK_PROJECTOR));
        pair(imports, importModel, importProjector);
        add(settings, imports, 6);
        asr = field("外部 llama-server 地址", "留空使用 APK 内置引擎", config.asrUrl(), InputType.TYPE_TEXT_VARIATION_URI);
        translation = field("翻译 API 地址", "https://服务地址/v1", config.translationUrl(), InputType.TYPE_TEXT_VARIATION_URI);
        model = field("翻译模型 ID", "由翻译服务提供", config.translationModel(), 0);
        key = field("翻译 API Key", "本机无需密钥的服务可留空", config.apiKey(), InputType.TYPE_TEXT_VARIATION_PASSWORD);
        target = field("实时字幕目标语言", "简体中文", config.target(), 0);
        add(settings, label("翻译服务接收转写文本。Kikoeta 入库固定为简体中文，实时字幕可设置其他目标语言。",
                13, SUBDUED), 9);
        add(settings, button("保存设置", false, () -> {
            if (saveSettings()) toast(DictationService.status.ready() ? "已保存，重启服务后生效" : "已保存");
        }), 8);
        updateModels();
        render();
    }

    private void render() {
        DictationService.Status current = DictationService.status;
        if (current.change() == rendered) return;
        rendered = current.change();
        status.setText(current.message());
        engine.setText(current.ready() || current.starting() ? "停止服务" : "启动服务");
        engine.setEnabled(!importing);
        mic.setEnabled(current.ready() && !current.live());
        playback.setEnabled(current.ready() && !current.live());
        stop.setEnabled(current.live());
        overlay.setEnabled(current.live());
        export.setEnabled(!current.cues().isEmpty());
        importModel.setEnabled(!importing && !current.ready() && !current.starting());
        importProjector.setEnabled(importModel.isEnabled());
        liveStatus.setText(current.liveMessage().isBlank() ? "选择音源开始" : current.liveMessage());
        StringBuilder text = new StringBuilder();
        for (int i = Math.max(0, current.cues().size() - 6); i < current.cues().size(); i++) {
            Pcm.Cue cue = current.cues().get(i);
            if (text.length() > 0) text.append("\n\n");
            text.append(cue.source());
            if (!cue.translation().isBlank()) text.append('\n').append(cue.translation());
        }
        caption.setText(text.length() == 0 ? "识别到的字幕会显示在这里" : text);
        if (current.live()) captionBox.post(() -> captionBox.scrollTo(0, caption.getHeight()));
    }

    private void startEngine() {
        if (!saveSettings()) return;
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)) {
            getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply();
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATIONS);
        } else {
            command(DictationService.START, null);
        }
    }

    private boolean saveSettings() {
        String asrUrl = asr.getText().toString().strip();
        String translationUrl = translation.getText().toString().strip();
        String translationModel = model.getText().toString().strip();
        String apiKey = key.getText().toString().strip();
        String language = target.getText().toString().strip();
        try {
            if (!asrUrl.isEmpty()) asrUrl = Inference.baseUrl(asrUrl);
            if (!translationUrl.isEmpty()) {
                translationUrl = Inference.baseUrl(translationUrl);
                Uri uri = Uri.parse(translationUrl);
                if (!apiKey.isEmpty() && "http".equals(uri.getScheme())
                        && !Arrays.asList("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())) {
                    throw new IllegalArgumentException("带 API Key 的远程翻译服务请使用 HTTPS");
                }
            }
            if (translationUrl.isEmpty() != translationModel.isEmpty()) {
                throw new IllegalArgumentException("请同时填写翻译 API 地址和模型 ID");
            }
        } catch (IllegalArgumentException error) {
            toast(error.getMessage());
            return false;
        }
        getSharedPreferences(DictationService.PREFS, MODE_PRIVATE).edit()
                .putString("asr", asrUrl).putString("translation", translationUrl).putString("model", translationModel)
                .putString("key", apiKey).putString("target", language.isEmpty() ? "简体中文" : language).apply();
        return true;
    }

    private void requestCapture(boolean capturePlayback) {
        playbackRequested = capturePlayback;
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, AUDIO_PERMISSION);
        } else if (capturePlayback) {
            startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),
                    CAPTURE_PLAYBACK);
        } else {
            command(DictationService.MIC, null);
        }
    }

    private void toggleOverlay() {
        if (Settings.canDrawOverlays(this)) command(DictationService.OVERLAY, null);
        else startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName())), OVERLAY);
    }

    private void command(String action, Intent permission) {
        Intent intent = new Intent(this, DictationService.class).setAction(action);
        if (permission != null) intent.putExtra(DictationService.PROJECTION_DATA, permission);
        if (action.equals(DictationService.START)) startForegroundService(intent);
        else startService(intent);
    }

    @Override public void onRequestPermissionsResult(int code, String[] names, int[] values) {
        super.onRequestPermissionsResult(code, names, values);
        if (code == NOTIFICATIONS) command(DictationService.START, null);
        if (code == AUDIO_PERMISSION && values.length > 0 && values[0] == PackageManager.PERMISSION_GRANTED) {
            requestCapture(playbackRequested);
        }
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("playbackRequested", playbackRequested);
    }

    private void pick(int request) {
        startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*")
                .addCategory(Intent.CATEGORY_OPENABLE), request);
    }

    private void export() {
        subtitleExport = Pcm.srt(DictationService.status.cues());
        startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("application/x-subrip")
                .addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, "Qwen字幕.srt"), EXPORT_SRT);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == OVERLAY && Settings.canDrawOverlays(this) && DictationService.status.live()) {
            command(DictationService.OVERLAY, null);
        }
        if (result != RESULT_OK || data == null) return;
        if (request == CAPTURE_PLAYBACK) command(DictationService.PLAYBACK, data);
        if (request == PICK_MODEL || request == PICK_PROJECTOR) {
            copyModel(data.getData(), request == PICK_MODEL ? Inference.MODEL : Inference.PROJECTOR);
        }
        if (request == EXPORT_SRT && data.getData() != null) {
            try (var output = getContentResolver().openOutputStream(data.getData(), "wt")) {
                if (output == null) throw new IOException();
                String srt = subtitleExport.isEmpty() ? Pcm.srt(DictationService.status.cues()) : subtitleExport;
                output.write(srt.getBytes(StandardCharsets.UTF_8));
                toast("SRT 已导出");
            } catch (IOException error) { toast("写入 SRT 失败"); }
        }
    }

    private void copyModel(Uri uri, String name) {
        if (uri == null || importing || DictationService.status.ready() || DictationService.status.starting()) return;
        importing = true;
        models.setText("正在导入模型…");
        rendered = -1;
        render();
        new Thread(() -> {
            File temp = new File(getFilesDir(), name + ".partial");
            try (var input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new IOException("无法读取文件");
                byte[] header = new byte[4];
                new java.io.DataInputStream(input).readFully(header);
                if (!Arrays.equals(header, new byte[]{'G', 'G', 'U', 'F'})) throw new IOException("文件不是 GGUF");
                try (var output = Files.newOutputStream(temp.toPath())) {
                    output.write(header);
                    input.transferTo(output);
                }
                Files.move(temp.toPath(), new File(getFilesDir(), name).toPath(),
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException error) {
                runOnUiThread(() -> toast("导入失败，" + error.getMessage()));
            } finally {
                temp.delete();
                runOnUiThread(() -> {
                    importing = false;
                    updateModels();
                    rendered = -1;
                    render();
                });
            }
        }, "Import GGUF").start();
    }

    private void updateModels() {
        models.setText("主模型 " + installed(Inference.MODEL) + "  ·  mmproj " + installed(Inference.PROJECTOR));
    }

    private String installed(String name) { return new File(getFilesDir(), name).isFile() ? "已导入" : "未导入"; }

    @Override protected void onResume() { super.onResume(); main.post(refresh); }
    @Override protected void onPause() { main.removeCallbacks(refresh); super.onPause(); }

    private EditText field(String title, String hint, String value, int variant) {
        add(settings, label(title, 13, INK), 9);
        EditText input = new EditText(this);
        input.setTextSize(15);
        input.setTextColor(INK);
        input.setHintTextColor(SUBDUED);
        input.setSingleLine();
        input.setHint(hint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | variant);
        input.setText(value);
        input.setBackground(background(0xfffcfefa, LIGHT, 8));
        input.setPadding(dp(11), dp(9), dp(11), dp(9));
        add(settings, input, 4);
        return input;
    }

    private TextView section(String text) {
        TextView title = label(text, 19, INK);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return title;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private Button button(String text, boolean primary, Runnable action) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setText(text);
        button.setTextSize(14);
        button.setMinHeight(dp(48));
        button.setTextColor(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xff657970, primary ? 0xffffffff : INK}));
        button.setBackgroundTintList(new ColorStateList(new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{0xffe5ebe6, primary ? GREEN : LIGHT}));
        if (action != null) button.setOnClickListener(view -> action.run());
        return button;
    }

    private GradientDrawable background(int color, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), border);
        return shape;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout row() { return new LinearLayout(this); }

    private void pair(LinearLayout row, View left, View right) {
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, -2, 1);
        first.rightMargin = dp(8);
        row.addView(left, first);
        row.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
    }

    private void add(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(top);
        parent.addView(child, params);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private void toast(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }
}
