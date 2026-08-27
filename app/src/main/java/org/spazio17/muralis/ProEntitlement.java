/*
 * Copyright 2026 Muralis contributors
 * All rights reserved. See LICENSE at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Log;

/**
 * Whether this panel may run the paid surfaces, and the only place that question is answered.
 *
 * <p>Pro is one purchase, bound to a Google account rather than a device, unlocking MQTT and the
 * web admin together. The rules below are the ones that make that work on a wall panel, and each
 * exists because the obvious alternative breaks something.
 *
 * <p><b>The answer is cached, and a negative answer never erases it.</b> {@link #record} writes a
 * verified purchase into {@link SecretStore} (keystore-backed AES-GCM, the same envelope the broker
 * and admin credentials use) and nothing removes it automatically. That asymmetry is the whole
 * design: {@code queryPurchasesAsync} returns an empty list both for "this account never bought
 * Pro" and for "there is no account signed in and no network to ask", and those must not have the
 * same consequence. A panel that bought Pro and was then de-Googled, or unplugged from the
 * internet, or simply asked at the wrong moment, keeps working. Juri's stated intent is that people
 * buy once and may then run the tablet with no Google account at all.
 *
 * <p><b>A refund therefore leaves the panel unlocked, knowingly.</b> Play can refund and revoke at
 * its discretion, and this cache does not follow it. That was accepted deliberately (see the DDA
 * notes in TODO-muralis-release.md) as the price of offline survival, and it is a consequence to
 * live with rather than a hole to close: closing it means a panel that goes dark because a broker
 * check failed on a Tuesday.
 *
 * <p><b>Nothing here trusts storage on its own.</b> The cached document is re-verified against
 * Play's signature on read, not merely read back, so tampering with the preferences file is not a
 * way in. {@link SecretStore} already binds the field name in as additional authenticated data, so
 * a value cannot be moved between fields either.
 *
 * <p>See {@link PurchaseSignature} for why verification happens on the device rather than on a
 * server, and for what that does and does not protect.
 */
final class ProEntitlement {

    private static final String TAG = "ProEntitlement";

    /** The signed purchase document, verbatim, because the signature is over exactly these bytes. */
    private static final String PURCHASE_JSON = "pro_purchase_json";
    /** Play's signature over {@link #PURCHASE_JSON}. */
    private static final String PURCHASE_SIGNATURE = "pro_purchase_signature";

    /**
     * The last computed answer, so a per-request caller cannot turn this into an RSA verification
     * per request. Volatile rather than synchronized: a stale read costs one extra verification,
     * and the process restarts nightly anyway, so there is nothing here worth a lock.
     */
    private static volatile Boolean cachedAnswer;

    private ProEntitlement() {
    }

    /**
     * Whether the paid surfaces may run on this panel.
     *
     * <p>Cheap after the first call. Safe from any thread.
     */
    static boolean isActive(Context context) {
        // Debug builds only, and both directions, because testing the free path matters as much as
        // testing the paid one: with a real purchase now on the developer's own account, "it works
        // on my panel" stopped being evidence that the gate works at all. BuildConfig.DEBUG is a
        // compile-time constant, so R8 removes this whole block from a release build rather than
        // leaving an override for somebody to find.
        if (BuildConfig.DEBUG && !BuildConfig.PRO_OVERRIDE.isEmpty()) {
            boolean forced = "on".equals(BuildConfig.PRO_OVERRIDE);
            Log.w(TAG, "Pro is forced " + (forced ? "ON" : "OFF")
                    + " by MURALIS_PRO_OVERRIDE; this is a debug build");
            return forced;
        }
        Boolean known = cachedAnswer;
        if (known != null) {
            return known;
        }
        boolean active = verifyStored(context);
        cachedAnswer = active;
        return active;
    }

    /**
     * Stores a purchase, if and only if Play really signed it.
     *
     * <p>Called for every purchase seen in the {@code PURCHASED} state, including ones that were
     * already known: re-recording an identical document costs one verification and keeps this the
     * single write path. A document that fails verification is not stored and, importantly, does
     * not disturb whatever was stored before.
     *
     * @return true when the entitlement is active after this call
     */
    static boolean record(Context context, String originalJson, String signature) {
        if (!PurchaseSignature.verify(BuildConfig.LICENSE_KEY, originalJson, signature)) {
            // Either this build has no licensing key (the likely case in development) or the
            // document is not Play's. Both mean "do not unlock", and neither is a reason to throw
            // away an entitlement that was verified earlier.
            Log.w(TAG, BuildConfig.LICENSE_KEY.isEmpty()
                    ? "No licensing key in this build, so a purchase cannot be verified"
                    : "A purchase failed signature verification and was not stored");
            return isActive(context);
        }
        SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
        secrets.put(PURCHASE_JSON, originalJson);
        secrets.put(PURCHASE_SIGNATURE, signature);
        cachedAnswer = Boolean.TRUE;
        Log.i(TAG, "Muralis Pro verified and stored for this device");
        return true;
    }

    /**
     * Reads the cached purchase back and re-verifies it.
     *
     * <p>A read failure is indistinguishable from an absent purchase here on purpose. The one case
     * worth naming: before the first unlock of a device with a secure lock screen, the keystore
     * cannot decrypt, so this answers false. The paid surfaces come up on the unlock, which is the
     * same moment the rest of the app's credentials become readable, so nothing needs a retry of
     * its own.
     */
    private static boolean verifyStored(Context context) {
        SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
        String json = secrets.getOrNull(PURCHASE_JSON);
        String signature = secrets.getOrNull(PURCHASE_SIGNATURE);
        if (json == null || signature == null) {
            return false;
        }
        boolean verified = PurchaseSignature.verify(BuildConfig.LICENSE_KEY, json, signature);
        if (!verified) {
            // Kept, not deleted. The likeliest cause by far is a build with no licensing key, and
            // erasing a good purchase because this particular build could not check it would be
            // the one bug this class exists to prevent.
            Log.w(TAG, "A stored purchase did not verify in this build; Pro stays off here");
        }
        return verified;
    }

    /** Drops the memoised answer, so the next {@link #isActive} recomputes it. */
    static void invalidate() {
        cachedAnswer = null;
    }
}
