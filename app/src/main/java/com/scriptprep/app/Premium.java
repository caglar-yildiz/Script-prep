package com.scriptprep.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;

/**
 * Premium entitlement. Store billing is not connected yet, so the plan is a local flag
 * that debug builds can switch from the Premium tab to test both plans.
 */
final class Premium {
    private static final String KEY = "premium_active";

    private Premium() {}

    static boolean isActive(SharedPreferences preferences) {
        return preferences.getBoolean(KEY, false);
    }

    static void setActive(SharedPreferences preferences, boolean active) {
        preferences.edit().putBoolean(KEY, active).apply();
    }

    /** Only debuggable builds may switch plans without a purchase. */
    static boolean canSwitchForTesting(Context context) {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }
}
