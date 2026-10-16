package org.spazio17.muralis;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.List;

/**
 * The Play Billing client: reads back what the signed-in Google account owns, and acknowledges a
 * purchase Play delivers.
 *
 * <p><b>Nothing is sold here until the fleet exists</b> (Juri, 2026-10-06: nothing is sold that
 * gates nothing). The price query and the purchase sheet went with the Buy button when MQTT and
 * the web admin became free, and come back with the fleet; this file's history has them as they
 * were, offer token and Play's per-product reasons included.
 *
 * <p><b>It decides nothing itself.</b> Every purchase it sees in the {@code PURCHASED} state goes
 * to {@link ProEntitlement#record}, which verifies Play's signature over the document and caches
 * what survives; that cached answer, not anything here, is what the fleet reads (0.7; until
 * 2026-10-06 it gated MQTT and the web admin in {@link KioskService}). This class asks Play and
 * reports, which is why it can be wrong, offline or absent without a panel changing behaviour.
 *
 * <p><b>What a signature does and does not gate, measured rather than assumed 2026-08-27.</b> This
 * class used to claim that only a Play-signed install could get any answer but "unavailable",
 * because Play Billing validates the installed app's signature against the published listing. That
 * claim was wrong. On the API 26 tablet, running a <i>debug-signed</i> APK installed by adb
 * ({@code installer=null}, certificate {@code CN=Android Debug}, not Google's app signing key),
 * with the package published and the signed-in account on the licence-tester list, Play returned
 * full product details, opened the purchase sheet, answered {@code ITEM_ALREADY_OWNED}, and served
 * the existing purchase back through {@code queryPurchasesAsync}. So neither a matching signature
 * nor {@code com.android.vending} as the installer is required to <i>read</i> an entitlement.
 *
 * <p>Do not over-read that. The account was on the licence-tester list, and licence testers are
 * the likeliest reason Play was lenient about the signature at all, so this may not generalise to
 * an ordinary buyer. Two things therefore stay untested, and neither can be tested with this
 * developer's own accounts: completing a <i>new</i> purchase from a locally-signed install, and any
 * of this for a stranger's account. The shipped QR therefore still points at the Play-signed
 * universal APK, now for the reasons that remain sound (Play updates, and not shipping a product
 * that rests on licence-tester behaviour) rather than because Billing was thought impossible
 * otherwise.
 *
 * <p>Results are surfaced on the configuration screen, never only in logcat: logd prunes this
 * app's lines silently under load, so a log line that matters is a log line that may not exist.
 *
 * <p>Billing Library 8, not 7: Play requires 8+ for every new app and update from 2026-08-31.
 *
 * <p><b>Every field in this class belongs to the main thread.</b> The billing library does not say
 * which thread its callbacks arrive on, and different callbacks are not promised the same one, so
 * each callback hops to the main handler before touching anything. That is what makes the plain
 * (unsynchronized, non-volatile) fields correct, and it is also what honours the
 * {@link StatusListener} contract below without a second mechanism.
 *
 * <p><b>Asked at startup, as Google's guide recommends</b>, from
 * {@code KioskActivity.startProBilling}, again in {@code onResume} (Google's
 * recommendation too; with the fleet it is what re-asks after the Play purchase sheet closes,
 * since coming back from the sheet is exactly a resume), and whenever the configuration screen is
 * built. <b>Startup means after the first-start wizard, never before it</b>: a panel with no
 * recorded way out is a panel nobody can leave, and until it has one, Muralis asks Google nothing.
 * See {@code KioskActivity.startProBilling} for why that ordering is the product rule rather than
 * caution. On this app "startup" is not a rare event: the nightly pass exits the process and an alarm
 * relaunches the activity, so the query runs at least daily and a connection opened at startup
 * lives a day at most. An earlier version deferred everything to the configuration screen, on the
 * reasoning that the panel's foreground lasts months and a Play binding should not; that reasoning
 * was wrong, because the process does not last months. Caught 2026-08-27.
 *
 * <p>Two things follow from asking at startup, and both are the point rather than side effects: a
 * purchase made on another device on the same account is picked up by the next nightly restart with
 * nobody touching the panel, and the entitlement is known before anything that reads it (the
 * fleet's anchor, the Pro card) asks.
 */
final class ProBilling implements PurchasesUpdatedListener {

    /** One id, shared between this code and the Play Console product. Changing it is a new product. */
    static final String PRODUCT_ID = "muralis_pro";

    private static final String TAG = "ProBilling";

    /** What the configuration screen renders. Always called on the main thread. */
    interface StatusListener {
        /** @param detail one plain sentence fit to show an operator */
        void onProStatus(String detail);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BillingClient client;

