package dev.workspace.qwen.asr;

import org.json.JSONObject;
import org.junit.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;

import static org.junit.Assert.*;

public final class ContractTest {
    @Test public void includesTwoPinnedGgufPairs() throws Exception {
        assertEquals(1019141728L, Models.SMALL.size());
        assertEquals(2520744288L, Models.LARGE.size());
        assertEquals(2, Models.CARDS.size());
        for (Models.Card card : Models.CARDS) {
            for (Models.Asset asset : new Models.Asset[]{card.main(), card.projector()}) {
                assertTrue(asset.sha256().matches("[0-9a-f]{64}"));
                assertTrue(card.url(asset).contains("/resolve/" + card.revision() + "/"));
            }
        }
        var path = Files.createTempFile("qwen-manifest", ".gguf");
        try {
            var bytes = "GGUFpayload".getBytes(StandardCharsets.US_ASCII);
            Files.write(path, bytes);
            var hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
            assertTrue(Models.valid(path.toFile(), new Models.Asset("test.gguf", bytes.length, hash)));
            Files.write(path, "GGUFpayload".replace('p', 'q').getBytes(StandardCharsets.US_ASCII));
            assertFalse(Models.valid(path.toFile(), new Models.Asset("test.gguf", bytes.length, hash)));
        } finally {
            Files.delete(path);
        }
    }

    @Test public void matchesIpv4Ipv6AndMappedPrefixes() throws Exception {
        var ipv4 = AccessPolicy.Prefix.parse("192.168.12.0/25");
        assertTrue(ipv4.contains(InetAddress.getByName("192.168.12.127")));
        assertFalse(ipv4.contains(InetAddress.getByName("192.168.12.128")));
        var ipv6 = AccessPolicy.Prefix.parse("2001:db8:c0::/42");
        assertTrue(ipv6.contains(InetAddress.getByName("2001:db8:df::1")));
        assertFalse(ipv6.contains(InetAddress.getByName("2001:db8:100::1")));
        var mapped = AccessPolicy.Prefix.parse("::ffff:192.168.12.0/121");
        assertTrue(mapped.contains(InetAddress.getByName("192.168.12.63")));
        assertThrows(IllegalArgumentException.class, () -> AccessPolicy.Prefix.parse("192.0.2.0/33"));
        assertFalse(AudioFetch.publicAddress(InetAddress.getByName("100.64.0.1")));
        assertFalse(AudioFetch.publicAddress(InetAddress.getByName("::ffff:127.0.0.1")));
        assertFalse(AudioFetch.publicAddress(InetAddress.getByName("fd00::1")));
    }

