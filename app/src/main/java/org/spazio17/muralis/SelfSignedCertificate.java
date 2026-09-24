/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * A self-signed X.509 certificate for an EC P-256 key pair, written out byte by byte.
 *
 * <p>Android has no public API that issues a certificate for a key it did not make itself: the
 * Keystore issues one along with a key pair it holds, and that is the only issuer on the
 * platform. The web admin's key pair no longer lives there (see {@code AdminCertificate} for the
 * measurement that moved it), so the certificate is assembled here from the ASN.1 in RFC 5280,
 * which for one name, one key and no extensions is a page of DER: a version, a serial number,
 * the signature algorithm, an issuer, a validity, a subject, the public key, and the signature
 * over all of it. No library, so nothing to bundle and nothing to keep up to date.
 *
 * <p>The certificate is version 3 with no extensions, and its issuer and subject are the same
 * single common name. A browser accepts it exactly as it accepted the Keystore's, with a warning
 * a person clicks through once after comparing the fingerprint; there is no address in it, since
 * a panel's address changes and the certificate lasts ten years, so the browser's name warning
 * is accepted by the same click. Pure Java, host-tested by {@code SelfSignedCertificateTest}.
 */
final class SelfSignedCertificate {
    /** {@code ecdsa-with-SHA256}, 1.2.840.10045.4.3.2, as an OBJECT IDENTIFIER. */
    private static final byte[] OID_ECDSA_WITH_SHA256 = {
        0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02};
    /** {@code id-at-commonName}, 2.5.4.3, as an OBJECT IDENTIFIER. */
    private static final byte[] OID_COMMON_NAME = {0x06, 0x03, 0x55, 0x04, 0x03};

    private static final int SEQUENCE = 0x30;
    private static final int SET = 0x31;
    private static final int INTEGER = 0x02;
    private static final int BIT_STRING = 0x03;
    private static final int UTF8_STRING = 0x0C;
    private static final int UTC_TIME = 0x17;
    private static final int GENERALIZED_TIME = 0x18;
    /** {@code [0] EXPLICIT}, the tag of the version field. */
    private static final int VERSION_TAG = 0xA0;
    /** The value of the version field for X.509 v3. */
    private static final byte[] VERSION_3 = {0x02};

    private SelfSignedCertificate() {
    }

    /**
     * Issues the certificate: {@code commonName} as issuer and subject, valid between the two
     * dates, signed with SHA-256 and the pair's own private key.
     *
     * <p>The result is parsed back through the platform's own X.509 reader and verified against
     * the pair's public key before it is returned, so a mistake in the encoding fails here, in
     * the panel's log, rather than in a browser's error page with nothing to say.
     */
    static X509Certificate issue(KeyPair pair, String commonName, Date notBefore, Date notAfter,
            BigInteger serial) throws GeneralSecurityException {
        byte[] algorithm = der(SEQUENCE, OID_ECDSA_WITH_SHA256);
        byte[] name = der(SEQUENCE, der(SET, der(SEQUENCE, concat(OID_COMMON_NAME,
                der(UTF8_STRING, commonName.getBytes(StandardCharsets.UTF_8))))));
        byte[] tbs = der(SEQUENCE, concat(
                der(VERSION_TAG, der(INTEGER, VERSION_3)),
                der(INTEGER, serial.toByteArray()),
                algorithm,
                name,
                der(SEQUENCE, concat(time(notBefore), time(notAfter))),
                name,
                pair.getPublic().getEncoded()));
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(pair.getPrivate());
        signer.update(tbs);
        // Java hands the signature back as the DER SEQUENCE of r and s, which is exactly what
        // X.509 carries in its BIT STRING; the leading zero says no bits are unused.
        byte[] signature = der(BIT_STRING, concat(new byte[] {0}, signer.sign()));
        byte[] encoded = der(SEQUENCE, concat(tbs, algorithm, signature));
        X509Certificate certificate = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(encoded));
        certificate.verify(pair.getPublic());
        return certificate;
    }

    /**
     * A time the way RFC 5280 wants it: UTCTime up to 2049, GeneralizedTime from 2050. Both are
     * in UTC to the second; a certificate lasting ten years does not need the milliseconds.
     */
    private static byte[] time(Date date) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"), Locale.ROOT);
        utc.setTime(date);
        boolean generalized = utc.get(Calendar.YEAR) >= 2050;
        SimpleDateFormat format = new SimpleDateFormat(
                generalized ? "yyyyMMddHHmmss'Z'" : "yyMMddHHmmss'Z'", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return der(generalized ? GENERALIZED_TIME : UTC_TIME,
                format.format(date).getBytes(StandardCharsets.US_ASCII));
    }

    /** One DER element: tag, length in the short form or the long form, content. */
    private static byte[] der(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 4);
        out.write(tag);
        int length = content.length;
        if (length < 0x80) {
            out.write(length);
        } else if (length < 0x100) {
            out.write(0x81);
            out.write(length);
        } else {
            out.write(0x82);
            out.write(length >> 8);
            out.write(length & 0xFF);
        }
        out.write(content, 0, length);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.write(part, 0, part.length);
        }
        return out.toByteArray();
    }
}
