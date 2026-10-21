/*
 * Copyright 2026 Muralis contributors
 * Licensed under the PolyForm Shield License 1.0.0: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A certificate and private key a person uploads for the web admin, read and checked before
 * anything is kept (planned 2026-09-24, built 2026-10-04).
 *
 * <p>No different from any service that takes a TLS certificate: the certificate file holds one
 * or more PEM blocks, the server's certificate first and then any intermediates
 * ({@code fullchain.pem} as certbot writes it, or {@code cert.pem} and {@code chain.pem} pasted
 * together); a root in it is the browser's business and is left out. The key is an unencrypted
 * PEM private key, RSA or EC, in PKCS#8 ({@code BEGIN PRIVATE KEY}) or in the traditional shapes
 * ({@code BEGIN EC PRIVATE KEY}, {@code BEGIN RSA PRIVATE KEY}), which are wrapped into PKCS#8 here
 * by a few bytes of DER so the platform's key factory reads them; no library. The two texts are
 * read as one, so a single file holding both, or the two files swapped, work too. An encrypted
 * key is refused: a wall panel has nowhere to type a passphrase when it starts.
 *
 * <p>The key has to belong to the first certificate, shown by signing with it and verifying with
 * the certificate's public key, the one test that holds for both kinds of key. The certificate has
 * to be one a browser can accept for a server: an extended key usage without serverAuth (a client
 * certificate) or a key usage without digitalSignature can never be trusted for a handshake, so
 * either is refused (review, 2026-10-05); an authority's own certificate usually falls here, its
 * key usage being to sign certificates. CA:TRUE alone is not refused: that is what
 * {@code openssl req -x509} makes by default, and Firefox lets a person past it like any
 * self-signed one (Mozilla bug 1034124). The same block chosen in both fields counts once, so
 * a file holding both picked twice works (review, 2026-10-05). Pure, so every shape and every
 * refusal is host-tested.
 */
final class CertificateUpload {
    /** More than any real chain; a file holding more is not one. */
    static final int MAX_CERTIFICATES = 8;
    /** The longest name a DNS name can be; a longer one matches no address (review, 2026-10-05). */
    static final int MAX_NAME_LENGTH = 253;

    private static final Pattern PEM = Pattern.compile(
            "-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----", Pattern.DOTALL);
    /** The extended key usages a server certificate may carry: serverAuth, and any. */
    private static final String SERVER_AUTH = "1.3.6.1.5.5.7.3.1";
    private static final String ANY_USAGE = "2.5.29.37.0";
    /** The extended key usage a client certificate carries. */
    private static final String CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";
    /** The AlgorithmIdentifier of an RSA key, rsaEncryption with NULL parameters. */
    private static final byte[] RSA_ALGORITHM = {
        0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01,
        0x01, 0x01, 0x05, 0x00};
    private static final String REMOVE_PASSPHRASE =
            "the private key is protected by a passphrase, and a panel has nowhere to type one "
                    + "when it starts; save it without one with openssl pkey -in key.pem "
                    + "-out key-open.pem";

    /** The certificates to serve, the server's own first, without a root. */
    final X509Certificate[] chain;
    final PrivateKey key;

    private CertificateUpload(X509Certificate[] chain, PrivateKey key) {
        this.chain = chain;
        this.key = key;
    }

    /** The upload read, or why it is refused, as a fragment to follow "Not uploaded: ". */
    static final class Result {
        final CertificateUpload upload;
        final String problem;

        private Result(CertificateUpload upload, String problem) {
            this.upload = upload;
            this.problem = problem;
        }
    }

    private static Result refused(String problem) {
        return new Result(null, problem);
    }

    /**
     * Reads a certificate file and a key file; either may hold both, and blank means not given.
     */
    static Result parse(String certificateText, String keyText) {
        return parse(certificateText, keyText, true);
    }

    /**
     * The same for a client certificate, the one MQTT may present to its broker: its extended
     * key usage must allow clientAuth instead of serverAuth, and its names are not checked.
     */
    static Result parseClient(String certificateText, String keyText) {
        return parse(certificateText, keyText, false);
    }

