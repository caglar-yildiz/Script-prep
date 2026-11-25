package com.scriptprep.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import org.junit.Test;

public class OcrTextTest {
    private static final int WIDTH = 2000;

    /** Lines of one book page, 40 px apart, starting at {@code left}. */
    private static List<OcrText.Line> page(int left, String... texts) {
        List<OcrText.Line> lines = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) {
            int top = 100 + i * 40;
            lines.add(new OcrText.Line(texts[i], left, top, left + 800, top + 30));
        }
        return lines;
    }

    @Test
    public void readsTheLeftPageOfASpreadBeforeTheRightOne() {
        List<OcrText.Line> lines = new ArrayList<>();
        lines.addAll(page(80, "12 KUŞLAR", "AYŞE. — Sabah oldu mu?", "Pencereyi aç.", "MEHMET. — Daha erken.",
            "Uyu biraz."));
        lines.addAll(page(1100, "Birinci Perde 13", "AYŞE. — Kuşlar ötüyor.", "MEHMET. — Duyuyorum.",
            "AYŞE. — Kalk o zaman.", "MEHMET. — Peki."));
        // The recogniser does not promise any order.
        Collections.shuffle(lines, new Random(7));

        List<String> rows = OcrText.pageRows(lines, WIDTH);

        assertEquals(Arrays.asList("AYŞE: Sabah oldu mu?", "Pencereyi aç.", "MEHMET: Daha erken.", "Uyu biraz.",
            "", "AYŞE: Kuşlar ötüyor.", "MEHMET: Duyuyorum.", "AYŞE: Kalk o zaman.", "MEHMET: Peki."), rows);
    }

    @Test
    public void joinsPiecesOfOnePrintedLine() {
        List<OcrText.Line> lines = new ArrayList<>(page(80, "Bir", "İki", "Üç", "Dört", "Beş"));
        lines.add(new OcrText.Line("AYŞE. —", 80, 300, 300, 330));
        lines.add(new OcrText.Line("Geldim.", 320, 302, 600, 332));

        List<String> rows = OcrText.pageRows(lines, WIDTH);

        assertEquals("AYŞE: Geldim.", rows.get(rows.size() - 1));
    }

    @Test
    public void joinsAWordSplitAtTheLineEnd() {
        assertEquals(Arrays.asList("ALİ: Bu iş zorbalıkla yürümez."),
            OcrText.clean(Arrays.asList("ALİ. — Bu iş zor-", "balıkla yürümez.")));
    }

    @Test
    public void keepsAHyphenBeforeACapital() {
        assertEquals(Arrays.asList("ALİ: Gel-", "Ankara'ya gidelim."),
            OcrText.clean(Arrays.asList("ALİ: Gel-", "Ankara'ya gidelim.")));
    }

    @Test
    public void removesSpecksAroundLines() {
        assertEquals("ORGON: Ne?", OcrText.cleanRow("© ORGON. — Ne?"));
        assertEquals("MADAM PERNELLE: Evet.", OcrText.cleanRow("'MADAM PERNELLE, —. Evet."));
        assertEquals("Allah, Allah!", OcrText.cleanRow(", Allah, Allah! N"));
        assertEquals("kalk.", OcrText.cleanRow("- kalk."));
    }

    @Test
    public void repairsMisreadSceneNumbers() {
        assertEquals("SAHNE II", OcrText.cleanRow("SAHNE Il"));
        assertEquals("SAHNE III", OcrText.cleanRow("SAHNE HI"));
        assertEquals("SAHNE 11", OcrText.cleanRow("SAHNE 11"));
        assertEquals("SAHNE IV", OcrText.cleanRow("SAHNE IV"));
    }

    @Test
    public void turnsACastListIntoANote() {
        assertEquals(Arrays.asList("SAHNE III", "*AYŞE, MEHMET, DOKTOR, KOMŞU.*", "AYŞE: Buyurun."),
            OcrText.clean(Arrays.asList("SAHNE III", "AYŞE, MEHMET,", "DOKTOR;,; KOMŞU.", "AYŞE. — Buyurun.")));
    }

    @Test
    public void dropsRunningHeadsAndPageNumbers() {
        assertTrue(OcrText.isRunningHead("84 TARTUFFE"));
        assertTrue(OcrText.isRunningHead("Perde V Sahne III 85"));
        assertTrue(OcrText.isRunningHead("- 12 -"));
        assertFalse(OcrText.isRunningHead("AYŞE: Saat 12 oldu, kalkmamız gerek, geç kalıyoruz artık."));
    }

    @Test
    public void unifiesRoleNamesMisreadByOneLetter() {
        String text = "*AYŞE, MEHMET*\nMEHMET: Bir.\nMEHMFT: İki.\nMEHMET: Üç.\nAYŞE: Dört.\nAYSE: Beş.";

        Scene scene = Scene.parse(OcrText.unifyRoles(text));

        assertEquals(Arrays.asList("MEHMET", "AYŞE"), scene.roles);
    }

    @Test
    public void keepsDifferentShortNames() {
        String text = "ALİ: Bir.\nALİ: İki.\nVELİ: Üç.";

        assertEquals(text, OcrText.unifyRoles(text));
    }

    @Test
    public void findsARecognisedLineInTheCleanedText() {
        String text = "AYŞE: Bu iş zorbalıkla yürümez.\nMEHMET: Neden?\nAYŞE: Bilmem.";

        assertEquals(0, OcrText.locate(text, "AYŞE. — Bu iş zor-", 0)[0]);
        int[] found = OcrText.locate(text, "© MEHMET. — Neden?", 0);
        assertEquals("MEHMET: Neden?", text.substring(found[0], found[1]));
        assertEquals(null, OcrText.locate(text, "Hiç olmayan bir satır", 0));
    }

    @Test
    public void findsALineWhoseCueWasRenamed() {
        String text = "CLÉANTE: Sakın boş bulunayım demeyin.";

        int[] found = OcrText.locate(text, "CLBANTE. — Sakın boş bulunayım demeyin.", 0);

        assertEquals("Sakın boş bulunayım demeyin.", text.substring(found[0], found[1]));
    }

    @Test
    public void prefersTheMatchAfterThePreviousOne() {
        String text = "ALİ: Evet.\nVELİ: Evet.";

        assertEquals(text.lastIndexOf("Evet."), OcrText.locate(text, "Evet.", 6)[0]);
        assertEquals(text.indexOf("Evet."), OcrText.locate(text, "Evet.", 0)[0]);
    }
}
