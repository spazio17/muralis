/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Log;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Calendar;
import java.util.Date;

import java.security.PrivateKey;
import java.security.cert.X509Certificate;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.security.auth.x500.X500Principal;

/**
 * The certificate the web admin serves HTTPS with, made by the panel itself and kept in the
 * Android Keystore, where the private key never leaves the hardware or the system process.
 *
 * <p>No authority signs it, so a browser warns once and a person accepts it, comparing the
 * fingerprint the browser shows with the one the tablet and the web admin print. That is the
 * trust model of a LAN device with no public name, and it is what turns the admin password from
 * a header any neighbour can read into one only the panel sees. A user with their own CA can
 * later install a certificate of their own; this one is what every panel has out of the box.
 *
 * <p>The Keystore makes the X.509 certificate along with the key pair, so no library is needed.
 * It cannot carry the panel's address as a name, which is why the browser's second warning, the
 * name mismatch, is accepted by the same click. Ten years of validity: a panel is installed and
 * forgotten, and a certificate that expires on the wall is a panel nobody can administer.
 */
final class AdminCertificate {
    private static final String TAG = "MuralisTls";
    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "muralis-admin-tls";
    private static final int VALID_YEARS = 10;

    final SSLSocketFactory socketFactory;
    /** SHA-256 of the certificate, colon-separated, the format browsers show. */
    final String fingerprint;

    private AdminCertificate(SSLSocketFactory socketFactory, String fingerprint) {
        this.socketFactory = socketFactory;
        this.fingerprint = fingerprint;
    }

    /** The panel's certificate, created on first use, or null when this device's Keystore cannot. */
    static AdminCertificate load(Context context) {
        try {
            KeyStore keyStore = KeyStore.getInstance(KEYSTORE);
            keyStore.load(null);
            if (keyStore.containsAlias(ALIAS) && !usableForTls(keyStore)) {
                // A key made before DIGEST_NONE was authorised cannot sign a handshake; there is
                // no such key on any released build, only on test devices, but self-healing is
                // cheaper than remembering that.
                keyStore.deleteEntry(ALIAS);
                Log.i(TAG, "Replaced a TLS key that could not sign handshakes");
            }
            if (!keyStore.containsAlias(ALIAS)) {
                generate(KioskConfig.load(context).deviceId);
            }
            Certificate certificate = keyStore.getCertificate(ALIAS);
            PrivateKey key = (PrivateKey) keyStore.getKey(ALIAS, null);
            if (!(certificate instanceof X509Certificate) || key == null) {
                Log.e(TAG, "Keystore holds no usable TLS key and certificate");
                return null;
            }
            // One alias, served directly. The default key manager walks every alias in the
            // Keystore and asks each for a certificate, which SecretStore's AES key does not have,
            // and the Keystore logs a stack trace for the question.
            X509Certificate[] chain = {(X509Certificate) certificate};
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(new KeyManager[] {new SingleKeyManager(key, chain)}, null, null);
            return new AdminCertificate(tls.getSocketFactory(),
                    TlsPresentation.fingerprint(certificate.getEncoded()));
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException failure) {
            Log.e(TAG, "This device cannot serve HTTPS; the web admin stays on plain HTTP", failure);
            return null;
        }
    }

    private static final class SingleKeyManager extends X509ExtendedKeyManager {
        private final PrivateKey key;
        private final X509Certificate[] chain;

        SingleKeyManager(PrivateKey key, X509Certificate[] chain) {
            this.key = key;
            this.chain = chain;
        }

        @Override public String chooseServerAlias(String keyType, java.security.Principal[] issuers, java.net.Socket socket) { return ALIAS; }
        @Override public String chooseEngineServerAlias(String keyType, java.security.Principal[] issuers, javax.net.ssl.SSLEngine engine) { return ALIAS; }
        @Override public String[] getServerAliases(String keyType, java.security.Principal[] issuers) { return new String[] {ALIAS}; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? chain.clone() : null; }
        @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
        @Override public String chooseClientAlias(String[] keyType, java.security.Principal[] issuers, java.net.Socket socket) { return null; }
        @Override public String[] getClientAliases(String keyType, java.security.Principal[] issuers) { return null; }
    }

    /**
     * Conscrypt hashes the handshake transcript itself and asks the Keystore to sign the digest
     * as-is ({@code NONEwithECDSA}), so the key must be authorised for {@code DIGEST_NONE}; one
     * authorised only for SHA-256 answers "Incompatible digest" and the handshake dies with an
     * I/O error. Measured on the Lenovo 2026-09-09.
     */
    private static boolean usableForTls(KeyStore keyStore) {
        try {
            PrivateKey key = (PrivateKey) keyStore.getKey(ALIAS, null);
            android.security.keystore.KeyInfo info = java.security.KeyFactory
                    .getInstance(key.getAlgorithm(), KEYSTORE)
                    .getKeySpec(key, android.security.keystore.KeyInfo.class);
            for (String digest : info.getDigests()) {
                if (KeyProperties.DIGEST_NONE.equals(digest)) {
                    return true;
                }
            }
            return false;
        } catch (GeneralSecurityException | RuntimeException unreadable) {
            // Only a key that is readable and demonstrably lacks the digest is replaced. A Keystore
            // that cannot answer right now (busy, early in the boot) must not cost the operator the
            // fingerprint they wrote down: that is what an attacker's certificate would look like.
            return true;
        }
    }

    private static void generate(String deviceId) throws GeneralSecurityException {
        Calendar notBefore = Calendar.getInstance();
        notBefore.add(Calendar.DAY_OF_MONTH, -1);
        Calendar notAfter = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, VALID_YEARS);
        KeyPairGenerator generator = KeyPairGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_EC, KEYSTORE);
        generator.initialize(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY)
                .setDigests(KeyProperties.DIGEST_NONE, KeyProperties.DIGEST_SHA256,
                        KeyProperties.DIGEST_SHA384)
                .setCertificateSubject(new X500Principal("CN=Muralis " + deviceId))
                .setCertificateSerialNumber(BigInteger.valueOf(System.currentTimeMillis()))
                .setCertificateNotBefore(new Date(notBefore.getTimeInMillis()))
                .setCertificateNotAfter(new Date(notAfter.getTimeInMillis()))
                .build());
        generator.generateKeyPair();
        Log.i(TAG, "Created the web admin's TLS certificate");
    }
}
