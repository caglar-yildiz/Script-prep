package com.scriptprep.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
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

        Result(ScriptLibrary.Script script, int scannedPages, int firstPage, int lastPage,
            int requestedLastPage) {
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

    /** A picked PDF, copied and counted, waiting for the pages to read. */
    static final class PdfFile {
        final PdfScriptReader.Source source;
        final String title;

        PdfFile(PdfScriptReader.Source source, String title) {
            this.source = source;
            this.title = title;
        }
    }

    static Result readText(Context context, Uri uri, int lengthLimit) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Missing stream");
            }
            String text = readText(stream, lengthLimit);
            return new Result(new ScriptLibrary.Script(null, readTitle(resolver, uri), text), 0, 0, 0, 0);
        }
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
        PdfOcr.Progress progress, BooleanSupplier cancelled) throws IOException {
        try {
            PdfScriptReader.Result text = PdfScriptReader.read(context, pdf.source, firstPage, lastPage,
                lengthLimit, progress, cancelled);
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
        try (Cursor cursor = resolver.query(uri, new String[] {OpenableColumns.DISPLAY_NAME},
            null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && cursor.getColumnCount() > 0) {
                String name = cursor.getString(0);
                if (name != null) {
                    return name.replaceFirst("(?i)\\.(?:txt|pdf)$", "");
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
