package dev.workspace.qwen.asr;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

final class RuntimeLog {
    record Event(long id, long time, String level, String component, String run, String model, String message) {
        String display() {
            return new SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(new Date(time)) + "  "
                    + level + "  " + component + "  " + run + "  " + model + "  " + message;
        }
    }
    private static final ArrayDeque<Event> events = new ArrayDeque<>();
    private static long sequence;

    // Callers supply fixed messages and parsed numeric or enumerated fields only.
    static synchronized void add(String level, String component, String run, String model, String message) {
        if (events.size() == 400) events.removeFirst();
        events.addLast(new Event(++sequence, System.currentTimeMillis(), level, component, run, model, message));
    }
    static synchronized List<Event> snapshot() { return List.copyOf(events); }
    static synchronized void clear() { events.clear(); }
}
