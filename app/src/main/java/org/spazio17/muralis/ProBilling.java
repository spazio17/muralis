package org.spazio17.muralis;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.android.billingclient.api.AcknowledgePurchaseParams;
import com.android.billingclient.api.BillingClient;
import com.android.billingclient.api.BillingClientStateListener;
import com.android.billingclient.api.BillingFlowParams;
import com.android.billingclient.api.BillingResult;
import com.android.billingclient.api.PendingPurchasesParams;
import com.android.billingclient.api.ProductDetails;
import com.android.billingclient.api.Purchase;
import com.android.billingclient.api.PurchasesUpdatedListener;
import com.android.billingclient.api.QueryProductDetailsParams;
import com.android.billingclient.api.QueryProductDetailsResult;
import com.android.billingclient.api.QueryPurchasesParams;
import com.android.billingclient.api.UnfetchedProduct;

import java.util.Collections;
import java.util.List;

/**
 * The Play Billing client: sees the Pro product, runs a purchase, and reads back what the
 * signed-in Google account owns.
 *
 * <p><b>It decides nothing itself.</b> Every purchase it sees in the {@code PURCHASED} state goes
 * to {@link ProEntitlement#record}, which verifies Play's signature over the document and caches
 * what survives; that cached answer, not anything here, is what gates MQTT and the web admin in
 * {@link KioskService}. This class asks Play and reports, which is why it can be wrong, offline or
 * absent without a panel changing behaviour.
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
 * {@code KioskActivity.initializeUserInterface}, again in {@code onResume} (Google's
 * recommendation too, and on this app it is what re-asks after the Play purchase sheet closes,
 * since coming back from the sheet is exactly a resume), and whenever the configuration screen is
 * built. On this app "startup" is not a rare event: the nightly pass exits the process and an alarm
 * relaunches the activity, so the query runs at least daily and a connection opened at startup
 * lives a day at most. An earlier version deferred everything to the configuration screen, on the
 * reasoning that the panel's foreground lasts months and a Play binding should not; that reasoning
 * was wrong, because the process does not last months. Juri caught it 2026-08-27.
 *
 * <p>Two things follow from asking at startup, and both are the point rather than side effects: a
 * purchase made on another device on the same account is picked up by the next nightly restart with
 * nobody touching the panel, and the entitlement will be known before MQTT and the web admin decide
 * whether to come up, which is a thing the gate cannot work without.
 */
final class ProBilling implements PurchasesUpdatedListener {

    /** One id, shared between this code and the Play Console product. Changing it is a new product. */
    static final String PRODUCT_ID = "muralis_pro";

    private static final String TAG = "ProBilling";

    /** What the configuration screen renders. Always called on the main thread. */
    interface StatusListener {
        /**
         * @param detail one plain sentence fit to show an operator
         * @param owned  a purchase of the product in the PURCHASED state exists for the signed-in
         *               account, acknowledged or not; never true for a merely PENDING one
         * @param buyable product details are in hand and a purchase could be launched now
         */
        void onProStatus(String detail, boolean owned, boolean buyable);
    }

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final BillingClient client;

    /**
     * The last details seen, kept ONLY to render the price on the card. Never passed to
     * {@link #buy}: Google's guidance is that a cached {@code ProductDetails} goes stale and makes
     * {@code launchBillingFlow} fail, so the purchase path re-queries and uses the fresh object.
     */
    private ProductDetails productForDisplay;
    /**
     * Why there are no details to show, as a full sentence, or null while details are in hand:
     * either Play's own per-product reason (see {@link #unfetchedStatus}) or the refusal the
     * details query got. Kept so the purchases result, which lands after the details result and
     * also repaints the card, cannot overwrite the explanation with a generic "not offered yet".
     */
    private String productProblem;
    private boolean owned;
    private boolean buyable;
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

