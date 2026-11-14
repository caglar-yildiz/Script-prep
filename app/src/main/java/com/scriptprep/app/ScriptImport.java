package com.scriptprep.app;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;

final class ScriptImport {
    private ScriptImport() {}

    static ScriptLibrary.Script read(Context context, Uri uri, boolean pdf, int lengthLimit)
        throws IOException {
        ContentResolver resolver = context.getContentResolver();
        String text;
        try (InputStream stream = resolver.openInputStream(uri)) {
            if (stream == null) {
                throw new IOException("Missing stream");
            }
            text = pdf ? PdfScriptReader.read(context, stream, lengthLimit) : readText(stream, lengthLimit);
        }
        return new ScriptLibrary.Script(null, readTitle(resolver, uri), text);
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
}
