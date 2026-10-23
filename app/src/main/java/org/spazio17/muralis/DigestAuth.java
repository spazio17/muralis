/*
 * Copyright 2026 Juri Calleri (spazio17) and the Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * HTTP Digest sign-in (RFC 7616, and RFC 2617 before it), which most network cameras ask for
 * instead of Basic: Hikvision, Dahua, Amcrest, Axis and the many brands built on them. Android's
 * {@code HttpURLConnection} answers only Basic, so {@code CameraView} reads the camera's
 * challenge and builds the answer here: MD5, MD5-sess or SHA-256, with {@code qop=auth} or none.
 * Pure, so the answer is host-tested against the RFC's own example.
 */
final class DigestAuth {
    /** A camera's challenge, its {@code WWW-Authenticate: Digest ...} parameters. */
    static final class Challenge {
        final String realm;
        final String nonce;
        final String opaque;
        final String algorithm;
        /** "auth" when the camera offers it, otherwise empty (the RFC 2069 form). */
        final String qop;
        /** The camera's own word that only the nonce was old, so the same sign-in may retry. */
        final boolean stale;
        private int count;

        Challenge(String realm, String nonce, String opaque, String algorithm, String qop) {
            this(realm, nonce, opaque, algorithm, qop, false);
        }

        Challenge(String realm, String nonce, String opaque, String algorithm, String qop,
                boolean stale) {
            this.stale = stale;
            this.realm = realm;
            this.nonce = nonce;
            this.opaque = opaque;
            this.algorithm = algorithm;
            this.qop = qop;
        }

        /** The next nonce count, as the header writes it; one challenge serves many requests. */
        synchronized String nextCount() {
            count++;
            return String.format(Locale.ROOT, "%08x", count);
        }
    }

    private DigestAuth() {
    }

    /**
     * The Digest challenge among a reply's {@code WWW-Authenticate} values, the strongest
     * algorithm this class answers first; null when there is none it can answer.
     */
    static Challenge parse(Iterable<String> headers) {
        Challenge best = null;
        if (headers == null) {
            return null;
        }
        for (String header : headers) {
            if (header == null || !header.trim().regionMatches(true, 0, "Digest ", 0, 7)) {
                continue;
            }
            Map<String, String> values = parameters(header.trim().substring(7));
            String algorithm = values.getOrDefault("algorithm", "MD5").toUpperCase(Locale.ROOT);
            if (!algorithm.equals("MD5") && !algorithm.equals("MD5-SESS")
                    && !algorithm.equals("SHA-256") && !algorithm.equals("SHA-256-SESS")) {
                continue;
            }
            String nonce = values.get("nonce");
            if (nonce == null) {
                continue;
            }
            String qop = "";
            for (String one : values.getOrDefault("qop", "").split(",")) {
                if (one.trim().equalsIgnoreCase("auth")) {
                    qop = "auth";
                }
            }
            if (!values.getOrDefault("qop", "").isEmpty() && qop.isEmpty()) {
                // Only auth-int offered, which needs the body: not answerable for a stream.
                continue;
            }
            Challenge challenge = new Challenge(values.getOrDefault("realm", ""), nonce,
                    values.get("opaque"), algorithm, qop,
                    "true".equalsIgnoreCase(values.getOrDefault("stale", "")));
            if (best == null || algorithm.startsWith("SHA-256")) {
                best = challenge;
            }
        }
        return best;
    }

    /** The {@code Authorization} header answering {@code challenge} for one request. */
    static String header(Challenge challenge, String method, String uri, String user,
            String password, String cnonce) {
        String nc = challenge.qop.isEmpty() ? "" : challenge.nextCount();
        return header(challenge, method, uri, user, password, cnonce, nc);
    }

    static String header(Challenge challenge, String method, String uri, String user,
            String password, String cnonce, String nc) {
        boolean sha = challenge.algorithm.startsWith("SHA-256");
        String ha1 = hash(sha, user + ":" + challenge.realm + ":" + password);
        if (challenge.algorithm.endsWith("-SESS")) {
            ha1 = hash(sha, ha1 + ":" + challenge.nonce + ":" + cnonce);
        }
        String ha2 = hash(sha, method + ":" + uri);
        String response = challenge.qop.isEmpty()
                ? hash(sha, ha1 + ":" + challenge.nonce + ":" + ha2)
                : hash(sha, ha1 + ":" + challenge.nonce + ":" + nc + ":" + cnonce + ":"
                        + challenge.qop + ":" + ha2);
        StringBuilder out = new StringBuilder("Digest username=\"").append(quote(user))
                .append("\", realm=\"").append(quote(challenge.realm))
                .append("\", nonce=\"").append(quote(challenge.nonce))
                .append("\", uri=\"").append(quote(uri))
                .append("\", response=\"").append(response).append('"');
        if (!challenge.algorithm.equals("MD5")) {
            out.append(", algorithm=").append(challenge.algorithm.replace("-SESS", "-sess"));
        }
        if (challenge.opaque != null) {
            out.append(", opaque=\"").append(quote(challenge.opaque)).append('"');
        }
        if (!challenge.qop.isEmpty()) {
            out.append(", qop=").append(challenge.qop).append(", nc=").append(nc)
                    .append(", cnonce=\"").append(quote(cnonce)).append('"');
        }
        return out.toString();
    }

    /** The parameters of a challenge, quoted or not, separated by commas outside quotes. */
    private static Map<String, String> parameters(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        int i = 0;
        int length = text.length();
        while (i < length) {
            while (i < length && (text.charAt(i) == ',' || text.charAt(i) == ' ')) {
                i++;
            }
            int equals = text.indexOf('=', i);
            if (equals < 0) {
                break;
            }
            String key = text.substring(i, equals).trim().toLowerCase(Locale.ROOT);
            i = equals + 1;
            StringBuilder value = new StringBuilder();
            if (i < length && text.charAt(i) == '"') {
                i++;
                while (i < length && text.charAt(i) != '"') {
                    if (text.charAt(i) == '\\' && i + 1 < length) {
                        i++;
                    }
                    value.append(text.charAt(i));
                    i++;
                }
                i++;
            } else {
                while (i < length && text.charAt(i) != ',') {
                    value.append(text.charAt(i));
                    i++;
                }
            }
            values.put(key, value.toString().trim());
        }
        return values;
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String hash(boolean sha, String text) {
        try {
            byte[] digest = MessageDigest.getInstance(sha ? "SHA-256" : "MD5")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", b & 0xff));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