    private static Result parse(String certificateText, String keyText, boolean server) {
        String all = (certificateText == null ? "" : certificateText) + "\n"
                + (keyText == null ? "" : keyText);
        List<byte[]> certificateDers = new ArrayList<>();
        List<String> keyLabels = new ArrayList<>();
        List<byte[]> keyDers = new ArrayList<>();
        boolean request = false;
        Matcher block = PEM.matcher(all);
        while (block.find()) {
            String label = block.group(1);
            String body = block.group(2);
            switch (label) {
                case "CERTIFICATE":
                    byte[] der = decode(body);
                    if (der == null) {
                        return refused("a certificate in the file cannot be read");
                    }
                    if (!holds(certificateDers, der)) {
                        certificateDers.add(der);
                    }
                    break;
                case "ENCRYPTED PRIVATE KEY":
                    return refused(REMOVE_PASSPHRASE);
                case "PRIVATE KEY":
                case "EC PRIVATE KEY":
                case "RSA PRIVATE KEY":
                    // The traditional shapes carry their encryption as headers in the block.
                    if (body.contains("Proc-Type:") || body.contains("DEK-Info:")) {
                        return refused(REMOVE_PASSPHRASE);
                    }
                    byte[] keyDer = decode(body);
                    if (keyDer == null) {
                        return refused("the private key cannot be read");
                    }
                    if (!holds(keyDers, keyDer)) {
                        keyLabels.add(label);
                        keyDers.add(keyDer);
                    }
                    break;
                case "CERTIFICATE REQUEST":
                case "NEW CERTIFICATE REQUEST":
                    request = true;
                    break;
                default:
                    // EC PARAMETERS before a key, a public key: nothing to serve, left alone.
                    break;
            }
        }
        if (certificateDers.isEmpty()) {
            if (all.trim().isEmpty()) {
                return refused("no certificate was chosen");
            }
            return refused(request
                    ? "that is a certificate request; upload the certificate the authority "
                            + "sent back for it"
                    : "no certificate was found; a certificate file begins with "
                            + "-----BEGIN CERTIFICATE-----");
        }
        if (certificateDers.size() > MAX_CERTIFICATES) {
            return refused("more than " + MAX_CERTIFICATES + " certificates were given");
        }
        if (keyDers.isEmpty()) {
            return refused("no private key was found; a key file begins with "
                    + "-----BEGIN PRIVATE KEY-----");
        }
        if (keyDers.size() > 1) {
            return refused("more than one private key was given");
        }

        List<X509Certificate> certificates = new ArrayList<>();
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            for (byte[] der : certificateDers) {
                certificates.add((X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(der)));
            }
        } catch (CertificateException | RuntimeException unreadable) {
            return refused("a certificate in the file cannot be read");
        }
        X509Certificate first = certificates.get(0);
        String algorithm = first.getPublicKey().getAlgorithm();
        if (!algorithm.equals("EC") && !algorithm.equals("RSA")) {
            return refused("the certificate's key is " + algorithm
                    + "; the web admin serves RSA and EC keys");
        }

