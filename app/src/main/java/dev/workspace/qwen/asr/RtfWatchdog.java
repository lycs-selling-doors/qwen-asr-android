package dev.workspace.qwen.asr;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Arrays;

final class RtfWatchdog {
    private static final String ENABLED = "rtfWatchdogEnabled";
    private static final int WINDOW = 9;
    private static final int MIN_SAMPLES = 5;
    private static final int INCIDENT_HISTORY = 24;

    record Settings(boolean enabled, Double manualLimit) {}
    record Status(String modelId, String backend, Settings settings, double rtfLimit, double overheadSeconds) {}
    record Limit(double rtf, double overheadSeconds) {
        boolean exceeded(double audioSeconds, double elapsedSeconds) {
            return elapsedSeconds > audioSeconds * rtf + overheadSeconds;
        }
    }

    private final ArrayDeque<Double> healthy = new ArrayDeque<>(WINDOW);

    static double audioSeconds(byte[] pcm) {
        return pcm.length / (2.0 * Pcm.RATE);
    }

    static Settings read(Context context, String modelId, String backend) {
        SharedPreferences prefs = DictationService.prefs(context);
        String stored = prefs.getString(key(modelId, backend), "");
        Double limit = null;
        if (!stored.isEmpty()) {
            try {
                double value = Double.parseDouble(stored);
                if (Double.isFinite(value) && value > 0) limit = value;
            } catch (NumberFormatException ignored) {
                // Treat an invalid stored threshold as automatic.
            }
        }
        return new Settings(prefs.getBoolean(ENABLED, true), limit);
    }

    static void save(Context context, String modelId, String backend, boolean enabled, Double manualLimit) {
        if (manualLimit != null && (!Double.isFinite(manualLimit) || manualLimit <= 0)) {
            throw new IllegalArgumentException("RTF 预警值必须是有限正数");
        }
        var edit = DictationService.prefs(context).edit().putBoolean(ENABLED, enabled);
        String key = key(modelId, backend);
        if (manualLimit == null) edit.remove(key);
        else edit.putString(key, Double.toString(manualLimit));
        edit.apply();
    }

    private static String key(String modelId, String backend) {
        return "rtf.limit." + modelId + "|" + backend;
    }

    synchronized Limit limit(Settings settings, String backend) {
        if (settings.manualLimit() != null) return new Limit(settings.manualLimit(), 0);
        if (healthy.size() < MIN_SAMPLES) {
            return new Limit("CPU".equals(backend) ? 1.2 : 0.8, 1);
        }
        double[] values = healthy.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(values);
        int mid = values.length / 2;
        double median = values.length % 2 == 0 ? (values[mid - 1] + values[mid]) / 2 : values[mid];
        return new Limit(3 * median, 1);
    }

    synchronized void healthy(double audioSeconds, double rtf) {
        if (audioSeconds < 4 || rtf <= 0) return;
        if (healthy.size() == WINDOW) healthy.removeFirst();
        healthy.addLast(rtf);
    }

    static synchronized void record(Context context, String level, String run, String model, String message) {
        RuntimeLog.add(level, "RTF 预警", run, model, message);
        var history = context.getSharedPreferences("rtf-incidents", Context.MODE_PRIVATE);
        String[] previous = history.getString("events", "").split("\n");
        var lines = new StringBuilder();
        for (int i = Math.max(0, previous.length - INCIDENT_HISTORY + 1); i < previous.length; i++) {
            if (!previous[i].isEmpty()) lines.append(previous[i]).append('\n');
        }
        lines.append(Instant.now()).append(" · ").append(level).append(" · ").append(run)
                .append(" · ").append(model).append(" · ").append(message);
        history.edit().putString("events", lines.toString()).apply();
    }
}
