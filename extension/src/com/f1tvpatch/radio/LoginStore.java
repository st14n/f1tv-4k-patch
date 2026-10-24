package com.f1tvpatch.radio;

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

/**
 * The F1 account used for automatic re-login, encrypted with a key that never leaves the Android
 * Keystore. Delivered once from the PC (scripts/set_login.sh); never part of the APK or a release.
 */
final class LoginStore {
    private LoginStore() { }

    private static final String KEY_ALIAS = "f1tvpatch_login";
    private static final String PREF_EMAIL = "login_email";
    private static final String PREF_PASSWORD = "login_password";

    static boolean has(Context c) {
        SharedPreferences prefs = Updater.prefs(c);
        return prefs.contains(PREF_EMAIL) && prefs.contains(PREF_PASSWORD);
    }

    static void save(Context c, String email, String password) throws Exception {
        Updater.prefs(c).edit()
                .putString(PREF_EMAIL, encrypt(email))
                .putString(PREF_PASSWORD, encrypt(password))
                .apply();
    }

    /** {email, password}, or null when none is stored or it can no longer be decrypted. */
    static String[] load(Context c) {
        SharedPreferences prefs = Updater.prefs(c);
        String email = prefs.getString(PREF_EMAIL, null);
        String password = prefs.getString(PREF_PASSWORD, null);
        if (email == null || password == null) return null;
        try {
            return new String[]{decrypt(email), decrypt(password)};
        } catch (Exception e) {
            return null;
        }
    }

    static void clear(Context c) {
        Updater.prefs(c).edit().remove(PREF_EMAIL).remove(PREF_PASSWORD).apply();
    }

    private static SecretKey key() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        KeyStore.Entry entry = keyStore.getEntry(KEY_ALIAS, null);
        if (entry instanceof KeyStore.SecretKeyEntry) return ((KeyStore.SecretKeyEntry) entry).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return generator.generateKey();
    }

    private static String encrypt(String plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] iv = cipher.getIV();
        byte[] sealed = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(sealed, Base64.NO_WRAP);
    }

    private static String decrypt(String stored) throws Exception {
        int split = stored.indexOf(':');
        byte[] iv = Base64.decode(stored.substring(0, split), Base64.NO_WRAP);
        byte[] sealed = Base64.decode(stored.substring(split + 1), Base64.NO_WRAP);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(sealed), StandardCharsets.UTF_8);
    }
}
