package com.scriptprep.app;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns text recognised on a scanned page into script rows: reading order across a two-page book
 * spread, no running heads or page numbers, words split by a line-end hyphen joined again and
 * typical recognition slips in role cues, cast lists and scene numbers repaired.
 */
final class OcrText {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    /** Specks read as punctuation in the margin before a line. */
    private static final Pattern LEADING_NOISE = Pattern.compile("^(?:[,.;:'`´’‘©®•·|~^_°*¨]+\\s*)+");
    private static final Pattern LEADING_DASH_NOISE = Pattern.compile("^[-‒–—−]\\s+(?=\\p{Ll})");
    /** "ORGON. — Text", "MADAM PERNELLE, — Text", "ORGON.— Text", "M. LOYAL. —. Text". */
    private static final Pattern DASH_CUE = Pattern.compile(
        "^(\\p{Lu}[\\p{Lu}\\p{M} .'’]{0,38}\\p{Lu})\\s*[.,]?\\s*[-‒–—−]{1,2}\\s*[.,;]?\\s*(.*)$");
    private static final Pattern NAME_LIST = Pattern.compile(
        "^\\p{Lu}[\\p{Lu}\\p{M} .'’-]*(?:\\s*,+\\s*\\p{Lu}[\\p{Lu}\\p{M} .'’-]*)*[,.]*$");
    private static final Pattern NUMBERED_HEADING = Pattern.compile(
        "^(\\p{L}+)\\s+([IVXlH1|!]{1,5})\\.?$");
    /** A speck read as a letter, digit or comma after the end of a sentence: "derecesi ! N". */
    private static final Pattern TRAILING_NOISE = Pattern.compile("(?<=[.!?…»”\"])(?:\\s+[^\\s\\p{L}\\p{N}]+|\\s+[\\p{L}\\p{N}])$");
    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d{1,4}");
    private static final Pattern COLON_CUE = Pattern.compile("^([^:]{2,40}):\\s");

    private OcrText() {}

    /** One line the recogniser found on a page image, in image pixels. */
    static final class Line {
        final String text;
        final int left;
        final int top;
        final int right;
        final int bottom;

        Line(String text, int left, int top, int right, int bottom) {
            this.text = text;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        int height() {
            return Math.max(1, bottom - top);
        }

        int center() {
            return (left + right) / 2;
        }
    }

    /** Script rows for one page image; an empty row marks a paragraph gap. */
    static List<String> pageRows(List<Line> found, int width) {
        List<Line> lines = new ArrayList<>();
        for (Line line : found) {
            String text = line.text == null ? "" : line.text.trim();
            if (text.codePoints().anyMatch(Character::isLetterOrDigit)) {
                lines.add(new Line(text, line.left, line.top, line.right, line.bottom));
            }
        }
        List<String> rows = new ArrayList<>();
        for (List<Line> column : columns(lines, width)) {
            List<String> columnRows = columnRows(column);
            if (!rows.isEmpty() && !columnRows.isEmpty()) {
                rows.add("");
            }
            rows.addAll(columnRows);
        }
        return clean(rows);
    }

    /** Splits a page image into its two halves when it holds a book spread. */
    private static List<List<Line>> columns(List<Line> lines, int width) {
        if (lines.size() < 6 || width <= 0) {
            return Collections.singletonList(lines);
        }
        boolean[] covered = new boolean[width];
        for (Line line : lines) {
            for (int x = Math.max(0, line.left); x < Math.min(width, line.right); x++) {
                covered[x] = true;
            }
        }
        int bestStart = -1;
        int bestLength = 0;
        int from = width * 3 / 10;
        int to = width * 7 / 10;
        for (int x = from; x < to; x++) {
            if (!covered[x]) {
                int start = x;
                while (x < to && !covered[x]) {
                    x++;
                }
                if (x - start > bestLength) {
                    bestStart = start;
                    bestLength = x - start;
                }
            }
        }
        if (bestLength < Math.max(4, width / 100)) {
            return Collections.singletonList(lines);
        }
        int gutter = bestStart + bestLength / 2;
        List<Line> left = new ArrayList<>();
        List<Line> right = new ArrayList<>();
        for (Line line : lines) {
            (line.center() < gutter ? left : right).add(line);
        }
        if (left.size() < 3 || right.size() < 3) {
            return Collections.singletonList(lines);
        }
        List<List<Line>> columns = new ArrayList<>();
        columns.add(left);
        columns.add(right);
        return columns;
    }

