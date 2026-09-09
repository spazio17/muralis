/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;

/** The fingerprint format and the plain-HTTP redirect, on the host. */
public final class TlsPresentationTest {
    public static void main(String[] args) {
        // SHA-256("abc") is the textbook vector.
        String abc = TlsPresentation.fingerprint("abc".getBytes(StandardCharsets.US_ASCII));
        require(abc.equals("BA:78:16:BF:8F:01:CF:EA:41:41:40:DE:5D:AE:22:23:B0:03:61:A3:96:17:7A:9C:B4:10:FF:61:F2:00:15:AD"),
                "the fingerprint is upper-case hex pairs joined by colons: " + abc);
        require(abc.length() == 95, "32 bytes make 95 characters");

        require("https://192.0.2.42:8080/".equals(TlsPresentation.redirectLocation("192.0.2.42", 8080)),
                "a plain request is sent to the address it reached, over https, at the root");
        require("https://[fe80::1]:8443/".equals(TlsPresentation.redirectLocation("fe80::1%wlan0", 8443)),
                "an IPv6 address is bracketed and loses its interface");
        System.out.println("TlsPresentationTest passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
