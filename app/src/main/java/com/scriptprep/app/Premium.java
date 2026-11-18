package com.scriptprep.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

/**
 * Premium entitlement: an active Google Play subscription, or the test switch that only
 * debuggable builds show.
 */
final class Premium {
    private static final String PURCHASED = "premium_purchased";
    private static final String TESTING = "premium_active";

    private Premium() {}

    static boolean isActive(SharedPreferences preferences) {
        return isPurchased(preferences) || isTesting(preferences);
    }

    static boolean isPurchased(SharedPreferences preferences) {
        return preferences.getBoolean(PURCHASED, false);
    }

    /** Stored from Google Play's answer so Premium also works offline. */
    static void setPurchased(SharedPreferences preferences, boolean purchased) {
        preferences.edit().putBoolean(PURCHASED, purchased).apply();
    }

    static boolean isTesting(SharedPreferences preferences) {
        return preferences.getBoolean(TESTING, false);
    }

    static void setTesting(SharedPreferences preferences, boolean active) {
        preferences.edit().putBoolean(TESTING, active).apply();
    }

    /** Only debuggable builds may switch plans without a purchase. */
    static boolean canSwitchForTesting(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
