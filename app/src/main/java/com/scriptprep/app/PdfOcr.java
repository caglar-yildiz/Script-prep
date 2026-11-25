package com.scriptprep.app;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;

import com.google.android.gms.tasks.Tasks;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * Reads scanned PDF pages with on-device text recognition (ML Kit, Latin script: Turkish,
 * English, Spanish, German, French, Portuguese and Italian). Page images never leave the phone.
 */
final class PdfOcr {
    /** Longest side of a rendered page; about 210 dpi for A4, enough for book print. */
    private static final int MAX_SIDE = 2500;
    private static final float MAX_SCALE = 300f / 72f;

    interface Progress {
        void page(int done, int total);
    }

    /** Receives each page's rows as soon as it is read; returning false stops reading. */
    interface PageSink {
        boolean page(int index, List<String> rows);
    }

    private PdfOcr() {}

    /** Reads the given pages (zero-based) in order and hands each page's script rows to {@code sink}. */
    static void read(File pdf, List<Integer> pages, Progress progress, PageSink sink)
        throws IOException {
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try (ParcelFileDescriptor descriptor =
                 ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int done = 0;
            for (int index : pages) {
                if (progress != null) {
                    progress.page(done, pages.size());
                }
                if (index < renderer.getPageCount()
                    && !sink.page(index, readPage(renderer, index, recognizer))) {
                    break;
                }
                done++;
            }
        } catch (SecurityException error) {
            // PdfRenderer refuses password-protected files.
            throw new IOException(error);
        } finally {
            recognizer.close();
        }
    }

    private static List<String> readPage(PdfRenderer renderer, int index, TextRecognizer recognizer)
        throws IOException {
        Bitmap bitmap;
        try {
            bitmap = render(renderer, index, MAX_SIDE);
        } catch (OutOfMemoryError error) {
            // A phone with little memory still gets the page, at a lower resolution.
            try {
                bitmap = render(renderer, index, MAX_SIDE / 2);
            } catch (OutOfMemoryError again) {
                throw new IOException(again);
            }
        }
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

    private static Bitmap render(PdfRenderer renderer, int index, int maxSide) {
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            float scale = Math.min(MAX_SCALE,
                maxSide / (float) Math.max(1, Math.max(page.getWidth(), page.getHeight())));
            int width = Math.max(1, Math.round(page.getWidth() * scale));
            int height = Math.max(1, Math.round(page.getHeight() * scale));
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            // Pages render onto a transparent bitmap; recognition needs dark text on white.
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            return bitmap;
        }
    }
}
