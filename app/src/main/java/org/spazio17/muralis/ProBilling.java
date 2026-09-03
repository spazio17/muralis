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
 * {@code KioskActivity.startProBilling}, again in {@code onResume} (Google's
 * recommendation too, and on this app it is what re-asks after the Play purchase sheet closes,
 * since coming back from the sheet is exactly a resume), and whenever the configuration screen is
 * built. <b>Startup means after the first-start wizard, never before it</b>: a panel with no
 * recorded way out is a panel nobody can leave, and until it has one, Muralis asks Google nothing.
 * See {@code KioskActivity.startProBilling} for why that ordering is the product rule rather than
 * caution. On this app "startup" is not a rare event: the nightly pass exits the process and an alarm
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
    /**
     * Whether Play has ever answered a product-details query on this client, with details or with a
     * refusal. False is not a problem to report, it is the ordinary state of a running panel: the
     * price is only fetched while a screen is showing it, so {@link #statusSentence} must not read
     * an absent {@link #productForDisplay} as Play refusing to offer the product. Set when the
     * answer lands, not when the question goes out: the purchases answer usually arrives first, and
     * a flag set on asking made that first repaint say "not offered" for the half second before the
     * price came in.
     */
    private boolean productAnswered;
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
            askPlay();
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
                        askPlay();
                    } else {
                        // owned stays: a setup failure is knowledge about the moment, not about
                        // the purchase, and wiping the gate here would drop the remote surfaces
                        // on a network blip. The one exception is the code that is about this
                        // device or account rather than the moment; see the helper.
                        closeGateIfPlayWillNotServe(result);
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
                        // Not an answer about the product, so productAnswered stays as it was: a
                        // Buy that failed on transport must not leave statusSentence with the
                        // flag set and neither details nor a problem to show for it.
                        report(unavailableSentence(result), owned, buyable);
                        return;
                    }
                    productAnswered = true;
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

    /**
     * The configuration screen has been left for the dashboard. Nothing is drawing the price now,
     * so {@link #askPlay} stops asking for it; the entitlement query is unaffected. Without this the
     * listener outlived its screen, and every refresh after the first visit to settings, nightly
     * included, fetched a price for a card that no longer existed.
     */
    void detachListener() {
        listener = null;
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

    /**
     * The two questions, and the rule for which of them to ask.
     *
     * <p>What the account owns is asked every time, because that is the entitlement and it gates
     * MQTT and the web admin whether or not anybody is looking at a screen.
     *
     * <p>What the product costs is asked only while a {@link StatusListener} is attached, which
     * means only while the configuration screen is up to show a price. A panel spends its life on
     * the dashboard, where the price is drawn nowhere and asked by nobody, so asking anyway was one
     * needless conversation with Play per launch on every panel in the world. {@link #buy} does not
     * depend on this either way: it re-queries for itself, deliberately, because Google's guidance
     * is that a cached {@code ProductDetails} goes stale.
     */
    private void askPlay() {
        if (listener != null) {
            queryProductForDisplay();
        }
        queryPurchases();
    }

    /** The price, for the card that shows it. Reports on its own; see {@link #askPlay}. */
    private void queryProductForDisplay() {
        client.queryProductDetailsAsync(productQuery(), (result, detailsResult) ->
                mainHandler.post(() -> {
                    productAnswered = true;
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
                    // Used to fall through to queryPurchases and let that one line report for both.
                    // The two are independent now, so this one says what it found rather than
                    // leaving the card on whatever the purchases answer happened to paint.
                    report(statusSentence(), owned, productForDisplay != null && !owned);
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
                        // could not run says nothing about the purchase, with the same exception.
                        closeGateIfPlayWillNotServe(result);
                        report(unavailableSentence(result), owned,
                                productForDisplay != null && !owned);
                        return;
                    }
                    // The one caller that speaks for the whole account, and so the only one that
                    // may read an absent purchase as "this account does not own Pro".
                    handlePurchases(purchases, true);
                }));
    }

    /**
     * {@code BILLING_UNAVAILABLE} is the one refusal that is about this device or account rather
     * than about the moment. Google lists an out-of-date Store, an unsupported country, an
     * enterprise that disabled purchases and a blocked Store; in practice it is also what a device
     * with no Google account signed in gets, which under Juri's rule is the case that matters. Play
     * will not vouch for anything here until the operator changes something, so Pro is off until
     * it does, and comes back by itself when Play answers again. Every other refusal, service
     * unavailable, disconnected, network, timeout, is about the moment and leaves the gate alone,
     * which is what keeps a panel with its account signed in and its internet down on Pro.
     */
    private void closeGateIfPlayWillNotServe(BillingResult result) {
        if (result.getResponseCode() != BillingClient.BillingResponseCode.BILLING_UNAVAILABLE) {
            return;
        }
        boolean activeBefore = ProEntitlement.isActive(context);
        ProEntitlement.drop(context, "Google Play billing is unavailable on this device or "
                + "account (" + result.getDebugMessage() + ")");
        // Play's raw answer is "no" too: the callers report this state, and a stale "owned" from
        // before the account went away would otherwise read as Pro being on the account.
        owned = false;
        applyGate(activeBefore);
    }

    /**
     * Runs the service's rebuild when the gate has just moved, in either direction.
     *
     * <p>Opening: the paid surfaces come up without waiting for a restart, because buying at the
     * panel should visibly work while the buyer is still standing there; the reload takes the
     * path a settings save takes (restartControllers), which finds both controllers null and
     * starts them. Closing: the same call tears them down, which it did not do before Pro could
     * end.
     */
    private void applyGate(boolean activeBefore) {
        boolean activeAfter = ProEntitlement.isActive(context);
        if (activeBefore != activeAfter) {
            Log.i(TAG, activeAfter
                    ? "Muralis Pro arrived; starting the remote surfaces"
                    : "Muralis Pro is gone; stopping the remote surfaces");
            KioskService.reloadConfiguration(context);
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
        buyable = productForDisplay != null && !nowOwned;
        // Play has spoken for the whole account and the purchase was not in it: a refund, or an
        // account that never bought Pro. At once, not after a grace, which is how client-only apps
        // on Play behave in general and what Juri asked for; a wrong empty answer from Play, which
        // does happen right after a boot before the Store has synced, costs a brief outage of the
        // paid surfaces and is corrected by the next query.
        if (fullAccountQuery && !nowOwned) {
            ProEntitlement.drop(context, "Google Play says this account does not own it");
        }
        applyGate(activeBefore);
        if (pending && !nowOwned) {
            report("A Pro purchase is pending; Google Play will complete it.", false, false);
        } else {
            report(statusSentence(), owned, buyable);
        }
    }

    /** The steady-state sentence for the card, from what the last queries established. */
    private String statusSentence() {
        if (owned) {
            // Two different questions, and this line used to answer only the first. "owned" is
            // Play's raw answer: a PURCHASED purchase exists for the signed-in account, and
            // reading that needs no signature. Whether the paid surfaces actually run is the
            // stricter question ProEntitlement answers, by verifying Play's signature over the
            // purchase against this build's licensing key. They agree on any correctly built
            // release. They diverge when the purchase cannot be verified here, and then the About
            // line claimed Pro was on the account while the MQTT and web-admin cards beside it
            // stayed locked and their Buy buttons answered ITEM_ALREADY_OWNED, which is an
            // operator being told everything is fine by the one surface that should have said
            // what was wrong (seen 2026-09-03 on a locally built panel whose build carried no
            // licensing key). The gate is right and stays; this sentence now reports it.
            return ProEntitlement.isActive(context)
                    ? "Muralis Pro is on this Google account."
                    : "Muralis Pro is on this Google account, but this panel could not verify the "
                            + "purchase, so the paid features stay locked.";
        }
        ProductDetails details = productForDisplay;
        if (details != null) {
            ProductDetails.OneTimePurchaseOfferDetails offer = offerOf(details);
            return offer == null
                    ? "Muralis Pro is available."
                    : "Muralis Pro is available: " + offer.getFormattedPrice() + ", one time.";
        }
        if (!productAnswered) {
            // No answer about the product yet, either because nothing is showing a price or because
            // the question is still in flight. Saying "not offered yet" here would be inventing a
            // refusal Play never gave; this is the one thing the panel does know.
            return "Muralis Pro is not on this Google account.";
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
                return "Google Play billing is not available on this device or account: sign in "
                        + "to a Google account, or update Google Play.";
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
