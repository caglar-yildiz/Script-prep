package com.scriptprep.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Keeps a cut-out of each line text recognition was unsure about, so it can be checked against
 * the page after the import. The cut-outs stay in the app's cache until the next import.
 */
final class OcrReview {
    /** Lines recognised with less confidence than this are offered for review. */
    static final float LOW_CONFIDENCE = 0.7f;
    private static final int MAX_ITEMS = 150;
    private static final int MAX_WIDTH = 1600;
    private static final String FOLDER = "ocr-review";

    /** A line to check: what was recognised, a picture of it and the page it is on (from 1). */
    static final class Item {
        final String text;
        final File image;
        final int page;

        Item(String text, File image, int page) {
            this.text = text;
            this.image = image;
            this.page = page;
        }
    }

    private final File folder;
    private final List<Item> items = new ArrayList<>();

    /** Starts a new review and removes the cut-outs of the previous one. */
    OcrReview(Context context) {
        folder = new File(context.getCacheDir(), FOLDER);
        File[] old = folder.listFiles();
        if (old != null) {
            for (File file : old) {
                file.delete();
            }
        }
        folder.mkdirs();
    }

    /** Saves a cut-out of the line when it was recognised with low confidence. */
    synchronized void add(Bitmap page, Rect box, String text, float confidence, int pageNumber) {
        if (confidence < 0 || confidence >= LOW_CONFIDENCE || items.size() >= MAX_ITEMS
            || text == null || OcrText.cleanRow(text).isEmpty()) {
            return;
        }
        // Some room around the line shows the letters cut by a tight box and the line's context.
        int pad = Math.max(8, box.height() / 2);
        int left = Math.max(0, box.left - pad);
        int top = Math.max(0, box.top - pad);
        int right = Math.min(page.getWidth(), box.right + pad);
        int bottom = Math.min(page.getHeight(), box.bottom + pad);
        if (right - left < 4 || bottom - top < 4) {
            return;
        }
        Bitmap crop = null;
        try {
            crop = Bitmap.createBitmap(page, left, top, right - left, bottom - top);
            if (crop.getWidth() > MAX_WIDTH) {
                Bitmap smaller = Bitmap.createScaledBitmap(crop, MAX_WIDTH,
                    Math.max(1, crop.getHeight() * MAX_WIDTH / crop.getWidth()), true);
                crop.recycle();
                crop = smaller;
            }
            File file = new File(folder, "line-" + items.size() + ".jpg");
            try (FileOutputStream output = new FileOutputStream(file)) {
                crop.compress(Bitmap.CompressFormat.JPEG, 85, output);
            }
            items.add(new Item(text, file, pageNumber));
        } catch (IOException | RuntimeException | OutOfMemoryError ignored) {
            // A line that cannot be saved is simply not offered for review.
        } finally {
            if (crop != null && crop != page) {
                crop.recycle();
            }
        }
    }

    synchronized List<Item> items() {
        return Collections.unmodifiableList(new ArrayList<>(items));
    }
}
