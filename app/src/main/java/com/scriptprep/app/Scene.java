package com.scriptprep.app;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class Scene {
    private static final Locale TURKISH = Locale.forLanguageTag("tr-TR");
    private static final String ROLE_PATTERN = "[\\p{L}][\\p{L}\\p{M}\\p{N} #.’'ʼ-]{0,39}";
    private static final Pattern ROLE = Pattern.compile(ROLE_PATTERN);
    private static final Pattern CUE = Pattern.compile(
        "^(" + ROLE_PATTERN + "?)\\s*(\\([^()]*\\))?(?:\\s*:\\s*|\\s+[-‒–—−]\\s*|\\s*[‒–—−]\\s*)(.*)$"
    );
    private static final Pattern BOLD_CUE = Pattern.compile("^\\*\\*(.+?)\\*\\*\\s*(.*)$");
    private static final Pattern ROLE_NOTE = Pattern.compile("^(.+?)\\s*(\\([^()]*\\))$");
    private static final String NUMBER =
        "(?:\\d+|[IVXLCDM]+|BIR|IKI|U[CÇ]|DORT|BES|ALTI|YEDI|SEKIZ|DOKUZ|ON|"
        + "BIRINCI|IKINCI|UCUNCU|DORDUNCU|BESINCI|ALTINCI|YEDINCI|SEKIZINCI|DOKUZUNCU|ONUNCU|"
        + "ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|FIRST|SECOND|THIRD|"
        + "PRIMERO|PRIMER|SEGUNDO|TERCERO|TERCER|CUARTO|QUINTO|"
        + "ERSTER|ERSTE|ZWEITER|ZWEITE|DRITTER|DRITTE|VIERTER|VIERTE|FUNFTER|FUNFTE|"
        + "PREMIER|PREMIERE|DEUXIEME|TROISIEME|QUATRIEME|CINQUIEME|"
        + "PRIMEIRO|PRIMEIRA|SEGUNDA|TERCEIRO|TERCEIRA|QUARTO|QUARTA|"
        + "PRIMO|SECONDO|TERZO|QUINTO)";
    private static final Pattern ACT = titledHeading("PERDE|ACT|ACTO|AKT|AUFZUG|ACTE|ATO|ATTO");
    private static final Pattern STAGE = titledHeading("SAHNE|SCENE|TABLO|ESCENA|CUADRO|SZENE|AUFTRITT|BILD|TABLEAU|CENA|QUADRO|SCENA");
    private static final Pattern TRADITIONAL = Pattern.compile("^(?:MUKADDIME|MUHAVERE|FASIL|BITIS)[.:]?$");
    private static final Pattern SCREEN = Pattern.compile(
        "^(?:\\d+[A-Z]?\\s+)?(?:INT\\.?/EXT|I/E|IC\\s*/\\s*DIS|INT|EXT|EST|IC|DIS)[.\\s]+.+$"
    );
    private static final Pattern END = Pattern.compile(
        "^(?:(?:[IVXLCDM]+|\\d+)\\.?\\s+)?(?:PERDE SONU|SAHNE SONU|END OF (?:ACT|SCENE).*|"
        + "BLACKOUT|CURTAIN|FADE OUT|(?:CUT|DISSOLVE|FADE) TO|SON|END|KARARIR|"
        + "FIN|TELON|OSCURO|ENDE|VORHANG|RIDEAU|NOIR|FIM|PANO|FINE|SIPARIO|BUIO)[.!:]?$"
    );
    private static final Pattern EXERCISE = Pattern.compile("^ETKINLIK\\s+\\d+\\b");
    private static final Pattern LINK = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);
    private static final Pattern PAGE = Pattern.compile("^(?:(?:SAYFA|PAGE|PAGINA|SEITE)\\s+)?\\d{1,4}(?:\\s*/\\s*\\d{1,4}|\\.)?$");

    /** Joins an act and a scene heading in a section title, as in "I. PERDE · 2. Sahne". */
    static final String SECTION_JOIN = " · ";

    final List<Line> lines;
    final List<String> roles;
    final List<Section> sections;

    private Scene(List<Line> lines, List<Section> sections) {
        this.lines = Collections.unmodifiableList(new ArrayList<>(lines));
        Set<String> uniqueRoles = new LinkedHashSet<>();
        for (Line line : lines) {
            uniqueRoles.add(line.role);
        }
        roles = Collections.unmodifiableList(new ArrayList<>(uniqueRoles));
        this.sections = Collections.unmodifiableList(new ArrayList<>(sections));
    }

    /** The given lines, in script order and without duplicates. */
    Scene subset(java.util.Collection<Integer> indices) {
        List<Line> chosen = new ArrayList<>();
        for (int index : new java.util.TreeSet<>(indices)) {
            if (index >= 0 && index < lines.size()) {
                chosen.add(lines.get(index));
            }
        }
        return new Scene(chosen, Collections.emptyList());
    }

    Scene forSection(int index) {
        if (index < 0 || index >= sections.size()) {
            return this;
        }
        Section section = sections.get(index);
        return new Scene(lines.subList(section.start, section.end), Collections.emptyList());
    }

    static Scene parseSolo(String source, String narrator, boolean byLine) {
        String[] rows = source.replace('\u00a0', ' ').split("\\r\\n|[\\r\\n]", -1);
        List<Line> lines = new ArrayList<>();
        StringBuilder paragraph = new StringBuilder();
        for (String raw : rows) {
            String text = raw.replace("**", "").trim();
            if (text.length() > 2 && text.startsWith("*") && text.endsWith("*")) {
                text = text.substring(1, text.length() - 1).trim();
            }
            if (!text.isEmpty()) {
                if (paragraph.length() > 0) {
                    paragraph.append(' ');
                }
                paragraph.append(text);
            }
            if (byLine || text.isEmpty()) {
                addSoloLine(lines, narrator, paragraph);
            }
        }
        addSoloLine(lines, narrator, paragraph);
        if (lines.isEmpty()) {
            return new Scene(lines, Collections.emptyList());
        }
        return new Scene(lines, Collections.singletonList(new Section("", 0, lines.size())));
    }

    private static void addSoloLine(List<Line> lines, String narrator, StringBuilder paragraph) {
        if (paragraph.length() > 0) {
            lines.add(new Line(narrator, paragraph.toString(), "", Collections.emptyList()));
            paragraph.setLength(0);
        }
    }

    static Scene parse(String source) {
        String[] rows = source.replace('\u00a0', ' ').split("\\r\\n|[\\r\\n]");
        Map<String, Integer> standaloneCounts = new LinkedHashMap<>();
        boolean hasExplicitCues = false;
        for (String raw : rows) {
            String row = raw.trim();
            if (heading(row) == null && !isNote(row) && !isPageNumber(row) && !isNonScript(row)) {
                if (explicitCue(row) != null) {
                    hasExplicitCues = true;
                } else {
                    Cue cue = standaloneCue(row);
                    if (cue != null) {
                        String key = roleKey(cue.name);
                        Integer count = standaloneCounts.get(key);
                        standaloneCounts.put(key, count == null ? 1 : count + 1);
                    }
                }
            }
        }

        Parser parser = new Parser();
        boolean previousBlank = true;
        for (int i = 0; i < rows.length; i++) {
            String row = rows[i].trim();
            if (row.isEmpty()) {
                previousBlank = true;
                continue;
            }
            if (isPageNumber(row) || isNonScript(row)) {
                continue;
            }
            if (parser.continuesNote()) {
                parser.addText(row);
                previousBlank = false;
                continue;
            }

            String heading = heading(row);
            if (heading != null) {
                parser.startSection(heading);
            } else if (isNote(row)) {
                parser.addText(row);
            } else {
                Cue cue = explicitCue(row);
                if (cue == null) {
                    Cue candidate = standaloneCue(row);
                    if (candidate != null && !parser.awaitingDialogue() && nextIsDialogue(rows, i)
                        && (!hasExplicitCues || previousBlank
                            || standaloneCounts.get(roleKey(candidate.name)) > 1
                            || parser.hasRole(candidate.name))) {
                        cue = candidate;
                    }
                }
                if (cue == null) {
                    parser.addText(row);
                } else {
                    parser.startCue(cue);
                }
            }
            previousBlank = false;
        }
        return parser.finish();
    }

    static boolean isHeading(String text) {
        return heading(text) != null;
    }

    static boolean isPageNumber(String text) {
        return PAGE.matcher(fold(text.trim())).matches();
    }

    static boolean isRoleName(String candidate) {
        return ROLE.matcher(candidate).matches() && !candidate.endsWith(".")
            && candidate.split("\\s+").length <= 5;
    }

    private static Pattern titledHeading(String label) {
        return Pattern.compile("^(?:(?:" + label + ")(?:\\s+" + NUMBER + ")?|"
            + NUMBER + "\\.?\\s+(?:" + label + "))(?:\\s*[:.‒–—−-]\\s*.*)?$");
    }

    private static String heading(String row) {
        String plain = row;
        if (plain.startsWith("**") && plain.endsWith("**") && plain.length() > 4) {
            plain = plain.substring(2, plain.length() - 2).trim();
        }
        String folded = fold(plain);
        if (ACT.matcher(folded).matches() || STAGE.matcher(folded).matches()
            || TRADITIONAL.matcher(folded).matches() || SCREEN.matcher(folded).matches()
            || END.matcher(folded).matches()) {
            return plain;
        }
        return null;
    }

    private static String fold(String value) {
        return java.text.Normalizer.normalize(value.toUpperCase(Locale.ROOT), java.text.Normalizer.Form.NFD)
            .replaceAll("\\p{M}+", "");
    }

    private static String roleKey(String value) {
        return value.toLowerCase(TURKISH).replaceAll("\\s+", " ");
    }

    private static boolean isNonScript(String row) {
        String plain = row.replace("**", "");
        if (EXERCISE.matcher(fold(plain)).find()) {
            return true;
        }
        Matcher link = LINK.matcher(plain);
        if (!link.find()) {
            return false;
        }
        Matcher role = CUE.matcher(plain);
        return !role.matches() || role.end(1) > link.start();
    }

    private static boolean isNote(String row) {
        return row.startsWith("(") || row.startsWith("[") || isItalic(row);
    }

    private static boolean isItalic(String row) {
        return row.length() > 2 && row.startsWith("*") && !row.startsWith("**")
            && row.endsWith("*");
    }

    private static Cue explicitCue(String row) {
        Matcher bold = BOLD_CUE.matcher(row);
        if (bold.matches()) {
            String name = bold.group(1).trim();
            String remaining = bold.group(2).trim();
            Matcher marked = CUE.matcher(name);
            if (marked.matches() && marked.group(3).isEmpty()) {
                name = marked.group(1).trim();
                remaining = (marked.group(2) == null ? "" : marked.group(2) + " ") + remaining;
            }
            Matcher note = ROLE_NOTE.matcher(name);
            if (note.matches()) {
                name = note.group(1).trim();
                remaining = note.group(2) + " " + remaining;
            }
            if (isRoleName(name)) {
                return new Cue(name, remaining.replaceFirst("^[:‒–—−-]\\s*", ""));
            }
        }

        Matcher separated = CUE.matcher(row);
        if (separated.matches()) {
            String name = separated.group(1).trim();
            // Printed plays often end the name with a period before the dash: "ORGON. — Text".
            if (!isRoleName(name) && name.endsWith(".") && separated.group(2) == null
                && !row.substring(separated.end(1)).trim().startsWith(":")) {
                String bare = name.substring(0, name.length() - 1).trim();
                if (isRoleName(bare) && bare.equals(bare.toUpperCase(TURKISH))
                    && !bare.equals(bare.toLowerCase(TURKISH))) {
                    name = bare;
                }
            }
            if (isRoleName(name)) {
                String aside = separated.group(2);
                return new Cue(name, (aside == null ? "" : aside + " ") + separated.group(3));
            }
        }
        return null;
    }

    private static Cue standaloneCue(String row) {
        Matcher note = ROLE_NOTE.matcher(row);
        String name = row;
        String aside = "";
        if (note.matches()) {
            name = note.group(1).trim();
            aside = note.group(2);
        }
        if (!isRoleName(name) || !name.equals(name.toUpperCase(TURKISH))
            || name.equals(name.toLowerCase(TURKISH))) {
            return null;
        }
        return new Cue(name, aside);
    }

    private static boolean nextIsDialogue(String[] rows, int index) {
        for (int i = index + 1; i < rows.length; i++) {
            String next = rows[i].trim();
            if (next.isEmpty() || isPageNumber(next) || isNote(next) || isNonScript(next)) {
                continue;
            }
            return !isHeading(next) && explicitCue(next) == null && standaloneCue(next) == null;
        }
        return false;
    }

    private static final class Cue {
        final String name;
        final String text;

        Cue(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }

    private static final class Parser {
        final List<Line> lines = new ArrayList<>();
        final List<Section> sections = new ArrayList<>();
        final Map<String, String> names = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();
        final StringBuilder dialogue = new StringBuilder();
        final StringBuilder note = new StringBuilder();
        final StringBuilder noteDelimiters = new StringBuilder();
        String act = "";
        String section = "";
        String role;
        int sectionStart;

        boolean continuesNote() {
            return noteDelimiters.length() > 0;
        }

        boolean awaitingDialogue() {
            return role != null && dialogue.length() == 0;
        }

        boolean hasRole(String name) {
            return names.containsKey(roleKey(name));
        }

        void startSection(String title) {
            String folded = fold(title);
            if (ACT.matcher(folded).matches()) {
                if (!fold(act).equals(folded)) {
                    finishSection();
                    act = title;
                    section = title;
                }
            } else if (END.matcher(folded).matches()) {
                finishSection();
                section = act;
            } else {
                String next = act.isEmpty() ? title : act + SECTION_JOIN + title;
                if (!fold(section).equals(fold(next))) {
                    finishSection();
                    section = next;
                }
            }
        }

        void startCue(Cue cue) {
            flushDialogue();
            String key = roleKey(cue.name);
            if (!names.containsKey(key)) {
                names.put(key, cue.name);
            }
            role = names.get(key);
            addText(cue.text);
        }

        void addText(String text) {
            if (isItalic(text) && !continuesNote()) {
                notes.add(text.substring(1, text.length() - 1));
                return;
            }
            if (role == null && !isNote(text) && !continuesNote()) {
                if (!section.isEmpty() && !text.isEmpty()) {
                    notes.add(text);
                }
                return;
            }
            for (int i = 0; i < text.length(); i++) {
                char character = text.charAt(i);
                if (character == '(' || character == '[') {
                    noteDelimiters.append(character == '(' ? ')' : ']');
                    note.append(character);
                } else if (continuesNote()) {
                    note.append(character);
                    int last = noteDelimiters.length() - 1;
                    if (character == noteDelimiters.charAt(last)) {
                        noteDelimiters.setLength(last);
                        if (!continuesNote()) {
                            notes.add(note.toString());
                            note.setLength(0);
                            appendSpace(dialogue);
                        }
                    }
                } else if (role != null) {
                    dialogue.append(character);
                }
            }
            if (continuesNote()) {
                appendSpace(note);
            } else {
                appendSpace(dialogue);
            }
        }

        void flushDialogue() {
            String spoken = dialogue.toString().trim().replaceAll("[ \\t]+", " ");
            if (role != null && !spoken.isEmpty()) {
                lines.add(new Line(role, spoken, section, notes));
                notes.clear();
            }
            dialogue.setLength(0);
        }

        void finishSection() {
            if (note.length() > 0) {
                notes.add(note.toString().trim());
                note.setLength(0);
                noteDelimiters.setLength(0);
            }
            flushDialogue();
            if (lines.size() > sectionStart) {
                if (!notes.isEmpty()) {
                    Line last = lines.get(lines.size() - 1);
                    List<String> allNotes = new ArrayList<>(last.notes);
                    allNotes.addAll(notes);
                    lines.set(lines.size() - 1, new Line(last.role, last.text, last.section, allNotes));
                }
                sections.add(new Section(section, sectionStart, lines.size()));
            }
            sectionStart = lines.size();
            role = null;
            notes.clear();
        }

        Scene finish() {
            finishSection();
            return new Scene(lines, sections);
        }

        private static void appendSpace(StringBuilder value) {
            if (value.length() > 0 && value.charAt(value.length() - 1) != ' ') {
                value.append(' ');
            }
        }
    }

    /**
     * Writes lines back as script text that {@link #parse} reads into the same lines: headings
     * on their own rows, "ROLE: text" for dialogue and each line's notes in parentheses after it.
     */
    static String toText(List<Line> lines) {
        StringBuilder text = new StringBuilder();
        String act = "";
        String section = "";
        for (Line line : lines) {
            if (!line.section.equals(section)) {
                int split = line.section.indexOf(SECTION_JOIN);
                String lineAct = split < 0 ? line.section : line.section.substring(0, split);
                String scene = split < 0 ? "" : line.section.substring(split + SECTION_JOIN.length());
                boolean actHeading = split >= 0 || ACT.matcher(fold(lineAct)).matches();
                if (actHeading && !lineAct.equals(act)) {
                    blankLine(text);
                    text.append(lineAct).append('\n');
                    act = lineAct;
                }
                String own = actHeading ? scene : line.section;
                if (!own.isEmpty()) {
                    blankLine(text);
                    text.append(own).append('\n');
                }
                text.append('\n');
                section = line.section;
            }
            // The parser gives a line every note that follows it until the next cue.
            text.append(line.role).append(": ").append(balanced(line.text.trim())).append('\n');
            for (String note : line.notes) {
                String clean = balanced(note.trim());
                if (clean.isEmpty()) {
                    continue;
                }
                boolean wrapped = (clean.startsWith("(") && clean.endsWith(")"))
                    || (clean.startsWith("[") && clean.endsWith("]"));
                text.append(wrapped ? clean : "(" + clean + ")").append('\n');
            }
        }
        return text.toString().trim();
    }

    /**
     * An unclosed bracket would make the parser read every following row as part of one note,
     * so brackets that do not pair up are dropped.
     */
    static String balanced(String value) {
        StringBuilder open = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '(' || character == '[') {
                open.append(character == '(' ? ')' : ']');
            } else if (character == ')' || character == ']') {
                if (open.length() == 0 || open.charAt(open.length() - 1) != character) {
                    return value.replaceAll("[()\\[\\]]", "").replaceAll("\\s+", " ").trim();
                }
                open.setLength(open.length() - 1);
            }
        }
        return open.length() == 0 ? value : value.replaceAll("[()\\[\\]]", "").replaceAll("\\s+", " ").trim();
    }

    private static void blankLine(StringBuilder text) {
        if (text.length() > 0 && !text.toString().endsWith("\n\n")) {
            text.append('\n');
        }
    }

    static final class Line {
        final String role;
        final String text;
        final String section;
        final List<String> notes;

        private Line(String role, String text, String section, List<String> notes) {
            this.role = role;
            this.text = text;
            this.section = section;
            this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        }

        /** A line written or changed by the user in the line editor. */
        static Line of(String role, String text, String section, List<String> notes) {
            return new Line(role, text, section, notes);
        }
    }

    static final class Section {
        final String title;
        final int start;
        final int end;

        private Section(String title, int start, int end) {
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }
}