    /**
     * A purchase of the product in the PURCHASED state exists for the signed-in account,
     * acknowledged or not; never true for a merely PENDING one. Play's raw answer, which
     * {@link #statusSentence} weighs against the verified one in {@link ProEntitlement}.
     */
    private boolean owned;
    private String detail = "Checking Google Play…";
    private StatusListener listener;
    private boolean connecting;
    /**
     * Purchase tokens with an acknowledgement in flight, so the same purchase is never
     * acknowledged twice at once.
     *
     * <p>Not hypothetical: on the first real test purchase (phone, 2026-08-27) two acknowledgements
     * fired 14ms apart and Play refused both with "Server error, please try again". Completing a
     * purchase delivers it through {@code onPurchasesUpdated} AND resumes this activity, which
     * re-queries, and neither path saw {@code isAcknowledged} yet because Play had not processed
     * the first call. The retry on the next query then succeeded, so nothing was lost, but one
     * write per token is what should have been sent.
     *
     * <p>Cleared when the call completes, whatever the outcome, so a genuine failure is retried by
     * the next query rather than being latched off. Main thread only, like every field here.
     */
    private final java.util.Set<String> acknowledging = new java.util.HashSet<>();

    ProBilling(Context context) {
        // enableOneTimeProducts is billing-8 for "this app handles pending purchases", which a
        // physical-store code purchase can produce; without declaring it the client refuses to
        // build. Auto-reconnection because the Play service connection is routinely dropped on an
        // idle panel, and this class would otherwise have to rebuild it by hand on every query.
        this.context = context.getApplicationContext();
        client = BillingClient.newBuilder(this.context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .enableAutoServiceReconnection()
                .build();
    }

    /**
     * The configuration screen registers itself here each time it is (re)built. Main thread. The
     * current state is published to the new listener synchronously, before Play is re-asked, so
     * the card paints the last known answer while its view tree is still being built.
     */
    void setListener(StatusListener statusListener) {
        listener = statusListener;
        publish();
        refresh();
    }

    /** Re-asks Play for the account's purchases. Main thread; safe to repeat. */
    void refresh() {
        if (client.isReady()) {
            queryPurchases();
            return;
        }
        if (connecting) {
            return;
        }
        connecting = true;
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                mainHandler.post(() -> {
                    connecting = false;
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                        queryPurchases();
                    } else {
                        // owned stays: a setup failure is knowledge about the moment, not about
                        // the purchase, and dropping it here would end Pro on a network blip.
                        // "Play cannot be asked here" (BILLING_UNAVAILABLE: no account, no Play)
                        // drops nothing either since 2026-10-06: a panel keeps the purchase it
                        // holds, and only Play's own "not owned" from a signed-in account ends it.
                        report(unavailableSentence(result));
                    }
                });
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Auto-reconnection is enabled on the client; nothing to rebuild here. The next
                // refresh() finds isReady() false and connects again if the automatic path lost.
                mainHandler.post(() -> connecting = false);
            }
        });
    }

    /**
     * The configuration screen has been left for the dashboard, so results stop going to a card
     * that no longer exists; the purchases query is unaffected.
     */
    void detachListener() {
        listener = null;
    }

    /** Called by the activity's onDestroy; the client holds a service binding. */
    void release() {
        listener = null;
        client.endConnection();
    }

    private void queryPurchases() {
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (result, purchases) -> mainHandler.post(() -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        // owned stays, same reasoning as the setup failure above: a query that
                        // could not run says nothing about the purchase.
                        report(unavailableSentence(result));
                        return;
                    }
                    // The one caller that speaks for the whole account, and so the only one that
                    // may read an absent purchase as "this account does not own Pro".
                    handlePurchases(purchases, true);
                }));
    }

    /**
     * Logs the moment Pro arrives or goes. Nothing restarts for it any more: until 2026-10-06 this
     * reloaded the service so MQTT and the web admin came up or went down with Pro; both are free
     * now, and the configuration screen follows the change through its own listener.
     */
    private void logChange(boolean activeBefore) {
        boolean activeAfter = ProEntitlement.isActive(context);
        if (activeBefore != activeAfter) {
            Log.i(TAG, activeAfter ? "Muralis Pro arrived" : "Muralis Pro is gone");
        }
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        mainHandler.post(() -> {
            int code = result.getResponseCode();
            if (code == BillingClient.BillingResponseCode.OK) {
                if (purchases != null) {
                    // One flow's purchases, not the account's: a buyer who backed out arrives here
                    // with nothing attached and must not be read as "this account owns nothing".
                    handlePurchases(purchases, false);
                } else {
                    // Documented combination: success with nothing attached. The query is the
                    // tiebreaker, exactly as for ITEM_ALREADY_OWNED below; reporting a refusal
                    // here would print a failure sentence over a success code.
                    queryPurchases();
                }
            } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                // Play's cache and ours disagree; the query is the tiebreaker.
                queryPurchases();
            } else {
                report(unavailableSentence(result));
            }
        });
    }

    /**
     * Main thread only, like every state touch in this class.
     *
     * @param fullAccountQuery whether {@code purchases} is a {@code queryPurchasesAsync} answer for
     *                         the whole account, the only kind of list whose silence about the
     *                         purchase means the account does not own it
     */
    private void handlePurchases(List<Purchase> purchases, boolean fullAccountQuery) {
        boolean activeBefore = ProEntitlement.isActive(context);
        boolean nowOwned = false;
        boolean pending = false;
        for (Purchase purchase : purchases) {
            if (!purchase.getProducts().contains(PRODUCT_ID)) {
                continue;
            }
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                nowOwned = true;
                // Verify Play's signature over the purchase and cache it, so the panel keeps Pro
                // offline and after the Google account is removed. Every sighting is recorded, not
                // only the first: the write is idempotent and this keeps one path rather than a
                // "have I seen this before" flag that could disagree with what is stored.
                ProEntitlement.record(context, purchase.getOriginalJson(),
                        purchase.getSignature());
                // Unacknowledged purchases are refunded by Play after three days, so a panel
                // that bought Pro and was then left alone would silently lose it. Acknowledged
                // here rather than after the entitlement work lands for exactly that reason; a
                // refused acknowledgement is retried by the next query, which is at latest the
                // nightly restart, well inside the three days.
                String token = purchase.getPurchaseToken();
                if (!purchase.isAcknowledged() && acknowledging.add(token)) {
                    client.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(token)
                                    .build(),
                            ackResult -> mainHandler.post(() -> {
                                acknowledging.remove(token);
                                if (ackResult.getResponseCode()
                                        != BillingClient.BillingResponseCode.OK) {
                                    Log.w(TAG, "Purchase acknowledgement refused: "
                                            + ackResult.getResponseCode()
                                            + " " + ackResult.getDebugMessage()
                                            + "; the next query retries it");
                                }
                            }));
                }
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                pending = true;
            }
        }
        owned = nowOwned;
        // Play has spoken for the whole account and the purchase was not in it: a refund, or an
        // account that never bought Pro. At once, not after a grace, which is how client-only apps
        // on Play behave in general and what was decided on 2026-09-03; a wrong empty answer from Play, which
        // does happen right after a boot before the Store has synced, costs Pro for a moment and is
        // corrected by the next query.
        if (fullAccountQuery && !nowOwned) {
            ProEntitlement.drop(context, "Google Play says this account does not own it");
        }
        logChange(activeBefore);
        if (pending && !nowOwned) {
            report("A Pro purchase is pending; Google Play will complete it.");
        } else {
            report(statusSentence());
        }
    }

    /** The steady-state sentence for the card, from what the last queries established. */
    private String statusSentence() {
        if (owned) {
            // Two different questions, and this line used to answer only the first. "owned" is
            // Play's raw answer: a PURCHASED purchase exists for the signed-in account, and
            // reading that needs no signature. Whether this panel has Pro is the stricter
            // question ProEntitlement answers, by verifying Play's signature over the
            // purchase against this build's licensing key. They agree on any correctly built
            // release. They diverge when the purchase cannot be verified here, and then the About
            // line claimed Pro was on the account while the MQTT and web-admin cards beside it
            // stayed locked and their Buy buttons answered ITEM_ALREADY_OWNED, which is an
            // operator being told everything is fine by the one surface that should have said
            // what was wrong (seen 2026-09-03 on a locally built panel whose build carried no
            // licensing key). The check is right and stays; this sentence reports it.
            return ProEntitlement.isActive(context)
                    ? "Muralis Pro is on this Google account."
                    : "Muralis Pro is on this Google account, but this panel could not verify the "
                            + "purchase.";
        }
        return "Muralis Pro is not on this Google account.";
    }

    /**
     * One plain sentence for a refusal. The response codes that matter each get words an
     * operator can act on; the rest carry the code so a report is at least specific.
     */
    private String unavailableSentence(BillingResult result) {
        switch (result.getResponseCode()) {
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                // No account or no Play here, which keeps a stored purchase (2026-10-06), so the
                // line says so rather than reading as Pro being gone. Nothing is for sale, so it
                // does not send anybody to sign in to Google either: a free panel is not nagged.
                return ProEntitlement.isActive(context)
                        ? "Muralis Pro stays on this panel. Google Play is not available here to "
                                + "check it."
                        : "Google Play is not available here.";
            case BillingClient.BillingResponseCode.SERVICE_DISCONNECTED:
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
            case BillingClient.BillingResponseCode.NETWORK_ERROR:
                // About the moment, not the device: a Play that dropped its connection on an idle
                // panel answers the same as one that cannot bill at all (the API 26 tablet with no
                // account ends here once the client's own reconnection gives up, 2026-10-06), so
                // the words claim neither; "Google Play refused (-1)" was printed before.
                return ProEntitlement.isActive(context)
                        ? "Muralis Pro stays on this panel. Google Play could not be reached to "
                                + "check it."
                        : "Google Play could not be reached.";
            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return "Google Play on this device is too old to be asked.";
            default:
                return "Google Play refused (" + result.getResponseCode() + "): "
                        + result.getDebugMessage();
        }
    }

    /** Main thread only: every caller is either a main-thread entry point or a hopped callback. */
    private void report(String sentence) {
        detail = sentence;
        publish();
    }

    private void publish() {
        StatusListener current = listener;
        if (current != null) {
            current.onProStatus(detail);
        }
    }
}
