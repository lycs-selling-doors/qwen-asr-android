package dev.workspace.qwen.asr;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

final class LiveCapture implements AutoCloseable {
    interface Listener {
        void source(Pcm.Cue cue);
        void translated(Pcm.Cue cue);
        void status(String message);
        void finished();
    }

    private record Clip(long start, byte[] pcm) {}
    private final ArrayBlockingQueue<Clip> clips = new ArrayBlockingQueue<>(2);
    private final ArrayBlockingQueue<Pcm.Cue> pendingTranslations = new ArrayBlockingQueue<>(3);
    private final Inference engine;
    private final AudioRecord recorder;
    private final Listener listener;
    private final Thread reader;
    private final Thread recognizer;
    private final Thread translator;
    private volatile boolean recording = true;
    private volatile boolean reading = true;
    private volatile boolean closed;
    private volatile boolean recognizing = true;
    private int dropped;

    LiveCapture(Inference engine, MediaProjection projection, Listener listener) throws IOException {
        this.engine = engine;
        this.listener = listener;
        int min = AudioRecord.getMinBufferSize(Pcm.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) throw new IOException("设备不支持 16 kHz 录音");
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(Pcm.RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build())
                .setBufferSizeInBytes(Math.max(min * 2, 16_384));
        if (projection == null) {
            builder.setAudioSource(MediaRecorder.AudioSource.MIC);
        } else {
            builder.setAudioPlaybackCaptureConfig(new AudioPlaybackCaptureConfiguration.Builder(projection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build());
        }
        recorder = builder.build();
        try {
            recorder.startRecording();
            if (recorder.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("未能开始录音");
            }
        } catch (RuntimeException | IOException error) {
            recorder.release();
            throw error;
        }
        reader = new Thread(this::read, "Caption audio capture");
        recognizer = new Thread(this::recognize, "Caption recognition");
        translator = engine.hasTranslation() ? new Thread(this::translate, "Caption translation") : null;
        reader.start();
        recognizer.start();
        if (translator != null) translator.start();
    }

    private void read() {
        byte[] pcm = new byte[Pcm.RATE * 2 * 3];
        int used = 0;
        long start = 0;
        try {
            while (recording) {
                int count = recorder.read(pcm, used, Math.min(pcm.length - used, 4096), AudioRecord.READ_BLOCKING);
                if (count < 0) {
                    if (recording) listener.status("录音已中断，错误 " + count);
                    break;
                }
                used += count;
                if (used < pcm.length) continue;
                enqueue(new Clip(start, pcm));
                start += pcm.length / 2;
                pcm = new byte[pcm.length];
                used = 0;
            }
            if (!closed && used >= Pcm.RATE / 4) enqueue(new Clip(start, Arrays.copyOf(pcm, used & ~1)));
        } finally {
            synchronized (recorder) {
                recorder.release();
                recording = false;
            }
            reading = false;
        }
    }

    private void enqueue(Clip clip) {
        if (Pcm.silent(clip.pcm)) return;
        while (!clips.offer(clip)) {
            if (clips.poll() != null) {
                dropped++;
                listener.status("推理跟不上输入，已跳过 " + dropped + " 个音频片段");
            }
        }
    }

    private void recognize() {
        try {
            while (!closed && (reading || !clips.isEmpty())) {
                Clip clip = clips.poll(250, TimeUnit.MILLISECONDS);
                if (clip == null) continue;
                try {
                    String source = engine.transcribe(clip.pcm);
                    if (source.isBlank() || closed) continue;
                    long start = Pcm.millis(clip.start);
                    long end = Pcm.millis(clip.start + clip.pcm.length / 2);
                    Pcm.Cue cue = new Pcm.Cue(start, end, source, "");
                    listener.source(cue);
                    if (translator != null && !pendingTranslations.offer(cue)) {
                        pendingTranslations.poll();
                        pendingTranslations.offer(cue);
                        listener.status("翻译跟不上输入，部分字幕只显示原文");
                    }
                } catch (Exception error) {
                    if (!closed) listener.status(error instanceof Inference.HttpError
                            ? error.getMessage() : "字幕处理失败（" + error.getClass().getSimpleName() + "）");
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            recognizing = false;
            if (translator == null) listener.finished();
        }
    }

    private void translate() {
        try {
            while (!closed && (recognizing || !pendingTranslations.isEmpty())) {
                Pcm.Cue cue = pendingTranslations.poll(250, TimeUnit.MILLISECONDS);
                if (cue == null) continue;
                try {
                    String text = engine.translateLive(cue.source());
                    if (!closed && !text.isBlank()) {
                        listener.translated(new Pcm.Cue(cue.startMs(), cue.endMs(), cue.source(), text));
                    }
                } catch (Exception error) {
                    if (!closed) listener.status(error instanceof Inference.HttpError
                            ? error.getMessage() : "部分字幕翻译失败");
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        } finally {
            listener.finished();
        }
    }

    void stopInput() {
        synchronized (recorder) {
            if (!recording) return;
            recording = false;
            try { recorder.stop(); } catch (IllegalStateException ignored) {}
        }
    }

    @Override public void close() {
        closed = true;
        stopInput();
        recognizer.interrupt();
        if (translator != null) translator.interrupt();
        clips.clear();
        pendingTranslations.clear();
    }
}
