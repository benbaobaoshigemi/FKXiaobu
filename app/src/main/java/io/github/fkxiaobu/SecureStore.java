package io.github.fkxiaobu;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Single atomic encrypted record. Credentials never leave this app's private storage. */
final class SecureStore {
    private static final String ALIAS = "fkxiaobu.oauth.v1";
    private final SharedPreferences prefs;
    SecureStore(Context context) { prefs = context.getSharedPreferences("protected_oauth", Context.MODE_PRIVATE); }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    synchronized JSONObject read() throws Exception {
        String record = prefs.getString("record", null);
        if (record == null) return new JSONObject();
        String[] parts = record.split("\\.", -1);
        if (parts.length != 2) throw new IllegalStateException("凭据存储格式损坏");
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
        return new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8));
    }
    synchronized void write(JSONObject record) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        String value = Base64.getEncoder().encodeToString(cipher.getIV()) + "." +
                Base64.getEncoder().encodeToString(cipher.doFinal(record.toString().getBytes(StandardCharsets.UTF_8)));
        if (!prefs.edit().putString("record", value).commit()) throw new IllegalStateException("凭据保存失败");
    }
}
