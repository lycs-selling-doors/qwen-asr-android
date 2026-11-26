package dev.workspace.qwen.asr;

import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class Pcm {
    static final int RATE = 16_000;
    record Cue(long startMs, long endMs, String source, String translation) {}

    interface Segments {
        void accept(long startSample, byte[] pcm) throws Exception;
    }

    static byte[] wav(byte[] pcm) {
        return ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length)
                .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16)
                .putShort((short) 1).putShort((short) 1).putInt(RATE).putInt(RATE * 2)
                .putShort((short) 2).putShort((short) 16)
                .put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm).array();
    }

    static boolean silent(byte[] pcm) {
        long power = 0;
        for (int i = 0; i < pcm.length; i += 2) {
            short sample = (short) ((pcm[i] & 255) | pcm[i + 1] << 8);
            power += (long) sample * sample;
        }
        return power < 24L * 24L * (pcm.length / 2);
    }

    static long millis(long sample) { return sample * 1000 / RATE; }

    static void decode(File audio, int seconds, String decoderMode, Runnable checkpoint, Segments segments) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null;
        try {
            extractor.setDataSource(audio.getAbsolutePath());
            MediaFormat track = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = candidate;
                    extractor.selectTrack(i);
                    break;
                }
            }
            if (track == null) throw new IOException("找不到可解码的音轨");
            String mime = track.getString(MediaFormat.KEY_MIME);
            Segmenter segmenter = new Segmenter(seconds, segments);
            if ("audio/raw".equals(mime) && (decoderMode.equals("auto") || decoderMode.equals("pcm"))) {
                ByteBuffer buffer = ByteBuffer.allocateDirect(1024 * 1024).order(ByteOrder.LITTLE_ENDIAN);
                int encoding = track.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                int count;
                while ((count = extractor.readSampleData(buffer, 0)) >= 0) {
                    checkpoint.run();
                    if (count > buffer.capacity()) throw new IOException("单个 PCM 数据块过大");
                    buffer.position(0).limit(count);
                    segmenter.write(buffer, track.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                            track.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding);
                    buffer.clear();
                    extractor.advance();
                }
                segmenter.flush();
                return;
            }
            if (decoderMode.equals("pcm")) throw new IOException("PCM 直读需要 WAV 等无压缩音频");
            decoder = decoder(track, mime, decoderMode);
            decoder.configure(track, null, null, 0);
            decoder.start();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            MediaFormat format = track;
            boolean inputEnded = false;
            boolean outputEnded = false;
            while (!outputEnded) {
                checkpoint.run();
                if (!inputEnded) {
                    int index = decoder.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = extractor.readSampleData(buffer, 0);
                        inputEnded = size < 0;
                        decoder.queueInputBuffer(index, 0, Math.max(0, size),
                                inputEnded ? 0 : extractor.getSampleTime(),
                                inputEnded ? MediaCodec.BUFFER_FLAG_END_OF_STREAM : 0);
                        if (!inputEnded) extractor.advance();
                    }
                }
                int index = decoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = decoder.getOutputFormat();
                if (index < 0) continue;
                try {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        ByteBuffer output = decoder.getOutputBuffer(index).order(ByteOrder.nativeOrder());
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        int encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT);
                        segmenter.write(output, format.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT), encoding);
                    }
                } finally {
                    decoder.releaseOutputBuffer(index, false);
                }
                outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
            }
            segmenter.flush();
        } finally {
            if (decoder != null) decoder.release();
            extractor.release();
        }
    }

    private static MediaCodec decoder(MediaFormat track, String mime, String mode) throws IOException {
        if (mode.equals("auto")) return MediaCodec.createDecoderByType(mime);
        boolean hardware = mode.equals("hardware");
        for (var codec : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
            if (codec.isEncoder() || (hardware ? !codec.isHardwareAccelerated() : !codec.isSoftwareOnly())) continue;
            for (String type : codec.getSupportedTypes()) {
                if (type.equalsIgnoreCase(mime) && codec.getCapabilitiesForType(type).isFormatSupported(track)) {
                    return MediaCodec.createByCodecName(codec.getName());
                }
            }
        }
        throw new IOException(hardware ? "此音频格式没有系统硬件解码器" : "此音频格式没有系统软件解码器");
    }

    private static final class Segmenter {
        private final Segments output;
        private final byte[] buffer;
        private int size;
        private long sample;
        private int phase;
        private long sum;
        private int count;
        private int previousRate;

        Segmenter(int seconds, Segments output) {
            this.output = output;
            buffer = new byte[seconds * RATE * 2];
        }

        void write(ByteBuffer bytes, int rate, int channels, int encoding) throws Exception {
            int width = switch (encoding) {
                case AudioFormat.ENCODING_PCM_8BIT -> 1;
                case AudioFormat.ENCODING_PCM_16BIT -> 2;
                case AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_32BIT -> 4;
                case AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3;
                default -> throw new IOException("不支持此 PCM 格式");
            };
            if (rate <= 0 || channels <= 0) throw new IOException("音轨参数无效");
            if (rate != previousRate) {
                phase = 0;
                sum = 0;
                count = 0;
                previousRate = rate;
            }
            while (bytes.remaining() >= width * channels) {
                int value = 0;
                for (int c = 0; c < channels; c++) {
                    value += switch (encoding) {
                        case AudioFormat.ENCODING_PCM_8BIT -> ((bytes.get() & 255) - 128) << 8;
                        case AudioFormat.ENCODING_PCM_FLOAT ->
                                (int) (Math.max(-1f, Math.min(1f, bytes.getFloat())) * 32767);
                        case AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                            bytes.get();
                            yield (bytes.get() & 255) | (bytes.get() << 8);
                        }
                        case AudioFormat.ENCODING_PCM_32BIT -> bytes.getInt() >> 16;
                        default -> bytes.getShort();
                    };
                }
                int mono = value / channels;
                sum += mono;
                count++;
                phase += RATE;
                if (phase < rate) continue;
                int filtered = (int) (sum / count);
                while (phase >= rate) {
                    buffer[size++] = (byte) filtered;
                    buffer[size++] = (byte) (filtered >> 8);
                    if (size == buffer.length) flush();
                    phase -= rate;
                }
                sum = 0;
                count = 0;
            }
        }

        void flush() throws Exception {
            if (size == 0) return;
            output.accept(sample, java.util.Arrays.copyOf(buffer, size));
            sample += size / 2;
            size = 0;
        }
    }

    static String lrc(List<Cue> cues, boolean translated) {
        return lrc(cues, translated ? "translation" : "source");
    }

    static String lrc(List<Cue> cues, String mode) {
        StringBuilder result = new StringBuilder();
        for (Cue cue : cues) {
            long cs = cue.startMs() / 10;
            String src = line(cue.source());
            String tr = line(cue.translation());
            String text = switch (mode == null ? "source" : mode) {
                case "translation" -> tr.isEmpty() ? src : tr;
                case "bilingual" -> (tr.isEmpty() || tr.equals(src)) ? src : src + " / " + tr;
                default -> src;
            };
            result.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]%s\n", cs / 6000,
                    cs / 100 % 60, cs % 100, text));
        }
        return result.toString();
    }

    static String srt(List<Cue> cues) {
        StringBuilder result = new StringBuilder();
        int index = 1;
        for (Cue cue : cues) {
            result.append(index++).append('\n').append(time(cue.startMs())).append(" --> ")
                    .append(time(cue.endMs())).append('\n').append(line(cue.source())).append('\n');
            if (!cue.translation().isBlank()) result.append(line(cue.translation())).append('\n');
            result.append('\n');
        }
        return result.toString();
    }

    private static String line(String value) { return value.replaceAll("[\\r\\n]+", " ").strip(); }

    private static String time(long ms) {
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d", ms / 3_600_000,
                ms / 60_000 % 60, ms / 1000 % 60, ms % 1000);
    }
}
