/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * SPAKE2 (RFC 9382) over a join code: both panels prove they know the code without sending it,
 * as Android's Wireless debugging pairing and Matter's setup code do. Someone on the network who
 * plays either side learns nothing it could test offline, and gets one guess per exchange.
 *
 * <p>The group is RFC 7919's ffdhe2048 (a safe prime, 2 generating the subgroup of order q), so
 * plain {@link BigInteger} arithmetic does it with no library. M and N are hashed into that
 * subgroup from fixed labels (squaring lands in it), so nobody knows their logarithms. The
 * transcript binds both panels' key hashes, which their TLS connection already pinned, so an
 * exchange cannot be relayed to another connection.
 *
 * <p>Pure, with no Android imports, so the rules are covered by host tests.
 */
final class Spake2 {
    static final BigInteger P = new BigInteger(
            "ffffffffffffffffadf85458a2bb4a9aafdc5620273d3cf1d8b9c583ce2d3695a9e13641146433fbcc93"
            + "9dce249b3ef97d2fe363630c75d8f681b202aec4617ad3df1ed5d5fd65612433f51f5f066ed085636555"
            + "3ded1af3b557135e7f57c935984f0c70e0e68b77e2a689daf3efe8721df158a136ade73530acca4f483a"
            + "797abc0ab182b324fb61d108a94bb2c8e3fbb96adab760d7f4681d4f42a3de394df4ae56ede76372bb19"
            + "0b07a7c8ee0a6d709e02fce1cdf7e2ecc03404cd28342f619172fe9ce98583ff8e4f1232eef28183c3fe"
            + "3b1b4c6fad733bb5fcbc2ec22005c58ef1837d1683b2c6f34a26c1b2effa886b423861285c97ffffffff"
            + "ffffffff", 16);
    static final BigInteger Q = P.subtract(BigInteger.ONE).shiftRight(1);
    static final BigInteger G = BigInteger.valueOf(2);
    static final BigInteger M = intoGroup("muralis spake2 M");
    static final BigInteger N = intoGroup("muralis spake2 N");

    private final boolean adopter;
    private final BigInteger w;
    private final BigInteger secret;
    private final BigInteger share;

    /**
     * One side of an exchange: the adopter (A, who typed or read the code) or the panel that
     * shows it (B).
     */
    Spake2(boolean adopter, String code, SecureRandom random) {
        this.adopter = adopter;
        this.w = new BigInteger(1, sha256(bytes("muralis spake2 w"), bytes(code))).mod(Q);
        this.secret = new BigInteger(256, random).add(BigInteger.ONE);
        this.share = G.modPow(secret, P).multiply((adopter ? M : N).modPow(w, P)).mod(P);
    }

    /** This side's share, as hex: X for the adopter, Y for the panel. */
    String share() {
        return share.toString(16);
    }

    /** The confirmations both sides derive once they hold both shares. */
    static final class Confirmations {
        /** What the adopter sends (cA) and the panel expects. */
        final byte[] adopter;
        /** What the panel sends (cB) and the adopter expects. */
        final byte[] panel;

        Confirmations(byte[] adopter, byte[] panel) {
            this.adopter = adopter;
            this.panel = panel;
        }
    }

    /**
     * Both confirmations from the other side's share and the two key hashes (the adopter's, the
     * panel's), or null when that share is not a member of the group.
     */
    Confirmations finish(String peerShare, String adopterSpki, String panelSpki) {
        BigInteger peer;
        try {
            peer = new BigInteger(peerShare, 16);
        } catch (NumberFormatException | NullPointerException malformed) {
            return null;
        }
        if (peer.compareTo(BigInteger.ONE) <= 0 || peer.compareTo(P.subtract(BigInteger.ONE)) >= 0
                || !peer.modPow(Q, P).equals(BigInteger.ONE)) {
            return null;
        }
        // The other side's mask taken off: its element to the power -w, Q - w in the subgroup.
        BigInteger unmasked = peer.multiply((adopter ? N : M).modPow(Q.subtract(w), P)).mod(P);
        BigInteger k = unmasked.modPow(secret, P);
        BigInteger x = adopter ? share : peer;
        BigInteger y = adopter ? peer : share;
        byte[] transcript = concat(field(bytes(adopterSpki)), field(bytes(panelSpki)),
                field(x.toByteArray()), field(y.toByteArray()), field(k.toByteArray()),
                field(w.toByteArray()));
        byte[] ka = java.util.Arrays.copyOfRange(sha256(transcript), 16, 32);
        byte[] keys = hkdf(ka, bytes("ConfirmationKeys"), 64);
        return new Confirmations(
                hmac(java.util.Arrays.copyOfRange(keys, 0, 32), transcript),
                hmac(java.util.Arrays.copyOfRange(keys, 32, 64), transcript));
    }

    /** Hex of a confirmation, as it travels. */
    static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte b : value) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }

    /** Whether a confirmation offered as hex is the expected one, in constant time. */
    static boolean matches(byte[] expected, String offered) {
        return offered != null && MessageDigest.isEqual(bytes(hex(expected)),
                offered.toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private static BigInteger intoGroup(String label) {
        // 2048 + 128 bits of hash, so the value mod P is close to uniform, then squared.
        byte[] wide = new byte[0];
        for (int i = 0; wide.length < 272; i++) {
            wide = concat(wide, sha256(bytes(label), new byte[] {(byte) i}));
        }
        return new BigInteger(1, wide).mod(P).modPow(BigInteger.valueOf(2), P);
    }

    private static byte[] field(byte[] value) {
        byte[] out = new byte[8 + value.length];
        long length = value.length;
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (length >>> (8 * i));
        }
        System.arraycopy(value, 0, out, 8, value.length);
        return out;
    }

    private static byte[] hkdf(byte[] key, byte[] info, int length) {
        byte[] prk = hmac(new byte[32], key);
        byte[] out = new byte[0];
        byte[] block = new byte[0];
        for (int i = 1; out.length < length; i++) {
            block = hmac(prk, concat(block, info, new byte[] {(byte) i}));
            out = concat(out, block);
        }
        return java.util.Arrays.copyOf(out, length);
    }

    private static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] sha256(byte[]... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (byte[] part : parts) {
                digest.update(part);
            }
            return digest.digest();
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] concat(byte[]... parts) {
        int length = 0;
        for (byte[] part : parts) {
            length += part.length;
        }
        byte[] out = new byte[length];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    private static byte[] bytes(String text) {
        return (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
    }
}
