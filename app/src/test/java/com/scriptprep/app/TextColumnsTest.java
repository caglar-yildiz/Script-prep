package com.scriptprep.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

public class TextColumnsTest {
    private static final float WIDTH = 842;

    private static List<float[]> words(float... edges) {
        List<float[]> row = new ArrayList<>();
        for (int i = 0; i < edges.length; i += 2) {
            row.add(new float[] {edges[i], edges[i + 1]});
        }
        return row;
    }

    @Test
    public void findsTheGutterOfABookSpread() {
        List<List<float[]>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(words(40, 120, 130, 380, 460, 560, 570, 800));
        }

        float gutter = TextColumns.gutter(rows, WIDTH);

        assertTrue(gutter > 380 && gutter < 460);
    }

    @Test
    public void keepsOneColumnOfShortLines() {
        List<List<float[]>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(words(60, 130, 140, 300));
        }
        // A page number on the right is not a second column.
        rows.add(words(780, 800));

        assertEquals(-1, TextColumns.gutter(rows, WIDTH), 0);
    }

    @Test
    public void keepsOneColumnWhenLinesCrossTheMiddle() {
        List<List<float[]>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(i % 3 == 0 ? words(60, 300, 310, 700) : words(60, 300, 460, 700));
        }

        assertEquals(-1, TextColumns.gutter(rows, WIDTH), 0);
    }

    @Test
    public void needsEnoughLines() {
        assertEquals(-1, TextColumns.gutter(Arrays.asList(words(40, 300, 500, 800)), WIDTH), 0);
    }
}