    private static List<String> columnRows(List<Line> column) {
        List<Line> sorted = new ArrayList<>(column);
        sorted.sort(Comparator.comparingInt((Line line) -> line.top).thenComparingInt(line -> line.left));

        // Pieces of one printed line can come back as separate lines; put them back together.
        List<List<Line>> visual = new ArrayList<>();
        for (Line line : sorted) {
            List<Line> last = visual.isEmpty() ? null : visual.get(visual.size() - 1);
            if (last != null && overlaps(last, line)) {
                last.add(line);
            } else {
                List<Line> row = new ArrayList<>();
                row.add(line);
                visual.add(row);
            }
        }

        List<Integer> heights = new ArrayList<>();
        for (List<Line> row : visual) {
            heights.add(rowBottom(row) - rowTop(row));
        }
        Collections.sort(heights);
        int typical = heights.isEmpty() ? 1 : Math.max(1, heights.get(heights.size() / 2));

        List<String> rows = new ArrayList<>();
        int previousBottom = Integer.MIN_VALUE;
        for (int i = 0; i < visual.size(); i++) {
            List<Line> row = visual.get(i);
            row.sort(Comparator.comparingInt(line -> line.left));
            StringBuilder text = new StringBuilder();
            for (Line line : row) {
                if (text.length() > 0) {
                    text.append(' ');
                }
                text.append(line.text);
            }
            String value = text.toString();
            if ((i == 0 || i == visual.size() - 1) && isRunningHead(value)) {
                continue;
            }
            if (previousBottom != Integer.MIN_VALUE && rowTop(row) - previousBottom > typical * 6 / 5
                && !rows.isEmpty()) {
                rows.add("");
            }
            rows.add(value);
            previousBottom = rowBottom(row);
        }
        return rows;
    }

    /** A piece beside the row, at the same height, rather than the next line below it. */
    private static boolean overlaps(List<Line> row, Line line) {
        for (Line piece : row) {
            int sharedWidth = Math.min(piece.right, line.right) - Math.max(piece.left, line.left);
            if (sharedWidth > Math.min(piece.right - piece.left, line.right - line.left) / 4) {
                return false;
            }
        }
        int top = rowTop(row);
        int bottom = rowBottom(row);
        int shared = Math.min(bottom, line.bottom) - Math.max(top, line.top);
        return shared * 5 > Math.min(bottom - top, line.height()) * 3;
    }

    private static int rowTop(List<Line> row) {
        int top = Integer.MAX_VALUE;
        for (Line line : row) {
            top = Math.min(top, line.top);
        }
        return top;
    }

    private static int rowBottom(List<Line> row) {
        int bottom = Integer.MIN_VALUE;
        for (Line line : row) {
            bottom = Math.max(bottom, line.bottom);
        }
        return bottom;
    }

    /** "84 TARTUFFE", "Perde V Sahne III 85", "- 12 -": a book's running head or foot. */
    static boolean isRunningHead(String text) {
        String plain = LEADING_NOISE.matcher(text.trim()).replaceFirst("").replaceAll("[-‒–—−]", " ").trim();
        if (Scene.isPageNumber(plain)) {
            return true;
        }
        String[] words = plain.split("\\s+");
        return words.length <= 8 && (NUMBER_TOKEN.matcher(words[0]).matches()
            || NUMBER_TOKEN.matcher(words[words.length - 1]).matches());
    }

    /** Repairs rows read from one page; empty rows are kept as paragraph gaps. */
    static List<String> clean(List<String> rows) {
        List<String> cleaned = new ArrayList<>();
        StringBuilder names = null;
        for (String raw : rows) {
            String row = cleanRow(raw);
            if (row.isEmpty()) {
                names = flushNames(cleaned, names);
                if (!cleaned.isEmpty() && !cleaned.get(cleaned.size() - 1).isEmpty()) {
                    cleaned.add("");
                }
                continue;
            }
            if (isNameList(row)) {
                if (names == null) {
                    names = new StringBuilder(row);
                } else {
                    names.append(' ').append(row);
                }
                continue;
            }
            names = flushNames(cleaned, names);
            int last = cleaned.size() - 1;
            String previous = last < 0 ? "" : cleaned.get(last);
            if (previous.matches(".*\\p{L}[-‐‑¬]$") && Character.isLowerCase(row.codePointAt(0))) {
                // "zor-" + "balıkla iş yürür mü?" was one word split at the line end.
                cleaned.set(last, previous.substring(0, previous.length() - 1) + row);
            } else {
                cleaned.add(row);
            }
        }
        flushNames(cleaned, names);
        while (!cleaned.isEmpty() && cleaned.get(cleaned.size() - 1).isEmpty()) {
            cleaned.remove(cleaned.size() - 1);
        }
        return cleaned;
    }

    private static StringBuilder flushNames(List<String> rows, StringBuilder names) {
        if (names != null) {
            // The characters on stage at the start of a scene: a note, not a role.
            String list = names.toString().replaceAll("\\s*[,;]+(?:\\s*[,;]+)*\\s*", ", ")
                .replaceAll("\\s+", " ").replaceFirst("[,\\s]+$", "");
            rows.add("*" + list + "*");
        }
        return null;
    }

