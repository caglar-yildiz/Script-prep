package com.scriptprep.app;

import static org.junit.Assert.assertEquals;

import java.util.Arrays;

import org.junit.Test;

public class SceneTest {
    @Test
    public void readsColonDashAndStandaloneCues() {
        Scene scene = Scene.parse("AYŞE: Merhaba.\nMEHMET — Selam.\n\nDOKTOR\nNasılsınız?");

        assertEquals(Arrays.asList("AYŞE", "MEHMET", "DOKTOR"), scene.roles);
        assertEquals("Nasılsınız?", scene.lines.get(2).text);
    }

    @Test
    public void readsANamePrintedWithAPeriodBeforeTheDash() {
        Scene scene = Scene.parse("ORGON. — Sormayın.\nM. LOYAL. — Günaydın.");

        assertEquals(Arrays.asList("ORGON", "M. LOYAL"), scene.roles);
        assertEquals("Sormayın.", scene.lines.get(0).text);
    }

    @Test
    public void doesNotTakeASentenceBeforeADashForAName() {
        Scene scene = Scene.parse("ALİ: Gitti. — Peki dedim.\nVELİ: Tamam.");

        assertEquals(Arrays.asList("ALİ", "VELİ"), scene.roles);
        assertEquals("Gitti. — Peki dedim.", scene.lines.get(0).text);
    }

    @Test
    public void joinsWrappedLinesAndKeepsSections() {
        Scene scene = Scene.parse("I. PERDE\n1. Sahne\nAYŞE: Bir\niki üç.\n(Çıkar.)\nMEHMET: Dört.");

        assertEquals("Bir iki üç.", scene.lines.get(0).text);
        assertEquals("I. PERDE" + Scene.SECTION_JOIN + "1. Sahne", scene.lines.get(0).section);
        assertEquals(Arrays.asList("(Çıkar.)"), scene.lines.get(0).notes);
    }

    @Test
    public void writesLinesBackToTextThatReadsTheSame() {
        Scene scene = Scene.parse("SAHNE I\nAYŞE: Bir (güler) iki.\nMEHMET: Üç.\n(Işıklar söner.)");

        Scene again = Scene.parse(Scene.toText(scene.lines));

        assertEquals(scene.roles, again.roles);
        assertEquals(scene.lines.size(), again.lines.size());
        for (int i = 0; i < scene.lines.size(); i++) {
            assertEquals(scene.lines.get(i).text, again.lines.get(i).text);
            assertEquals(scene.lines.get(i).notes, again.lines.get(i).notes);
        }
    }
}
