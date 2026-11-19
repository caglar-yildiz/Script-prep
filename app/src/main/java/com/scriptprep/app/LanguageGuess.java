package com.scriptprep.app;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Guesses which supported language a text is written in from its common words and letters. */
final class LanguageGuess {
    private static final Pattern WORD = Pattern.compile("[\\p{L}']+");
    private static final int MINIMUM_WORDS = 12;
    private static final Map<ScriptLanguage, Set<String>> WORDS = new EnumMap<>(ScriptLanguage.class);
    private static final Map<ScriptLanguage, String> LETTERS = new EnumMap<>(ScriptLanguage.class);

    static {
        words(ScriptLanguage.TURKISH, "ve bir bu da de ne ben sen o için ile mi mı mu mü değil var yok gibi çok ama "
            + "şey daha nasıl neden evet hayır benim beni seni sana bana şimdi hiç kadar sonra önce çünkü");
        words(ScriptLanguage.ENGLISH, "the and you to of is it that in what i me my your be have not this are do "
            + "we with for on just no yes was he she they will can don't i'm it's");
        words(ScriptLanguage.SPANISH, "el la que de y en los las un una no es por con para lo se me te mi tu pero "
            + "qué sí está como yo del al muy esto eso porque también");
        words(ScriptLanguage.GERMAN, "der die das und ich du nicht ist ein eine zu mit sie es wir was auf ja nein "
            + "mein dich mir sich den dem aber auch noch hier bin bist");
        words(ScriptLanguage.FRENCH, "le la les et je tu ne pas est un une de des que qui il elle nous vous mais "
            + "oui non mon ton ce c'est dans pour avec suis j'ai qu'il");
        words(ScriptLanguage.PORTUGUESE, "o a os as e que não um uma de do da em eu você se com para por mas sim "
            + "meu isso está ele ela no na tudo também muito");
        words(ScriptLanguage.ITALIAN, "il lo la i gli le e che non un una di da in io tu è sono ma sì mio questo "
            + "per con mi ti cosa perché del della anche come");
        LETTERS.put(ScriptLanguage.TURKISH, "ğşıİ");
        LETTERS.put(ScriptLanguage.SPANISH, "ñ¿¡");
        LETTERS.put(ScriptLanguage.GERMAN, "ß");
        LETTERS.put(ScriptLanguage.FRENCH, "èêëàâîïôûœ");
        LETTERS.put(ScriptLanguage.PORTUGUESE, "ãõ");
        LETTERS.put(ScriptLanguage.ITALIAN, "ìò");
    }

    private LanguageGuess() {}

    private static void words(ScriptLanguage language, String list) {
        WORDS.put(language, new HashSet<>(Arrays.asList(list.split(" "))));
    }

    /** The most likely language, or null when the text is too short or too mixed to tell. */
    static ScriptLanguage guess(String text) {
        Map<ScriptLanguage, Double> scores = new EnumMap<>(ScriptLanguage.class);
        for (ScriptLanguage language : ScriptLanguage.values()) {
            scores.put(language, 0.0);
        }
        int total = 0;
        Matcher matcher = WORD.matcher(text);
        while (matcher.find()) {
            String word = matcher.group().toLowerCase(Locale.ROOT).replace('’', '\'');
            total++;
            for (Map.Entry<ScriptLanguage, Set<String>> entry : WORDS.entrySet()) {
                if (entry.getValue().contains(word)) {
                    scores.put(entry.getKey(), scores.get(entry.getKey()) + 1);
                }
            }
        }
        if (total < MINIMUM_WORDS) {
            return null;
        }
        // Letters only one language uses are strong hints; each counts like a few common words.
        for (int i = 0; i < text.length(); i++) {
            char letter = text.charAt(i);
            for (Map.Entry<ScriptLanguage, String> entry : LETTERS.entrySet()) {
                if (entry.getValue().indexOf(letter) >= 0 || entry.getValue().indexOf(Character.toLowerCase(letter)) >= 0) {
                    scores.put(entry.getKey(), scores.get(entry.getKey()) + 0.5);
                }
            }
        }
        ScriptLanguage best = null;
        double first = 0;
        double second = 0;
        for (Map.Entry<ScriptLanguage, Double> entry : scores.entrySet()) {
            double score = entry.getValue();
            if (score > first) {
                second = first;
                first = score;
                best = entry.getKey();
            } else if (score > second) {
                second = score;
            }
        }
        boolean clear = first >= 3 && first >= total * 0.08 && first >= second * 1.3;
        return clear ? best : null;
    }
}