    @Test public void multipartPreservesBoundaryLikeBytesAndRejectsDuplicateFields() throws Exception {
        String file = "--x\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.wav\"\r\n\r\n";
        String model = "--x\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nlocal\r\n";
        byte[] body = (file + "binary\r\n--x-not-a-delimiter\r\n" + model + "--x--\r\n")
                .getBytes(StandardCharsets.UTF_8);
        var parts = Multipart.parse("Multipart/Form-Data; boundary=\"x\"", body);
        assertEquals("binary\r\n--x-not-a-delimiter", parts.get("file").text(body));
        assertEquals("local", parts.get("model").text(body));
        assertThrows(Wire.ApiError.class, () -> Multipart.parse("multipart/form-data; boundary=x",
                (model + model + "--x--").getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void audioErrorSchemasRemainDistinct() throws Exception {
        var openai = new JSONObject(new String(AudioApi.error(false, true, 401, "invalid").body(), StandardCharsets.UTF_8));
        assertEquals("invalid_api_key", openai.getJSONObject("error").getString("code"));
        assertTrue(openai.getJSONObject("error").isNull("param"));
        var dash = new JSONObject(new String(AudioApi.error(true, false, 404, "missing").body(), StandardCharsets.UTF_8));
        assertEquals("ModelNotFound", dash.getString("code"));
        assertEquals("missing", dash.getString("message"));
        assertFalse(dash.getString("request_id").isBlank());
    }

    @Test public void subtitlesKeepSampleBasedTimestampsAndLateTranslation() {
        long start = Pcm.millis(48000);
        long end = Pcm.millis(70400);
        var cue = new Pcm.Cue(start, end, "Original", "译文");
        assertEquals("1\n00:00:03,000 --> 00:00:04,400\nOriginal\n译文\n\n", Pcm.srt(java.util.List.of(cue)));
        assertEquals("1\n00:00:03,000 --> 00:00:04,400\nOriginal\n\n", Pcm.srt(java.util.List.of(cue), "source"));
        assertEquals("1\n00:00:03,000 --> 00:00:04,400\n译文\n\n", Pcm.srt(java.util.List.of(cue), "translation"));
        assertEquals("Original", Pcm.plainText(java.util.List.of(cue), "source"));
        assertEquals("Original\n译文", Pcm.plainText(java.util.List.of(cue), "bilingual"));
        assertEquals("译文", Pcm.plainText(java.util.List.of(cue), "translation"));
        assertTrue(Pcm.lrc(java.util.List.of(cue), true).contains("[00:03.00]译文"));
        assertEquals("[00:03.00]Original\n", Pcm.lrc(java.util.List.of(cue), "source"));
        assertEquals("[00:03.00]Original / 译文\n", Pcm.lrc(java.util.List.of(cue), "bilingual"));
        assertEquals("[00:03.00]Original\n", Pcm.lrc(java.util.List.of(new Pcm.Cue(start, end, "Original", "")), "bilingual"));
    }

    @Test public void audioFetchAllowsVpnFakeIpMixedDnsAndConfiguredPrivateLan() throws Exception {
        // Clash / Mihomo / sing-box Fake-IP ranges are allowed even when private LAN is disabled.
        assertTrue(AudioFetch.isFakeIp(InetAddress.getByName("198.18.0.2")));
        assertTrue(AudioFetch.isFakeIp(InetAddress.getByName("198.19.255.1")));
        assertTrue(AudioFetch.isFakeIp(InetAddress.getByName("fc00::2")));
        assertFalse(AudioFetch.isFakeIp(InetAddress.getByName("fd00::1")));

        assertTrue(AudioFetch.isAllowedAddress(InetAddress.getByName("198.18.0.5"), false, false));
        assertTrue(AudioFetch.isAllowedAddress(InetAddress.getByName("fc00::5"), false, false));

        // Loopback, link-local, multicast, and local device interfaces are always blocked.
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("127.0.0.1"), true, false));
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("::1"), true, false));
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("::ffff:127.0.0.1"), true, false));
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("169.254.169.254"), true, false));
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("fe80::1"), true, false));
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("198.18.0.1"), true, true));

        // Private LAN and Tailscale (100.64.0.0/10) respect the allowPrivate toggle.
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("192.168.1.50"), false, false));
        assertFalse(AudioFetch.isAllowedAddress(InetAddress.getByName("100.64.12.34"), false, false));
        assertTrue(AudioFetch.isAllowedAddress(InetAddress.getByName("192.168.1.50"), true, false));
        assertTrue(AudioFetch.isAllowedAddress(InetAddress.getByName("10.0.0.8"), true, false));
        assertTrue(AudioFetch.isAllowedAddress(InetAddress.getByName("100.64.12.34"), true, false));
        assertTrue(AudioFetch.isAllowedAddress(InetAddress.getByName("fd00::1"), true, false));

        // Mixed DNS results filter out restricted entries instead of failing valid public/fake-IP entries.
        var mixed = java.util.List.of(
                InetAddress.getByName("fe80::1"),
                InetAddress.getByName("198.18.0.10"),
                InetAddress.getByName("93.184.216.34")
        );
        var filtered = AudioFetch.filterAuthorized(mixed, false, addr -> false);
        assertEquals(2, filtered.size());

        // Blocked private LAN throws a specific, actionable AddressBlockedException.
        var lanBlocked = assertThrows(AudioFetch.AddressBlockedException.class, () ->
                AudioFetch.filterAuthorized(java.util.List.of(InetAddress.getByName("192.168.1.50")), false, addr -> false));
        assertEquals("private-lan", lanBlocked.category);

        // URL parser tolerates custom ports, spaces, CJK characters, and strips fragments.
        var parsed = AudioFetch.url("HTTPS://nas.example.com:8888/audio/[RJ001] 音声 01.mp3?token=abc#section");
        assertEquals("https", parsed.getScheme());
        assertEquals(8888, parsed.getPort());
        assertNull(parsed.getFragment());
        assertEquals(2, AudioFetch.parseOrigins("http://192.168.1.50:8888, https://100.64.0.10:8443").size());
    }

    @Test public void rtfWatchdogThresholdValidationAndBoundaries() {
        assertEquals(Double.valueOf(2.0), DictationModelKt.parseManualRtfLimit("2.0"));
        assertEquals(Double.valueOf(0.75), DictationModelKt.parseManualRtfLimit(" 0.75 "));
        assertNull(DictationModelKt.parseManualRtfLimit(""));
        assertNull(DictationModelKt.parseManualRtfLimit("0"));
        assertNull(DictationModelKt.parseManualRtfLimit("-1.5"));
        assertNull(DictationModelKt.parseManualRtfLimit("NaN"));
        assertNull(DictationModelKt.parseManualRtfLimit("Infinity"));
        assertNull(DictationModelKt.parseManualRtfLimit("abc"));

        var manual = new RtfWatchdog.Limit(2.0, 0.0);
        assertFalse(manual.exceeded(8.0, 16.0));
        assertTrue(manual.exceeded(8.0, 16.01));

        var watchdog = new RtfWatchdog();
        var auto = new RtfWatchdog.Settings(true, null);
        assertEquals(1.2, watchdog.limit(auto, "CPU").rtf(), 1e-6);
        assertEquals(1.0, watchdog.limit(auto, "CPU").overheadSeconds(), 1e-6);
        assertEquals(0.8, watchdog.limit(auto, "HTP0").rtf(), 1e-6);
        assertEquals(1.0, watchdog.limit(auto, "HTP0").overheadSeconds(), 1e-6);

        // Short (<4s) samples are ignored for baseline; >=5 healthy >=4s samples compute 3x median + 1s.
        watchdog.healthy(3.0, 10.0);
        for (double sample : new double[]{0.5, 0.875, 0.625, 1.0, 0.75}) watchdog.healthy(8.0, sample);
        var dynamic = watchdog.limit(auto, "HTP0");
        assertEquals(2.25, dynamic.rtf(), 1e-6);
        assertEquals(1.0, dynamic.overheadSeconds(), 1e-6);
        assertFalse(dynamic.exceeded(8.0, 8.0 * 2.25 + 1.0));
        assertTrue(dynamic.exceeded(8.0, 8.0 * 2.25 + 1.01));
    }

    @Test public void performanceLimitPresetsAndStatusBoundaries() {
        var tuning = new NativeRuntime.Tuning("HTP0", 6, 1024, 2048, 256, 99);

        var fg = PerformanceLimit.Preset.FOREGROUND.configure(tuning);
        assertEquals(4, fg.tuning().threads());
        assertEquals(512, fg.tuning().batch());
        assertEquals(256, fg.microBatch());
        assertEquals(1, fg.niceAdjustment());
        assertEquals(300, PerformanceLimit.Preset.FOREGROUND.bulkGapMillis);

        var balanced = PerformanceLimit.Preset.BALANCED.configure(tuning);
        assertEquals(6, balanced.tuning().threads());
        assertEquals(1024, balanced.tuning().batch());
        assertEquals(256, balanced.microBatch());
        assertEquals(1, balanced.niceAdjustment());
        assertEquals(0, PerformanceLimit.Preset.BALANCED.bulkGapMillis);

        var smallBatch = new NativeRuntime.Tuning("CPU", 6, 128, 2048, 256, 0);
        assertEquals(128, PerformanceLimit.Preset.BALANCED.configure(smallBatch).microBatch());

        var full = PerformanceLimit.Preset.FULL_SPEED.configure(tuning);
        assertEquals(6, full.tuning().threads());
        assertEquals(1024, full.tuning().batch());
        assertEquals(512, full.microBatch());
        assertEquals(0, full.niceAdjustment());
        assertEquals(0, PerformanceLimit.Preset.FULL_SPEED.bulkGapMillis);

        var reloadNeeded = PerformanceLimit.status(true,
                PerformanceLimit.Preset.FOREGROUND, PerformanceLimit.Preset.BALANCED, balanced, true);
        assertTrue(reloadNeeded.available());
        assertTrue(reloadNeeded.reloadRequired());
        assertEquals("foreground", reloadNeeded.selectedPreset());
        assertEquals("balanced", reloadNeeded.activePreset());
        assertEquals(1, reloadNeeded.nativeNiceAdjustment());
        assertEquals(300, reloadNeeded.bulkGapMillis());

        var rejectedNice = PerformanceLimit.status(true,
                PerformanceLimit.Preset.BALANCED, PerformanceLimit.Preset.BALANCED, balanced, false);
        assertFalse(rejectedNice.reloadRequired());
        assertEquals(0, rejectedNice.nativeNiceAdjustment());

        var external = PerformanceLimit.status(false,
                PerformanceLimit.Preset.FOREGROUND, null, fg, false);
        assertFalse(external.available());
        assertEquals("", external.activePreset());
        assertEquals(0, external.bulkGapMillis());
    }
}
