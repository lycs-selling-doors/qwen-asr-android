package dev.workspace.qwen.asr;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Secrets {
    private static final String ALIAS = "qwen-dictation-v1";

    static synchronized String get(Context context, String name, boolean generate) {
        SharedPreferences prefs = prefs(context);
        String stored = prefs.getString("sealed." + name, "");
        try {
            if (stored.isEmpty()) {
                String legacy = prefs.getString(name, "");
                String value = legacy.isEmpty() && generate ? Inference.newPassword() : legacy;
                if (!value.isEmpty()) set(context, name, value);
                return value;
            }
            byte[] bytes = Base64.decode(stored, Base64.NO_WRAP);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Arrays.copyOf(bytes, 12)));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("无法读取本机安全存储", error);
        }
    }

    static synchronized void set(Context context, String name, String value) {
        try {
            var editor = prefs(context).edit().remove(name);
            if (value.isEmpty()) {
                editor.remove("sealed." + name).apply();
                return;
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] bytes = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, bytes, iv.length, encrypted.length);
            if (!editor.putString("sealed." + name, Base64.encodeToString(bytes, Base64.NO_WRAP)).commit()) {
                throw new IllegalStateException("无法保存凭据");
            }
        } catch (Exception error) {
            throw new IllegalStateException("无法写入本机安全存储", error);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(DictationService.PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.getKey(ALIAS, null) instanceof SecretKey key) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
