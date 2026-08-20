/*
 * Copyright 2026 KiOSk contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.kiosk.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
    private static final String TAG = "KiOSkSecrets";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "kiosk-config-aes-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT_VERSION = 1;

    private final SharedPreferences preferences;

    SecretStore(Context context) {
        preferences = context.getSharedPreferences("kiosk_secrets", Context.MODE_PRIVATE);
    }

    void put(String name, String value) {
        if (value == null || value.isEmpty()) {
            preferences.edit().remove(name).apply();
            return;
        }

        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] iv = cipher.getIV();
            ByteBuffer envelope = ByteBuffer.allocate(2 + iv.length + ciphertext.length);
            envelope.put(FORMAT_VERSION);
            envelope.put((byte) iv.length);
            envelope.put(iv);
            envelope.put(ciphertext);
            preferences.edit().putString(name,
                    Base64.encodeToString(envelope.array(), Base64.NO_WRAP)).apply();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt kiosk configuration", exception);
        }
    }

    String get(String name) {
        String encoded = preferences.getString(name, "");
        if (encoded.isEmpty()) {
            return "";
        }

        try {
            ByteBuffer envelope = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            if (envelope.get() != FORMAT_VERSION) {
                throw new GeneralSecurityException("Unsupported secret format");
            }
            int ivLength = Byte.toUnsignedInt(envelope.get());
            if (ivLength < 12 || ivLength > 32 || envelope.remaining() <= ivLength) {
                throw new GeneralSecurityException("Invalid secret envelope");
            }
            byte[] iv = new byte[ivLength];
            envelope.get(iv);
            byte[] ciphertext = new byte[envelope.remaining()];
            envelope.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException exception) {
            Log.e(TAG, "Discarding an unreadable encrypted configuration value", exception);
            preferences.edit().remove(name).apply();
            return "";
        }
    }

    private SecretKey getOrCreateKey() throws GeneralSecurityException {
        KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
        try {
            keyStore.load(null);
        } catch (java.io.IOException exception) {
            throw new GeneralSecurityException(exception);
        }
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) {
            return existing;
        }

        KeyGenerator generator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }
}
