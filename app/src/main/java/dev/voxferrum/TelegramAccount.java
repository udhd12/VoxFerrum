package dev.voxferrum;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Local account details. Only the encrypted envelope is stored in preferences. */
final class TelegramAccount {
    private static final String ALIAS = "dev.voxferrum.telegram.v1";
    final String token;
    final String chat;

    TelegramAccount(String token, String chat) {
        this.token = token;
        this.chat = chat;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("telegram_account", Context.MODE_PRIVATE);
    }

    static boolean exists(Context context) {
        return prefs(context).contains("data");
    }

    static void clear(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey existing = (SecretKey) store.getKey(ALIAS, null);
        if (existing != null) return existing;
        if (!create) throw new IOException("Missing local key");

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    void save(Context context) throws Exception {
        JSONObject account = new JSONObject().put("token", token).put("chat", chat);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key(true));
        byte[] bytes = cipher.doFinal(account.toString().getBytes(StandardCharsets.UTF_8));
        String envelope = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)
                + "." + Base64.encodeToString(bytes, Base64.NO_WRAP);
        if (!prefs(context).edit().putString("data", envelope).commit()) {
            throw new IOException("Failed to save local account");
        }
    }

    static TelegramAccount load(Context context) throws Exception {
        String data = prefs(context).getString("data", null);
        if (data == null) return null;
        String[] parts = data.split("\\.", 2);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(false),
                new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
        JSONObject account = new JSONObject(new String(
                cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8));
        return new TelegramAccount(account.getString("token"), account.getString("chat"));
    }
}
