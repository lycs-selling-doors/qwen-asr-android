package dev.workspace.qwen.asr;

import android.content.Context;
import android.os.SystemClock;
import android.content.SharedPreferences;

final class PerformanceLimit {
    private static final String LEGACY_ENABLED = "limitInferencePerformance";
    private static final String PRESET = "inferencePerformancePreset";
    private static final long CHECKPOINT_MS = 100;

    enum Preset {
        FOREGROUND("foreground", "保护前台", 256, 1, 300),
        BALANCED("balanced", "均衡", 256, 1, 0),
        FULL_SPEED("full_speed", "全速", 512, 0, 0);

        final String id;
        final String label;
        final int microBatchLimit;
        final int niceAdjustment;
        final int bulkGapMillis;

        Preset(String id, String label, int microBatchLimit, int niceAdjustment, int bulkGapMillis) {
            this.id = id;
            this.label = label;
            this.microBatchLimit = microBatchLimit;
            this.niceAdjustment = niceAdjustment;
            this.bulkGapMillis = bulkGapMillis;
        }

        NativeSetup configure(NativeRuntime.Tuning tuning) {
            NativeRuntime.Tuning effective = tuning;
            if (this == FOREGROUND) {
                effective = new NativeRuntime.Tuning(tuning.device(), Math.min(tuning.threads(), 4),
                        Math.min(tuning.batch(), 512), tuning.context(), tuning.output(), tuning.layers());
            }
            return new NativeSetup(effective, Math.min(effective.batch(), microBatchLimit), niceAdjustment);
        }
    }

    record NativeSetup(NativeRuntime.Tuning tuning, int microBatch, int niceAdjustment) {}

    record Status(boolean available, String selectedPreset, String activePreset, boolean reloadRequired,
                  int nativeThreads, int nativeBatch, int nativeMicroBatch, int nativeNiceAdjustment,
                  int bulkGapMillis) {
        boolean enabled() { return available && Preset.FOREGROUND.id.equals(selectedPreset); }
    }

    private long lastBulkFinishedAt;
    private volatile boolean waiting;

    static Preset selected(Context context) {
        SharedPreferences prefs = DictationService.prefs(context);
        Preset preset = fromId(prefs.getString(PRESET, ""));
        if (preset != null) return preset;
        return prefs.getBoolean(LEGACY_ENABLED, false) ? Preset.FOREGROUND : Preset.BALANCED;
    }

    static void save(Context context, String id) {
        Preset preset = fromId(id);
        if (preset == null) throw new IllegalArgumentException("无效的性能预设");
        DictationService.prefs(context).edit().putString(PRESET, preset.id).remove(LEGACY_ENABLED).apply();
    }

    private static Preset fromId(String id) {
        return switch (id) {
            case "foreground" -> Preset.FOREGROUND;
            case "balanced" -> Preset.BALANCED;
            case "full_speed" -> Preset.FULL_SPEED;
            default -> null;
        };
    }

    static Status status(boolean available, Preset selected, Preset active, NativeSetup setup,
                         boolean niceApplied) {
        if (!available) return new Status(false, selected.id, "", false, 0, 0, 0, 0, 0);
        return new Status(true, selected.id, active == null ? "" : active.id,
                active != null && selected != active, setup.tuning().threads(), setup.tuning().batch(),
                setup.microBatch(), niceApplied ? setup.niceAdjustment() : 0, selected.bulkGapMillis);
    }

    boolean waiting() { return waiting; }

    // Called only inside the serialized local batch inference path.
    void await(Context context, Runnable checkpoint) throws InterruptedException {
        try {
            while (true) {
                long remaining = lastBulkFinishedAt + selected(context).bulkGapMillis
                        - SystemClock.elapsedRealtime();
                if (remaining <= 0) break;
                waiting = true;
                checkpoint.run();
                Thread.sleep(Math.min(remaining, CHECKPOINT_MS));
            }
        } finally {
            waiting = false;
        }
        checkpoint.run();
    }

    void complete() {
        lastBulkFinishedAt = SystemClock.elapsedRealtime();
    }
}
