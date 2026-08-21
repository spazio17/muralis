/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

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
    private static final String TAG = "MuralisSecrets";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "kiosk-config-aes-v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final byte FORMAT_VERSION = 1;

    private final SharedPreferences preferences;

    SecretStore(Context context) {
        preferences = context.getSharedPreferences("kiosk_secrets", Context.MODE_PRIVATE);
    }

    /**
     * Removes a stored secret, deliberately and on request.
     *
     * <p>Separate from {@link #put} with an empty value because the two are different intentions.
     * Clearing the admin password is a real feature — it switches the web admin off — and must work
     * even while the Keystore is unavailable. An incidental write of a value that could not be read
     * must not delete anything; see {@link #getOrNull}.
     */
    void clear(String name) {
        preferences.edit().remove(name).apply();
    }

    void put(String name, String value) {
        if (value == null || value.isEmpty()) {
            clear(name);
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
            // Fail soft, like everywhere else here. This used to throw, and since a settings save
            // runs on an HTTP worker thread, an unavailable Keystore took the whole process down.
            // The old value is deliberately left in place: overwriting it with nothing would turn a
            // transient encryption failure into a lost credential.
            Log.e(TAG, "Unable to encrypt configuration value; leaving the stored one unchanged",
                    exception);
        }
    }

    /** Empty for anything unreadable, which is what most callers want. See {@link #getOrNull}. */
    String get(String name) {
        String value = getOrNull(name);
        return value == null ? "" : value;
    }

    /**
     * The stored secret, {@code ""} when nothing is stored, or {@code null} when a value IS stored
     * but could not be decrypted on this attempt.
     *
     * <p>The three-way answer exists because collapsing the last two into {@code ""} caused a real
     * loss-of-credential path. A Keystore that is briefly unavailable — notably direct boot, where
     * BootReceiver starts KioskService before the user has unlocked — made every secret read as
     * empty, and the next {@code KioskConfig.save()} wrote those empties straight back, which
     * {@link #put} turns into a delete. One unrelated settings save during that window and the admin
     * password and broker credentials were gone for good: the web admin fails closed and never binds
     * again, while MQTT fails OPEN and silently reconnects anonymously. Callers that persist must
     * use this method and skip what they could not read.
     */
    String getOrNull(String name) {
        String encoded = preferences.getString(name, "");
        if (encoded.isEmpty()) {
            return "";
        }

        try {
            ByteBuffer envelope = ByteBuffer.wrap(Base64.decode(encoded, Base64.NO_WRAP));
            // IllegalArgumentException, not GeneralSecurityException: these two describe stored
            // bytes that are definitively malformed, so they belong in the "discard it" branch
            // below rather than the "the Keystore was busy, try again later" one.
            if (envelope.get() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported secret format");
            }
            int ivLength = Byte.toUnsignedInt(envelope.get());
            if (ivLength < 12 || ivLength > 32 || envelope.remaining() <= ivLength) {
                throw new IllegalArgumentException("Invalid secret envelope");
            }
            byte[] iv = new byte[ivLength];
            envelope.get(iv);
            byte[] ciphertext = new byte[envelope.remaining()];
            envelope.get(ciphertext);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(128, iv));
            cipher.updateAAD(name.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (javax.crypto.BadPaddingException | javax.crypto.IllegalBlockSizeException
                | IllegalArgumentException | java.nio.BufferUnderflowException corrupt) {
            // The stored bytes really are unreadable: a failed GCM tag, a truncated envelope, an
            // envelope too short to even read its header, or Base64 that will not decode. Nothing
            // will ever recover this value, so drop it rather than re-failing on it forever.
            Log.e(TAG, "Discarding an unreadable encrypted configuration value", corrupt);
            preferences.edit().remove(name).apply();
            return "";
        } catch (GeneralSecurityException environmental) {
            // The Keystore itself was unavailable, not the data. Deleting here was a real hazard:
            // a transient failure — notably the direct-boot path, where BootReceiver starts
            // KioskService before the user has unlocked — would permanently wipe the admin password
            // and the broker credentials. The web admin then fails closed and the panel is
            // unreachable except at the glass, which is the one stuck state this product cannot
            // afford. Report empty for this read and leave the ciphertext alone so the next read,
            // once the Keystore is back, returns the real value.
            Log.e(TAG, "Could not decrypt configuration value; keeping it for a later attempt",
                    environmental);
            return null;
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
