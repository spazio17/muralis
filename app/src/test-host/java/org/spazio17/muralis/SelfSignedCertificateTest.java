/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Calendar;
import java.util.Date;

/**
 * Host tests for {@link SelfSignedCertificate}: the DER written by hand has to be what the
 * platform's own X.509 reader, and so a browser, reads back, field for field.
 */
public final class SelfSignedCertificateTest {

    public static void main(String[] args) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair pair = generator.generateKeyPair();
        KeyPair other = generator.generateKeyPair();

        Calendar notBefore = Calendar.getInstance();
        notBefore.add(Calendar.DAY_OF_MONTH, -1);
        Calendar notAfter = Calendar.getInstance();
        notAfter.add(Calendar.YEAR, 10);
        BigInteger serial = BigInteger.valueOf(1758700000000L);
        X509Certificate certificate = SelfSignedCertificate.issue(pair, "Muralis kiosk-1c866f5a",
                notBefore.getTime(), notAfter.getTime(), serial);

        check("version 3", certificate.getVersion() == 3);
        check("serial kept", serial.equals(certificate.getSerialNumber()));
        check("subject is the common name",
                "CN=Muralis kiosk-1c866f5a".equals(certificate.getSubjectX500Principal().getName()));
        check("issuer is the subject",
                certificate.getIssuerX500Principal().equals(certificate.getSubjectX500Principal()));
        check("signed with ECDSA and SHA-256",
                "SHA256withECDSA".equals(certificate.getSigAlgName()));
        check("public key is the pair's",
                certificate.getPublicKey().equals(pair.getPublic()));
        check("not before, to the second",
                seconds(certificate.getNotBefore()) == seconds(notBefore.getTime()));
        check("not after, to the second",
                seconds(certificate.getNotAfter()) == seconds(notAfter.getTime()));
        check("no extensions", certificate.getCriticalExtensionOIDs() == null
                && certificate.getNonCriticalExtensionOIDs() == null);
        certificate.checkValidity();
        certificate.verify(pair.getPublic());
        boolean refused = false;
        try {
            certificate.verify(other.getPublic());
        } catch (java.security.GeneralSecurityException expected) {
            refused = true;
        }
        check("another key does not verify it", refused);

        // A serial with the top bit set must not come out negative: BigInteger's encoding keeps
        // the sign byte, and the field is a plain INTEGER.
        BigInteger highBit = new BigInteger("9223372036854775807");
        X509Certificate wide = SelfSignedCertificate.issue(pair, "Muralis x", notBefore.getTime(),
                notAfter.getTime(), highBit);
        check("wide serial kept", highBit.equals(wide.getSerialNumber()));

        // A date past 2049 is written as GeneralizedTime and still reads back.
        Calendar far = Calendar.getInstance();
        far.set(2060, Calendar.JANUARY, 1, 0, 0, 0);
        X509Certificate late = SelfSignedCertificate.issue(pair, "Muralis y", notBefore.getTime(),
                far.getTime(), serial);
        check("GeneralizedTime read back", seconds(late.getNotAfter()) == seconds(far.getTime()));

        // A name longer than 127 bytes crosses into the long form of the DER length.
        StringBuilder longName = new StringBuilder("Muralis ");
        while (longName.length() < 140) {
            longName.append('x');
        }
        X509Certificate named = SelfSignedCertificate.issue(pair, longName.toString(),
                notBefore.getTime(), notAfter.getTime(), serial);
        check("long name read back", ("CN=" + longName).equals(
                named.getSubjectX500Principal().getName()));

        System.out.println("SelfSignedCertificateTest: all checks passed");
    }

    private static long seconds(Date date) {
        return date.getTime() / 1000L;
    }

    private static void check(String what, boolean condition) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
