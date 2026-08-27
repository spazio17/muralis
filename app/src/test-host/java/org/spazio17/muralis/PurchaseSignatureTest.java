/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

/**
 * Host tests for {@link PurchaseSignature}, against real RSA key pairs generated here rather than
 * fixtures: the point is to prove the verification accepts exactly what Google Play's signing
 * produces and refuses everything else, including the near misses.
 *
 * <p>Why this class is worth testing at all when the rest of the billing code is not: this is the
 * whole of the paid unlock's integrity check, it runs with no network and no second opinion, and
 * both of its failure modes are silent. A check that wrongly accepts sells nothing; a check that
 * wrongly refuses takes Pro away from somebody who paid, on a wall panel, with no error they can
 * act on.
 */
public final class PurchaseSignatureTest {

    /** The shape of a real Play purchase document, which is what gets signed. */
    private static final String PURCHASE_JSON =
            "{\"orderId\":\"GPA.1234-5678-9012-34567\","
            + "\"packageName\":\"org.spazio17.muralis\","
            + "\"productId\":\"muralis_pro\","
            + "\"purchaseTime\":1756300000000,\"purchaseState\":0,"
            + "\"purchaseToken\":\"abcdefghijklmnop.AO-J1Ox\",\"acknowledged\":true}";

    public static void main(String[] args) throws Exception {
        KeyPair play = generate();
        KeyPair impostor = generate();

        String key = encodePublic(play);
        String signature = sign(play, PURCHASE_JSON);

        playsOwnSignatureIsAccepted(key, signature);
        anotherKeysSignatureIsRefused(key, sign(impostor, PURCHASE_JSON));
        theWrongPublicKeyIsRefused(encodePublic(impostor), signature);
        editedDocumentIsRefused(key, signature);
        reformattedDocumentIsRefused(play);
        missingInputsAreRefused(key, signature);
        malformedInputsAreRefusedNotThrown(key, signature);

        System.out.println("PurchaseSignatureTest passed");
    }

    /** The one case that must succeed: Play's key, Play's signature, Play's bytes. */
    private static void playsOwnSignatureIsAccepted(String key, String signature) {
        expect(PurchaseSignature.verify(key, PURCHASE_JSON, signature),
                "a genuine Play signature must verify");
    }

    /** A forged purchase: correctly formed, signed by somebody who is not Google. */
    private static void anotherKeysSignatureIsRefused(String key, String forged) {
        expect(!PurchaseSignature.verify(key, PURCHASE_JSON, forged),
                "a signature from a different key must be refused");
    }

    /** The mirror image: right signature, wrong app's key, which is a misconfigured build. */
    private static void theWrongPublicKeyIsRefused(String otherKey, String signature) {
        expect(!PurchaseSignature.verify(otherKey, PURCHASE_JSON, signature),
                "the wrong public key must refuse a good signature");
    }

    /**
     * The attack this actually stops: take a real signed purchase and change what it says. Both
     * directions are tried, since only tampering that keeps the document plausible is interesting.
     */
    private static void editedDocumentIsRefused(String key, String signature) {
        expect(!PurchaseSignature.verify(key,
                        PURCHASE_JSON.replace("muralis_pro", "muralis_free"), signature),
                "a purchase edited to name another product must be refused");
        expect(!PurchaseSignature.verify(key,
                        PURCHASE_JSON.replace("\"purchaseState\":0", "\"purchaseState\":1"),
                        signature),
                "a purchase edited to change its state must be refused");
        expect(!PurchaseSignature.verify(key,
                        PURCHASE_JSON.replace("org.spazio17.muralis", "com.example.other"),
                        signature),
                "a purchase edited to name another package must be refused");
    }

    /**
     * The mistake this class's javadoc warns about, pinned as a test: the signature is over exact
     * bytes, so pretty-printing or re-serialising the JSON breaks it even though the *meaning* is
     * unchanged. Anyone tempted to parse and re-emit the document before storing it fails here.
     */
    private static void reformattedDocumentIsRefused(KeyPair play) throws Exception {
        String key = encodePublic(play);
        String signature = sign(play, PURCHASE_JSON);
        String sameMeaningDifferentBytes = PURCHASE_JSON.replace(",", ", ");
        expect(!PurchaseSignature.verify(key, sameMeaningDifferentBytes, signature),
                "a reformatted document must be refused: the signature is over bytes");
        expect(!PurchaseSignature.verify(key, PURCHASE_JSON + "\n", signature),
                "a trailing newline must be refused for the same reason");
    }

    /** A build with no licensing key, and a purchase with nothing to check, both refuse. */
    private static void missingInputsAreRefused(String key, String signature) {
        expect(!PurchaseSignature.verify("", PURCHASE_JSON, signature),
                "no licensing key means nothing is verified");
        expect(!PurchaseSignature.verify(null, PURCHASE_JSON, signature),
                "a null key must be refused, not thrown");
        expect(!PurchaseSignature.verify(key, "", signature),
                "an empty document must be refused");
        expect(!PurchaseSignature.verify(key, PURCHASE_JSON, ""),
                "an empty signature must be refused");
        expect(!PurchaseSignature.verify(key, null, signature),
                "a null document must be refused, not thrown");
        expect(!PurchaseSignature.verify(key, PURCHASE_JSON, null),
                "a null signature must be refused, not thrown");
    }

    /**
     * Garbage in every input. The assertion is as much that nothing throws as that nothing
     * verifies: this runs on the path that decides whether MQTT starts, and an exception there
     * would take the panel down over a corrupt preference.
     */
    private static void malformedInputsAreRefusedNotThrown(String key, String signature) {
        expect(!PurchaseSignature.verify("not base64 at all!", PURCHASE_JSON, signature),
                "a key that is not base64 must be refused");
        expect(!PurchaseSignature.verify(key, PURCHASE_JSON, "not base64 at all!"),
                "a signature that is not base64 must be refused");
        expect(!PurchaseSignature.verify(Base64.getEncoder().encodeToString(
                        "this is base64 but not a key".getBytes(StandardCharsets.UTF_8)),
                        PURCHASE_JSON, signature),
                "valid base64 that is not a public key must be refused");
        expect(!PurchaseSignature.verify(key, PURCHASE_JSON,
                        Base64.getEncoder().encodeToString(new byte[] {1, 2, 3})),
                "a signature of the wrong length must be refused");
    }

    private static KeyPair generate() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        // 2048 because that is what Play Console issues; the test would pass at any size, but a
        // test that signs with a key Play would never use proves less than one that matches.
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    /** Exactly the encoding Play Console prints: X.509 SubjectPublicKeyInfo, base64. */
    private static String encodePublic(KeyPair pair) {
        return Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
    }

    /** What Google Play does with its private half, so the test's input is the real shape. */
    private static String sign(KeyPair pair, String data) throws Exception {
        Signature signer = Signature.getInstance("SHA1withRSA");
        signer.initSign(pair.getPrivate());
        signer.update(data.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }

    private static void expect(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }
}
