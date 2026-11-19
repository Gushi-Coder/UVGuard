package com.uvguard.companion;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** AES-GCM ciphertext in private preferences; the AES key stays in AndroidKeyStore.
 * This protects storage, not a rooted phone, malicious keyboard or an unlocked app process.
 * No provider key is bundled, copied to logs, placed in backup or returned to an EditText.
 */
public final class AiKeyStore {
    private final SharedPreferences prefs;
    private final String alias;
    public AiKeyStore(Context context){this(context,"deepseek_key_v1","uvguard.deepseek.aes.v1");}
    // Separate alias/preferences let instrumentation test encryption without touching user keys.
    AiKeyStore(Context context,String preferences,String keyAlias){prefs=context.getSharedPreferences(preferences,0);alias=keyAlias;}
    private KeyStore store()throws Exception{KeyStore s=KeyStore.getInstance("AndroidKeyStore");s.load(null);return s;}
    private SecretKey key(boolean create)throws Exception{
        KeyStore s=store();
        if(s.containsAlias(alias))return (SecretKey)s.getKey(alias,null);
        if(!create)throw new IllegalStateException("密钥保护项已失效，请删除后重新保存API密钥。");
        KeyGenerator gen=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        gen.init(new KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).setKeySize(256).build());
        return gen.generateKey();
    }
    public boolean hasSaved(){return prefs.contains("ciphertext");}
    public void save(String secret)throws Exception{
        if(!DeepSeekPolicy.validKey(secret))throw new IllegalArgumentException("API密钥格式不正确。");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key(true));
        byte[] encrypted=cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
        if(!prefs.edit().putString("iv",Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP))
            .putString("ciphertext",Base64.encodeToString(encrypted,Base64.NO_WRAP)).commit())
            throw new java.io.IOException("无法保存加密密钥。");
    }
    public String load()throws Exception{
        if(!hasSaved())throw new IllegalStateException("请先在AI设置保存自己的DeepSeek API密钥。");
        byte[] iv=Base64.decode(prefs.getString("iv",""),Base64.NO_WRAP);
        byte[] data=Base64.decode(prefs.getString("ciphertext",""),Base64.NO_WRAP);
        if(iv.length!=12||data.length<16||data.length>1024)throw new IllegalStateException("密钥保存项损坏，请删除后重新保存。");
        Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,key(false),new GCMParameterSpec(128,iv));
        String secret=new String(cipher.doFinal(data),StandardCharsets.UTF_8);
        if(!DeepSeekPolicy.validKey(secret))throw new IllegalStateException("密钥保存项无效。");
        return secret;
    }
    public void delete()throws Exception{
        // Delete the cryptographic key first, so even stale ciphertext cannot be decrypted.
        KeyStore s=store();if(s.containsAlias(alias))s.deleteEntry(alias);
        if(!prefs.edit().clear().commit())throw new java.io.IOException("无法清除密钥保存项，请重试。");
    }
}
