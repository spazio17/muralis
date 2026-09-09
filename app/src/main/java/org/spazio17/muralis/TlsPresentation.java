/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * The two strings the web admin's TLS needs that are pure text work: the fingerprint a person
 * compares against what the browser shows, and the address a plain-HTTP request is sent on to.
 *
 * <p>Pure, so the redirect's refusal of a forged Host header and the fingerprint's exact
 * formatting are host-tested rather than checked by hand in a browser's certificate viewer.
 */
final class TlsPresentation {
    private TlsPresentation() {
    }

    /** SHA-256 of a DER certificate as upper-case hex pairs joined by colons, the browsers' format. */
    static String fingerprint(byte[] der) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
            StringBuilder text = new StringBuilder(digest.length * 3);
            for (byte b : digest) {
                if (text.length() > 0) {
                    text.append(':');
                }
                text.append(String.format(Locale.ROOT, "%02X", b & 0xff));
            }
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /**
     * Where to send a plain-HTTP request that arrived on the HTTPS port: the address it connected
     * to, over https, at the root. The request's own path and Host header went into the failed
     * handshake and are not available; the local address the socket was accepted on is, and it
     * is what the person typed, or what their name for the panel resolved to.
     */
    static String redirectLocation(String localAddress, int port) {
        // A link-local IPv6 address carries its interface ("fe80::1%wlan0"), which no browser
        // accepts in a URL and which means nothing to the client anyway.
        int zone = localAddress.indexOf('%');
        if (zone >= 0) {
            localAddress = localAddress.substring(0, zone);
        }
        String host = localAddress.indexOf(':') >= 0 ? "[" + localAddress + "]" : localAddress;
        return "https://" + host + ":" + port + "/";
    }
}
