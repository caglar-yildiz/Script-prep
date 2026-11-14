package com.scriptprep.app;

import android.content.Context;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.font.PDFont;
import com.tom_roush.pdfbox.pdmodel.font.PDFontDescriptor;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

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

final class PdfScriptReader extends PDFTextStripper {
    private static final long MAX_BYTES = 25L * 1024 * 1024;
    private static final int MAX_PAGES = 200;
    private static final int MARGIN_ROWS = 2;
    private static final int BOLD = 1;
    private static final int ITALIC = 2;

    private final int characterLimit;
    private final List<List<Row>> pages = new ArrayList<>();
    private final Map<PDFont, Integer> styles = new IdentityHashMap<>();
    private final StringBuilder current = new StringBuilder();
    private final List<Part> parts = new ArrayList<>();
    private List<Row> page;
    private int characters;

    private PdfScriptReader(int characterLimit) throws IOException {
        this.characterLimit = characterLimit;
        setSortByPosition(true);
    }

    static String read(Context context, InputStream stream, int characterLimit) throws IOException {
        File file = File.createTempFile("script-import-", ".pdf", context.getCacheDir());
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                byte[] buffer = new byte[16384];
                long size = 0;
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    size += count;
                    if (size > MAX_BYTES) {
                        throw new ScriptImport.LengthLimitException();
                    }
                    output.write(buffer, 0, count);
                }
            }

            MemoryUsageSetting memory = MemoryUsageSetting.setupMixed(8L * 1024 * 1024)
                .setTempDir(context.getCacheDir());
            try (PDDocument document = PDDocument.load(file, memory)) {
                if (document.getNumberOfPages() > MAX_PAGES) {
                    throw new ScriptImport.LengthLimitException();
                }
                return extract(document, characterLimit);
            }
        } finally {
            file.delete();
        }
    }

    static String extract(PDDocument document, int characterLimit) throws IOException {
        PdfScriptReader reader = new PdfScriptReader(characterLimit);
        reader.writeText(document, new StringWriter());
        return reader.scriptText();
    }

    @Override
    protected void startPage(PDPage source) {
        page = new ArrayList<>();
        pages.add(page);
    }

    @Override
    protected void writeString(String text, List<TextPosition> positions) throws IOException {
        characters += text.length();
        if (characters > characterLimit * 2) {
            throw new ScriptImport.LengthLimitException();
        }
        current.append(text);
        int style = 0;
        if (!positions.isEmpty()) {
            style = BOLD | ITALIC;
            for (TextPosition position : positions) {
                String glyph = position.getUnicode();
                if (glyph != null && !glyph.trim().isEmpty()) {
                    style &= fontStyle(position.getFont());
                }
            }
        }
        parts.add(new Part(text, style));
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
        current.append(' ');
        parts.add(new Part(" ", 0));
    }

    @Override
    protected void writeLineSeparator() {
        flushRow();
    }

    @Override
    protected void writeParagraphStart() {
        flushRow();
        if (!page.isEmpty() && !page.get(page.size() - 1).text.isEmpty()) {
            page.add(new Row("", ""));
        }
    }

    @Override
    protected void endPage(PDPage source) {
        flushRow();
    }

    private void flushRow() {
        String text = current.toString().trim();
        if (!text.isEmpty()) {
            page.add(new Row(text, addStyle(text)));
        }
        current.setLength(0);
        parts.clear();
    }

    private String addStyle(String text) {
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
        for (List<Row> rows : pages) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                Integer count = repeated.get(row.text);
                if (isMargin(i, rows.size()) && (Scene.isPageNumber(row.text)
                    || (count != null && count > 1 && !row.formatted.startsWith("**")
                        && !looksLikeRole(row.text)))) {
                    continue;
                }
                foundText |= !row.text.isEmpty();
                output.append(row.formatted).append('\n');
                if (output.length() > characterLimit) {
                    throw new ScriptImport.LengthLimitException();
                }
            }
            output.append('\n');
        }
        if (!foundText) {
            throw new NoTextException();
        }
        return output.toString().trim();
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

        Part(String text, int style) {
            this.text = text;
            this.style = style;
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
}
