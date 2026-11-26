package dev.workspace.qwen.asr;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.SystemClock;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.List;
import java.util.function.Consumer;

final class Models {
    record Asset(String name, long size, String sha256) {}
    record Card(String id, String title, String repo, String revision, Asset main, Asset projector) {
        long size() { return main.size + projector.size; }
        String url(Asset asset) { return "https://huggingface.co/ggml-org/" + repo + "/resolve/" + revision + "/" + asset.name; }
    }
    record Pair(Card card, File main, File projector, boolean legacy) {}
    record Progress(String label, long bytes, long total, long bytesPerSecond) {}

    static final String LICENSE = "Apache-2.0 (Qwen3-ASR upstream)";
    static final Card SMALL = new Card("qwen3-asr-0.6b-q8_0", "Qwen3-ASR-0.6B Q8_0",
            "Qwen3-ASR-0.6B-GGUF", "928ab958557df9aa2ef1c93e0e83c7ad0933fae2",
            new Asset("Qwen3-ASR-0.6B-Q8_0.gguf", 804749248L,
                    "bca259818b50ca7c4c05e9bdb35a5dc04fa039653a6d6f3f0f331f96f6aa1971"),
            new Asset("mmproj-Qwen3-ASR-0.6B-Q8_0.gguf", 214392480L,
                    "41a342b5e4c514e968cb756de6cd1b7be39eff43c44c57a2ef5fc6522e36603d"));
    static final Card LARGE = new Card("qwen3-asr-1.7b-q8_0", "Qwen3-ASR-1.7B Q8_0",
            "Qwen3-ASR-1.7B-GGUF", "36a678687ba7d07a74ca70ccb0e36902e005fb80",
            new Asset("Qwen3-ASR-1.7B-Q8_0.gguf", 2165034944L,
                    "58e22d0532d4eacaf034cfac17a6fed159f37c41390c710186783be439d1fc57"),
            new Asset("mmproj-Qwen3-ASR-1.7B-Q8_0.gguf", 355709344L,
                    "46c1d533af3f354ceb37ce855dbceff7da7fa7cf1e6a523df3b13440bd164c0d"));
    static final List<Card> CARDS = List.of(SMALL, LARGE);
    private final Context context;
    private final File root;

    Models(Context context) {
        this.context = context.getApplicationContext();
        root = new File(context.getFilesDir(), "asr-models");
    }

    Pair installed(Card card) {
        File directory = new File(root, card.id);
        File main = new File(directory, card.main.name);
        File projector = new File(directory, card.projector.name);
        if (main.length() == card.main.size && projector.length() == card.projector.size) {
            return new Pair(card, main, projector, false);
        }
        var prefs = context.getSharedPreferences(DictationService.PREFS, Context.MODE_PRIVATE);
        main = new File(context.getFilesDir(), Inference.MODEL);
        projector = new File(context.getFilesDir(), Inference.PROJECTOR);
        if (prefs.getString("legacyModel", "").equals(card.id)
                && prefs.getString("legacyStamp", "").equals(stamp(main, projector))) {
            return new Pair(card, main, projector, true);
        }
        return null;
    }

    Pair selected(String id) {
        for (Card card : CARDS) {
            if (id.equals(card.id)) return installed(card);
        }
        if (!id.isBlank()) return null;
        for (Card card : CARDS) {
            Pair pair = installed(card);
            if (pair != null) return pair;
        }
        return null;
    }

    boolean hasLegacyFiles() {
        return new File(context.getFilesDir(), Inference.MODEL).isFile()
                && new File(context.getFilesDir(), Inference.PROJECTOR).isFile();
    }

    Card migrateLegacy() throws IOException {
        File main = new File(context.getFilesDir(), Inference.MODEL);
        File projector = new File(context.getFilesDir(), Inference.PROJECTOR);
        var prefs = context.getSharedPreferences(DictationService.PREFS, Context.MODE_PRIVATE);
        String stamp = stamp(main, projector);
        if (!hasLegacyFiles() || stamp.equals(prefs.getString("legacyRemoved", ""))) return null;
        if (stamp.equals(prefs.getString("legacyStamp", ""))) {
            return CARDS.stream().filter(card -> card.id.equals(prefs.getString("legacyModel", "")))
                    .findFirst().orElse(null);
        }
        if (stamp.equals(prefs.getString("legacyRejected", ""))) return null;
        for (Card card : CARDS) {
            if (valid(main, card.main) && valid(projector, card.projector)) {
                prefs.edit().putString("legacyModel", card.id).putString("legacyStamp", stamp).apply();
                return card;
            }
        }
        prefs.edit().putString("legacyRejected", stamp).apply();
        return null;
    }

    private static String stamp(File main, File projector) {
        return main.length() + ":" + main.lastModified() + ":" + projector.length() + ":" + projector.lastModified();
    }

