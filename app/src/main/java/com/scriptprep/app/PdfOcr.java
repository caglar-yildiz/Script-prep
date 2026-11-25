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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Reads scanned PDF pages with on-device text recognition (ML Kit, Latin script: Turkish,
 * English, Spanish, German, French, Portuguese and Italian). Page images never leave the phone.
 */
final class PdfOcr {
    /** Longest side of a rendered page; about 300 dpi for A4 and enough for small print. */
    private static final int MAX_SIDE = 3000;
    private static final float MAX_SCALE = 300f / 72f;

    interface Progress {
        void page(int done, int total);
    }

    private PdfOcr() {}

    /** Script rows for each of the given pages (zero-based), in page order. */
    static Map<Integer, List<String>> read(File pdf, List<Integer> pages, Progress progress)
        throws IOException {
        Map<Integer, List<String>> rows = new LinkedHashMap<>();
        TextRecognizer recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        try (ParcelFileDescriptor descriptor =
                 ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
             PdfRenderer renderer = new PdfRenderer(descriptor)) {
            int done = 0;
            for (int index : pages) {
                if (progress != null) {
                    progress.page(done, pages.size());
                }
                if (index < renderer.getPageCount()) {
                    rows.put(index, readPage(renderer, index, recognizer));
                }
                done++;
            }
        } catch (SecurityException error) {
            // PdfRenderer refuses password-protected files.
            throw new IOException(error);
        } finally {
            recognizer.close();
        }
        return rows;
    }

    private static List<String> readPage(PdfRenderer renderer, int index, TextRecognizer recognizer)
        throws IOException {
        Bitmap bitmap;
        try (PdfRenderer.Page page = renderer.openPage(index)) {
            float scale = Math.min(MAX_SCALE,
                MAX_SIDE / (float) Math.max(1, Math.max(page.getWidth(), page.getHeight())));
            int width = Math.max(1, Math.round(page.getWidth() * scale));
            int height = Math.max(1, Math.round(page.getHeight() * scale));
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            // Pages render onto a transparent bitmap; recognition needs dark text on white.
            bitmap.eraseColor(Color.WHITE);
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
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
        } catch (ExecutionException error) {
            throw new IOException(error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        } finally {
            bitmap.recycle();
        }
    }
}
