package dev.workspace.qwen.asr;

import android.content.Context;
import android.os.Build;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

final class NativeRuntime {
    static final String REVISION = "dde9de82cbb9b1050ae75c95ab9a9f955340647f";
    static final int PROMPT_CACHE_MIB = 0;
    private static final String NICE = "/system/bin/nice";
    record Devices(boolean arm64, boolean bundled, String lists, List<String> devices) {}
    record Tuning(String device, int threads, int batch, int context, int output, int layers) {
        String fingerprint() { return device + ":" + threads + ":" + batch + ":" + context + ":" + output + ":" + layers; }
    }
    record Started(Process process, int port, boolean lowerPriority) {}
    static final String BACKEND = "(?:HTP\\d+|GPUOpenCL\\d*|OpenCL\\d+|Vulkan\\d+|GPU\\d+)(?::\\d+)?";
    private static final Pattern DEVICE = Pattern.compile("^\\s*(" + BACKEND + "):\\s.*$", Pattern.CASE_INSENSITIVE);

    static boolean cpuOnly() { return BuildConfig.CPU_ONLY; }
    static String runtimeLabel() { return BuildConfig.RUNTIME_LABEL; }
    static boolean arm64() { return Arrays.asList(Build.SUPPORTED_ABIS).contains("arm64-v8a"); }
    static File executable(Context context) {
        return new File(context.getApplicationInfo().nativeLibraryDir, "libqwen_llama_server.so");
    }

    static Devices probe(Context context) throws Exception {
        if (!arm64() || !executable(context).canExecute()) {
            return new Devices(arm64(), false, "当前构建未提供 arm64 推理运行时", List.of());
        }
        if (BuildConfig.CPU_ONLY) {
            return new Devices(true, true, "通用 ARM64 CPU 运行时，自动匹配 CPU 指令集", List.of());
        }
        Process process = builder(context, List.of("--list-devices"), true).start();
        try {
            List<String> output = new java.util.concurrent.CopyOnWriteArrayList<>();
            Thread reader = new Thread(() -> {
                try (var lines = new java.io.BufferedReader(new java.io.InputStreamReader(
                        process.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = lines.readLine()) != null) {
                        var matcher = DEVICE.matcher(line);
                        if (matcher.matches()) output.add(matcher.group(1));
                    }
                } catch (IOException ignored) {}
            }, "llama device enumeration");
            reader.start();
            if (!process.waitFor(12, TimeUnit.SECONDS)) {
                return new Devices(true, true, "设备枚举超时，可手动选择 CPU", List.of());
            }
            reader.join(500);
            return new Devices(true, true,
                    process.exitValue() == 0 ? "运行时枚举完成，实际后端待短音频验证" : "运行时枚举失败，可尝试 CPU",
                    List.copyOf(output));
        } finally {
            process.destroyForcibly();
        }
    }

    static String verificationKey(String model, Tuning tuning) {
        return "probe." + Build.FINGERPRINT + "|" + REVISION + "|" + Build.SUPPORTED_ABIS[0] + "|" + model
                + "|" + tuning.fingerprint();
    }

    private static String verificationKey(String model, PerformanceLimit.NativeSetup setup) {
        return verificationKey(model, setup.tuning()) + "|ub=" + setup.microBatch() + "|cache=" + PROMPT_CACHE_MIB;
    }

    static boolean verified(Context context, String model, Tuning tuning) {
        return verified(context, model, tuning, PerformanceLimit.selected(context));
    }

    static boolean verified(Context context, String model, Tuning tuning, PerformanceLimit.Preset preset) {
        var prefs = DictationService.prefs(context);
        var setup = preset.configure(tuning);
        String key = verificationKey(model, setup);
        if (prefs.contains(key)) return prefs.getBoolean(key, false);

        // A prior full-speed or legacy probe can select the device for the first load of a new preset.
        return prefs.getBoolean(verificationKey(model, PerformanceLimit.Preset.FULL_SPEED.configure(tuning)), false)
                || prefs.getBoolean(verificationKey(model, tuning), false)
                || prefs.getBoolean(verificationKey(model, setup.tuning()), false);
    }

