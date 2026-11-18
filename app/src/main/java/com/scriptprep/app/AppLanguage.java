package com.scriptprep.app;

import android.app.Activity;
import android.app.LocaleManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;

import java.util.Locale;

enum AppLanguage {
    SYSTEM("", R.string.language_system),
    TURKISH("tr", R.string.language_turkish),
    ENGLISH("en", R.string.language_english),
    SPANISH("es", R.string.language_spanish),
    GERMAN("de", R.string.language_german),
    FRENCH("fr", R.string.language_french),
    PORTUGUESE("pt", R.string.language_portuguese),
    ITALIAN("it", R.string.language_italian);

    private static final String PREFERENCES = "script_prep";
    private static final String KEY = "app_language";

    final String tag;
    final int label;

    AppLanguage(String tag, int label) {
        this.tag = tag;
        this.label = label;
    }

    static Context wrapLegacyContext(Context base) {
        if (Build.VERSION.SDK_INT >= 33) {
            return base;
        }
        AppLanguage selected = legacyChoice(base);
        if (selected == SYSTEM) {
            return base;
        }
        Configuration override = new Configuration();
        override.setLocale(Locale.forLanguageTag(selected.tag));
        return base.createConfigurationContext(override);
    }

    static AppLanguage current(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = context.getSystemService(LocaleManager.class);
            LocaleList locales = manager.getApplicationLocales();
            return locales.isEmpty() ? SYSTEM : fromTag(locales.get(0).getLanguage());
        }
        return legacyChoice(context);
    }

    void apply(Activity activity) {
        if (this == current(activity)) {
            return;
        }
        if (Build.VERSION.SDK_INT >= 33) {
            LocaleManager manager = activity.getSystemService(LocaleManager.class);
            manager.setApplicationLocales(LocaleList.forLanguageTags(tag));
        } else {
            preferences(activity).edit().putString(KEY, tag).apply();
            activity.recreate();
        }
    }

    static void migrateLegacyChoice(Context context) {
        if (Build.VERSION.SDK_INT < 33 || !preferences(context).contains(KEY)) {
            return;
        }
        AppLanguage legacy = legacyChoice(context);
        preferences(context).edit().remove(KEY).apply();
        LocaleManager manager = context.getSystemService(LocaleManager.class);
        if (legacy != SYSTEM && manager.getApplicationLocales().isEmpty()) {
            manager.setApplicationLocales(LocaleList.forLanguageTags(legacy.tag));
        }
    }

    private static AppLanguage legacyChoice(Context context) {
        return fromTag(preferences(context).getString(KEY, ""));
    }

    private static AppLanguage fromTag(String language) {
        for (AppLanguage option : values()) {
            if (option.tag.equals(language)) {
                return option;
            }
        }
        return SYSTEM;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }
}