    /** Re-asks Play for the product and the account's purchases. Main thread; safe to repeat. */
    void refresh() {
        if (client.isReady()) {
            query();
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
                        query();
                    } else {
                        // owned stays: a setup failure is transient knowledge about Play, not
                        // knowledge that the purchase went away. When the gate reuses this state,
                        // wiping it here would drop the remote surfaces on a network blip.
                        report(unavailableSentence(result), owned, false);
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
     * Starts the Play purchase sheet, on details fetched for this attempt rather than on whatever
     * the card happens to be displaying. Google's guidance is explicit that a cached
     * {@code ProductDetails} can be stale and fail {@code launchBillingFlow}, and on a wall panel
     * the gap between "the screen was opened" and "somebody pressed Buy" can be very long indeed.
     *
     * <p>The purchase result arrives at {@link #onPurchasesUpdated}, not here.
     */
    void buy(Activity activity) {
        client.queryProductDetailsAsync(productQuery(), (result, detailsResult) ->
                mainHandler.post(() -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        report(unavailableSentence(result), owned, buyable);
                        return;
                    }
                    List<ProductDetails> found = detailsResult.getProductDetailsList();
                    if (found.isEmpty()) {
                        productForDisplay = null;
                        productProblem = unfetchedSentence(detailsResult);
                        report(productProblem, owned, false);
                        return;
                    }
                    ProductDetails fresh = found.get(0);
                    productForDisplay = fresh;
                    productProblem = null;
                    BillingFlowParams.ProductDetailsParams.Builder line =
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(fresh);
                    // Google's billing-8 flow takes the offer token from the one-time offer list,
                    // the same as it always has for subscriptions. This app sells one product with
                    // its default purchase option, so the first offer is the offer; a product page
                    // configured with several offers would need a chooser, not a different token.
                    // Only set when present and non-empty: billing 8 throws on an empty token, and
                    // a details object with no offer list launches on the product alone.
                    String offerToken = offerTokenOf(fresh);
                    if (offerToken != null) {
                        line.setOfferToken(offerToken);
                    }
                    BillingFlowParams params = BillingFlowParams.newBuilder()
                            .setProductDetailsParamsList(
                                    Collections.singletonList(line.build()))
                            .build();
                    // Already on the main thread, which launchBillingFlow requires.
                    BillingResult launch = client.launchBillingFlow(activity, params);
                    if (launch.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        report(unavailableSentence(launch), owned, buyable);
                    }
                }));
    }

    /** Called by the activity's onDestroy; the client holds a service binding. */
    void release() {
        listener = null;
        client.endConnection();
    }

    /** The one product this app sells. Rebuilt per call rather than held, like the details. */
    private QueryProductDetailsParams productQuery() {
        return QueryProductDetailsParams.newBuilder()
                .setProductList(Collections.singletonList(
                        QueryProductDetailsParams.Product.newBuilder()
                                .setProductId(PRODUCT_ID)
                                .setProductType(BillingClient.ProductType.INAPP)
                                .build()))
                .build();
    }

    private void query() {
        client.queryProductDetailsAsync(productQuery(), (result, detailsResult) ->
                mainHandler.post(() -> {
                    List<ProductDetails> found = detailsResult.getProductDetailsList();
                    if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                            && !found.isEmpty()) {
                        productForDisplay = found.get(0);
                        productProblem = null;
                    } else if (result.getResponseCode()
                            != BillingClient.BillingResponseCode.OK) {
                        productForDisplay = null;
                        productProblem = unavailableSentence(result);
                        Log.w(TAG, "Product details refused: " + result.getResponseCode()
                                + " " + result.getDebugMessage());
                    } else {
                        productForDisplay = null;
                        // The v8 API that says WHY a product came back empty, rather than leaving
                        // "not offered yet" as the only thing anyone can report. This is the
                        // difference between "the Play Console product does not exist" and "this
                        // install cannot be matched to the listing", which look identical
                        // without it.
                        productProblem = unfetchedSentence(detailsResult);
                        Log.w(TAG, "Product " + PRODUCT_ID + " unfetched: " + productProblem);
                    }
                    queryPurchases();
                }));
    }

    /** The per-product reason Play gives for not returning details, or null if it gave none. */
    private String unfetchedStatus(QueryProductDetailsResult detailsResult) {
        List<UnfetchedProduct> unfetched = detailsResult.getUnfetchedProductList();
        if (unfetched == null || unfetched.isEmpty()) {
            return null;
        }
        StringBuilder reasons = new StringBuilder();
        for (UnfetchedProduct entry : unfetched) {
            if (reasons.length() > 0) {
                reasons.append("; ");
            }
            reasons.append(entry.getProductId())
                    .append(": ")
                    .append(statusName(entry.getStatusCode()));
        }
        return reasons.toString();
    }

    /**
     * Words rather than a number. These are {@link UnfetchedProduct.StatusCode} values, which are
     * their own set and are NOT {@code BillingResponseCode} values however similar the numbers
     * look; read off the 8.3.0 artifact rather than assumed. The distinction matters because the
     * one this panel actually reports, 3, is BILLING_UNAVAILABLE under the other set, which would
     * send somebody debugging the device instead of creating the product.
     */
    private static String statusName(int statusCode) {
        switch (statusCode) {
            case UnfetchedProduct.StatusCode.PRODUCT_NOT_FOUND:
                return "no such product on this Play listing yet";
            case UnfetchedProduct.StatusCode.INVALID_PRODUCT_ID_FORMAT:
                return "the product id is not a valid one";
            case UnfetchedProduct.StatusCode.NO_ELIGIBLE_OFFER:
                return "no offer this account is eligible for";
            case UnfetchedProduct.StatusCode.UNKNOWN:
                return "Google Play gave no reason";
            default:
                return "status " + statusCode;
        }
    }

