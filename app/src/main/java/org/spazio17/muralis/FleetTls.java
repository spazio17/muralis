/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Set;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Mutual TLS between panels with no authority: each side presents its own self-signed fleet
 * certificate and accepts the other only by the SHA-256 of its public key. The shape KDE Connect and Syncthing use: the certificate's chain, dates and names are not
 * what is trusted, the key is.
 *
 * <p>Pure, with no Android imports, so the handshakes are covered by host tests with real sockets.
 */
final class FleetTls {

    private FleetTls() {
    }

    /** Decides whether a peer's key hash may complete a handshake. */
    interface Trust {
        boolean admits(String spki);
    }

    /** A key this panel trusts and has not revoked. Revocation wins: removal sticks. */
    static boolean admits(String spki, Set<String> trusted, Set<String> revoked) {
        return spki != null && !revoked.contains(spki) && trusted.contains(spki);
    }

    /** SHA-256 of the certificate's public key (its SubjectPublicKeyInfo), lowercase hex. */
    static String spkiOf(X509Certificate certificate) {
        return spkiOf(certificate.getPublicKey().getEncoded());
    }

    /** The same hash of an encoded public key, which the fleet document's signatures carry. */
    static String spkiOf(byte[] encodedPublicKey) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(encodedPublicKey));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Bytes as lowercase hex. */
    static String hex(byte[] bytes) {
        StringBuilder hex = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 0xF, 16))
                    .append(Character.forDigit(b & 0xF, 16));
        }
        return hex.toString();
    }

    /** The first eight characters, for logs and screens; never enough to trust on. */
    static String shortForm(String spki) {
        return spki == null || spki.length() < 8 ? String.valueOf(spki) : spki.substring(0, 8);
    }

    /**
     * A TLS context that presents {@code certificate} with {@code key}, as a server and as a
     * client, and completes a handshake only with a peer whose key {@code trust} admits. TLS 1.2
     * and 1.3 as the platform offers them.
     */
    static SSLContext context(PrivateKey key, X509Certificate certificate, Trust trust) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[] {new OwnKey(key, certificate)},
                    new TrustManager[] {new PinnedKeys(trust)}, null);
            return context;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** One key, offered whatever the peer asks for: there is no authority to choose by. */
    private static final class OwnKey extends X509ExtendedKeyManager {
        private static final String ALIAS = "muralis-fleet";
        private final PrivateKey key;
        private final X509Certificate[] chain;

        OwnKey(PrivateKey key, X509Certificate certificate) {
            this.key = key;
            this.chain = new X509Certificate[] {certificate};
        }

        @Override public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) { return ALIAS; }
        @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) { return ALIAS; }
        @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return new String[] {ALIAS}; }
        @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return new String[] {ALIAS}; }
        @Override public X509Certificate[] getCertificateChain(String alias) { return ALIAS.equals(alias) ? chain.clone() : null; }
        @Override public PrivateKey getPrivateKey(String alias) { return ALIAS.equals(alias) ? key : null; }
    }

    /**
     * Admits a peer by its key alone, in both directions. Extended, so the platform adds no
     * hostname or chain check of its own around it: a panel's address changes and there is no
     * authority to chain to.
     */
    private static final class PinnedKeys extends X509ExtendedTrustManager {
        private final Trust trust;

        PinnedKeys(Trust trust) {
            this.trust = trust;
        }

        private void check(X509Certificate[] chain) throws CertificateException {
            // One self-signed certificate, nothing chained to it, as Syncthing and KDE Connect
            // require.
            if (chain == null || chain.length != 1) {
                throw new CertificateException("expected one certificate, got "
                        + (chain == null ? 0 : chain.length));
            }
            String spki = spkiOf(chain[0]);
            if (!trust.admits(spki)) {
                throw new CertificateException("key " + shortForm(spki) + " is not a member");
            }
        }

        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException { check(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException { check(chain); }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { check(chain); }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException { check(chain); }
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }
}