    static void mark(Context context, String model, PerformanceLimit.NativeSetup setup, boolean success) {
        DictationService.prefs(context).edit().putBoolean(verificationKey(model, setup), success).apply();
    }

    static Started start(Context context, Models.Pair pair, PerformanceLimit.NativeSetup setup, String key)
            throws IOException {
        Tuning tuning = setup.tuning();
        if (!executable(context).canExecute()) throw new IOException("此构建缺少 arm64 llama-server");
        int port;
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            port = socket.getLocalPort();
        }
        boolean cpu = BuildConfig.CPU_ONLY || tuning.device.equals("CPU");
        List<String> arguments = new ArrayList<>(List.of("--host", "127.0.0.1", "--port", Integer.toString(port),
                "--api-key", key, "-m", pair.main().getAbsolutePath(), "--mmproj", pair.projector().getAbsolutePath(),
                "--device", cpu ? "none" : tuning.device, "-ngl", Integer.toString(cpu ? 0 : tuning.layers),
                "--no-mmproj-offload", "-t", Integer.toString(tuning.threads()), "--threads-batch",
                Integer.toString(tuning.threads()), "-b", Integer.toString(tuning.batch()),
                "--ubatch-size", Integer.toString(setup.microBatch()), "--cache-ram", Integer.toString(PROMPT_CACHE_MIB),
                "-c", Integer.toString(tuning.context()), "-n", Integer.toString(tuning.output()), "--parallel", "1",
                "-lv", "4"));
        ProcessBuilder process = builder(context, arguments, !cpu);
        int nice = setup.niceAdjustment();
        if (nice != 0 && canLowerPriority(nice)) {
            var original = process.command();
            var command = new ArrayList<>(List.of(NICE, "-n", Integer.toString(nice)));
            command.addAll(original);
            try {
                return new Started(process.command(command).start(), port, true);
            } catch (IOException unavailable) {
                process.command(original);
            }
        }
        return new Started(process.start(), port, false);
    }

    private static boolean canLowerPriority(int nice) throws IOException {
        if (!new File(NICE).canExecute()) return false;
        Process probe;
        try {
            probe = new ProcessBuilder(NICE, "-n", Integer.toString(nice), "/system/bin/true").start();
        } catch (IOException unavailable) {
            return false;
        }
        try {
            return probe.waitFor(2, TimeUnit.SECONDS) && probe.exitValue() == 0;
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("已取消加载本地运行时");
        } finally {
            if (probe.isAlive()) probe.destroyForcibly();
        }
    }

    private static ProcessBuilder builder(Context context, List<String> arguments, boolean dsp) throws IOException {
        File executable = executable(context);
        File libDir = executable.getParentFile();
        List<String> command = new ArrayList<>();
        command.add(executable.getAbsolutePath());
        command.addAll(arguments);
        ProcessBuilder builder = new ProcessBuilder(command).directory(libDir).redirectErrorStream(true);
        builder.environment().put("LD_LIBRARY_PATH", libDir.getAbsolutePath() + ":/system/lib64:/vendor/lib64");
        if (dsp) {
            File dspDir = new File(context.getFilesDir(), "htp-" + REVISION);
            Files.createDirectories(dspDir.toPath());
            String[] libraries = context.getAssets().list("htp");
            if (libraries != null) {
                for (String library : libraries) {
                    File destination = new File(dspDir, library);
                    if (destination.isFile()) continue;
                    File temporary = File.createTempFile("htp-", ".so", dspDir);
                    try (var input = context.getAssets().open("htp/" + library)) {
                        Files.copy(input, temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } finally {
                        temporary.delete();
                    }
                }
            }
            builder.environment().put("ADSP_LIBRARY_PATH", dspDir.getAbsolutePath());
        }
        return builder;
    }
}
