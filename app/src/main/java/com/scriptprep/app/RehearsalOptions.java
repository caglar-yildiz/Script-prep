package com.scriptprep.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class RehearsalOptions {
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*");

    enum Mode { LISTEN, PRACTICE }

    enum Visibility {
        FULL, HINT, INITIALS, HIDDEN;

        String visibleText(String line) {
            return switch (this) {
                case FULL -> line;
                case HINT -> hint(line);
                case INITIALS -> initials(line);
                case HIDDEN -> "";
            };
        }
    }

    private RehearsalOptions() {}

    static boolean shouldSpeak(Mode mode, String selectedRole, String lineRole) {
        return mode == Mode.LISTEN || !lineRole.equals(selectedRole);
    }

    private static String hint(String line) {
        Matcher words = WORD.matcher(line);
        if (!words.find()) {
            return "…";
        }
        int start = words.start();
        int end = words.end();
        if (!words.find()) {
            return line.substring(0, line.offsetByCodePoints(start, 1)) + "…";
        }
        return line.substring(0, end) + " …";
    }

    private static String initials(String line) {
        Matcher words = WORD.matcher(line);
        StringBuilder masked = new StringBuilder();
        int position = 0;
        while (words.find()) {
            int initialEnd = line.offsetByCodePoints(words.start(), 1);
            masked.append(line, position, initialEnd);
            if (initialEnd < words.end()) {
                masked.append('·');
            }
            position = words.end();
        }
        return masked.append(line, position, line.length()).toString();
    }
}