    /** One sentence for an empty product list, carrying Play's reason when it gave one. */
    private String unfetchedSentence(QueryProductDetailsResult detailsResult) {
        String status = unfetchedStatus(detailsResult);
        return status == null
                ? "Google Play does not offer Muralis Pro to this device yet."
                : "Google Play did not return the product (" + status + ").";
    }

    /**
     * The offer this app sells, from the billing-8 offer list with the legacy single-offer shape
     * as the fallback. Null when the details carry neither, which is a details object this app has
     * never actually seen but must not crash on.
     */
    private static ProductDetails.OneTimePurchaseOfferDetails offerOf(ProductDetails details) {
        List<ProductDetails.OneTimePurchaseOfferDetails> offers =
                details.getOneTimePurchaseOfferDetailsList();
        if (offers != null && !offers.isEmpty()) {
            return offers.get(0);
        }
        return details.getOneTimePurchaseOfferDetails();
    }

    /** The token of {@link #offerOf}'s offer, or null when there is no token to pass. */
    private static String offerTokenOf(ProductDetails details) {
        ProductDetails.OneTimePurchaseOfferDetails offer = offerOf(details);
        if (offer == null) {
            return null;
        }
        String token = offer.getOfferToken();
        return token == null || token.isEmpty() ? null : token;
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
                        report(unavailableSentence(result), owned,
                                productForDisplay != null && !owned);
                        return;
                    }
                    handlePurchases(purchases);
                }));
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        mainHandler.post(() -> {
            int code = result.getResponseCode();
            if (code == BillingClient.BillingResponseCode.OK) {
                if (purchases != null) {
                    handlePurchases(purchases);
                } else {
                    // Documented combination: success with nothing attached. The query is the
                    // tiebreaker, exactly as for ITEM_ALREADY_OWNED below; reporting a refusal
                    // here would print a failure sentence over a success code.
                    queryPurchases();
                }
            } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
                report(statusSentence(), owned, buyable);
            } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
                // Play's cache and ours disagree; the query is the tiebreaker.
                queryPurchases();
            } else {
                report(unavailableSentence(result), owned, buyable);
            }
        });
    }

    /** Main thread only, like every state touch in this class. */
    private void handlePurchases(List<Purchase> purchases) {
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
        buyable = productForDisplay != null && !nowOwned;
        // The moment Pro arrives, the paid surfaces come up, without waiting for a restart:
        // buying at the panel should visibly work while the buyer is still standing there. The
        // reload takes the same path a settings save takes (restartControllers), which finds both
        // controllers null and starts them, now past the gate.
        if (!activeBefore && ProEntitlement.isActive(context)) {
            Log.i(TAG, "Muralis Pro arrived; starting the remote surfaces");
            KioskService.reloadConfiguration(context);
        }
        if (pending && !nowOwned) {
            report("A Pro purchase is pending; Google Play will complete it.", false, false);
        } else {
            report(statusSentence(), owned, buyable);
        }
    }

    /** The steady-state sentence for the card, from what the last queries established. */
    private String statusSentence() {
        if (owned) {
            return "Muralis Pro is on this Google account.";
        }
        ProductDetails details = productForDisplay;
        if (details != null) {
            ProductDetails.OneTimePurchaseOfferDetails offer = offerOf(details);
            return offer == null
                    ? "Muralis Pro is available."
                    : "Muralis Pro is available: " + offer.getFormattedPrice() + ", one time.";
        }
        return productProblem == null
                ? "Google Play does not offer Muralis Pro to this device yet."
                : productProblem;
    }

    /**
     * One plain sentence for a refusal. The response codes that matter each get words an
     * operator can act on; the rest carry the code so a report is at least specific.
     */
    private String unavailableSentence(BillingResult result) {
        switch (result.getResponseCode()) {
            case BillingClient.BillingResponseCode.BILLING_UNAVAILABLE:
                return "Google Play billing is not available on this device or account.";
            case BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE:
            case BillingClient.BillingResponseCode.NETWORK_ERROR:
                return "Google Play could not be reached; check the network and try again.";
            case BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED:
                return "This device's Google Play version cannot handle purchases.";
            default:
                return "Google Play refused (" + result.getResponseCode() + "): "
                        + result.getDebugMessage();
        }
    }

    /** Main thread only: every caller is either a main-thread entry point or a hopped callback. */
    private void report(String sentence, boolean nowOwned, boolean nowBuyable) {
        owned = nowOwned;
        buyable = nowBuyable;
        detail = sentence;
        publish();
    }

    private void publish() {
        StatusListener current = listener;
        if (current != null) {
            current.onProStatus(detail, owned, buyable);
        }
    }
}
