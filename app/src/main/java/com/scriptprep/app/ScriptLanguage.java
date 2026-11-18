package com.scriptprep.app;

import java.util.Locale;

/** The language a text is written in; it drives the voices, the AI voice and speech recognition. */
enum ScriptLanguage {
    TURKISH("tr", "tr-TR", R.string.language_turkish, "Hazırsan sahneyi baştan alalım."),
    ENGLISH("en", "en-US", R.string.language_english, "If you're ready, let's take the scene from the top."),
    SPANISH("es", "es-ES", R.string.language_spanish, "Si estás listo, empecemos la escena desde el principio."),
    GERMAN("de", "de-DE", R.string.language_german, "Wenn du so weit bist, nehmen wir die Szene noch einmal von vorn."),
    FRENCH("fr", "fr-FR", R.string.language_french, "Si tu es prêt, reprenons la scène depuis le début."),
    PORTUGUESE("pt", "pt-BR", R.string.language_portuguese, "Se você estiver pronto, vamos começar a cena do início."),
    ITALIAN("it", "it-IT", R.string.language_italian, "Se sei pronto, riprendiamo la scena dall'inizio.");

    /** ISO 639-1 code, also the language name the AI voice expects. */
    final String code;
    final Locale locale;
    final int label;
    final String sample;

    ScriptLanguage(String code, String tag, int label, String sample) {
        this.code = code;
        this.locale = Locale.forLanguageTag(tag);
        this.label = label;
        this.sample = sample;
    }

    /** Texts saved before languages existed were all Turkish. */
    static ScriptLanguage fromCode(String code) {
        for (ScriptLanguage language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        return TURKISH;
    }

    /** The language matching the app's interface, or English when it has no match. */
    static ScriptLanguage forLocale(Locale locale) {
        String code = locale == null ? "" : locale.getLanguage();
        for (ScriptLanguage language : values()) {
            if (language.code.equals(code)) {
                return language;
            }
        }
        return ENGLISH;
    }
}
