package br.com.rian.deck;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class KioskPin {
    private static final String KEY = "riandeck.kiosk.pin.v1";
    private static final int ATTEMPTS = 5;
    private static final long COOLDOWN = 60000;
    private final SharedPreferences preferences;

    KioskPin(SharedPreferences preferences) { this.preferences = preferences; }

    boolean configured() { return preferences.contains("kiosk-pin"); }

    static boolean valid(String pin) { return pin.matches("[0-9]{6,10}"); }

    synchronized void setup(String pin) throws GeneralSecurityException {
        if (!valid(pin) || configured()) throw new GeneralSecurityException("PIN inválido ou já configurado");
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        byte[] check = digest(pin, salt, true);
        if (!preferences.edit().putString("kiosk-salt", encode(salt)).putString("kiosk-pin", encode(check))
            .remove("maintenance-pin").remove("maintenance-salt").commit())
            throw new GeneralSecurityException("Falha ao salvar PIN");
    }

    synchronized long delaySeconds() {
        long remaining = preferences.getLong("kiosk-until", 0) - System.currentTimeMillis();
        return (Math.max(0, Math.min(COOLDOWN, remaining)) + 999) / 1000;
    }

    synchronized boolean verify(String candidate) throws GeneralSecurityException {
        if (!configured() || delaySeconds() > 0) return false;
        byte[] salt = decode(preferences.getString("kiosk-salt", ""));
        byte[] expected = decode(preferences.getString("kiosk-pin", ""));
        if (valid(candidate) && MessageDigest.isEqual(expected, digest(candidate, salt, false))) {
            preferences.edit().remove("kiosk-failures").remove("kiosk-until").commit();
            return true;
        }
        int failures = preferences.getInt("kiosk-failures", 0) + 1;
        SharedPreferences.Editor change = preferences.edit().putInt("kiosk-failures", failures % ATTEMPTS);
        if (failures >= ATTEMPTS) change.putLong("kiosk-until", System.currentTimeMillis() + COOLDOWN);
        change.commit();
        return false;
    }

    private byte[] digest(String pin, byte[] salt, boolean create) throws GeneralSecurityException {
        KeyStore keystore = KeyStore.getInstance("AndroidKeyStore");
        try { keystore.load(null); }
        catch (java.io.IOException problem) { throw new GeneralSecurityException(problem); }
        SecretKey key = (SecretKey) keystore.getKey(KEY, null);
        if (key == null && create) {
            KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_SIGN).build());
            key = generator.generateKey();
        }
        if (key == null) throw new GeneralSecurityException("Chave do PIN ausente");
        PBEKeySpec spec = new PBEKeySpec(pin.toCharArray(), salt, 120000, 256);
        byte[] derived = null;
        try {
            derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return mac.doFinal(derived);
        } finally {
            spec.clearPassword();
            if (derived != null) java.util.Arrays.fill(derived, (byte) 0);
        }
    }

    private static String encode(byte[] value) { return Base64.encodeToString(value, Base64.NO_WRAP); }
    private static byte[] decode(String value) { return Base64.decode(value, Base64.DEFAULT); }
}
