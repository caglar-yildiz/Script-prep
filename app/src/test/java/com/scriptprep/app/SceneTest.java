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
    public void takesTheSampleSceneDirectionsAsNotes() {
        Scene scene = Scene.parse("ECE: (fısıldayarak) Mert! Uyanık mısın?\nMERT: (sessizce, utanarak) …Ha.\n"
            + "(Işıklar titrer. İkisi de donup kalır.)\nECE: Duydun mu?");

        assertEquals("Mert! Uyanık mısın?", scene.lines.get(0).text);
        assertEquals(Arrays.asList("(fısıldayarak)"), scene.lines.get(0).notes);
        assertEquals("…Ha.", scene.lines.get(1).text);
        assertEquals(Arrays.asList("(sessizce, utanarak)", "(Işıklar titrer. İkisi de donup kalır.)"),
            scene.lines.get(1).notes);
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

    @Test
    public void readsANameAfterADashAtTheStartOfARow() {
        Scene scene = Scene.parse("- AHMET: Gel buraya.\n— AYŞE — Geliyorum.\n– Ahmet: Çabuk ol.\n— AYŞE\nTamam.");

        assertEquals(Arrays.asList("AHMET", "AYŞE"), scene.roles);
        assertEquals(Arrays.asList("Gel buraya.", "Geliyorum.", "Çabuk ol.", "Tamam."), texts(scene));
    }

    @Test
    public void keepsNovelStyleDialogueWithTheSpeaker() {
        Scene scene = Scene.parse("AHMET: Bak.\n— Gel buraya.\n— Bak: şurada.");

        assertEquals(Arrays.asList("AHMET"), scene.roles);
        assertEquals("Bak. — Gel buraya. — Bak: şurada.", scene.lines.get(0).text);
    }

    @Test
    public void doesNotTakeAClockTimeForACue() {
        Scene scene = Scene.parse("ALİ: Yarın\nSaat 12:30'da gel.\nASKER 2: 3 kişi geldi.");

        assertEquals(Arrays.asList("ALİ", "ASKER 2"), scene.roles);
        assertEquals("Yarın Saat 12:30'da gel.", scene.lines.get(0).text);
    }

    @Test
    public void keepsLabelsAsDirections() {
        Scene scene = Scene.parse("I. PERDE\nYer: Bir köy kahvesi.\nAHMET: Çay!\nNot: Işık azalır.\nAYŞE: Geldi.");

        assertEquals(Arrays.asList("AHMET", "AYŞE"), scene.roles);
        assertEquals(Arrays.asList("(Yer: Bir köy kahvesi.)", "(Not: Işık azalır.)"), scene.lines.get(0).notes);
    }

    @Test
    public void takesAMixedCaseDashCueOnlyWhenTheNameRepeats() {
        Scene once = Scene.parse("ALİ: Ne dedi?\nEvet - dedi Ali sonra.");
        Scene twice = Scene.parse("Ali - Merhaba.\nVeli - Selam.\nAli - Nasılsın?\nVeli - İyiyim.");

        assertEquals(Arrays.asList("ALİ"), once.roles);
        assertEquals("Ne dedi? Evet - dedi Ali sonra.", once.lines.get(0).text);
        assertEquals(Arrays.asList("Ali", "Veli"), twice.roles);
    }

    @Test
    public void readsAMixedCaseNameWithAPeriodWhenItRepeats() {
        Scene scene = Scene.parse("Orgon. — Sormayın.\nDorine. — Zavallı!\nOrgon. — Ne?\nDorine. — Evet.");

        assertEquals(Arrays.asList("Orgon", "Dorine"), scene.roles);
    }

    @Test
    public void takesAOnceOnlySpeakerWhenTheTextWritesCuesThatWay() {
        Scene scene = Scene.parse("Orgon. — Bir.\nCleante. — İki.\nOrgon. — Üç.\nCleante. — Dört.\nDorine. — Beş.");

        assertEquals(Arrays.asList("Orgon", "Cleante", "Dorine"), scene.roles);
    }

    @Test
    public void writesAnyRoleBackToTextThatReadsTheSame() {
        Scene scene = Scene.parse("Ali - Merhaba.\nAli - Nasılsın?\nASKER 2: 3 kişi geldi.\n- VELİ: Gel.");

        assertEquals(scene.roles, Scene.parse(Scene.toText(scene.lines)).roles);
    }

    @Test
    public void takesScreenplayHeadingsOnlyInCapitalsOrWithAPeriod() {
        assertEquals(true, Scene.isHeading("İÇ. MUTFAK - GECE"));
        assertEquals(true, Scene.isHeading("İç. Mutfak - Gece"));
        assertEquals(true, Scene.isHeading("12 EXT. BAHÇE"));
        assertEquals(false, Scene.isHeading("İç çeker."));
        assertEquals(false, Scene.isHeading("Dış kapı açılır."));

        Scene scene = Scene.parse("AYŞE: Peki.\nİç çeker.\nAHMET: Ne oldu?");
        assertEquals("Peki. İç çeker.", scene.lines.get(0).text);
    }

    @Test
    public void endsASceneOnlyWithAClearEndMarker() {
        assertEquals(true, Scene.isHeading("SON"));
        assertEquals(true, Scene.isHeading("Perde sonu."));
        assertEquals(true, Scene.isHeading("I. Perde Sonu"));
        assertEquals(false, Scene.isHeading("Son."));
        assertEquals(false, Scene.isHeading("Fin."));
    }

    @Test
    public void closesABracketLeftOpenAtTheNextHeadingOrCue() {
        Scene scene = Scene.parse("SAHNE 1\nAHMET: Bir (güler\nSAHNE 2\nAYŞE: İki (oturur\nAHMET: Üç.");

        assertEquals(2, scene.sections.size());
        assertEquals(Arrays.asList("AHMET", "AYŞE"), scene.roles);
        assertEquals(Arrays.asList("(güler)"), scene.lines.get(0).notes);
        assertEquals("Üç.", scene.lines.get(2).text);
    }

    private static java.util.List<String> texts(Scene scene) {
        java.util.List<String> texts = new java.util.ArrayList<>();
        for (Scene.Line line : scene.lines) {
            texts.add(line.text);
        }
        return texts;
    }
}
