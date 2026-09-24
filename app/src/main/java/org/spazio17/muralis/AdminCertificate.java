/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import java.io.ByteArrayInputStream;
import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Calendar;
import java.util.Date;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * The certificate the web admin serves HTTPS with, made by the panel itself: an EC P-256 key
 * pair generated in software, kept encrypted at rest through {@link SecretStore}, and a
 * self-signed X.509 built by {@link SelfSignedCertificate}.
 *
 * <p>Until 2026-09-24 the key pair lived in the Android Keystore, which issued the certificate
 * for free and kept the private key in the hardware. The hardware also signed every TLS
 * handshake, and on the Huawei tablet one such signature costs about 0.4 s: a full handshake
 * measured 0.55 s where a resumed one measured 0.04 s ({@code openssl s_time}). Every response
 * closes its connection, a browser opens six at once for a page of thumbnails, and six
 * handshakes queued behind one Keymaster overran the server's 2 s handshake deadline: the page
 * said "the panel did not answer" and the thumbnails never loaded. A software key signs in
 * well under a millisecond. What is given up: a private key that root could read out of the
 * app's storage instead of never leaving the hardware, on a certificate whose only job is to
 * keep the admin password off the wire of a LAN. What the Keystore still does: encrypt that
 * key at rest, once, through SecretStore. The old Keystore key is deleted the first time this
 * runs, so a panel updated across that date shows a new fingerprint once.
 *
 * <p>No authority signs it, so a browser warns once and a person accepts it, comparing the
 * fingerprint the browser shows with the one the tablet and the web admin print. That is the
 * trust model of a LAN device with no public name, and it is what turns the admin password from
 * a header any neighbour can read into one only the panel sees. A user with their own CA can
 * later install a certificate of their own; this one is what every panel has out of the box.
 *
 * <p>There is no address in the certificate, since a panel's address changes and the certificate
 * lasts ten years, so the browser's second warning, the name mismatch, is accepted by the same
 * click. Ten years of validity: a panel is installed and forgotten, and a certificate that
 * expires on the wall is a panel nobody can administer.
 */
final class AdminCertificate {
    private static final String TAG = "MuralisTls";
    /** The SecretStore names: the private key as PKCS#8, and the certificate as DER, both base64. */
    private static final String KEY_NAME = "admin_tls_key";
    private static final String CERTIFICATE_NAME = "admin_tls_certificate";
    /** The Keystore alias the key pair had until 2026-09-24; deleted once the software key exists. */
    private static final String RETIRED_KEYSTORE_ALIAS = "muralis-admin-tls";
    private static final String ALIAS = "muralis-admin-tls";
    private static final int VALID_YEARS = 10;

    final SSLSocketFactory socketFactory;
    /** SHA-256 of the certificate, colon-separated, the format browsers show. */
    final String fingerprint;

    private AdminCertificate(SSLSocketFactory socketFactory, String fingerprint) {
        this.socketFactory = socketFactory;
        this.fingerprint = fingerprint;
    }

    /**
     * The panel's certificate, created on first use, or null when it cannot be had right now.
     *
     * <p>Null also when the stored key is there but cannot be decrypted at this moment, which is
     * what SecretStore reports before the first unlock: a new key is made only when nothing is
     * stored, never over a key that is merely unreadable, so a Keystore that is slow to wake
     * cannot cost the operator the fingerprint they wrote down.
     */
    static AdminCertificate load(Context context) {
        try {
            SecretStore secrets = new SecretStore(context);
            String storedKey = secrets.getOrNull(KEY_NAME);
            String storedCertificate = secrets.getOrNull(CERTIFICATE_NAME);
            if (storedKey == null || storedCertificate == null) {
                Log.e(TAG, "The web admin's TLS key cannot be read right now; staying on plain HTTP");
                return null;
            }
            PrivateKey key;
            X509Certificate certificate;
            if (storedKey.isEmpty() || storedCertificate.isEmpty()) {
                KeyPair pair = generate();
                certificate = issue(pair, KioskConfig.load(context).deviceId);
                key = pair.getPrivate();
                secrets.put(KEY_NAME, Base64.encodeToString(key.getEncoded(), Base64.NO_WRAP));
                secrets.put(CERTIFICATE_NAME,
                        Base64.encodeToString(certificate.getEncoded(), Base64.NO_WRAP));
                if (!secrets.has(KEY_NAME) || !secrets.has(CERTIFICATE_NAME)) {
                    Log.w(TAG, "The web admin's TLS certificate could not be kept; "
                            + "a new one is made at the next start");
                }
                retireKeystoreKey();
                Log.i(TAG, "Created the web admin's TLS certificate");
            } else {
                key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(
                        Base64.decode(storedKey, Base64.NO_WRAP)));
                certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(
                                Base64.decode(storedCertificate, Base64.NO_WRAP)));
            }
            // One alias, served directly: the default key manager would want a KeyStore, and a
            // key manager of one key needs none.
            X509Certificate[] chain = {certificate};
            SSLContext tls = SSLContext.getInstance("TLS");
            tls.init(new KeyManager[] {new SingleKeyManager(key, chain)}, null, null);
            return new AdminCertificate(tls.getSocketFactory(),
                    TlsPresentation.fingerprint(certificate.getEncoded()));
        } catch (GeneralSecurityException | RuntimeException failure) {
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

    private static KeyPair generate() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static X509Certificate issue(KeyPair pair, String deviceId)
            throws GeneralSecurityException {
        Calendar notBefore = Calendar.getInstance();
        notBefore.add(Calendar.DAY_OF_MONTH, -1);
        Calendar notAfter = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, VALID_YEARS);
        return SelfSignedCertificate.issue(pair, "Muralis " + deviceId,
                new Date(notBefore.getTimeInMillis()), new Date(notAfter.getTimeInMillis()),
                BigInteger.valueOf(System.currentTimeMillis()));
    }

    /** Removes the Keystore key pair of the earlier design, so no unused key lingers there. */
    private static void retireKeystoreKey() {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);
            if (keyStore.containsAlias(RETIRED_KEYSTORE_ALIAS)) {
                keyStore.deleteEntry(RETIRED_KEYSTORE_ALIAS);
                Log.i(TAG, "Removed the Keystore TLS key of the earlier design");
            }
        } catch (GeneralSecurityException | java.io.IOException | RuntimeException ignored) {
            // A key left behind costs nothing but an alias; the new one is what serves.
        }
    }
}
