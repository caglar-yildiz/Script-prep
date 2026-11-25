package com.scriptprep.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;

final class ScriptImport {
    private ScriptImport() {}

    /** An imported script; for a PDF also which pages it holds and how many were scanned. */
    static final class Result {
        final ScriptLibrary.Script script;
        final int scannedPages;
        final int firstPage;
        final int lastPage;
        final int requestedLastPage;
        /** Read from photos rather than a file; {@link #scannedPages} counts the photos. */
        final boolean photos;
        /** Converted from Word, Fountain or Final Draft, so worth a look at the detected roles. */
        final boolean converted;

        Result(ScriptLibrary.Script script, int scannedPages, int firstPage, int lastPage,
            int requestedLastPage) {
            this(script, scannedPages, firstPage, lastPage, requestedLastPage, false, false);
        }

        Result(ScriptLibrary.Script script, int scannedPages, int firstPage, int lastPage,
            int requestedLastPage, boolean photos) {
            this(script, scannedPages, firstPage, lastPage, requestedLastPage, photos, false);
        }

        Result(ScriptLibrary.Script script, int scannedPages, int firstPage, int lastPage,
            int requestedLastPage, boolean photos, boolean converted) {
            this.photos = photos;
            this.converted = converted;
            this.script = script;
            this.scannedPages = scannedPages;
            this.firstPage = firstPage;
            this.lastPage = lastPage;
            this.requestedLastPage = requestedLastPage;
        }

        /** The text limit was reached before the last page asked for. */
        boolean cutShort() {
            return lastPage < requestedLastPage;
        }
    }

    /** Reads script pages from photos; {@code title} names the text. */
    static Result readPhotos(Context context, List<Uri> photos, String title, int lengthLimit,
        PdfOcr.Progress progress, BooleanSupplier cancelled, OcrReview review) throws IOException {
        PhotoOcr.Result read = PhotoOcr.read(context, photos, lengthLimit, progress, cancelled, review);
        return new Result(new ScriptLibrary.Script(null, title, read.text), read.pages, 0, 0, 0, true);
    }

    /** A picked PDF, copied and counted, waiting for the pages to read. */
    static final class PdfFile {
        final PdfScriptReader.Source source;
        final String title;

        PdfFile(PdfScriptReader.Source source, String title) {
            this.source = source;
            this.title = title;
        }
    }

    /** Reads a TXT, Word (DOCX), Fountain or Final Draft (FDX) file. */
    static Result readText(Context context, Uri uri, int lengthLimit) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String name = displayName(resolver, uri);
        ScriptFormats.Kind kind = ScriptFormats.kindOf(name);
        String text;
        try (InputStream opened = resolver.openInputStream(uri)) {
            if (opened == null) {
                throw new IOException("Missing stream");
            }
            BufferedInputStream stream = new BufferedInputStream(opened);
            if (kind == ScriptFormats.Kind.TEXT) {
                kind = sniff(stream);
            }
            text = switch (kind) {
                case DOCX -> ScriptFormats.docx(stream);
                case FDX -> ScriptFormats.fdx(stream);
                case FOUNTAIN -> ScriptFormats.fountain(readText(stream, lengthLimit * 2));
                case TEXT -> readText(stream, lengthLimit);
            };
        }
        if (text.length() > lengthLimit) {
            throw new LengthLimitException();
        }
        if (kind != ScriptFormats.Kind.TEXT && text.trim().isEmpty()) {
            throw new IOException("No script text");
        }
        String title = name.replaceFirst("(?i)\\.(?:txt|text|docx|fountain|spmd|fdx)$", "");
        return new Result(new ScriptLibrary.Script(null, title, text), 0, 0, 0, 0, false,
            kind != ScriptFormats.Kind.TEXT);
    }

    /** Word and Final Draft files picked without a telling name are known by how they start. */
    private static ScriptFormats.Kind sniff(BufferedInputStream stream) throws IOException {
        byte[] head = new byte[512];
        stream.mark(head.length);
        int count = 0;
        int read;
        while (count < head.length && (read = stream.read(head, count, head.length - count)) > 0) {
            count += read;
        }
        stream.reset();
        if (count >= 4 && head[0] == 'P' && head[1] == 'K' && head[2] == 3 && head[3] == 4) {
            return ScriptFormats.Kind.DOCX;
        }
        String start = new String(head, 0, count, StandardCharsets.UTF_8);
        return start.contains("<FinalDraft") ? ScriptFormats.Kind.FDX : ScriptFormats.Kind.TEXT;
    }

    static PdfFile openPdf(Context context, Uri uri, BooleanSupplier cancelled) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        // The text strip reader initializes PDFBox font resources in its superclass.
        PDFBoxResourceLoader.init(context);
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Missing stream");
            }
            return new PdfFile(PdfScriptReader.open(context, stream, cancelled), readTitle(resolver, uri));
        }
    }

    /** Reads pages {@code firstPage} to {@code lastPage} of the PDF and removes its copy. */
    static Result readPdf(Context context, PdfFile pdf, int firstPage, int lastPage, int lengthLimit,
        PdfOcr.Progress progress, BooleanSupplier cancelled, OcrReview review) throws IOException {
        try {
            PdfScriptReader.Result text = PdfScriptReader.read(context, pdf.source, firstPage, lastPage,
                lengthLimit, progress, cancelled, review);
            String title = pdf.title;
            if (firstPage > 1 || lastPage < pdf.source.pageCount) {
                title = (title.isEmpty() ? "" : title + " ") + "(" + text.firstPage + "–" + text.lastPage + ")";
            }
            return new Result(new ScriptLibrary.Script(null, title, text.text), text.scannedPages,
                text.firstPage, text.lastPage, text.requestedLastPage);
        } finally {
            pdf.source.close();
        }
    }

    private static String readText(InputStream stream, int lengthLimit) throws IOException {
        StringBuilder text = new StringBuilder();
        Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8.newDecoder());
        char[] buffer = new char[1024];
        int count;
        while ((count = reader.read(buffer)) != -1) {
            text.append(buffer, 0, count);
            if (text.length() > lengthLimit + 1) {
                throw new LengthLimitException();
            }
        }
        if (text.length() > 0 && text.charAt(0) == '\uFEFF') {
            text.deleteCharAt(0);
        }
        if (text.length() > lengthLimit) {
            throw new LengthLimitException();
        }
        return text.toString();
    }

    private static String readTitle(ContentResolver resolver, Uri uri) {
        return displayName(resolver, uri).replaceFirst("(?i)\\.(?:txt|pdf)$", "");
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
            null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getColumnCount() > 0) {
                String name = cursor.getString(0);
                if (name != null) {
                    return name;
                }
            }
        } catch (RuntimeException ignored) {
            // A provider can expose the contents without exposing a display name.
        }
        return "";
    }

    static final class LengthLimitException extends IOException {}

    /** The user stopped the import. */
    static final class CancelledException extends IOException {}

    /** Throws {@link CancelledException} once the user has stopped the import. */
    static void check(BooleanSupplier cancelled) throws CancelledException {
        if (cancelled != null && cancelled.getAsBoolean()) {
            throw new CancelledException();
        }
    }
}
