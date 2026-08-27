/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Checks that a purchase document really came from Google Play, by verifying Play's signature
 * over it with this app's licensing public key.
 *
 * <p>Pure Java on purpose, with no Android imports and no state, so the one piece of the paid
 * unlock that has to be exactly right is host-tested against real key pairs rather than reasoned
 * about. {@link ProEntitlement} owns the storage and the policy; this owns the arithmetic.
 *
 * <p><b>Client-side verification is a deliberate choice against Google's current advice, and the
 * reason is structural.</b> Google's Play Billing security page now recommends one thing only:
 * send the purchase token to a backend and confirm it through the Play Developer API. Muralis has
 * no backend and will not have one. That is not laziness, it is the product: the privacy policy
 * states there is no Muralis server before or after a purchase and that nothing about a purchase
 * reaches the developer, which is a promise a verification server would break. So this uses the
 * scheme Google documented for years before that page changed, and which the Billing Library still
 * supports by continuing to expose {@code Purchase.getOriginalJson()} and
 * {@code Purchase.getSignature()}.
 *
 * <p><b>What this defends against, and what it cannot.</b> It defends against a forged or replayed
 * purchase document: something claiming to be a Play response that Play never signed, which is what
 * a fake-billing app on a rooted device serves up. It cannot defend against someone patching the
 * check out of the APK, because no client-side scheme can. That is why the release build is
 * obfuscated (see {@code minifyEnabled} in app/build.gradle) and why this is proportionate rather
 * than airtight: the asset being protected is a 4.99 euro unlock on hardware its owner controls
 * completely.
 *
 * <p>SHA1withRSA is Play's signing scheme for this document, not a choice made here, and it cannot
 * be modernised from this side: the signature is produced by Google with the private half of the
 * RSA pair the Play Console generated for this app.
 */
final class PurchaseSignature {

    private static final String KEY_ALGORITHM = "RSA";
    private static final String SIGNATURE_ALGORITHM = "SHA1withRSA";

    private PurchaseSignature() {
    }

    /**
     * @param base64PublicKey the app's licensing public key, exactly as Play Console prints it
     *                        (X.509 SubjectPublicKeyInfo, base64), or empty when this build was
     *                        given none
     * @param signedData      the purchase document Play signed, {@code Purchase.getOriginalJson()},
     *                        byte for byte: any reformatting invalidates the signature
     * @param base64Signature {@code Purchase.getSignature()}
     * @return true only when the signature is Play's over exactly this document
     */
    static boolean verify(String base64PublicKey, String signedData, String base64Signature) {
        if (isBlank(base64PublicKey) || isBlank(signedData) || isBlank(base64Signature)) {
            // A build with no key configured, or a purchase with nothing to check, verifies
            // nothing. Reported as "not verified" rather than thrown, because the caller's
            // question is only ever "may this panel unlock", and the answer here is no.
            return false;
        }
        try {
            PublicKey key = KeyFactory.getInstance(KEY_ALGORITHM).generatePublic(
                    new X509EncodedKeySpec(Base64.getDecoder().decode(base64PublicKey)));
            Signature verifier = Signature.getInstance(SIGNATURE_ALGORITHM);
            verifier.initVerify(key);
            verifier.update(signedData.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getDecoder().decode(base64Signature));
        } catch (Exception cannotVerify) {
            // Deliberately every exception, and deliberately without rethrowing: a malformed key,
            // a truncated signature, base64 that is not base64, a provider missing the algorithm.
            // Each is a different way of not having verified, and this method has exactly one
            // failure answer. Nothing is logged here either: this class is pure, and the caller
            // that has a Context is the one that can report.
            return false;
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isEmpty();
    }
}
