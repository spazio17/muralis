/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
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
import java.util.List;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;

/**
 * The certificate the web admin serves HTTPS with, made by the panel itself: an EC P-256 key
 * pair generated in software, kept encrypted at rest through {@link SecretStore} in
 * device-protected storage, and a
 * self-signed X.509 built by {@link SelfSignedCertificate}.
 *
 * <p>Until 2026-09-24 the key pair lived in the Android Keystore, which issued the certificate
 * for free and kept the private key in the hardware. The hardware also signed every TLS
 * handshake, and on the Huawei tablet one such signature costs about 0.4 s: a full handshake
 * measured 0.55 s where a resumed one measured 0.04 s ({@code openssl s_time}). Every response
 * then closed its connection, a browser opens six at once for a page of thumbnails, and six
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
 * a header any neighbour can read into one only the panel sees. This one is what every panel has
 * out of the box.
 *
 * <p>A person with a certificate of their own, from their own authority or from Let's Encrypt for
 * a name of theirs, uploads it with its key on the web admin ({@link #upload}, read and checked by
 * {@link CertificateUpload}), and the panel serves it while "Uploaded" is chosen
 * ({@code http_certificate}, on both settings surfaces). The key is kept like the panel's own,
 * through SecretStore in device-protected storage; its names, its end and its fingerprint are
 * plain settings, since a certificate is public. An upload that cannot be read at a start, a
 * Keystore not awake yet or a stored pair that no longer verifies, is not served: the panel's own
 * certificate is, and the reason is said on both surfaces, so a bad upload never takes the web
 * admin away (2026-10-01).
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
    /** The SecretStore names of an uploaded certificate: its key and its chain, as CertificateUpload keeps them. */
    private static final String UPLOAD_KEY_NAME = "admin_tls_upload_key";
    private static final String UPLOAD_CHAIN_NAME = "admin_tls_upload_chain";
    private static final String ALIAS = "muralis-admin-tls";
    private static final int VALID_YEARS = 10;

    /** Null when no certificate can be had right now: the web admin then stays closed. */
    final SSLSocketFactory socketFactory;
    /** SHA-256 of the certificate, colon-separated, the format browsers show; empty while closed. */
    final String fingerprint;
    /** Whether the uploaded certificate is the one served. */
    final boolean uploaded;
    /** Why the uploaded certificate, chosen, is not the one served; null when it is or is not chosen. */
    final String uploadProblem;

    private AdminCertificate(SSLSocketFactory socketFactory, String fingerprint, boolean uploaded,
            String uploadProblem) {
        this.socketFactory = socketFactory;
        this.fingerprint = fingerprint;
        this.uploaded = uploaded;
        this.uploadProblem = uploadProblem;
    }

    /** Whether a certificate is served at all, HTTPS rather than the closed admin. */
    boolean secure() {
        return socketFactory != null;
    }

    /**
     * The certificate the web admin serves: the uploaded one while it is chosen and can be read,
     * this panel's own otherwise ({@link #loadOwn}), or none when not even that can be had now.
     * Never null, so why an upload chosen is not served is said even then (review, 2026-10-05:
     * with both unreadable before the first unlock, the reason was dropped).
     */
    static AdminCertificate load(Context context) {
        String problem = null;
        if (KioskConfig.CERTIFICATE_UPLOADED.equals(KioskConfig.httpCertificateOf(context))) {
            SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
            String chain = secrets.getOrNull(UPLOAD_CHAIN_NAME);
            String key = secrets.getOrNull(UPLOAD_KEY_NAME);
            if (chain == null || key == null) {
                // SecretStore before the Keystore will decrypt; KioskService tries again at the
                // first unlock.
                problem = "it cannot be read yet";
            } else if (chain.isEmpty() || key.isEmpty()) {
                problem = "it is no longer kept on this panel; upload it again";
            } else {
                CertificateUpload upload = CertificateUpload.restore(chain, key);
                if (upload == null) {
                    problem = "it no longer reads as a certificate and its key; upload it again";
                } else {
                    try {
                        SSLContext tls = SSLContext.getInstance("TLS");
                        tls.init(new KeyManager[] {new SingleKeyManager(upload.key, upload.chain)},
                                null, null);
                        return new AdminCertificate(tls.getSocketFactory(),
                                TlsPresentation.fingerprint(upload.chain[0].getEncoded()), true,
                                null);
                    } catch (GeneralSecurityException | RuntimeException failure) {
                        Log.e(TAG, "The uploaded certificate cannot be served", failure);
                        problem = "this device cannot serve it";
                    }
                }
            }
            Log.w(TAG, "The uploaded certificate is not served: " + problem
                    + "; serving this panel's own");
        }
        AdminCertificate own = loadOwn(context);
        if (own == null) {
            return new AdminCertificate(null, "", false, problem);
        }
        return problem == null ? own
                : new AdminCertificate(own.socketFactory, own.fingerprint, false, problem);
    }

    /** One upload or Delete at a time: two renewals at once kept one's chain and the other's key. */
    private static final Object STORE = new Object();

    /**
     * Keeps an uploaded certificate and its key, and chooses it, once {@link CertificateUpload}
     * has read the pair and shown the key to belong to the certificate; nothing is kept before
     * that. The web admin serves it once the caller has it read again.
     *
     * @return why it was refused, a fragment to follow "Not uploaded: ", or null when kept
     */
    static String upload(Context context, String certificateText, String keyText) {
        CertificateUpload.Result read = CertificateUpload.parse(certificateText, keyText);
        if (read.problem != null) {
            return read.problem;
        }
        String chain = read.upload.chainText();
        String key = read.upload.keyText();
        X509Certificate first = read.upload.chain[0];
        synchronized (STORE) {
            SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
            String oldChain = secrets.getOrNull(UPLOAD_CHAIN_NAME);
            String oldKey = secrets.getOrNull(UPLOAD_KEY_NAME);
            secrets.put(UPLOAD_CHAIN_NAME, chain);
            secrets.put(UPLOAD_KEY_NAME, key);
            // Read back, not has(): SecretStore leaves the old value in place when it cannot
            // encrypt, so has() said yes over a renewal that was never kept (review, 2026-10-05).
            String keptChain = secrets.getOrNull(UPLOAD_CHAIN_NAME);
            String keptKey = secrets.getOrNull(UPLOAD_KEY_NAME);
            if (!chain.equals(keptChain) || !key.equals(keptKey)) {
                Log.e(TAG, "An uploaded certificate could not be kept");
                if (java.util.Objects.equals(keptChain, oldChain)
                        && java.util.Objects.equals(keptKey, oldKey)) {
                    // Nothing was written: the pair kept before, if there was one, is still whole
                    // and still served.
                    return oldChain == null || oldChain.isEmpty()
                            ? "this panel could not keep it; try again"
                            : "this panel could not keep it, and the one it had stays; try again";
                }
                // A pair half written is no pair: both go, and the person is told.
                forget(context, secrets);
                return "this panel could not keep it; upload it again";
            }
            try {
                List<String> names = CertificateUpload.nameList(first);
                KioskConfig.edit(context)
                        .httpUpload(TlsPresentation.fingerprint(first.getEncoded()), names.get(0),
                                CertificateUpload.names(first), first.getNotAfter().getTime())
                        .httpCertificate(KioskConfig.CERTIFICATE_UPLOADED)
                        .apply();
            } catch (java.security.cert.CertificateEncodingException impossible) {
                throw new IllegalStateException(impossible);
            }
        }
        Log.i(TAG, "Kept an uploaded certificate for " + CertificateUpload.names(first));
        return null;
    }

    /**
     * Forgets the uploaded certificate and its key; the panel's own is chosen again. With
     * {@code expectedSha256}, the upload the question named: a renewal that landed while the
     * question was open is not deleted in its place (review, 2026-10-05).
     *
     * @return why nothing was deleted, a fragment to follow "Not deleted: ", or null when done
     */
    static String removeUpload(Context context, String expectedSha256) {
        synchronized (STORE) {
            String kept = KioskConfig.certificateFacts(context).httpUploadSha256;
            if (kept.isEmpty()) {
                return "no certificate is uploaded";
            }
            // Colons and case do not count: a script may send the hex as sha256sum prints it.
            if (expectedSha256 != null && !expectedSha256.trim().isEmpty()
                    && !hex(expectedSha256).equals(hex(kept))) {
                return "the uploaded certificate was replaced since this was asked; nothing was "
                        + "deleted";
            }
            forget(context, new SecretStore(KioskConfig.storageContext(context)));
        }
        Log.i(TAG, "Removed the uploaded certificate");
        return null;
    }

    private static String hex(String fingerprint) {
        return fingerprint.replaceAll("[^0-9A-Fa-f]", "").toUpperCase(java.util.Locale.ROOT);
    }

    private static void forget(Context context, SecretStore secrets) {
        secrets.clear(UPLOAD_CHAIN_NAME);
        secrets.clear(UPLOAD_KEY_NAME);
        KioskConfig.edit(context)
                .httpUpload("", "", "", 0)
                .httpCertificate(KioskConfig.CERTIFICATE_OWN)
                .apply();
    }

    // ---- the words both surfaces use for it, worded once

    /**
     * The sentence above the fingerprint; {@code page} is how the surface names the web admin's
     * page, "this page" on it and "that page" on the panel.
     */
    static String servedSentence(boolean uploaded, String page) {
        return (uploaded ? "Served over HTTPS with the uploaded certificate."
                : "Served over HTTPS with a certificate this panel made itself, so your browser "
                        + "warned once.")
                + " Its SHA-256 fingerprint, to compare with the one the browser shows for "
                + page + ":";
    }

    static final String CLOSED_WITHOUT_TLS_SENTENCE = "Closed: this panel cannot use its "
            + "certificate right now, and the web admin never takes the password unencrypted. "
            + "After a restart it opens once the device has been unlocked.";

    /** Why the uploaded certificate, chosen, is not served, and what is served instead. */
    static String notServedSentence(String problem, boolean ownServed) {
        return "The uploaded certificate is not served: " + problem + "."
                + (ownServed ? " This panel's own is." : "");
    }

    /** The reason beside the greyed Uploaded choice while nothing is uploaded, on the web admin. */
    static final String NOTHING_UPLOADED = "Nothing is uploaded yet.";

    /** The same on the panel, which says where an upload is made, since it cannot make one. */
    static final String NOTHING_UPLOADED_HERE =
            "Nothing is uploaded yet; certificates are uploaded on the web admin.";

    /** What else a Delete of the upload changes, said after "... will be deleted." on both surfaces. */
    static final String DELETE_CONSEQUENCE =
            "Its key goes with it, and the web admin serves this panel's own certificate again.";

    /** The upload as its row says it, "panel.lan, 192.168.1.20 · until 4 Oct 2027", or "None yet". */
    static String uploadLine(KioskConfig config, long nowMs) {
        return config.httpUploadSha256.isEmpty() ? "None yet"
                : config.httpUploadNames + " · "
                        + CertificateUpload.validity(config.httpUploadUntil, nowMs);
    }

    /** Whether the upload kept has expired, which both surfaces show in the error colour. */
    static boolean uploadExpired(KioskConfig config, long nowMs) {
        return !config.httpUploadSha256.isEmpty()
                && CertificateUpload.expired(config.httpUploadUntil, nowMs);
    }

    /**
     * The upload's first name, what a Delete calls it; from the names as shown when that was
     * not kept on its own, and a plain description when there is no name at all.
     */
    static String mainName(KioskConfig config) {
        if (!config.httpUploadName.trim().isEmpty()) {
            return config.httpUploadName;
        }
        String shown = config.httpUploadNames;
        int comma = shown.indexOf(", ");
        String first = (comma < 0 ? shown : shown.substring(0, comma)).trim();
        return first.isEmpty() ? "uploaded certificate" : first;
    }

    /**
     * Everything the certificate parts of both surfaces are drawn from, so a change made on the
     * other surface, or by the server, draws them again: the panel on its live sync, the web page
     * through the stats' {@code lists.certificate}.
     */
    static String stateKey(KioskConfig facts) {
        return facts.httpCertificate + "|" + facts.httpUploadSha256 + "|"
                + KioskRuntimeState.httpAdminListening() + "|" + KioskRuntimeState.httpAdminSecure()
                + "|" + KioskRuntimeState.httpAdminCertificate() + "|"
                + KioskRuntimeState.httpAdminCertificateProblem();
    }

    /**
     * The panel's certificate, created on first use, or null when it cannot be had right now.
     *
     * <p>Kept in device-protected storage, like every other secret here
     * ({@link KioskConfig#storageContext}), because the service starts on
     * {@code LOCKED_BOOT_COMPLETED}, before the user's first unlock, and credential-encrypted
     * storage cannot be opened then. 0.6.0 kept the key in credential-encrypted storage by mistake,
     * so a panel whose service came up before the unlock served plain HTTP until something
     * restarted the web admin (review of 2026-09-25); {@link #adoptCredentialStoredKey} moves such a
     * key across once, so the fingerprint does not change a second time.
     *
     * <p>Null also when the stored key is there but SecretStore cannot decrypt it at this moment,
     * and when nothing is stored yet but the user is still locked, since 0.6.0's copy may be
     * waiting in the storage that cannot be opened yet. Before the first unlock the Keystore did
     * decrypt on the Android 10 test tablet with a PIN set (2026-09-25), so HTTPS was up while
     * the lock screen still showed; a device whose Keystore waits for the unlock gets null here. A new key is made only when nothing is stored anywhere, never
     * over a key that is merely unreadable, so a Keystore that is slow to wake cannot cost the
     * operator the fingerprint they wrote down. {@code KioskService} starts the web admin once more
     * at the first unlock for exactly these cases.
     */
    private static AdminCertificate loadOwn(Context context) {
        try {
            SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
            if (!secrets.has(KEY_NAME) && !adoptCredentialStoredKey(context, secrets)
                    && !isUserUnlocked(context)) {
                Log.i(TAG, "No TLS key readable before the first unlock; web admin closed until then");
                return null;
            }
            String storedKey = secrets.getOrNull(KEY_NAME);
            String storedCertificate = secrets.getOrNull(CERTIFICATE_NAME);
            if (storedKey == null || storedCertificate == null) {
                Log.e(TAG, "The web admin's TLS key cannot be read right now; web admin closed");
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
                    TlsPresentation.fingerprint(certificate.getEncoded()), false, null);
        } catch (GeneralSecurityException | RuntimeException failure) {
            Log.e(TAG, "This device cannot serve HTTPS; the web admin stays closed", failure);
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
     * Moves a key kept by 0.6.0 in credential-encrypted storage into the device-protected store,
     * and clears the old copy once the new one is in place.
     *
     * @return true when a key was moved, false when there was none or it cannot be read yet
     */
    private static boolean adoptCredentialStoredKey(Context context, SecretStore target) {
        if (!isUserUnlocked(context)) {
            return false;
        }
        try {
            // Credential-encrypted on purpose: this is where 0.6.0 kept it, and the only read.
            SecretStore old = new SecretStore(context);
            String key = old.getOrNull(KEY_NAME);
            String certificate = old.getOrNull(CERTIFICATE_NAME);
            if (key == null || certificate == null || key.isEmpty() || certificate.isEmpty()) {
                return false;
            }
            target.put(KEY_NAME, key);
            target.put(CERTIFICATE_NAME, certificate);
            if (!target.has(KEY_NAME) || !target.has(CERTIFICATE_NAME)) {
                return false;
            }
            old.clear(KEY_NAME);
            old.clear(CERTIFICATE_NAME);
            Log.i(TAG, "Moved the web admin's TLS key to device-protected storage");
            return true;
        } catch (RuntimeException unreadable) {
            return false;
        }
    }

    private static boolean isUserUnlocked(Context context) {
        android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
        return users == null || users.isUserUnlocked();
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
