package com.scriptprep.app;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;

/** Reads script pages from photos with on-device text recognition; photos never leave the phone. */
final class PhotoOcr {
    /** Most photos read in one import. */
    static final int MAX_PHOTOS = 60;
    /** Photos are scaled down until their longest side is at most this; enough for book print. */
    private static final int MAX_SIDE = 3000;

    private PhotoOcr() {}

    /** The script text of the photos read and how many of them it holds. */
    static final class Result {
        final String text;
        final int pages;

        Result(String text, int pages) {
            this.text = text;
            this.pages = pages;
        }
    }

    /**
     * Reads the photos in order. When the text grows past the limit, it stops after the last
     * whole photo that fits.
     */
    static Result read(Context context, List<Uri> photos, int characterLimit, PdfOcr.Progress progress,
        BooleanSupplier cancelled) throws IOException {
        StringBuilder text = new StringBuilder();
        int pages = 0;
        boolean letters = false;
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try {
            for (int i = 0; i < photos.size(); i++) {
                ScriptImport.check(cancelled);
                if (progress != null) {
                    progress.page(i, photos.size());
                }
                List<String> rows = readPhoto(context.getContentResolver(), photos.get(i), recognizer);
                int before = text.length();
                for (String row : rows) {
                    text.append(row).append('\n');
                    letters |= row.codePoints().anyMatch(Character::isLetter);
                }
                text.append('\n');
                if (text.length() > characterLimit) {
                    if (pages == 0) {
                        throw new ScriptImport.LengthLimitException();
                    }
                    text.setLength(before);
                    break;
                }
                pages++;
            }
        } finally {
            recognizer.close();
        }
        if (!letters) {
            throw new PdfScriptReader.NoTextException();
        }
        return new Result(OcrText.unifyRoles(text.toString().trim()), pages);
    }

    private static List<String> readPhoto(ContentResolver resolver, Uri photo, TextRecognizer recognizer)
        throws IOException {
        Bitmap bitmap = decodeUpright(resolver, photo);
        try {
            Text result = Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0)));
            List<OcrText.Line> lines = new ArrayList<>();
            for (Text.TextBlock block : result.getTextBlocks()) {
                for (Text.Line line : block.getLines()) {
                    Rect box = line.getBoundingBox();
                    if (box != null) {
                        lines.add(new OcrText.Line(line.getText(), box.left, box.top, box.right, box.bottom));
                    }
                }
            }
            return OcrText.pageRows(lines, bitmap.getWidth());
        } catch (ExecutionException | OutOfMemoryError error) {
            throw new IOException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        } finally {
            bitmap.recycle();
        }
    }

    /** Decodes a photo scaled down to a size recognition handles well, turned the way it was taken. */
    private static Bitmap decodeUpright(ContentResolver resolver, Uri photo) throws IOException {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = open(resolver, photo)) {
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("Not an image");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        while (Math.max(bounds.outWidth, bounds.outHeight) / options.inSampleSize > MAX_SIDE) {
            options.inSampleSize *= 2;
        }
        Bitmap bitmap;
        try (InputStream stream = open(resolver, photo)) {
            bitmap = BitmapFactory.decodeStream(stream, null, options);
        } catch (OutOfMemoryError error) {
            throw new IOException(error);
        }
        if (bitmap == null) {
            throw new IOException("Not an image");
        }
        int degrees = 0;
        try (InputStream stream = open(resolver, photo)) {
            int orientation = new ExifInterface(stream).getAttributeInt(
                ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            degrees = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
        } catch (IOException | RuntimeException ignored) {
            // A photo without readable orientation is used as it is.
        }
        if (degrees == 0) {
            return bitmap;
        }
        Matrix turn = new Matrix();
        turn.postRotate(degrees);
        try {
            Bitmap upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), turn, true);
            if (upright != bitmap) {
                bitmap.recycle();
            }
            return upright;
        } catch (OutOfMemoryError error) {
            return bitmap;
        }
    }

    private static InputStream open(ContentResolver resolver, Uri photo) throws IOException {
        InputStream stream = resolver.openInputStream(photo);
        if (stream == null) {
            throw new IOException("Missing stream");
        }
        return stream;
    }
}
