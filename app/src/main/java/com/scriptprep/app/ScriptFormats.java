package com.scriptprep.app;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

/**
 * Turns Word (DOCX), Fountain and Final Draft (FDX) scripts into the plain script text the
 * parser reads: "ROLE: line", headings on their own rows and directions in parentheses.
 */
final class ScriptFormats {
    private static final String WORD = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    /** The largest unpacked Word document read; far more than any play needs. */
    private static final long MAX_XML_BYTES = 30L * 1024 * 1024;
    private static final Pattern CONTINUED = Pattern.compile(
        "\\s*\\((?:CONT['’]?D|CONTINUED|MORE)\\)\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern TITLE_KEY = Pattern.compile(
        "^(?i)(?:title|credit|author|authors|source|draft date|date|contact|copyright|notes|revision)\\s*:.*");
    private static final Pattern SCENE_HEADING = Pattern.compile(
        "^(?i)(?:INT|EXT|EST|INT\\./EXT|INT/EXT|I/E)[ .].*");

    enum Kind { TEXT, DOCX, FOUNTAIN, FDX }

    private ScriptFormats() {}

    /** The format a file name points to; plain text when the ending is unknown. */
    static Kind kindOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".docx")) {
            return Kind.DOCX;
        }
        if (name.endsWith(".fountain") || name.endsWith(".spmd")) {
            return Kind.FOUNTAIN;
        }
        if (name.endsWith(".fdx")) {
            return Kind.FDX;
        }
        return Kind.TEXT;
    }

    // ---------------------------------------------------------------- Word

    /** The paragraphs of a Word document; a name in bold at the start becomes a role cue. */
    static String docx(InputStream stream) throws IOException {
        byte[] xml = null;
        try (ZipInputStream zip = new ZipInputStream(stream)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if ("word/document.xml".equals(entry.getName())) {
                    xml = readLimited(zip);
                    break;
                }
            }
        }
        if (xml == null) {
            throw new IOException("Not a Word document");
        }
        Document document = parse(xml, true);
        NodeList paragraphs = document.getElementsByTagNameNS(WORD, "p");
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            text.append(wordParagraph((Element) paragraphs.item(i))).append('\n');
        }
        return text.toString().trim();
    }

    private static String wordParagraph(Element paragraph) {
        StringBuilder plain = new StringBuilder();
        int boldEnd = 0;
        boolean leadingBold = true;
        boolean allItalic = true;
        boolean anyText = false;
        NodeList runs = paragraph.getElementsByTagNameNS(WORD, "r");
        for (int i = 0; i < runs.getLength(); i++) {
            Element run = (Element) runs.item(i);
            String runText = runText(run);
            if (runText.isEmpty()) {
                continue;
            }
            plain.append(runText);
            if (runText.trim().isEmpty()) {
                continue;
            }
            anyText = true;
            boolean bold = hasStyle(run, "b");
            allItalic &= hasStyle(run, "i");
            leadingBold &= bold;
            if (leadingBold) {
                boldEnd = plain.length();
            }
        }
        String text = plain.toString().replace(' ', ' ').trim();
        if (!anyText) {
            return "";
        }
        if (Scene.isHeading(text)) {
            return text;
        }
        int lead = plain.length() - plain.toString().replaceFirst("^\\s+", "").length();
        if (boldEnd > lead) {
            String prefix = plain.substring(lead, boldEnd).trim();
            String name = prefix.replaceFirst("\\s*[:‒–—−-]$", "").replaceFirst("\\s*\\([^()]*\\)$", "").trim();
            if (Scene.isRoleName(name)) {
                String rest = plain.substring(boldEnd).trim();
                return "**" + prefix + "**" + (rest.isEmpty() ? "" : " " + rest);
            }
        }
        return allItalic ? "*" + text + "*" : text;
    }

    private static String runText(Element run) {
        StringBuilder text = new StringBuilder();
        NodeList children = run.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (!WORD.equals(child.getNamespaceURI())) {
                continue;
            }
            String name = child.getLocalName();
            if ("t".equals(name)) {
                text.append(child.getTextContent());
            } else if ("tab".equals(name) || "br".equals(name) || "cr".equals(name)) {
                text.append(' ');
            }
        }
        return text.toString();
    }

    /** Whether a run is bold ("b") or italic ("i"), allowing for w:val="0" turning it off. */
    private static boolean hasStyle(Element run, String style) {
        NodeList properties = run.getElementsByTagNameNS(WORD, "rPr");
        if (properties.getLength() == 0) {
            return false;
        }
        NodeList marks = ((Element) properties.item(0)).getElementsByTagNameNS(WORD, style);
        if (marks.getLength() == 0) {
            return false;
        }
        String value = ((Element) marks.item(0)).getAttributeNS(WORD, "val");
        return !"0".equals(value) && !"false".equalsIgnoreCase(value) && !"none".equalsIgnoreCase(value);
    }

    // ---------------------------------------------------------------- Fountain

    /** A Fountain screenplay: characters become cues, action becomes directions. */
    static String fountain(String source) {
        String text = source.replace("\r\n", "\n").replace('\r', '\n')
            .replaceAll("(?s)/\\*.*?\\*/", "")
            .replaceAll("(?s)\\[\\[.*?]]", "");
        String[] rows = text.split("\n", -1);
        int start = 0;
        if (rows.length > 0 && TITLE_KEY.matcher(rows[0]).matches()) {
            while (start < rows.length && !rows[start].trim().isEmpty()) {
                start++;
            }
        }

        StringBuilder output = new StringBuilder();
        for (int i = start; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.isEmpty() || row.matches("^={3,}$") || row.startsWith("=")) {
                continue;
            }
            boolean afterBlank = i == start || rows[i - 1].trim().isEmpty();
            boolean beforeText = i + 1 < rows.length && !rows[i + 1].trim().isEmpty();
            if (row.startsWith("#")) {
                blank(output);
                output.append(plain(row.replaceFirst("^#+\\s*", ""))).append('\n');
            } else if (row.startsWith(".") && !row.startsWith("..") || SCENE_HEADING.matcher(row).matches()) {
                blank(output);
                output.append(plain(row.startsWith(".") ? row.substring(1) : row)).append('\n');
            } else if (row.startsWith(">") && row.endsWith("<")) {
                output.append(note(row.substring(1, row.length() - 1))).append('\n');
            } else if (row.startsWith(">") || afterBlank && isTransition(row)) {
                continue;
            } else if (afterBlank && beforeText && isCharacter(row)) {
                String name = CONTINUED.matcher(row.startsWith("@") ? row.substring(1) : row)
                    .replaceAll(" ").replaceFirst("\\s*\\^$", "").trim();
                blank(output);
                output.append(plain(name)).append(":\n");
                i = dialogue(rows, i + 1, output) - 1;
            } else {
                // Action: kept as a direction, one paragraph per note.
                StringBuilder action = new StringBuilder(row.startsWith("!") ? row.substring(1) : row);
                while (i + 1 < rows.length && !rows[i + 1].trim().isEmpty()) {
                    action.append(' ').append(rows[++i].trim());
                }
                output.append(note(action.toString())).append('\n');
            }
        }
        return output.toString().trim();
    }

    /** Copies the dialogue rows after a character, up to the next blank row; returns that row. */
    private static int dialogue(String[] rows, int from, StringBuilder output) {
        int i = from;
        for (; i < rows.length && !rows[i].trim().isEmpty(); i++) {
            String row = rows[i].trim();
            if (row.startsWith("(") && row.endsWith(")")) {
                output.append(note(row.substring(1, row.length() - 1))).append('\n');
            } else {
                output.append(plain(row.startsWith("~") ? row.substring(1) : row)).append('\n');
            }
        }
        return i;
    }

    private static boolean isCharacter(String row) {
        if (row.startsWith("@")) {
            return row.length() > 1;
        }
        String name = row.replaceFirst("\\s*\\^$", "").replaceAll("\\([^()]*\\)", "").trim();
        return !name.isEmpty() && name.equals(name.toUpperCase(Locale.ROOT))
            && !name.equals(name.toLowerCase(Locale.ROOT)) && !SCENE_HEADING.matcher(name).matches()
            && Scene.isRoleName(name.replaceAll("[^\\p{L}\\p{N} .'’-]", "").trim());
    }

    private static boolean isTransition(String row) {
        return row.equals(row.toUpperCase(Locale.ROOT)) && row.endsWith("TO:");
    }

    /** Fountain emphasis marks (*, **, ***, _) are left out; escaped characters are kept. */
    private static String plain(String row) {
        return row.replaceAll("(?<!\\\\)[*_]+", "").replace("\\*", "*").replace("\\_", "_").trim();
    }

    // ---------------------------------------------------------------- Final Draft

    /** A Final Draft script: its paragraph types say which rows are cues, dialogue or action. */
    static String fdx(InputStream stream) throws IOException {
        Document document = parse(readLimited(stream), false);
        NodeList contents = document.getElementsByTagName("Content");
        if (contents.getLength() == 0) {
            throw new IOException("Not a Final Draft script");
        }
        NodeList paragraphs = ((Element) contents.item(0)).getElementsByTagName("Paragraph");
        StringBuilder output = new StringBuilder();
        for (int i = 0; i < paragraphs.getLength(); i++) {
            Element paragraph = (Element) paragraphs.item(i);
            String text = fdxText(paragraph);
            if (text.isEmpty()) {
                continue;
            }
            switch (paragraph.getAttribute("Type")) {
                case "Scene Heading" -> {
                    blank(output);
                    output.append(text).append('\n');
                }
                case "Character" -> {
                    blank(output);
                    output.append(CONTINUED.matcher(text).replaceAll(" ").trim()).append(":\n");
                }
                case "Dialogue", "Lyrics" -> output.append(text).append('\n');
                case "Parenthetical" -> output.append(note(text.replaceAll("^\\(|\\)$", ""))).append('\n');
                case "Transition", "Shot" -> {
                    // Camera and editing directions are not read in a rehearsal.
                }
                default -> output.append(note(text)).append('\n');
            }
        }
        return output.toString().trim();
    }

    private static String fdxText(Element paragraph) {
        StringBuilder text = new StringBuilder();
        NodeList children = paragraph.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element && "Text".equals(((Element) child).getTagName())) {
                text.append(child.getTextContent());
            }
        }
        return text.toString().replace(' ', ' ').replaceAll("\\s+", " ").trim();
    }

    // ---------------------------------------------------------------- Shared

    private static String note(String text) {
        String clean = Scene.balanced(plain(text)).replaceAll("\\s+", " ").trim();
        return clean.isEmpty() ? "" : "(" + clean + ")";
    }

    private static void blank(StringBuilder output) {
        if (output.length() > 0 && !output.toString().endsWith("\n\n")) {
            output.append('\n');
        }
    }

    private static byte[] readLimited(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[16384];
        int count;
        long total = 0;
        while ((count = stream.read(buffer)) != -1) {
            total += count;
            if (total > MAX_XML_BYTES) {
                throw new ScriptImport.LengthLimitException();
            }
            bytes.write(buffer, 0, count);
        }
        return bytes.toByteArray();
    }

    private static Document parse(byte[] xml, boolean namespaces) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(namespaces);
            factory.setExpandEntityReferences(false);
            try {
                // Scripts never need a DTD; refusing one keeps outside entities out.
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            } catch (ParserConfigurationException | RuntimeException unsupported) {
                // Android's parser does not load outside entities in any case.
            }
            DocumentBuilder builder = factory.newDocumentBuilder();
            return builder.parse(new ByteArrayInputStream(xml));
        } catch (ParserConfigurationException | org.xml.sax.SAXException error) {
            throw new IOException(error);
        }
    }
}
