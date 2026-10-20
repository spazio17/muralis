/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * MQTT over TLS: the broker is trusted when Android trusts its certificate for the broker's
 * name, or when its certificate is the one a person trusted on this panel (one SHA-256, as a
 * page's certificate is pinned, {@link CertificatePins}), which is how a home broker with a
 * self-signed certificate is reached without switching the check off. A certificate trusted by
 * neither is refused and kept in {@link #waiting}, so both settings surfaces can show it with
 * Trust. An optional client certificate is presented when the broker asks for one, as
 * zigbee2mqtt's cert and key do. No Android imports.
 */
final class MqttTls {
    /** The broker's certificate last refused, waiting for a person's answer; null when none. */
    static volatile CertificatePins.Waiting waiting;

    private MqttTls() {
    }

    /** The refused certificate as both surfaces word it. */
    static String waitingLine(CertificatePins.Waiting refused) {
        return "The broker's certificate is not trusted: issued to " + refused.issuedTo
                + ", valid until " + refused.validUntil + ", SHA-256 " + refused.fingerprint
                + ". Trust it only if it is your broker's.";
    }

    /** The client certificate line both surfaces show. */
    static String clientLine(String names, long untilMs) {
        return names.isEmpty() ? "No client certificate; a broker that asks for one gets it"
                + " from an upload in the web admin."
                : "Client certificate for " + names + ", valid until "
                        + new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT)
                                .format(new java.util.Date(untilMs)) + ".";
    }

    /** What Paho needs for one connection: the socket factory and the name check. */
    static final class Connection {
        final SSLSocketFactory socketFactory;
        final HostnameVerifier hostnameVerifier;

        Connection(SSLSocketFactory socketFactory, HostnameVerifier hostnameVerifier) {
            this.socketFactory = socketFactory;
            this.hostnameVerifier = hostnameVerifier;
        }
    }

    /**
     * The connection to {@code host}, trusting {@code pin} (a fingerprint, or empty) besides
     * Android's authorities, presenting {@code client} when not null.
     */
    static Connection connection(String host, String pin, CertificateUpload client)
            throws GeneralSecurityException {
        return connection(host, pin, client, refused -> waiting = refused);
    }

    /**
     * The same, handing a refused certificate to {@code refused} instead of the broker's slot:
     * a camera's certificate waits where a page's does ({@code CertificatePins.waiting}).
     */
    static Connection connection(String host, String pin, CertificateUpload client,
            java.util.function.Consumer<CertificatePins.Waiting> refused)
            throws GeneralSecurityException {
        TrustManagerFactory system = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        system.init((KeyStore) null);
        X509TrustManager android = null;
        for (TrustManager one : system.getTrustManagers()) {
            if (one instanceof X509TrustManager) {
                android = (X509TrustManager) one;
            }
        }
        if (android == null) {
            throw new GeneralSecurityException("no trust manager");
        }
        javax.net.ssl.KeyManager[] keys = null;
        if (client != null) {
            KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
            try {
                store.load(null, null);
            } catch (java.io.IOException impossible) {
                throw new GeneralSecurityException(impossible);
            }
            char[] none = new char[0];
            store.setKeyEntry("client", client.key, none, client.chain);
            KeyManagerFactory factory = KeyManagerFactory.getInstance(
                    KeyManagerFactory.getDefaultAlgorithm());
            factory.init(store, none);
            keys = factory.getKeyManagers();
        }
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys, new TrustManager[] {new Trust(android, pin, host, refused)}, null);
        HostnameVerifier names = HttpsURLConnection.getDefaultHostnameVerifier();
        HostnameVerifier verifier = (name, session) -> {
            String leaf = leafFingerprint(session);
            if (!pin.isEmpty() && pin.equals(leaf)) {
                return true;
            }
            if (names.verify(host, session)) {
                return true;
            }
            // Trusted by Android, but issued for another name: shown for a person to decide.
            refused.accept(waitingFor(session, host));
            return false;
        };
        return new Connection(context.getSocketFactory(), verifier);
    }

    private static String leafFingerprint(SSLSession session) {
        try {
            java.security.cert.Certificate[] peer = session.getPeerCertificates();
            return peer.length == 0 ? "" : CertificatePins.fingerprint(peer[0].getEncoded());
        } catch (SSLPeerUnverifiedException | java.security.cert.CertificateEncodingException none) {
            return "";
        }
    }

    private static CertificatePins.Waiting waitingFor(SSLSession session, String host) {
        try {
            java.security.cert.Certificate[] peer = session.getPeerCertificates();
            if (peer.length > 0 && peer[0] instanceof X509Certificate) {
                return describe((X509Certificate) peer[0], host);
            }
        } catch (SSLPeerUnverifiedException none) {
            // Nothing to show.
        }
        return null;
    }

    static CertificatePins.Waiting describe(X509Certificate leaf, String host) {
        try {
            return new CertificatePins.Waiting(host, CertificatePins.fingerprint(leaf.getEncoded()),
                    CertificateUpload.names(leaf), new java.text.SimpleDateFormat("yyyy-MM-dd",
                            java.util.Locale.ROOT).format(leaf.getNotAfter()));
        } catch (java.security.cert.CertificateEncodingException unreadable) {
            return null;
        }
    }

    /** Android's authorities, or the pinned certificate; anything else waits for a person. */
    private static final class Trust extends X509ExtendedTrustManager {
        private final X509TrustManager android;
        private final String pin;
        private final String host;
        private final java.util.function.Consumer<CertificatePins.Waiting> refused;

        Trust(X509TrustManager android, String pin, String host,
                java.util.function.Consumer<CertificatePins.Waiting> refused) {
            this.android = android;
            this.pin = pin;
            this.host = host;
            this.refused = refused;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("the server sent no certificate");
            }
            if (!pin.isEmpty() && pin.equals(CertificatePins.fingerprint(chain[0].getEncoded()))) {
                return;
            }
            try {
                android.checkServerTrusted(chain, authType);
            } catch (CertificateException notTrusted) {
                refused.accept(describe(chain[0], host));
                throw notTrusted;
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            checkServerTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType)
                throws CertificateException {
            throw new CertificateException("a broker connection takes no clients");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            checkClientTrusted(chain, authType);
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return android.getAcceptedIssuers();
        }
    }
}
