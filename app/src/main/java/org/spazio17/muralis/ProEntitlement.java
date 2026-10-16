/*
 * Copyright 2026 Muralis contributors
 * Fair Core License, FCL-1.0-ALv2: see LICENSE.md at the repository root.
 */
package org.spazio17.muralis;

import android.content.Context;
import android.util.Log;

/**
 * Whether this panel has Muralis Pro, and the only place that question is answered.
 *
 * <p>Pro is one purchase, bound to a Google account rather than a device. Pro is the fleet,
 * which reads this answer on the panel that holds the account and carries it to the others on a
 * lease ({@link FleetTier}). The rules below are the ones that make a Play purchase work
 * on a wall panel, and each exists because the obvious alternative breaks something.
 *
 * <p><b>Google Play's answer is the truth, and the stored copy is only a bridge.</b> {@link #record}
 * writes a verified purchase into {@link SecretStore} (keystore-backed AES-GCM, the same envelope
 * the broker and admin credentials use) so the answer is known at boot, before Play has been
 * asked, and stays while Play cannot be asked: the service unreachable, the network down, the
 * Store mid-update, and also {@code BILLING_UNAVAILABLE}, which is what a device
 * with no Google account signed in gets. Those answers change nothing. What Play says clearly
 * does: an {@code OK} answer for the whole account that does not contain the purchase makes
 * {@link ProBilling} call {@link #drop}, at once. So a refund ends Pro the next time a signed-in
 * account is asked, and a panel that lost its account keeps what it holds until one says
 * otherwise, even one whose owner disabled the Store or removed the account to keep it after a
 * refund: the price of carrying Pro to panels without Google, and how apps that keep a purchase
 * offline behave. In a fleet the lease does the rest. There is no account check and no grace
 * period of its own: Play already answers the question, and that is how client-only apps on
 * Play behave.
 *
 * <p><b>Reversible by design.</b> Signing back in, or Play recovering, returns the purchase on the
 * next query and {@link #record} stores it again. Nothing but Play's own answer is needed to get
 * Pro back, and nothing is asked of the operator.
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
     * What to tell the operator when a debug build has taken the decision out of Play's hands, or
     * null when no override is compiled in.
     *
     * <p>Exists because the Pro line reports Play's own answer, and on a panel with no Google
     * account that answer is a refusal even while the override has Pro on. Showing "Google Play
     * refused" beside a forced answer is the same invisible divergence the honest-status work
     * removed once already (2026-09-10, the API 26 tablet after its reset). {@code BuildConfig.DEBUG}
     * is a compile-time constant, so this string is not in a release build at all.
     */
    static String overrideDetail() {
        if (!BuildConfig.DEBUG || BuildConfig.PRO_OVERRIDE.isEmpty()) {
            return null;
        }
        return "on".equals(BuildConfig.PRO_OVERRIDE)
                ? "Forced on by this test build. Google Play was not asked."
                : "Forced off by this test build. Google Play was not asked.";
    }

    /**
     * Whether this panel has Muralis Pro.
     *
     * <p>Cheap after the first call. Safe from any thread.
     */
    static boolean isActive(Context context) {
        // Debug builds only, and both directions, because testing the free path matters as much as
        // testing the paid one: with a real purchase now on the developer's own account, "it works
        // on my panel" stopped being evidence that a panel without Pro works. BuildConfig.DEBUG is a
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
        // A negative computed before the user's first unlock is not memoised. Before the unlock
        // the keystore cannot decrypt anything, so "no purchase" is not a finding, it is the
        // keystore being closed, and the service is directBootAware so this genuinely runs then.
        // Caching that false would hold Pro off until the next process restart on any device with
        // a secure lock screen, which is exactly the panel-stays-broken shape the
        // credential-loss fix in SecretStore exists to prevent. A positive is always safe to keep,
        // and a negative computed after the unlock is a real answer.
        if (active || isUserUnlocked(context)) {
            cachedAnswer = active;
        }
        return active;
    }

    private static boolean isUserUnlocked(Context context) {
        android.os.UserManager users = context.getSystemService(android.os.UserManager.class);
        // No manager, no way to tell, so do not memoise: one extra verification per call is the
        // cheap side of this mistake.
        return users != null && users.isUserUnlocked();
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
            // document is not Play's. Both mean "not Pro", and neither is a reason to throw
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
     * cannot decrypt, so this answers false. {@link #isActive} deliberately does not memoise that
     * pre-unlock false, so the first ask after the unlock recomputes against a readable keystore
     * and nothing needs a retry of its own.
     */
    private static boolean verifyStored(Context context) {
        SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
        String json = secrets.getOrNull(PURCHASE_JSON);
        String signature = secrets.getOrNull(PURCHASE_SIGNATURE);
        // Two different nothings, and both mean "not Pro here". Null is the Keystore being
        // unavailable, which SecretStore reports separately so a caller does not mistake it for an
        // absent value. Empty is genuinely nothing stored: a panel that never bought Pro, or one
        // {@link #drop} has cleared. The empty case used to fall through to a signature check over
        // two empty strings, which failed and logged "a stored purchase did not verify", a sentence
        // that reads like tampering on a panel that simply owns nothing. Unreachable until drop()
        // existed; seen on the tablet the day it did, 2026-09-03.
        if (json == null || signature == null || json.isEmpty() || signature.isEmpty()) {
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

    /**
     * Erases the stored purchase, because Google Play has just said clearly that this device does
     * not hold it. The only path that deletes, and {@link ProBilling} is its only caller.
     *
     * <p>Quiet when there was nothing to erase, which is every query on a free panel; the warning
     * is for the panel that had Pro and has just lost it.
     *
     * @param why one line for the log: which answer from Play this was
     */
    static void drop(Context context, String why) {
        SecretStore secrets = new SecretStore(KioskConfig.storageContext(context));
        if (secrets.has(PURCHASE_JSON) || secrets.has(PURCHASE_SIGNATURE)) {
            secrets.clear(PURCHASE_JSON);
            secrets.clear(PURCHASE_SIGNATURE);
            Log.w(TAG, "Muralis Pro is off on this panel: " + why);
        }
        cachedAnswer = Boolean.FALSE;
    }

    /**
     * Whether a purchase Play signed is stored and verifies in this build, whatever a debug
     * override says: what a panel may tell its fleet (FleetTier), since a forced answer is no
     * purchase to lease to others.
     */
    static boolean verifiedHere(Context context) {
        return verifyStored(context);
    }

    /** Drops the memoised answer, so the next {@link #isActive} recomputes it. */
    static void invalidate() {
        cachedAnswer = null;
    }
}
