/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Real handshakes on the loopback, both directions, with the fleet's own certificates. */
public final class FleetTlsTest {

    private static final class Panel {
        final KeyPair pair;
        final X509Certificate certificate;
        final String spki;

        Panel(String name) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            pair = generator.generateKeyPair();
            certificate = issue(pair, name);
            spki = FleetTls.spkiOf(certificate);
        }
    }

    public static void main(String[] args) throws Exception {
        Panel server = new Panel("Muralis fleet kiosk-00000001");
        Panel member = new Panel("Muralis fleet kiosk-00000002");
        Panel stranger = new Panel("Muralis fleet kiosk-00000003");

        testKeyHash(server);
        testAdmits(member);

        Set<String> trusted = new HashSet<>(Collections.singleton(member.spki));
        Set<String> revoked = new HashSet<>();
        require(handshake(server, trusted, revoked, member, server.spki),
                "a trusted member was refused");
        require(!handshake(server, trusted, revoked, stranger, server.spki),
                "a stranger was admitted");
        require(!handshakeWithoutCertificate(server, trusted, revoked),
                "a caller with no certificate was admitted");
        require(!handshake(server, trusted, revoked, member, stranger.spki),
                "a member trusted a server whose key it had not pinned");
        revoked.add(member.spki);
        require(!handshake(server, trusted, revoked, member, server.spki),
                "a revoked member was admitted");
        System.out.println("FleetTlsTest passed");
    }

    private static void testKeyHash(Panel panel) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(panel.pair.getPublic().getEncoded());
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        require(panel.spki.equals(hex.toString()), "the key hash is not SHA-256 of the public key");
        require(panel.spki.matches("[0-9a-f]{64}"), "the key hash is not 64 lowercase hex characters");
        // A certificate made again over the same key is the same panel.
        X509Certificate again = issue(panel.pair, "Muralis fleet renamed");
        require(FleetTls.spkiOf(again).equals(panel.spki), "a new certificate changed the identity");
        require(FleetTls.shortForm(panel.spki).equals(panel.spki.substring(0, 8)), "short form");
    }

    private static void testAdmits(Panel member) {
        Set<String> trusted = Collections.singleton(member.spki);
        require(FleetTls.admits(member.spki, trusted, Collections.emptySet()), "trusted key refused");
        require(!FleetTls.admits(member.spki, trusted, trusted), "revoked key admitted");
        require(!FleetTls.admits(member.spki, Collections.emptySet(), Collections.emptySet()),
                "unknown key admitted");
        require(!FleetTls.admits(null, trusted, Collections.emptySet()), "no key admitted");
    }

    /**
     * Whether a client presenting {@code caller}'s certificate and pinning {@code pinned} completes
     * a request to {@code server}, which admits by {@code trusted} and {@code revoked}.
     */
    private static boolean handshake(Panel server, Set<String> trusted, Set<String> revoked,
            Panel caller, String pinned) throws Exception {
        SSLContext serverContext = FleetTls.context(server.pair.getPrivate(), server.certificate,
                spki -> FleetTls.admits(spki, trusted, revoked));
        SSLContext clientContext = FleetTls.context(caller.pair.getPrivate(), caller.certificate,
                spki -> spki.equals(pinned));
        return exchange(serverContext, clientContext);
    }

    private static boolean handshakeWithoutCertificate(Panel server, Set<String> trusted,
            Set<String> revoked) throws Exception {
        SSLContext serverContext = FleetTls.context(server.pair.getPrivate(), server.certificate,
                spki -> FleetTls.admits(spki, trusted, revoked));
        SSLContext anonymous = SSLContext.getInstance("TLS");
        anonymous.init(null, new TrustManager[] {new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return exchange(serverContext, anonymous);
    }

    /** One request and its answer; false when either side refuses at any point. */
    private static boolean exchange(SSLContext serverContext, SSLContext clientContext)
            throws Exception {
        try (SSLServerSocket listening = (SSLServerSocket) serverContext.getServerSocketFactory()
                .createServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            listening.setNeedClientAuth(true);
            AtomicReference<Boolean> served = new AtomicReference<>(false);
            Thread accept = new Thread(() -> {
                try (Socket accepted = listening.accept()) {
                    accepted.setSoTimeout(5_000);
                    ((SSLSocket) accepted).startHandshake();
                    InputStream in = accepted.getInputStream();
                    if (in.read() == 'Q') {
                        OutputStream out = accepted.getOutputStream();
                        out.write('A');
                        out.flush();
                        served.set(true);
                    }
                } catch (Exception refused) {
                    served.set(false);
                }
            });
            accept.start();
            boolean answered;
            try (SSLSocket client = (SSLSocket) clientContext.getSocketFactory()
                    .createSocket(InetAddress.getLoopbackAddress(), listening.getLocalPort())) {
                client.setSoTimeout(5_000);
                client.startHandshake();
                OutputStream out = client.getOutputStream();
                out.write('Q');
                out.flush();
                answered = client.getInputStream().read() == 'A';
            } catch (Exception refused) {
                answered = false;
            }
            accept.join(10_000);
            return answered && served.get();
        }
    }

    private static X509Certificate issue(KeyPair pair, String name) throws Exception {
        long now = System.currentTimeMillis();
        return SelfSignedCertificate.issue(pair, name, new Date(now - 86_400_000L),
                new Date(now + 10L * 365 * 86_400_000L), BigInteger.valueOf(now));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
