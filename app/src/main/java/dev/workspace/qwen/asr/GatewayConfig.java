package dev.workspace.qwen.asr;

import android.content.Context;
import android.content.Intent;

record GatewayConfig(int port, String bind, String mode, String ranges, boolean kikoeta, boolean openai,
                     boolean dashscope, String alias) {
    static GatewayConfig read(Context context) {
        var prefs = DictationService.prefs(context);
        return new GatewayConfig(prefs.getInt("apiPort", Wire.PORT), prefs.getString("apiBind", ""),
                prefs.getString("apiMode", "LOCAL"), prefs.getString("apiRanges", ""), prefs.getBoolean("apiKikoeta", true),
                prefs.getBoolean("apiOpenai", false), prefs.getBoolean("apiDash", false), prefs.getString("apiAlias", ""));
    }

    AccessPolicy policy() throws java.io.IOException {
        return AccessPolicy.parse(port, bind, AccessPolicy.Mode.valueOf(mode), ranges, kikoeta, openai, dashscope, alias.strip());
    }

    Intent put(Intent intent) {
        return intent.putExtra("port", port).putExtra("bind", bind).putExtra("mode", mode).putExtra("ranges", ranges)
                .putExtra("kikoeta", kikoeta).putExtra("openai", openai).putExtra("dashscope", dashscope).putExtra("alias", alias);
    }

    static GatewayConfig from(Intent intent) {
        return new GatewayConfig(intent.getIntExtra("port", Wire.PORT), intent.getStringExtra("bind"), intent.getStringExtra("mode"),
                intent.getStringExtra("ranges"), intent.getBooleanExtra("kikoeta", true), intent.getBooleanExtra("openai", false),
                intent.getBooleanExtra("dashscope", false), intent.getStringExtra("alias"));
    }

    void save(Context context) {
        DictationService.prefs(context).edit().putInt("apiPort", port).putString("apiBind", bind).putString("apiMode", mode)
                .putString("apiRanges", ranges).putBoolean("apiKikoeta", kikoeta).putBoolean("apiOpenai", openai)
                .putBoolean("apiDash", dashscope).putString("apiAlias", alias.strip()).apply();
    }
}