        boolean anyEc = false;
        for (X509Certificate certificate : certificates) {
            anyEc |= certificate.getPublicKey().getAlgorithm().equals("EC");
        }
        if (keyLabels.get(0).equals("EC PRIVATE KEY") && !anyEc) {
            return refused("the private key does not belong to the certificate");
        }
        PrivateKey key = readKey(keyLabels.get(0), keyDers.get(0), certificates);
        if (key == null) {
            return refused("the private key cannot be read");
        }
        int owner = -1;
        for (int index = 0; index < certificates.size() && owner < 0; index++) {
            if (belongsTo(key, certificates.get(index))) {
                owner = index;
            }
        }
        if (owner > 0) {
            return refused("the private key belongs to another certificate in the file; the "
                    + "server's own certificate has to come first");
        }
        if (owner < 0) {
            return refused("the private key does not belong to the certificate");
        }
        String usage = server ? serverUsageProblem(first) : clientUsageProblem(first);
        if (usage != null) {
            return refused(usage);
        }
        for (String name : server ? nameList(first) : java.util.Collections.<String>emptyList()) {
            if (name.length() > MAX_NAME_LENGTH) {
                return refused("a name in the certificate is longer than " + MAX_NAME_LENGTH
                        + " characters, which no browser matches to an address");
            }
        }
        List<X509Certificate> served = new ArrayList<>();
        served.add(first);
        for (int index = 1; index < certificates.size(); index++) {
            X509Certificate next = certificates.get(index);
            if (!next.getSubjectX500Principal().equals(next.getIssuerX500Principal())) {
                served.add(next);
            }
        }
        return new Result(new CertificateUpload(served.toArray(new X509Certificate[0]), key),
                null);
    }

    /** The certificates as kept: each DER in base64, joined by commas. */
    String chainText() {
        StringBuilder text = new StringBuilder();
        try {
            for (X509Certificate certificate : chain) {
                if (text.length() > 0) {
                    text.append(',');
                }
                text.append(Base64.getEncoder().encodeToString(certificate.getEncoded()));
            }
        } catch (CertificateException impossible) {
            throw new IllegalStateException(impossible);
        }
        return text.toString();
    }

    /** The key as kept: PKCS#8 in base64. */
    String keyText() {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /**
     * An upload as {@link #chainText} and {@link #keyText} kept it, read back at every start;
     * null when it cannot be, which the caller reports and falls back from.
     */
    static CertificateUpload restore(String chainText, String keyText) {
        if (chainText == null || chainText.isEmpty() || keyText == null || keyText.isEmpty()) {
            return null;
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (String der : chainText.split(",")) {
                chain.add((X509Certificate) factory.generateCertificate(
                        new ByteArrayInputStream(Base64.getDecoder().decode(der))));
            }
            PrivateKey key = KeyFactory.getInstance(chain.get(0).getPublicKey().getAlgorithm())
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(keyText)));
            if (!belongsTo(key, chain.get(0))) {
                return null;
            }
            return new CertificateUpload(chain.toArray(new X509Certificate[0]), key);
        } catch (GeneralSecurityException | RuntimeException unreadable) {
            return null;
        }
    }

    /** How many names the surfaces show before "and N more", so the end date stays in view. */
    static final int NAMES_SHOWN = 3;

    /**
     * The names a browser checks the address against: the certificate's DNS names and
     * addresses, or its common name when it has none, control characters taken out (a name is
     * written to the log, and a line break in it forged a line there; review, 2026-10-05).
     */
    static List<String> nameList(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            Collection<List<?>> alternatives = certificate.getSubjectAlternativeNames();
            if (alternatives != null) {
                for (List<?> entry : alternatives) {
                    Object type = entry.get(0);
                    if ((Integer.valueOf(2).equals(type) || Integer.valueOf(7).equals(type))
                            && entry.get(1) instanceof String) {
                        names.add(cleanName((String) entry.get(1)));
                    }
                }
            }
        } catch (CertificateParsingException unreadable) {
            // The common name below is still a name to show.
        }
        if (names.isEmpty()) {
            names.add(cleanName(commonName(certificate.getSubjectX500Principal()
                    .getName(javax.security.auth.x500.X500Principal.RFC2253))));
        }
        return names;
    }

    /**
     * The names as the surfaces show them: "panel.lan, 192.168.1.20", and past
     * {@link #NAMES_SHOWN} the first ones and "and 57 more" (review, 2026-10-05: sixty names
     * pushed the end date off the row).
     */
    static String names(X509Certificate certificate) {
        List<String> names = nameList(certificate);
        if (names.size() <= NAMES_SHOWN) {
            return String.join(", ", names);
        }
        return String.join(", ", names.subList(0, NAMES_SHOWN)) + ", and "
                + (names.size() - NAMES_SHOWN) + " more";
    }

    /** A name without control characters, which nothing a browser checks contains. */
    static String cleanName(String name) {
        return name.replaceAll("\\p{Cntrl}", "");
    }

    /**
     * The common name of an RFC 2253 name, its escapes undone ("Panel\\, Hall" is "Panel, Hall",
     * "\\3C" is "<"), or the whole name when it has none. Read by hand: javax.naming, which
     * parses these, is not on Android.
     */
    static String commonName(String rfc2253) {
        int at = 0;
        while (at < rfc2253.length()) {
            int equals = rfc2253.indexOf('=', at);
            if (equals < 0) {
                break;
            }
            String type = rfc2253.substring(at, equals).trim();
            StringBuilder value = new StringBuilder();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            int index = equals + 1;
            while (index < rfc2253.length()) {
                char c = rfc2253.charAt(index);
                if (c == '\\' && index + 1 < rfc2253.length()) {
                    char next = rfc2253.charAt(index + 1);
                    if (index + 2 < rfc2253.length() && Character.digit(next, 16) >= 0
                            && Character.digit(rfc2253.charAt(index + 2), 16) >= 0) {
                        bytes.write(Integer.parseInt(rfc2253.substring(index + 1, index + 3), 16));
                        index += 3;
                        continue;
                    }
                    flush(bytes, value);
                    value.append(next);
                    index += 2;
                    continue;
                }
                if (c == ',' || c == '+') {
                    break;
                }
                flush(bytes, value);
                value.append(c);
                index++;
            }
            flush(bytes, value);
            if (type.equalsIgnoreCase("CN")) {
                return value.toString();
            }
            at = index + 1;
        }
        return rfc2253;
    }

    private static void flush(ByteArrayOutputStream bytes, StringBuilder value) {
        if (bytes.size() > 0) {
            value.append(new String(bytes.toByteArray(), java.nio.charset.StandardCharsets.UTF_8));
            bytes.reset();
        }
    }

    /**
     * "until 4 Oct 2027", or "expired on 4 Oct 2025" once {@code nowMs} is past it, the date
     * held together by no-break spaces so a narrow row does not split it (review, 2026-10-05).
     */
    static String validity(long notAfterMs, long nowMs) {
        String day = new SimpleDateFormat("d MMM yyyy", Locale.ENGLISH).format(new Date(notAfterMs))
                .replace(' ', '\u00a0');
        return (expired(notAfterMs, nowMs) ? "expired on\u00a0" : "until\u00a0") + day;
    }

    static boolean expired(long notAfterMs, long nowMs) {
        return nowMs > notAfterMs;
    }

    /**
     * Why no browser can accept the certificate for a server, or null: an extended key usage
     * that names neither serverAuth nor any, or a key usage without digitalSignature, which
     * every handshake a browser offers today signs with.
     */
    private static String serverUsageProblem(X509Certificate certificate) {
        try {
            List<String> extended = certificate.getExtendedKeyUsage();
            if (extended != null && !extended.contains(SERVER_AUTH)
                    && !extended.contains(ANY_USAGE)) {
                return "the certificate is not one for a server (its extended key usage has no "
                        + "serverAuth), and no browser accepts it for one";
            }
        } catch (CertificateParsingException unreadable) {
            return "a certificate in the file cannot be read";
        }
        boolean[] usage = certificate.getKeyUsage();
        if (usage != null && !usage[0]) {
            return "the certificate's key usage has no digitalSignature, and no browser accepts "
                    + "it for a server";
        }
        return null;
    }

    /** Why a broker cannot accept the certificate from a client, or null. */
    private static String clientUsageProblem(X509Certificate certificate) {
        try {
            List<String> extended = certificate.getExtendedKeyUsage();
            if (extended != null && !extended.contains(CLIENT_AUTH)
                    && !extended.contains(ANY_USAGE)) {
                return "the certificate is not one for a client (its extended key usage has no "
                        + "clientAuth), and a broker does not accept it from one";
            }
        } catch (CertificateParsingException unreadable) {
            return "a certificate in the file cannot be read";
        }
        return null;
    }

    private static boolean holds(List<byte[]> blocks, byte[] block) {
        for (byte[] held : blocks) {
            if (java.util.Arrays.equals(held, block)) {
                return true;
            }
        }
        return false;
    }

    private static byte[] decode(String body) {
        try {
            return Base64.getMimeDecoder().decode(body.trim());
        } catch (IllegalArgumentException unreadable) {
            return null;
        }
    }

    /**
     * The key in PKCS#8, from any of the three shapes. A traditional EC key takes its curve from
     * the first EC certificate of the file, the one it has to belong to anyway; parse refuses one
     * with no such certificate before it gets here.
     */
    private static PrivateKey readKey(String label, byte[] der, List<X509Certificate> certificates) {
        try {
            switch (label) {
                case "RSA PRIVATE KEY":
                    return KeyFactory.getInstance("RSA").generatePrivate(
                            new PKCS8EncodedKeySpec(wrap(RSA_ALGORITHM, der)));
                case "EC PRIVATE KEY": {
                    for (X509Certificate certificate : certificates) {
                        if (certificate.getPublicKey().getAlgorithm().equals("EC")) {
                            byte[] algorithm = firstElement(certificate.getPublicKey().getEncoded());
                            return KeyFactory.getInstance("EC").generatePrivate(
                                    new PKCS8EncodedKeySpec(wrap(algorithm, der)));
                        }
                    }
                    return null;
                }
                default:
                    for (String algorithm : new String[] {"EC", "RSA"}) {
                        try {
                            return KeyFactory.getInstance(algorithm)
                                    .generatePrivate(new PKCS8EncodedKeySpec(der));
                        } catch (GeneralSecurityException notThisOne) {
                            // The other kind next.
                        }
                    }
                    return null;
            }
        } catch (GeneralSecurityException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Whether a signature made with the key verifies with the certificate's public key. */
    static boolean belongsTo(PrivateKey key, X509Certificate certificate) {
        String algorithm = key.getAlgorithm();
        if (!algorithm.equals(certificate.getPublicKey().getAlgorithm())) {
            return false;
        }
        String signatureAlgorithm = algorithm.equals("EC") ? "SHA256withECDSA" : "SHA256withRSA";
        try {
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature signer = Signature.getInstance(signatureAlgorithm);
            signer.initSign(key);
            signer.update(challenge);
            byte[] signature = signer.sign();
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(certificate.getPublicKey());
            verifier.update(challenge);
            return verifier.verify(signature);
        } catch (GeneralSecurityException | RuntimeException mismatch) {
            return false;
        }
    }

    /** PKCS#8 around a traditional key: version 0, the algorithm, the key as an octet string. */
    private static byte[] wrap(byte[] algorithm, byte[] key) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x02);
        body.write(0x01);
        body.write(0x00);
        body.write(algorithm, 0, algorithm.length);
        writeTag(body, 0x04, key);
        ByteArrayOutputStream whole = new ByteArrayOutputStream();
        writeTag(whole, 0x30, body.toByteArray());
        return whole.toByteArray();
    }

    private static void writeTag(ByteArrayOutputStream out, int tag, byte[] content) {
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else if (length < 0x10000) {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xff);
        } else {
            out.write(0x83);
            out.write(length >> 16);
            out.write((length >> 8) & 0xff);
            out.write(length & 0xff);
        }
        out.write(content, 0, length);
    }

    /**
     * The first element of a DER SEQUENCE, tag and length included: of a SubjectPublicKeyInfo,
     * the key's AlgorithmIdentifier, the curve with it.
     */
    private static byte[] firstElement(byte[] sequence) {
        int start = 1 + lengthBytes(sequence, 1);
        int contentStart = start + 1 + lengthBytes(sequence, start + 1);
        int length = contentStart - start + contentLength(sequence, start + 1);
        byte[] element = new byte[length];
        System.arraycopy(sequence, start, element, 0, length);
        return element;
    }

    /** How many bytes the length at {@code at} takes. */
    private static int lengthBytes(byte[] der, int at) {
        int first = der[at] & 0xff;
        return first < 0x80 ? 1 : 1 + (first & 0x7f);
    }

    private static int contentLength(byte[] der, int at) {
        int first = der[at] & 0xff;
        if (first < 0x80) {
            return first;
        }
        int length = 0;
        for (int index = 1; index <= (first & 0x7f); index++) {
            length = (length << 8) | (der[at + index] & 0xff);
        }
        return length;
    }
}