    private static boolean isNameList(String row) {
        return row.matches(".*[,;].*") && NAME_LIST.matcher(row.replace(';', ',').trim()).matches()
            && row.equals(row.toUpperCase(TURKISH)) && !Scene.isHeading(row);
    }

    static String cleanRow(String raw) {
        String row = raw.replace(' ', ' ').replaceAll("\\s+", " ").trim();
        row = LEADING_NOISE.matcher(row).replaceFirst("");
        row = LEADING_DASH_NOISE.matcher(row).replaceFirst("");
        row = TRAILING_NOISE.matcher(row).replaceFirst("");
        if (row.isEmpty()) {
            return row;
        }

        Matcher heading = NUMBERED_HEADING.matcher(row);
        if (heading.matches()) {
            String number = heading.group(2);
            if (number.matches(".*[lH|!].*") || number.matches(".*1.*") && number.matches(".*[IVX].*")) {
                String fixed = heading.group(1) + " " + number.replace("H", "II")
                    .replaceAll("[l1|!]", "I");
                if (Scene.isHeading(fixed)) {
                    return fixed;
                }
            }
            if (Scene.isHeading(row)) {
                return row;
            }
        }

        Matcher cue = DASH_CUE.matcher(row);
        if (cue.matches()) {
            String name = cue.group(1).replaceAll("\\s+", " ").trim();
            if (Scene.isRoleName(name) && name.equals(name.toUpperCase(TURKISH))
                && !Scene.isHeading(name)) {
                return name + ": " + cue.group(2).trim();
            }
        }
        return row;
    }

    /**
     * Joins role names that differ by one misread letter ("CLBANTE" and "CLEANTE") into the
     * spelling read most often. The names in cast lists count as readings too.
     */
    static String unifyRoles(String text) {
        String[] rows = text.split("\n", -1);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String row : rows) {
            Matcher cue = COLON_CUE.matcher(row);
            if (cue.find() && Scene.isRoleName(cue.group(1).trim())) {
                counts.merge(cue.group(1).trim(), 1, Integer::sum);
            } else if (row.length() > 2 && row.startsWith("*") && row.endsWith("*")) {
                String list = row.substring(1, row.length() - 1);
                if (isNameList(list)) {
                    for (String name : list.split(",")) {
                        String trimmed = name.replaceAll("[.]$", "").trim();
                        if (!trimmed.isEmpty()) {
                            counts.merge(trimmed, 1, Integer::sum);
                        }
                    }
                }
            }
        }
        List<String> spellings = new ArrayList<>(counts.keySet());
        Map<String, String> replacements = new HashMap<>();
        for (int i = 0; i < spellings.size(); i++) {
            String rare = spellings.get(i);
            int rareCount = counts.get(rare);
            String best = null;
            int bestCount = 0;
            for (int j = 0; j < spellings.size(); j++) {
                String common = spellings.get(j);
                int commonCount = counts.get(common);
                // A tie only joins spellings that differ in accents ("ELMİRE", "ELMIRE"): the first wins.
                boolean wins = commonCount > rareCount || commonCount == rareCount && j < i
                    && fold(rare).equals(fold(common));
                if (j != i && wins && commonCount > bestCount && rare.length() >= 4
                    && close(fold(rare), fold(common))) {
                    best = common;
                    bestCount = commonCount;
                }
            }
            if (best != null) {
                replacements.put(rare, best);
            }
        }
        for (Map.Entry<String, String> entry : replacements.entrySet()) {
            String target = entry.getValue();
            for (int hops = 0; replacements.containsKey(target) && hops < spellings.size(); hops++) {
                target = replacements.get(target);
            }
            entry.setValue(target);
        }
        if (replacements.isEmpty()) {
            return text;
        }
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i];
            Matcher cue = COLON_CUE.matcher(row);
            if (cue.find()) {
                String replacement = replacements.get(cue.group(1).trim());
                if (replacement != null) {
                    row = replacement + row.substring(cue.group(1).length());
                }
            }
            output.append(row);
            if (i < rows.length - 1) {
                output.append('\n');
            }
        }
        return output.toString();
    }

    private static String fold(String value) {
        return Normalizer.normalize(value.toUpperCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
    }

    /** Equal, or one letter replaced, added or left out. */
    private static boolean close(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        if (Math.abs(a.length() - b.length()) > 1) {
            return false;
        }
        int i = 0;
        while (i < a.length() && i < b.length() && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        int j = 0;
        while (j < a.length() - i && j < b.length() - i
            && a.charAt(a.length() - 1 - j) == b.charAt(b.length() - 1 - j)) {
            j++;
        }
        return a.length() - i - j <= 1 && b.length() - i - j <= 1;
    }
}
