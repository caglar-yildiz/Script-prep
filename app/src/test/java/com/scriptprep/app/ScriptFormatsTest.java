package com.scriptprep.app;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Test;

public class ScriptFormatsTest {
    private static final String W = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"";

    private static byte[] docx(String body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<Types/>".getBytes(StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document " + W + "><w:body>" + body
                + "</w:body></w:document>").getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static String run(String text, boolean bold, boolean italic) {
        String style = (bold ? "<w:b/>" : "") + (italic ? "<w:i/>" : "");
        return "<w:r>" + (style.isEmpty() ? "" : "<w:rPr>" + style + "</w:rPr>")
            + "<w:t xml:space=\"preserve\">" + text + "</w:t></w:r>";
    }

    @Test
    public void readsBoldNamesAndItalicDirectionsFromWord() throws IOException {
        String body = "<w:p>" + run("1. Sahne", false, false) + "</w:p>"
            + "<w:p>" + run("AYŞE", true, false) + run(" Kapıyı kim çaldı?", false, false) + "</w:p>"
            + "<w:p>" + run("(Pencereye gider.)", false, true) + "</w:p>"
            + "<w:p/>"
            + "<w:p>" + run("Mehmet:", true, false) + run(" Ben", false, false) + run(" geldim.", false, false) + "</w:p>"
            + "<w:p>" + run("Bir", false, false) + "<w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t> iki</w:t></w:r></w:p>";

        String text = ScriptFormats.docx(new ByteArrayInputStream(docx(body)));

        assertEquals("1. Sahne\n**AYŞE** Kapıyı kim çaldı?\n*(Pencereye gider.)*\n\n**Mehmet:** Ben geldim.\nBir iki", text);
        Scene scene = Scene.parse(text);
        assertEquals(Arrays.asList("AYŞE", "Mehmet"), scene.roles);
        assertEquals("Ben geldim. Bir iki", scene.lines.get(1).text);
    }

    @Test
    public void readsAFountainScreenplay() {
        String source = "Title: Kuşlar\nAuthor: Biri\n\n"
            + "INT. MUTFAK - SABAH\n\n"
            + "Ayşe pencereyi açar. *Kuşlar* ötüyor.\n\n"
            + "AYŞE\n(fısıltıyla)\nSabah oldu.\nKalk artık.\n\n"
            + "MEHMET (CONT'D)\nDaha erken.\n\n"
            + "@McCLANE\nYippee.\n\n"
            + "CUT TO:\n\n"
            + "/* boneyard */[[a note]]\n"
            + "> SON <\n";

        String text = ScriptFormats.fountain(source);

        assertEquals("INT. MUTFAK - SABAH\n(Ayşe pencereyi açar. Kuşlar ötüyor.)\n\n"
            + "AYŞE:\n(fısıltıyla)\nSabah oldu.\nKalk artık.\n\nMEHMET:\nDaha erken.\n\nMcCLANE:\nYippee.\n(SON)", text);
        Scene scene = Scene.parse(text);
        assertEquals(Arrays.asList("AYŞE", "MEHMET", "McCLANE"), scene.roles);
        assertEquals("Sabah oldu. Kalk artık.", scene.lines.get(0).text);
        // Directions before a line are kept with it, in order.
        assertEquals(Arrays.asList("(Ayşe pencereyi açar. Kuşlar ötüyor.)", "(fısıltıyla)"), scene.lines.get(0).notes);
    }

    @Test
    public void readsAFinalDraftScript() throws IOException {
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>"
            + "<FinalDraft DocumentType=\"Script\" Version=\"5\"><Content>"
            + "<Paragraph Type=\"Scene Heading\"><Text>INT. MUTFAK - SABAH</Text></Paragraph>"
            + "<Paragraph Type=\"Action\"><Text>Ayşe pencereyi </Text><Text Style=\"Bold\">açar</Text><Text>.</Text></Paragraph>"
            + "<Paragraph Type=\"Character\"><Text>AYŞE</Text></Paragraph>"
            + "<Paragraph Type=\"Parenthetical\"><Text>(fısıltıyla)</Text></Paragraph>"
            + "<Paragraph Type=\"Dialogue\"><Text>Sabah oldu.</Text></Paragraph>"
            + "<Paragraph Type=\"Character\"><Text>MEHMET (CONT'D)</Text></Paragraph>"
            + "<Paragraph Type=\"Dialogue\"><Text>Daha erken.</Text></Paragraph>"
            + "<Paragraph Type=\"Transition\"><Text>CUT TO:</Text></Paragraph>"
            + "</Content><TitlePage><Content><Paragraph><Text>Kuşlar</Text></Paragraph></Content></TitlePage>"
            + "</FinalDraft>";

        String text = ScriptFormats.fdx(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

        assertEquals("INT. MUTFAK - SABAH\n(Ayşe pencereyi açar.)\n\nAYŞE:\n(fısıltıyla)\nSabah oldu.\n\nMEHMET:\nDaha erken.",
            text);
        Scene scene = Scene.parse(text);
        assertEquals(Arrays.asList("AYŞE", "MEHMET"), scene.roles);
    }

    @Test
    public void choosesTheFormatFromTheFileName() {
        assertEquals(ScriptFormats.Kind.DOCX, ScriptFormats.kindOf("Oyun.DOCX"));
        assertEquals(ScriptFormats.Kind.FOUNTAIN, ScriptFormats.kindOf("film.fountain"));
        assertEquals(ScriptFormats.Kind.FDX, ScriptFormats.kindOf("film.fdx"));
        assertEquals(ScriptFormats.Kind.TEXT, ScriptFormats.kindOf("oyun.txt"));
        assertEquals(ScriptFormats.Kind.TEXT, ScriptFormats.kindOf(null));
    }
}
