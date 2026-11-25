package com.scriptprep.app;

import android.content.Context;
import android.util.Log;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

final class PdfScriptReader extends PDFTextStripper {
    private static final long MAX_BYTES = 100L * 1024 * 1024;
    private static final int MAX_PAGES = 1000;
    /** Most pages read in one import; a longer play is added in parts. */
    static final int MAX_RANGE = 200;
    private static final String TEMP_PREFIX = "script-import-";
    private static final int MARGIN_ROWS = 2;
    private static final int BOLD = 1;
    private static final int ITALIC = 2;
    /** A page with fewer letters than this in its text layer is treated as a scan. */
    private static final int SCANNED_PAGE_LETTERS = 20;

    private final int characterLimit;
    private final List<List<Row>> pages = new ArrayList<>();
    private final Map<PDFont, Integer> styles = new IdentityHashMap<>();
    /** Words of the line being read. */
    private final List<Part> parts = new ArrayList<>();
    /** Lines of the page being read, as words with their position; an empty line is a paragraph gap. */
    private final List<List<Part>> pageLines = new ArrayList<>();
    private List<Row> page;
    private int characters;
    private int scannedPages;
    private int includedPages;
    private BooleanSupplier cancelled;

    private PdfScriptReader(int characterLimit) throws IOException {
        this.characterLimit = characterLimit;
        setSortByPosition(true);
    }

    /** A PDF copied to the cache, so its pages can be counted before choosing which to read. */
    static final class Source implements Closeable {
        final File file;
        final int pageCount;

        Source(File file, int pageCount) {
            this.file = file;
            this.pageCount = pageCount;
        }

        @Override
        public void close() {
            file.delete();
        }
    }

    /** The script text of the pages read and how many of them were read from the scanned image. */
    static final class Result {
        final String text;
        final int scannedPages;
        final int firstPage;
        final int lastPage;
        /** The last page asked for; later than {@link #lastPage} when the text limit was reached. */
        final int requestedLastPage;

        Result(String text, int scannedPages, int firstPage, int lastPage, int requestedLastPage) {
            this.text = text;
            this.scannedPages = scannedPages;
            this.firstPage = firstPage;
            this.lastPage = lastPage;
            this.requestedLastPage = requestedLastPage;
        }
    }

