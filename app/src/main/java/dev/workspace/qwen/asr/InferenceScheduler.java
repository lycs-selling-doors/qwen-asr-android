package dev.workspace.qwen.asr;

import java.util.concurrent.Callable;

final class InferenceScheduler {
    private boolean running;
    private int liveWaiting;
    private int waiting;

    synchronized int pending() { return waiting; }

    <T> T run(boolean live, Callable<T> task) throws Exception {
        synchronized (this) {
            waiting++;
            if (live) liveWaiting++;
            try {
                while (running || (!live && liveWaiting != 0)) wait();
                running = true;
            } finally {
                waiting--;
                if (live) liveWaiting--;
                notifyAll();
            }
        }
        try {
            return task.call();
        } finally {
            synchronized (this) {
                running = false;
                notifyAll();
            }
        }
    }
}