    void install(Card card, Uri main, Uri projector, boolean mobile, Consumer<Progress> progress) throws Exception {
        if (installed(card) != null) return;
        Files.createDirectories(root.toPath());
        File staging = new File(root, card.id + (main == null ? ".download" : ".import"));
        Files.createDirectories(staging.toPath());
        File mainFile = new File(staging, card.main.name);
        File projectorFile = new File(staging, card.projector.name);
        long needed = card.size();
        if (main == null) {
            needed -= Math.min(mainFile.length(), card.main.size);
            needed -= Math.min(projectorFile.length(), card.projector.size);
        }
        if (root.getUsableSpace() < needed + 64L * 1024 * 1024) throw new IOException("剩余空间不足");
        try {
            installAsset(card, card.main, mainFile, main, mobile, 0, progress);
            installAsset(card, card.projector, projectorFile, projector, mobile, card.main.size, progress);
            checkCancelled();
            File destination = new File(root, card.id);
            if (destination.isDirectory() && installed(card) == null) {
                Files.deleteIfExists(new File(destination, card.main.name).toPath());
                Files.deleteIfExists(new File(destination, card.projector.name).toPath());
                Files.delete(destination.toPath());
            }
            Files.move(staging.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } finally {
            if (main != null) {
                mainFile.delete();
                projectorFile.delete();
                staging.delete();
            }
        }
    }

    private void installAsset(Card card, Asset asset, File file, Uri uri, boolean mobile, long prior,
                              Consumer<Progress> progress) throws Exception {
        if (uri == null && file.length() == asset.size) {
            progress.accept(new Progress("校验 " + asset.name, prior + asset.size, card.size(), 0));
            if (valid(file, asset)) return;
            Files.delete(file.toPath());
        }
        if (uri == null) {
            download(card, asset, file, mobile, prior, progress);
        } else {
            try (InputStream input = context.getContentResolver().openInputStream(uri);
                 RandomAccessFile output = new RandomAccessFile(file, "rw")) {
                if (input == null) throw new IOException("无法读取导入文件");
                output.setLength(0);
                transfer(input, output, card, asset, prior, 0, null, progress);
            }
        }
        progress.accept(new Progress("校验 " + asset.name, prior + file.length(), card.size(), 0));
        if (!valid(file, asset)) {
            Files.deleteIfExists(file.toPath());
            throw new IOException(asset.name + " 长度或 SHA-256 不匹配，请核对模型档位");
        }
    }

    private void download(Card card, Asset asset, File file, boolean mobile, long prior,
                          Consumer<Progress> progress) throws Exception {
        ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
        var network = manager.getActiveNetwork();
        Runnable verifyNetwork = () -> {
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || (!mobile && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))) {
                throw new IllegalStateException(mobile ? "没有可用网络" : "等待 Wi-Fi，或允许使用移动网络");
            }
        };
        verifyNetwork.run();
        HttpURLConnection connection = (HttpURLConnection) network.openConnection(URI.create(card.url(asset)).toURL());
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            long offset = output.length();
            if (offset > asset.size) {
                output.setLength(0);
                offset = 0;
            }
            connection.setConnectTimeout(12000);
            connection.setReadTimeout(12000);
            connection.setRequestProperty("Accept-Encoding", "identity");
            if (offset != 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            int status = connection.getResponseCode();
            if (status == 200) {
                offset = 0;
                output.setLength(0);
            } else if (status != 206 || offset == 0 || !("bytes " + offset + "-" + (asset.size - 1)
                    + "/" + asset.size).equals(connection.getHeaderField("Content-Range"))) {
                throw new IOException("下载响应无效（HTTP " + status + "）");
            }
            output.seek(offset);
            try (var input = connection.getInputStream()) {
                transfer(input, output, card, asset, prior, offset, verifyNetwork, progress);
            }
        } catch (java.net.SocketTimeoutException error) {
            throw new IOException("模型下载连接超时，已保留断点，可重试或导入 GGUF", error);
        } finally {
            connection.disconnect();
        }
    }

    private static void transfer(InputStream input, RandomAccessFile output, Card card, Asset asset,
                                 long prior, long offset, Runnable network, Consumer<Progress> progress) throws IOException {
        byte[] buffer = new byte[256 * 1024];
        long written = offset;
        long started = SystemClock.elapsedRealtime();
        long reported = started - 300;
        while (true) {
            checkCancelled();
            long now = SystemClock.elapsedRealtime();
            if (now - reported >= 250) {
                if (network != null) network.run();
                progress.accept(new Progress("接收 " + asset.name, prior + written, card.size(),
                        (written - offset) * 1000 / Math.max(1, now - started)));
                reported = now;
            }
            int count = input.read(buffer);
            if (count < 0) break;
            if (count > asset.size - written) throw new IOException("文件超过清单长度");
            output.write(buffer, 0, count);
            written += count;
        }
        if (written != asset.size) throw new IOException("传输中断，已接收 " + written + " 字节");
        output.getFD().sync();
    }

    static boolean valid(File file, Asset asset) throws IOException {
        if (!file.isFile() || file.length() != asset.size) return false;
        try (var raw = Files.newInputStream(file.toPath())) {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            try (var input = new DigestInputStream(raw, sha)) {
                if (!java.util.Arrays.equals(input.readNBytes(4), new byte[]{'G', 'G', 'U', 'F'})) return false;
                byte[] buffer = new byte[1024 * 1024];
                while (input.read(buffer) >= 0) checkCancelled();
            }
            byte[] expected = new byte[32];
            for (int i = 0; i < 32; i++) expected[i] = (byte) Integer.parseInt(asset.sha256.substring(i * 2, i * 2 + 2), 16);
            return MessageDigest.isEqual(sha.digest(), expected);
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    void uninstall(Pair pair) throws IOException {
        var prefs = context.getSharedPreferences(DictationService.PREFS, Context.MODE_PRIVATE);
        if (pair.legacy) {
            prefs.edit().remove("legacyStamp").remove("legacyModel")
                    .putString("legacyRemoved", stamp(pair.main, pair.projector)).apply();
        }
        Files.deleteIfExists(pair.main.toPath());
        Files.deleteIfExists(pair.projector.toPath());
        if (!pair.legacy) Files.deleteIfExists(pair.main.getParentFile().toPath());
    }

    private static void checkCancelled() throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("已取消");
    }
}
