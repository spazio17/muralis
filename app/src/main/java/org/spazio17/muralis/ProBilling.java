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
 * The Play Billing spike: proves that this panel can see the Pro product, complete a purchase,
 * and read it back. Deliberately gates nothing yet: MQTT and the web admin stay open regardless
 * of what this reports, because the entitlement layer (signature verification, the SecretStore
 * cache, the development bypass) is a separate step that only makes sense once this one is proven
 * on hardware. See "Prove the QR plus Billing path" in TODO-muralis-release.md.
 *
 * <p>What "proven" means, and why it needs real hardware: Play Billing validates the installed
 * app's signature against the published listing, so only a Play-signed install (from Play itself,
 * or QR-provisioned from the Play-signed universal APK) can ever get an answer other than
 * "unavailable" out of this class. A debug build shows the card and reports why it cannot ask,
 * which is itself useful: it is the same thing a user on a de-Googled device would see.
 *
 * <p>Results are surfaced on the configuration screen, never only in logcat: logd prunes this
 * app's lines silently under load, so a log line that matters is a log line that may not exist.
 *
 * <p>Billing Library 8, not 7: Play requires 8+ for every new app and update from 2026-08-31.
 *
 * <p><b>Asked at startup, as Google's guide recommends</b>, from
 * {@code KioskActivity.initializeUserInterface}, and again whenever the configuration screen is
 * built. On this app "startup" is not a rare event: the nightly pass exits the process and an alarm
 * relaunches the activity, so the query runs at least daily and a connection opened at startup
 * lives a day at most. An earlier version of this class deferred the whole thing to the
 * configuration screen, on the reasoning that the panel's foreground lasts months and a Play
 * binding should not; that reasoning was wrong, because the process does not last months. Juri
 * caught it 2026-08-27.
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
         * @param owned  the signed-in account holds an acknowledged (or pending-free) purchase
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
    /** Play's own reason for an empty product list, when it gave one. See {@link #unfetchedStatus}. */
    private String unfetchedDetail;
    private boolean owned;
    private boolean buyable;
    private String detail = "Checking Google Play…";
    private StatusListener listener;
    private boolean connecting;

    ProBilling(Context context) {
        this.context = context.getApplicationContext();
        // enableOneTimeProducts is billing-8 for "this app handles pending purchases", which a
        // physical-store code purchase can produce; without declaring it the client refuses to
        // build. Auto-reconnection because the Play service connection is routinely dropped on an
        // idle panel, and this class would otherwise have to rebuild it by hand on every query.
        client = BillingClient.newBuilder(this.context)
                .setListener(this)
                .enablePendingPurchases(PendingPurchasesParams.newBuilder()
                        .enableOneTimeProducts()
                        .build())
                .enableAutoServiceReconnection()
                .build();
    }

    /** The configuration screen registers itself here each time it is (re)built. */
    void setListener(StatusListener statusListener) {
        listener = statusListener;
        publish();
        refresh();
    }

    /** Re-asks Play for the product and the account's purchases. Safe to call repeatedly. */
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
                connecting = false;
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    query();
                } else {
                    report(unavailableSentence(result), false, false);
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                // Auto-reconnection is enabled on the client; nothing to rebuild here. The next
                // refresh() finds isReady() false and connects again if the automatic path lost.
                connecting = false;
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
        client.queryProductDetailsAsync(productQuery(), (result, detailsResult) -> {
            if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                report(unavailableSentence(result), owned, buyable);
                return;
            }
            List<ProductDetails> found = detailsResult.getProductDetailsList();
            if (found.isEmpty()) {
                productForDisplay = null;
                report(unfetchedSentence(detailsResult), owned, false);
                return;
            }
            ProductDetails fresh = found.get(0);
            productForDisplay = fresh;
            BillingFlowParams params = BillingFlowParams.newBuilder()
                    .setProductDetailsParamsList(Collections.singletonList(
                            BillingFlowParams.ProductDetailsParams.newBuilder()
                                    .setProductDetails(fresh)
                                    .build()))
                    .build();
            // launchBillingFlow must run on the main thread; the billing callback does not
            // promise one.
            mainHandler.post(() -> {
                BillingResult launch = client.launchBillingFlow(activity, params);
                if (launch.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    report(unavailableSentence(launch), owned, buyable);
                }
            });
        });
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
        client.queryProductDetailsAsync(productQuery(), (result, detailsResult) -> {
            List<ProductDetails> found = detailsResult.getProductDetailsList();
            if (result.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && !found.isEmpty()) {
                productForDisplay = found.get(0);
                unfetchedDetail = null;
            } else {
                productForDisplay = null;
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    unfetchedDetail = null;
                    Log.w(TAG, "Product details refused: " + result.getResponseCode()
                            + " " + result.getDebugMessage());
                } else {
                    // The v8 API that says WHY a product came back empty, rather than leaving
                    // "not offered yet" as the only thing anyone can report. This is the
                    // difference between "the Play Console product does not exist" and "this
                    // install cannot be matched to the listing", which look identical without it.
                    unfetchedDetail = unfetchedStatus(detailsResult);
                    if (unfetchedDetail != null) {
                        Log.w(TAG, "Product " + PRODUCT_ID + " unfetched: " + unfetchedDetail);
                    }
                }
            }
            queryPurchases();
        });
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

    private void queryPurchases() {
        client.queryPurchasesAsync(
                QueryPurchasesParams.newBuilder()
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build(),
                (result, purchases) -> {
                    if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                        report(unavailableSentence(result), false, productForDisplay != null);
                        return;
                    }
                    handlePurchases(purchases);
                });
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            handlePurchases(purchases);
        } else if (code == BillingClient.BillingResponseCode.USER_CANCELED) {
            report(statusSentence(), owned, buyable);
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            // Play's cache and ours disagree; the query is the tiebreaker.
            queryPurchases();
        } else {
            report(unavailableSentence(result), owned, buyable);
        }
    }

    private void handlePurchases(List<Purchase> purchases) {
        boolean nowOwned = false;
        boolean pending = false;
        for (Purchase purchase : purchases) {
            if (!purchase.getProducts().contains(PRODUCT_ID)) {
                continue;
            }
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                nowOwned = true;
                // Unacknowledged purchases are refunded by Play after three days, so a panel
                // that bought Pro and was then left alone would silently lose it. Acknowledged
                // here rather than after the entitlement work lands for exactly that reason.
                if (!purchase.isAcknowledged()) {
                    client.acknowledgePurchase(
                            AcknowledgePurchaseParams.newBuilder()
                                    .setPurchaseToken(purchase.getPurchaseToken())
                                    .build(),
                            ackResult -> {
                                if (ackResult.getResponseCode()
                                        != BillingClient.BillingResponseCode.OK) {
                                    Log.w(TAG, "Purchase acknowledgement refused: "
                                            + ackResult.getResponseCode()
                                            + " " + ackResult.getDebugMessage());
                                }
                            });
                }
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                pending = true;
            }
        }
        owned = nowOwned;
        buyable = productForDisplay != null && !nowOwned;
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
            ProductDetails.OneTimePurchaseOfferDetails offer =
                    details.getOneTimePurchaseOfferDetails();
            return offer == null
                    ? "Muralis Pro is available."
                    : "Muralis Pro is available: " + offer.getFormattedPrice() + ", one time.";
        }
        return unfetchedDetail == null
                ? "Google Play does not offer Muralis Pro to this device yet."
                : "Google Play did not return the product (" + unfetchedDetail + ").";
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

    private void report(String sentence, boolean nowOwned, boolean nowBuyable) {
        owned = nowOwned;
        buyable = nowBuyable;
        detail = sentence;
        publish();
    }

    private void publish() {
        mainHandler.post(() -> {
            StatusListener current = listener;
            if (current != null) {
                current.onProStatus(detail, owned, buyable);
            }
        });
    }
}
