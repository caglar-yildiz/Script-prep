package com.scriptprep.app;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
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
import com.android.billingclient.api.QueryPurchasesParams;

import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Premium subscription through Google Play Billing. The Play Console needs a subscription with
 * the product id {@link #PRODUCT} and two auto-renewing base plans, {@link #MONTHLY} and
 * {@link #YEARLY}; an optional free-trial offer on either plan is picked up automatically.
 * An extra studio voice script is the one-time, consumable product {@link #EXTRA}: it is consumed
 * once the studio Worker has counted it, so it can be bought again.
 */
final class Billing implements PurchasesUpdatedListener {
    static final String PRODUCT = "memo_premium";
    static final String EXTRA = "memo_extra_script";
    private static final String TOKENS = "premium_tokens";

    /** Told of extra scripts paid for and not yet counted by the Worker. */
    interface ExtraListener {
        void onExtraPaid(List<String> purchaseTokens);
    }
    static final String MONTHLY = "monthly";
    static final String YEARLY = "yearly";

    enum Status { CONNECTING, READY, UNAVAILABLE }

    interface Listener {
        void onBillingChanged();
    }

    /** What the store shows for one base plan. */
    static final class Plan {
        final String basePlan;
        final String offerToken;
        final String price;
        final String period;
        /** ISO 8601 length of a free trial, or null when the plan has none. */
        final String trial;

        Plan(String basePlan, String offerToken, String price, String period, String trial) {
            this.basePlan = basePlan;
            this.offerToken = offerToken;
            this.price = price;
            this.period = period;
            this.trial = trial;
        }
    }

    private static final String TAG = "ScriptPrep";
    private static final Pattern DURATION = Pattern.compile("P(\\d+)([DWMY])");

    private final SharedPreferences preferences;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BillingClient client;
    private Listener listener;
    private volatile Status status = Status.CONNECTING;
    private volatile ProductDetails product;
    private volatile ProductDetails extraProduct;
    private ExtraListener extraListener;
    private volatile boolean pending;

    Billing(Context context, SharedPreferences preferences) {
        this.preferences = preferences;
        client = BillingClient.newBuilder(context.getApplicationContext())
            .setListener(this)
            .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
            .enableAutoServiceReconnection()
            .build();
    }

    void setListener(Listener value) {
        listener = value;
    }

    void setExtraListener(ExtraListener value) {
        extraListener = value;
    }

    /** The extra script's price as the store shows it, or null until it is known. */
    String extraPrice() {
        ProductDetails details = extraProduct;
        return details == null || details.getOneTimePurchaseOfferDetails() == null ? null
            : details.getOneTimePurchaseOfferDetails().getFormattedPrice();
    }

    /** Opens Google Play's purchase sheet for an extra script. */
    boolean buyExtra(Activity activity) {
        ProductDetails details = extraProduct;
        if (details == null || !client.isReady()) {
            return false;
        }
        BillingFlowParams.ProductDetailsParams item = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details).build();
        return client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(Collections.singletonList(item)).build()).getResponseCode()
            == BillingClient.BillingResponseCode.OK;
    }

    /** Consumes an extra script the Worker has counted, so another can be bought. */
    void consume(String purchaseToken) {
        client.consumeAsync(com.android.billingclient.api.ConsumeParams.newBuilder().setPurchaseToken(purchaseToken)
            .build(), (result, token) -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Consuming an extra script failed: " + result.getDebugMessage());
                }
            });
    }

    /** The purchase tokens of the active Premium subscription, for the studio Worker to check. */
    List<String> subscriptionTokens() {
        return new java.util.ArrayList<>(preferences.getStringSet(TOKENS, Collections.emptySet()));
    }

    Status status() {
        return status;
    }

    /** A purchase is waiting for payment, for example cash at a store. */
    boolean isPending() {
        return pending;
    }

    /** Connects to Google Play and refreshes the products and the subscription state. */
    void connect() {
        if (client.isReady()) {
            refresh();
            return;
        }
        status = Status.CONNECTING;
        client.startConnection(new BillingClientStateListener() {
            @Override
            public void onBillingSetupFinished(BillingResult result) {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    status = Status.READY;
                    refresh();
                } else {
                    Log.w(TAG, "Billing unavailable: " + result.getDebugMessage());
                    status = Status.UNAVAILABLE;
                    changed();
                }
            }

            @Override
            public void onBillingServiceDisconnected() {
                status = Status.UNAVAILABLE;
                changed();
            }
        });
    }

    void end() {
        listener = null;
        client.endConnection();
    }

    private void refresh() {
        QueryProductDetailsParams.Product query = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(PRODUCT)
            .setProductType(BillingClient.ProductType.SUBS)
            .build();
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(query)).build(), (result, details) -> {
                List<ProductDetails> found = details == null ? null : details.getProductDetailsList();
                product = result.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && found != null && !found.isEmpty() ? found.get(0) : null;
                changed();
            });
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS).build(), (result, purchases) -> {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    apply(purchases, true);
                }
            });
        client.queryProductDetailsAsync(QueryProductDetailsParams.newBuilder()
            .setProductList(Collections.singletonList(QueryProductDetailsParams.Product.newBuilder()
                .setProductId(EXTRA).setProductType(BillingClient.ProductType.INAPP).build())).build(),
            (result, details) -> {
                List<ProductDetails> found = details == null ? null : details.getProductDetailsList();
                extraProduct = result.getResponseCode() == BillingClient.BillingResponseCode.OK
                    && found != null && !found.isEmpty() ? found.get(0) : null;
            });
        // Extra scripts paid for earlier but not yet counted (the app closed, the network failed).
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.INAPP).build(), (result, purchases) -> {
                if (result.getResponseCode() == BillingClient.BillingResponseCode.OK) {
                    paidExtras(purchases);
                }
            });
    }

    private void paidExtras(List<Purchase> purchases) {
        List<String> tokens = new java.util.ArrayList<>();
        for (Purchase purchase : purchases) {
            if (purchase.getProducts().contains(EXTRA)
                && purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                tokens.add(purchase.getPurchaseToken());
            }
        }
        if (!tokens.isEmpty()) {
            main.post(() -> {
                if (extraListener != null) {
                    extraListener.onExtraPaid(tokens);
                }
            });
        }
    }

    /** Looks the subscription up again, for the "Restore purchases" action. */
    void restore() {
        if (client.isReady()) {
            refresh();
        } else {
            connect();
        }
    }

    Plan plan(String basePlan) {
        ProductDetails details = product;
        if (details == null || details.getSubscriptionOfferDetails() == null) {
            return null;
        }
        ProductDetails.SubscriptionOfferDetails base = null;
        ProductDetails.SubscriptionOfferDetails trial = null;
        for (ProductDetails.SubscriptionOfferDetails offer : details.getSubscriptionOfferDetails()) {
            if (!basePlan.equals(offer.getBasePlanId())) {
                continue;
            }
            if (offer.getOfferId() == null) {
                base = offer;
            } else if (trial == null && freeTrial(offer) != null) {
                trial = offer;
            }
        }
        ProductDetails.SubscriptionOfferDetails chosen = trial != null ? trial : base;
        if (chosen == null) {
            return null;
        }
        List<ProductDetails.PricingPhase> phases = chosen.getPricingPhases().getPricingPhaseList();
        if (phases.isEmpty()) {
            return null;
        }
        ProductDetails.PricingPhase recurring = phases.get(phases.size() - 1);
        return new Plan(basePlan, chosen.getOfferToken(), recurring.getFormattedPrice(),
            recurring.getBillingPeriod(), freeTrial(chosen));
    }

    private static String freeTrial(ProductDetails.SubscriptionOfferDetails offer) {
        List<ProductDetails.PricingPhase> phases = offer.getPricingPhases().getPricingPhaseList();
        return !phases.isEmpty() && phases.get(0).getPriceAmountMicros() == 0
            ? phases.get(0).getBillingPeriod() : null;
    }

    /** Splits an ISO 8601 period such as P1M or P7D into its amount and unit letter. */
    static int[] duration(String period) {
        Matcher matcher = period == null ? null : DURATION.matcher(period);
        if (matcher == null || !matcher.matches()) {
            return null;
        }
        return new int[] {Integer.parseInt(matcher.group(1)), matcher.group(2).charAt(0)};
    }

    /** Opens Google Play's purchase sheet; the result arrives through the listener. */
    boolean purchase(Activity activity, Plan plan) {
        ProductDetails details = product;
        if (details == null || !client.isReady()) {
            return false;
        }
        BillingFlowParams.ProductDetailsParams item = BillingFlowParams.ProductDetailsParams.newBuilder()
            .setProductDetails(details)
            .setOfferToken(plan.offerToken)
            .build();
        BillingResult result = client.launchBillingFlow(activity, BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(Collections.singletonList(item)).build());
        return result.getResponseCode() == BillingClient.BillingResponseCode.OK;
    }

    @Override
    public void onPurchasesUpdated(BillingResult result, List<Purchase> purchases) {
        int code = result.getResponseCode();
        if (code == BillingClient.BillingResponseCode.OK && purchases != null) {
            paidExtras(purchases);
            apply(purchases, false);
        } else if (code == BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED) {
            restore();
        } else if (code != BillingClient.BillingResponseCode.USER_CANCELED) {
            Log.w(TAG, "Purchase failed: " + result.getDebugMessage());
        }
    }

    /**
     * Updates the stored entitlement. A full purchase query is authoritative and can also
     * remove Premium; a single purchase update only adds it.
     */
    private void apply(List<Purchase> purchases, boolean complete) {
        boolean owned = false;
        boolean waiting = false;
        java.util.Set<String> tokens = new java.util.HashSet<>();
        for (Purchase purchase : purchases) {
            if (!purchase.getProducts().contains(PRODUCT)) {
                continue;
            }
            if (purchase.getPurchaseState() == Purchase.PurchaseState.PURCHASED) {
                owned = true;
                tokens.add(purchase.getPurchaseToken());
                if (!purchase.isAcknowledged()) {
                    acknowledge(purchase);
                }
            } else if (purchase.getPurchaseState() == Purchase.PurchaseState.PENDING) {
                waiting = true;
            }
        }
        pending = waiting;
        if (owned || complete) {
            Premium.setPurchased(preferences, owned);
            preferences.edit().putStringSet(TOKENS, tokens).apply();
        }
        changed();
    }

    /** Google refunds purchases that are not acknowledged within three days. */
    private void acknowledge(Purchase purchase) {
        client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.getPurchaseToken()).build(), result -> {
                if (result.getResponseCode() != BillingClient.BillingResponseCode.OK) {
                    Log.w(TAG, "Acknowledgement failed: " + result.getDebugMessage());
                }
            });
    }

    private void changed() {
        main.post(() -> {
            if (listener != null) {
                listener.onBillingChanged();
            }
        });
    }
}
