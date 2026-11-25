package com.scriptprep.app;

import java.util.List;

/**
 * Finds the gutter of a page printed in two columns, such as a book spread saved as one PDF page,
 * so the left column can be read before the right one instead of line by line across both.
 */
final class TextColumns {
    private TextColumns() {}

    /**
     * The x position of the gutter, or -1 for a single column. {@code rows} holds, for each line,
     * the left and right edges of its words, in the same units as {@code width}.
     */
    static float gutter(List<List<float[]>> rows, float width) {
        int cells = (int) Math.ceil(width);
        if (cells <= 0 || rows.size() < 6) {
            return -1;
        }
        boolean[] covered = new boolean[cells];
        for (List<float[]> row : rows) {
            for (float[] word : row) {
                for (int x = Math.max(0, (int) Math.floor(word[0])); x < Math.min(cells, (int) Math.ceil(word[1])); x++) {
                    covered[x] = true;
                }
            }
        }
        int from = cells * 3 / 10;
        int to = cells * 7 / 10;
        int bestStart = -1;
        int bestLength = 0;
        for (int x = from; x < to; x++) {
            if (!covered[x]) {
                int start = x;
                while (x < to && !covered[x]) {
                    x++;
                }
                if (x - start > bestLength) {
                    bestStart = start;
                    bestLength = x - start;
                }
            }
        }
        // Wider than any space between words, even in justified text.
        if (bestLength < Math.max(12, cells / 50)) {
            return -1;
        }
        float gutter = bestStart + bestLength / 2f;
        // Both sides need real text: short lines in one column also leave the middle empty.
        int left = 0;
        int right = 0;
        for (List<float[]> row : rows) {
            boolean hasLeft = false;
            boolean hasRight = false;
            for (float[] word : row) {
                hasLeft |= word[1] <= gutter;
                hasRight |= word[0] >= gutter;
            }
            left += hasLeft ? 1 : 0;
            right += hasRight ? 1 : 0;
        }
        int enough = Math.max(3, rows.size() / 4);
        return left >= enough && right >= enough ? gutter : -1;
    }
}
