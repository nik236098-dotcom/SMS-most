package ru.smsbridge.app;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class Crypto {
    private static final String ALIAS = "sms_bridge_aes_v1";
    // Loading AndroidKeyStore is a binder round trip. Every setting read used to pay it.
    private static volatile SecretKey cached;
    private static SecretKey key() throws Exception {
        SecretKey k=cached;if(k!=null)return k;
        synchronized(Crypto.class){if(cached==null)cached=load();return cached;}
    }
    private static synchronized void forget(SecretKey stale){if(cached==stale)cached=null;}
    private static SecretKey load() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build()); g.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
    static String seal(String plaintext) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        SecretKey k=key();try{c.init(Cipher.ENCRYPT_MODE,k);}catch(java.security.InvalidKeyException e){forget(k);c.init(Cipher.ENCRYPT_MODE,key());}
        return Base64.encodeToString(c.getIV(), Base64.NO_WRAP) + ":" +
            Base64.encodeToString(c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
    }
    static String open(String encrypted) throws Exception {
        String[] parts = encrypted.split(":", 2);
        if (parts.length != 2) throw new IllegalStateException("Невозможно прочитать защищённые данные");
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec iv=new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP));
        SecretKey k=key();try{c.init(Cipher.DECRYPT_MODE,k,iv);}catch(java.security.InvalidKeyException e){forget(k);c.init(Cipher.DECRYPT_MODE,key(),iv);}
        return new String(c.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
    }
}