    static Source open(Context context, InputStream stream, BooleanSupplier cancelled) throws IOException {
        // An import left by a closed app or a cancelled page choice is no longer needed.
        File[] leftovers = context.getCacheDir().listFiles((dir, name) -> name.startsWith(TEMP_PREFIX));
        if (leftovers != null) {
            for (File leftover : leftovers) {
                leftover.delete();
            }
        }
        File file = File.createTempFile(TEMP_PREFIX, ".pdf", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384];
                long size = 0;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    ScriptImport.check(cancelled);
                    size += count;
                    if (size > MAX_BYTES) {
                        throw new ScriptImport.LengthLimitException();
                    }
                    output.write(buffer, 0, count);
                }
            }
            int pageCount;
            try (PDDocument document = load(context, file)) {
                pageCount = document.getNumberOfPages();
            }
            if (pageCount > MAX_PAGES) {
                throw new ScriptImport.LengthLimitException();
            }
            return new Source(file, pageCount);
        } catch (IOException | RuntimeException error) {
            file.delete();
            throw error;
        }
    }

    private static PDDocument load(Context context, File file) throws IOException {
        MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
            .setTempDir(context.getCacheDir());
        return PDDocument.load(file, memory);
    }

    /**
     * Reads pages {@code firstPage} to {@code lastPage} (counted from 1). When the text grows past
     * the limit, reading stops at the end of the last whole page that fits.
     */
    static Result read(Context context, Source source, int firstPage, int lastPage,
        int characterLimit, PdfOcr.Progress progress, BooleanSupplier cancelled, OcrReview review)
        throws IOException {
        PdfScriptReader reader = new PdfScriptReader(characterLimit);
        reader.cancelled = cancelled;
        reader.setStartPage(firstPage);
        reader.setEndPage(lastPage);
        try (PDDocument document = load(context, source.file)) {
            try {
                reader.writeText(document, new StringWriter());
            } catch (Full full) {
                // The page that was being read is incomplete; the next import starts with it.
                if (reader.pages.size() > 1) {
                    reader.pages.remove(reader.pages.size() - 1);
                }
            }
        }
        List<Integer> scanned = reader.pagesWithoutText();
        if (!scanned.isEmpty()) {
            List<Integer> pageNumbers = new ArrayList<>();
            for (int index : scanned) {
                pageNumbers.add(firstPage - 1 + index);
            }
            try {
                PdfOcr.read(source.file, pageNumbers, progress, cancelled, review,
                    (pageIndex, rows) -> reader.useRecognized(pageIndex - firstPage + 1, rows));
            } catch (ScriptImport.CancelledException stopped) {
                throw stopped;
            } catch (IOException | RuntimeException | LinkageError | OutOfMemoryError error) {
                // Keep whatever the text layer had; with nothing at all this ends in NoTextException.
                Log.w("ScriptPrep", "Could not read scanned pages", error);
            }
        }
        String text = reader.scriptText();
        return new Result(text, reader.scannedPages, firstPage,
            firstPage + Math.max(1, reader.includedPages) - 1, lastPage);
    }

    /** Pages whose text layer is missing or nearly empty, so their text is only in the image. */
    private List<Integer> pagesWithoutText() {
        List<Integer> scanned = new ArrayList<>();
        for (int i = 0; i < pages.size(); i++) {
            int letters = 0;
            for (Row row : pages.get(i)) {
                letters += row.text.codePoints().filter(Character::isLetter).count();
            }
            if (letters < SCANNED_PAGE_LETTERS) {
                scanned.add(i);
            }
        }
        return scanned;
    }

    /**
     * Puts recognised rows in place of the page at {@code index}. Returns false, and drops the
     * pages after it, once the text so far reaches the limit, so no more pages are recognised.
     */
    private boolean useRecognized(int index, List<String> recognized) {
        if (index < 0 || index >= pages.size()) {
            return true;
        }
        List<Row> rows = new ArrayList<>();
        int letters = 0;
        for (String text : recognized) {
            boolean note = text.length() > 2 && text.startsWith("*") && text.endsWith("*");
            rows.add(new Row(note ? text.substring(1, text.length() - 1) : text, text));
            letters += text.codePoints().filter(Character::isLetter).count();
        }
        if (letters > 0) {
            pages.set(index, rows);
            scannedPages++;
        }
        long length = 0;
        for (int i = 0; i <= index; i++) {
            for (Row row : pages.get(i)) {
                length += row.formatted.length() + 1;
            }
        }
        if (length > characterLimit && index > 0) {
            pages.subList(index + 1, pages.size()).clear();
            return false;
        }
        return true;
    }

    @Override
    protected void startPage(PDPage source) throws IOException {
        ScriptImport.check(cancelled);
        page = new ArrayList<>();
        pages.add(page);
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        characters += text.length();
        if (characters > characterLimit * 2) {
            throw new Full();
        }
        int style = 0;
        float left = Float.NaN;
        float right = Float.NaN;
        if (!positions.isEmpty()) {
            style = BOLD | ITALIC;
            for (TextPosition position : positions) {
                String glyph = position.getUnicode();
                if (glyph != null && !glyph.trim().isEmpty()) {
                    style &= fontStyle(position.getFont());
                }
                float x = position.getXDirAdj();
                left = Float.isNaN(left) ? x : Math.min(left, x);
                right = Float.isNaN(right) ? x + position.getWidthDirAdj()
                    : Math.max(right, x + position.getWidthDirAdj());
            }
        }
        parts.add(new Part(text, style, left, right));
    }

    private int fontStyle(PDFont font) {
        if (font == null) {
            return 0;
        }
        Integer known = styles.get(font);
        if (known != null) {
            return known;
        }
        String name = font.getName();
        String folded = name == null ? "" : name.toLowerCase(Locale.ROOT);
        PDFontDescriptor descriptor = font.getFontDescriptor();
        boolean bold = folded.matches(".*(?:bold|demi|black|heavy).*")
            || (descriptor != null && (descriptor.isForceBold() || descriptor.getFontWeight() >= 600));
        boolean italic = folded.matches(".*(?:italic|oblique).*")
            || (descriptor != null && (descriptor.isItalic() || descriptor.getItalicAngle() != 0));
        int style = (bold ? BOLD : 0) | (italic ? ITALIC : 0);
        styles.put(font, style);
        return style;
    }

    @Override
    protected void writeWordSeparator() {
        parts.add(new Part(" ", 0, Float.NaN, Float.NaN));
    }

    @Override
    protected void writeLineSeparator() {
        flushRow();
    }

    @Override
    protected void writeParagraphStart() {
        flushRow();
        if (!pageLines.isEmpty() && !pageLines.get(pageLines.size() - 1).isEmpty()) {
            pageLines.add(new ArrayList<>());
        }
    }

    @Override
    protected void endPage(PDPage source) {
        flushRow();
        float width = source.getRotation() % 180 == 0 ? source.getCropBox().getWidth()
            : source.getCropBox().getHeight();
        List<List<float[]>> spans = new ArrayList<>();
        for (List<Part> line : pageLines) {
            List<float[]> words = new ArrayList<>();
            for (Part part : line) {
                if (!Float.isNaN(part.left)) {
                    words.add(new float[] {part.left, part.right});
                }
            }
            if (!words.isEmpty()) {
                spans.add(words);
            }
        }
        float gutter = TextColumns.gutter(spans, width);
        if (gutter < 0) {
            for (List<Part> line : pageLines) {
                addRow(line);
            }
        } else {
            // Two pages side by side: the left column first, then the right one.
            for (List<Part> line : pageLines) {
                addRow(side(line, gutter, true));
            }
            addRow(new ArrayList<>());
            for (List<Part> line : pageLines) {
                addRow(side(line, gutter, false));
            }
        }
        pageLines.clear();
    }

    private void flushRow() {
        boolean words = false;
        for (Part part : parts) {
            words |= !part.text.trim().isEmpty();
        }
        if (words) {
            pageLines.add(new ArrayList<>(parts));
        }
        parts.clear();
    }

    /** The words of a line on one side of the gutter; spaces go with the word before them. */
    private static List<Part> side(List<Part> line, float gutter, boolean left) {
        if (line.isEmpty()) {
            return line;
        }
        List<Part> kept = new ArrayList<>();
        boolean onLeft = true;
        boolean seenWord = false;
        for (Part part : line) {
            if (!Float.isNaN(part.left)) {
                onLeft = (part.left + part.right) / 2 < gutter;
                seenWord = true;
            }
            if (onLeft == left && (seenWord || !Float.isNaN(part.left))) {
                kept.add(part);
            }
        }
        boolean words = false;
        for (Part part : kept) {
            words |= !part.text.trim().isEmpty();
        }
        return words ? kept : null;
    }

    /** Adds a line to the page; an empty line marks a paragraph gap, null adds nothing. */
    private void addRow(List<Part> line) {
        if (line == null) {
            return;
        }
        StringBuilder joined = new StringBuilder();
        for (Part part : line) {
            joined.append(part.text);
        }
        String text = joined.toString().trim();
        if (text.isEmpty()) {
            if (!page.isEmpty() && !page.get(page.size() - 1).text.isEmpty()) {
                page.add(new Row("", ""));
            }
            return;
        }
        page.add(new Row(text, addStyle(line, joined.toString(), text)));
    }

    private String addStyle(List<Part> parts, String current, String text) {
        if (Scene.isHeading(text)) {
            return text;
        }
        int offset = 0;
        int boldEnd = 0;
        boolean leadingBold = true;
        boolean allItalic = true;
        for (Part part : parts) {
            offset += part.text.length();
            if (part.text.trim().isEmpty()) {
                continue;
            }
            allItalic &= (part.style & ITALIC) != 0;
            leadingBold &= (part.style & BOLD) != 0;
            if (leadingBold) {
                boldEnd = offset;
            }
        }
        if (boldEnd > 0) {
            String prefix = current.substring(0, boldEnd).trim();
            String name = prefix.replaceFirst("\\s*[:‒–—−-]$", "").replaceFirst("\\s*\\([^()]*\\)$", "");
            if (Scene.isRoleName(name.trim())) {
                String rest = current.substring(boldEnd).trim();
                return "**" + prefix + "**" + (rest.isEmpty() ? "" : " " + rest);
            }
        }
        return allItalic ? "*" + text + "*" : text;
    }

    private String scriptText() throws IOException {
        Map<String, Integer> repeated = new HashMap<>();
        for (List<Row> rows : pages) {
            Set<String> seen = new HashSet<>();
            for (int i = 0; i < rows.size(); i++) {
                String plain = rows.get(i).text;
                if (isMargin(i, rows.size()) && !plain.isEmpty() && seen.add(plain)
                    && !Scene.isHeading(plain)) {
                    Integer count = repeated.get(plain);
                    repeated.put(plain, count == null ? 1 : count + 1);
                }
            }
        }

        StringBuilder output = new StringBuilder();
        boolean foundText = false;
        for (int p = 0; p < pages.size(); p++) {
            List<Row> rows = pages.get(p);
            int pageStart = output.length();
            boolean pageText = false;
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                Integer count = repeated.get(row.text);
                if (isMargin(i, rows.size()) && (Scene.isPageNumber(row.text)
                    || (count != null && count > 1 && !row.formatted.startsWith("**")
                        && !looksLikeRole(row.text)))) {
                    continue;
                }
                pageText |= !row.text.isEmpty();
                output.append(row.formatted).append('\n');
            }
            output.append('\n');
            if (output.length() > characterLimit) {
                if (p == 0) {
                    throw new ScriptImport.LengthLimitException();
                }
                // Stop after the last whole page that fits; the rest is imported separately.
                output.setLength(pageStart);
                break;
            }
            foundText |= pageText;
            includedPages = p + 1;
        }
        if (!foundText) {
            throw new NoTextException();
        }
        String text = output.toString().trim();
        return scannedPages > 0 ? OcrText.unifyRoles(text) : text;
    }

    private static boolean isMargin(int index, int size) {
        return index < MARGIN_ROWS || size - index <= MARGIN_ROWS;
    }

    private static boolean looksLikeRole(String text) {
        return Scene.isRoleName(text) && text.equals(text.toUpperCase(Locale.ROOT));
    }

    private static final class Part {
        final String text;
        final int style;

        /** Left and right edge on the page; not a number for the space between words. */
        final float left;
        final float right;

        Part(String text, int style, float left, float right) {
            this.text = text;
            this.style = style;
            this.left = left;
            this.right = right;
        }
    }

    private static final class Row {
        final String text;
        final String formatted;

        Row(String text, String formatted) {
            this.text = text;
            this.formatted = formatted;
        }
    }

    static final class NoTextException extends IOException {}

    /** Stops reading the text layer once far more text than fits has been read. */
    private static final class Full extends IOException {}
}
