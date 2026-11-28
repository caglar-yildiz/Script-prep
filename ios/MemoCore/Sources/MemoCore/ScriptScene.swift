import Foundation

/// A script read into lines, characters and sections. A line-for-line port of the Android
/// `ScriptScene` parser, so the same text gives the same roles, lines and scenes on both platforms.
public struct ScriptScene: Equatable, Sendable {
    public struct Line: Equatable, Hashable, Sendable {
        public let role: String
        public let text: String
        public let section: String
        public let notes: [String]

        public init(role: String, text: String, section: String, notes: [String]) {
            self.role = role
            self.text = text
            self.section = section
            self.notes = notes
        }
    }

    public struct Section: Equatable, Sendable {
        public let title: String
        public let start: Int
        public let end: Int
    }

    /// Joins an act and a scene heading in a section title, as in "I. PERDE · 2. Sahne".
    public static let sectionJoin = " · "

    public let lines: [Line]
    public let roles: [String]
    public let sections: [Section]

    init(lines: [Line], sections: [Section]) {
        self.lines = lines
        var seen = Set<String>()
        var unique: [String] = []
        for line in lines where seen.insert(line.role).inserted {
            unique.append(line.role)
        }
        roles = unique
        self.sections = sections
    }

    public static let empty = ScriptScene(lines: [], sections: [])

    /// The given lines, in script order and without duplicates.
    public func subset(_ indices: [Int]) -> ScriptScene {
        let chosen = Set(indices).sorted().filter { $0 >= 0 && $0 < lines.count }.map { lines[$0] }
        return ScriptScene(lines: chosen, sections: [])
    }

    /// The lines that pass a test, in order, as a scene of their own.
    public func only(_ keep: (Line) -> Bool) -> ScriptScene {
        ScriptScene(lines: lines.filter(keep), sections: [])
    }

    public func forSection(_ index: Int) -> ScriptScene {
        guard index >= 0 && index < sections.count else {
            return self
        }
        let section = sections[index]
        return ScriptScene(lines: Array(lines[section.start..<section.end]), sections: [])
    }

    // MARK: - Patterns

    private static let rolePattern = "[\\p{L}][\\p{L}\\p{M}\\p{N} #.’'ʼ-]{0,39}"
    private static let role = JRegex(rolePattern)
    private static let cue = JRegex(
        "^(" + rolePattern + "?)\\s*(\\([^()]*\\))?(?:\\s*:\\s*|\\s+[-‒–—−]\\s*|\\s*[‒–—−]\\s*)(.*)$"
    )
    private static let boldCue = JRegex("^\\*\\*(.+?)\\*\\*\\s*(.*)$")
    private static let roleNote = JRegex("^(.+?)\\s*(\\([^()]*\\))$")
    private static let number =
        "(?:\\d+|[IVXLCDM]+|BIR|IKI|U[CÇ]|DORT|BES|ALTI|YEDI|SEKIZ|DOKUZ|ON|"
        + "BIRINCI|IKINCI|UCUNCU|DORDUNCU|BESINCI|ALTINCI|YEDINCI|SEKIZINCI|DOKUZUNCU|ONUNCU|"
        + "ONE|TWO|THREE|FOUR|FIVE|SIX|SEVEN|EIGHT|NINE|TEN|FIRST|SECOND|THIRD|"
        + "PRIMERO|PRIMER|SEGUNDO|TERCERO|TERCER|CUARTO|QUINTO|"
        + "ERSTER|ERSTE|ZWEITER|ZWEITE|DRITTER|DRITTE|VIERTER|VIERTE|FUNFTER|FUNFTE|"
        + "PREMIER|PREMIERE|DEUXIEME|TROISIEME|QUATRIEME|CINQUIEME|"
        + "PRIMEIRO|PRIMEIRA|SEGUNDA|TERCEIRO|TERCEIRA|QUARTO|QUARTA|"
        + "PRIMO|SECONDO|TERZO|QUINTO)"
    private static let act = titledHeading("PERDE|ACT|ACTO|AKT|AUFZUG|ACTE|ATO|ATTO")
    private static let stage = titledHeading(
        "SAHNE|SCENE|TABLO|ESCENA|CUADRO|SZENE|AUFTRITT|BILD|TABLEAU|CENA|QUADRO|SCENA")
    private static let traditional = JRegex("^(?:MUKADDIME|MUHAVERE|FASIL|BITIS)[.:]?$")
    private static let screen = JRegex(
        "^(?:\\d+[A-Z]?\\s+)?(?:INT\\.?/EXT|I/E|IC\\s*/\\s*DIS|INT|EXT|EST|IC|DIS)[.\\s]+.+$")
    private static let end = JRegex(
        "^(?:(?:[IVXLCDM]+|\\d+)\\.?\\s+)?(?:PERDE SONU|SAHNE SONU|END OF (?:ACT|SCENE).*|"
        + "BLACKOUT|CURTAIN|FADE OUT|(?:CUT|DISSOLVE|FADE) TO|SON|END|KARARIR|"
        + "FIN|TELON|OSCURO|ENDE|VORHANG|RIDEAU|NOIR|FIM|PANO|FINE|SIPARIO|BUIO)[.!:]?$")
    /// A screenplay heading marked with a period or slash: "İç. Mutfak", "INT./EXT. CAR".
    private static let screenMarked = JRegex(
        "^(?:\\d+[A-Z]?\\s+)?(?:INT\\.?/EXT|I/E|IC\\s*/\\s*DIS|INT|EXT|EST|IC|DIS)[./].+$")
    /// End markers that cannot be an ordinary short line, so any case will do.
    private static let endPhrase = JRegex(
        "^(?:(?:[IVXLCDM]+|\\d+)\\.?\\s+)?(?:PERDE SONU|SAHNE SONU|END OF (?:ACT|SCENE).*|"
        + "FADE OUT|(?:CUT|DISSOLVE|FADE) TO|KARARIR)[.!:]?$")
    private static let exercise = JRegex("^ETKINLIK\\s+\\d+\\b")
    private static let link = JRegex("https?://", caseInsensitive: true)
    /// A dash at the start of a row, before a role name: "- AHMET: Text", "— AHMET — Text".
    private static let leadingDash = JRegex("^[-‒–—−]\\s*(?=\\p{L}|\\*\\*)")
    /// Labels before a colon in front matter and directions ("Yer: Bir köy kahvesi").
    private static let labels: Set<String> = [
        "NOT", "NOTLAR", "DIKKAT", "UYARI", "ORNEK", "KAYNAK", "YAZAN", "YAZAR", "CEVIREN", "CEVIRI",
        "KISILER", "OYUN KISILERI", "OYUNUN KISILERI", "DEKOR", "ZAMAN", "YER", "MEKAN", "KONU", "SURE",
        "NOTE", "NOTES", "AUTHOR", "TRANSLATOR", "TRANSLATION", "CHARACTERS", "CAST", "SETTING", "TIME",
        "PLACE", "SOURCE", "NOTA", "NOTAS", "PERSONAJES", "LUGAR", "TIEMPO", "ANMERKUNG", "PERSONEN", "ORT",
        "ZEIT", "PERSONNAGES", "LIEU", "TEMPS", "PERSONAGENS", "TEMPO", "PERSONAGGI", "LUOGO",
    ]
    private static let page = JRegex("^(?:(?:SAYFA|PAGE|PAGINA|SEITE)\\s+)?\\d{1,4}(?:\\s*/\\s*\\d{1,4}|\\.)?$")
    private static let spacesTabs = JRegex("[ \\t]+")
    private static let whitespace = JRegex("\\s+")
    private static let closingSpace = JRegex("\\s+([)\\]])")
    private static let separatorStart = JRegex("^[:‒–—−-]\\s*")
    private static let brackets = JRegex("[()\\[\\]]")

    private static func titledHeading(_ label: String) -> JRegex {
        JRegex("^(?:(?:" + label + ")(?:\\s+" + number + ")?|"
            + number + "\\.?\\s+(?:" + label + "))(?:\\s*[:.‒–—−-]\\s*.*)?$")
    }

    // MARK: - Solo texts

    public static func parseSolo(_ source: String, narrator: String, byLine: Bool) -> ScriptScene {
        let rows = JText.rows(source.replacingOccurrences(of: "\u{00a0}", with: " "), keepTrailing: true)
        var lines: [Line] = []
        var paragraph = ""
        func addLine() {
            if !paragraph.isEmpty {
                lines.append(Line(role: narrator, text: paragraph, section: "", notes: []))
                paragraph = ""
            }
        }
        for raw in rows {
            var text = JText.trim(raw.replacingOccurrences(of: "**", with: ""))
            if JText.length(text) > 2 && text.hasPrefix("*") && text.hasSuffix("*") {
                text = JText.trim(JText.sub(text, 1, JText.length(text) - 1))
            }
            if !text.isEmpty {
                if !paragraph.isEmpty {
                    paragraph += " "
                }
                paragraph += text
            }
            if byLine || text.isEmpty {
                addLine()
            }
        }
        addLine()
        if lines.isEmpty {
            return ScriptScene(lines: lines, sections: [])
        }
        return ScriptScene(lines: lines, sections: [Section(title: "", start: 0, end: lines.count)])
    }

    // MARK: - Scripts with roles

    public static func parse(_ source: String) -> ScriptScene {
        let rows = JText.rows(source.replacingOccurrences(of: "\u{00a0}", with: " "), keepTrailing: false)
        var standaloneCounts: [String: Int] = [:]
        // How often each name is written as a cue; a name in a weak cue counts once it repeats.
        var cueCounts: [String: Int] = [:]
        var found: [Cue] = []
        for raw in rows {
            let row = JText.trim(raw)
            if heading(row) == nil && !isNote(row) && !isPageNumber(row) && !isNonScript(row) {
                if let cue = cueOf(row) {
                    if !cue.label {
                        cueCounts[roleKey(cue.name), default: 0] += 1
                        found.append(cue)
                    }
                } else if let alone = standaloneCue(withoutLeadingDash(row)) {
                    standaloneCounts[roleKey(alone.name), default: 0] += 1
                }
            }
        }
        var hasExplicitCues = false
        // A weak style used by two or more characters who speak more than once is how this text
        // writes cues, so a character in that style who speaks only once counts too.
        var repeatingByStyle: [Int: Set<String>] = [:]
        for cue in found {
            let repeats = (cueCounts[roleKey(cue.name)] ?? 0) > 1
            hasExplicitCues = hasExplicitCues || !cue.weak || repeats
            if cue.weak && repeats {
                repeatingByStyle[cue.style, default: []].insert(roleKey(cue.name))
            }
        }
        let trustedStyles = Set(repeatingByStyle.filter { $0.value.count > 1 }.keys)

        let parser = Parser()
        var previousBlank = true
        for i in 0..<rows.count {
            let row = JText.trim(rows[i])
            if row.isEmpty {
                previousBlank = true
                continue
            }
            if isPageNumber(row) || isNonScript(row) {
                continue
            }
            if parser.continuesNote {
                let strong = cueOf(row)
                if heading(row) == nil && (strong == nil || strong!.weak || strong!.label) {
                    parser.addText(row)
                    previousBlank = false
                    continue
                }
                // A bracket left open by a typo or a lost line ends before a heading or a clear cue.
                parser.closeNote()
            }

            if let heading = heading(row) {
                parser.startSection(heading)
            } else if isNote(row) {
                parser.addText(row)
            } else {
                var cue = cueOf(row)
                if let labelled = cue, labelled.label {
                    parser.addText("(" + balanced(row) + ")")
                    previousBlank = false
                    continue
                }
                if let weak = cue, weak.weak, (cueCounts[roleKey(weak.name)] ?? 0) < 2,
                   !parser.hasRole(weak.name), !trustedStyles.contains(weak.style) {
                    cue = nil
                }
                if cue == nil, let candidate = standaloneCue(withoutLeadingDash(row)),
                   !parser.awaitingDialogue, nextIsDialogue(rows, i),
                   !hasExplicitCues || previousBlank || (standaloneCounts[roleKey(candidate.name)] ?? 0) > 1
                    || parser.hasRole(candidate.name) {
                    cue = candidate
                }
                if let cue {
                    parser.startCue(cue)
                } else {
                    parser.addText(row)
                }
            }
            previousBlank = false
        }
        return parser.finish()
    }

    public static func isHeading(_ text: String) -> Bool {
        heading(text) != nil
    }

    public static func isPageNumber(_ text: String) -> Bool {
        page.isMatch(JText.fold(JText.trim(text)))
    }

    public static func isRoleName(_ candidate: String) -> Bool {
        role.isMatch(candidate) && !candidate.hasSuffix(".") && whitespace.split(candidate).count <= 5
    }

    private static func heading(_ row: String) -> String? {
        var plain = row
        if plain.hasPrefix("**") && plain.hasSuffix("**") && JText.length(plain) > 4 {
            plain = JText.trim(JText.sub(plain, 2, JText.length(plain) - 2))
        }
        let folded = JText.fold(plain)
        if act.isMatch(folded) || stage.isMatch(folded) || traditional.isMatch(folded) {
            return plain
        }
        // "İç çeker." and "Dış kapı açılır." are directions; a screenplay heading is in capitals
        // or marks İÇ/DIŞ/INT/EXT with a period.
        if screen.isMatch(folded) && (isCapitals(plain) || screenMarked.isMatch(folded)) {
            return plain
        }
        // "Son." can be a whole line of dialogue; "SON" and "Perde sonu" end a scene.
        if end.isMatch(folded) && (isCapitals(plain) || endPhrase.isMatch(folded)) {
            return plain
        }
        return nil
    }

    static func roleKey(_ value: String) -> String {
        whitespace.replaceAll(JText.lowerTurkish(value), " ")
    }

    private static func isNonScript(_ row: String) -> Bool {
        let plain = row.replacingOccurrences(of: "**", with: "")
        if exercise.contains(JText.fold(plain)) {
            return true
        }
        guard let found = link.find(plain) else {
            return false
        }
        guard let role = cue.matches(plain) else {
            return true
        }
        return role.end(1) > found.start()
    }

    private static func isNote(_ row: String) -> Bool {
        row.hasPrefix("(") || row.hasPrefix("[") || isItalic(row)
    }

    static func isItalic(_ row: String) -> Bool {
        JText.length(row) > 2 && row.hasPrefix("*") && !row.hasPrefix("**") && row.hasSuffix("*")
    }

    private static func explicitCue(_ row: String) -> Cue? {
        if let bold = boldCue.matches(row) {
            var name = JText.trim(bold.string(1))
            var remaining = JText.trim(bold.string(2))
            if let marked = cue.matches(name), marked.string(3).isEmpty {
                name = JText.trim(marked.string(1))
                remaining = (marked.group(2).map { $0 + " " } ?? "") + remaining
            }
            if let note = roleNote.matches(name) {
                name = JText.trim(note.string(1))
                remaining = note.string(2) + " " + remaining
            }
            if isRoleName(name) {
                return Cue(name: name, text: separatorStart.replaceFirst(remaining, ""), style: Cue.strong,
                           label: labels.contains(JText.fold(name)))
            }
        }

        if let separated = cue.matches(row) {
            var name = JText.trim(separated.string(1))
            let separatorFrom = separated.group(2) == nil ? separated.end(1) : separated.end(2)
            let separator = JText.trim(JText.sub(row, separatorFrom, separated.start(3)))
            let colon = separator.hasPrefix(":")
            let colonAt = JText.indexOf(row, ":", from: separated.end(1))
            if colon && colonAt > 0 && colonAt + 1 < JText.length(row)
                && JText.isDigit(JText.unit(row, colonAt - 1)) && JText.isDigit(JText.unit(row, colonAt + 1)) {
                // "Saat 12:30'da gel." is a clock time, not a name and a line.
                return nil
            }
            var style = Cue.strong
            // Printed plays often end the name with a period before the dash: "ORGON. — Text".
            if !isRoleName(name) && name.hasSuffix(".") && separated.group(2) == nil && !colon {
                let bare = JText.trim(JText.sub(name, 0, JText.length(name) - 1))
                if isRoleName(bare) && bare != JText.lowerTurkish(bare) {
                    style = isCapitals(bare) ? Cue.strong : Cue.periodDash
                    name = bare
                }
            }
            if isRoleName(name) {
                // A dash after an ordinary word is as likely a sentence as a cue: "Evet - dedi Ali".
                if style == Cue.strong && !colon && !isCapitals(name) {
                    style = Cue.dash
                }
                let aside = separated.group(2)
                return Cue(name: name, text: (aside.map { $0 + " " } ?? "") + separated.string(3), style: style,
                           label: colon && labels.contains(JText.fold(name)))
            }
        }
        return nil
    }

    /// The cue a row starts with, if any. A dash before the name ("- AHMET: Text") is allowed;
    /// the cue is then weak unless the name is in capitals.
    private static func cueOf(_ row: String) -> Cue? {
        let direct = explicitCue(row)
        let bare = withoutLeadingDash(row)
        if direct != nil || bare == row {
            return direct
        }
        guard let cue = explicitCue(bare) else {
            return nil
        }
        return Cue(name: cue.name, text: cue.text,
                   style: cue.weak || !isCapitals(cue.name) ? Cue.leadingDash : Cue.strong, label: cue.label)
    }

    private static func withoutLeadingDash(_ row: String) -> String {
        leadingDash.replaceFirst(row, "")
    }

    static func isCapitals(_ name: String) -> Bool {
        name == JText.upperTurkish(name) && name != JText.lowerTurkish(name)
    }

    private static func standaloneCue(_ row: String) -> Cue? {
        var name = row
        var aside = ""
        if let note = roleNote.matches(row) {
            name = JText.trim(note.string(1))
            aside = note.string(2)
        }
        if !isRoleName(name) || name != JText.upperTurkish(name) || name == JText.lowerTurkish(name) {
            return nil
        }
        return Cue(name: name, text: aside, style: Cue.strong, label: false)
    }

    private static func nextIsDialogue(_ rows: [String], _ index: Int) -> Bool {
        var i = index + 1
        while i < rows.count {
            let next = JText.trim(rows[i])
            i += 1
            if next.isEmpty || isPageNumber(next) || isNote(next) || isNonScript(next) {
                continue
            }
            return !isHeading(next) && cueOf(next) == nil && standaloneCue(withoutLeadingDash(next)) == nil
        }
        return false
    }

    fileprivate struct Cue {
        static let strong = 0
        /// "Ali - Text": an ordinary word before a dash.
        static let dash = 1
        /// "Orgon. — Text" with the name not in capitals.
        static let periodDash = 2
        /// "– Ahmet: Text": a dash before a name not in capitals.
        static let leadingDash = 3

        let name: String
        let text: String
        /// How the cue is written; any style but `strong` could be an ordinary sentence.
        let style: Int
        /// "Yer:", "Not:": a label kept as a direction.
        let label: Bool

        /// Taken as a cue only when the name repeats or its style is how the text writes cues.
        var weak: Bool {
            style != Cue.strong
        }
    }

    fileprivate final class Parser {
        var lines: [Line] = []
        var sections: [Section] = []
        var names: [String: String] = [:]
        var notes: [String] = []
        var dialogue: [unichar] = []
        var note: [unichar] = []
        var noteDelimiters: [unichar] = []
        var act = ""
        var section = ""
        var role: String?
        var sectionStart = 0

        var continuesNote: Bool {
            !noteDelimiters.isEmpty
        }

        /// Closes brackets left open, keeping what was in them as a note.
        func closeNote() {
            for delimiter in noteDelimiters.reversed() {
                note.append(delimiter)
            }
            noteDelimiters.removeAll()
            let closed = JText.trim(ScriptScene.closingSpace.replaceAll(string(note), "$1"))
            note.removeAll()
            if !closed.isEmpty {
                notes.append(closed)
            }
            appendSpace(&dialogue)
        }

        var awaitingDialogue: Bool {
            role != nil && dialogue.isEmpty
        }

        func hasRole(_ name: String) -> Bool {
            names[ScriptScene.roleKey(name)] != nil
        }

        func startSection(_ title: String) {
            let folded = JText.fold(title)
            if ScriptScene.act.isMatch(folded) {
                if JText.fold(act) != folded {
                    finishSection()
                    act = title
                    section = title
                }
            } else if ScriptScene.end.isMatch(folded) {
                finishSection()
                section = act
            } else {
                let next = act.isEmpty ? title : act + ScriptScene.sectionJoin + title
                if JText.fold(section) != JText.fold(next) {
                    finishSection()
                    section = next
                }
            }
        }

        func startCue(_ cue: Cue) {
            flushDialogue()
            let key = ScriptScene.roleKey(cue.name)
            if names[key] == nil {
                names[key] = cue.name
            }
            role = names[key]
            addText(cue.text)
        }

        func addText(_ text: String) {
            if ScriptScene.isItalic(text) && !continuesNote {
                notes.append(JText.sub(text, 1, JText.length(text) - 1))
                return
            }
            if role == nil && !ScriptScene.isNote(text) && !continuesNote {
                if !section.isEmpty && !text.isEmpty {
                    notes.append(text)
                }
                return
            }
            let open = unichar(UInt8(ascii: "("))
            let square = unichar(UInt8(ascii: "["))
            let close = unichar(UInt8(ascii: ")"))
            let squareClose = unichar(UInt8(ascii: "]"))
            for character in Array(text.utf16) {
                if character == open || character == square {
                    noteDelimiters.append(character == open ? close : squareClose)
                    note.append(character)
                } else if continuesNote {
                    note.append(character)
                    if character == noteDelimiters[noteDelimiters.count - 1] {
                        noteDelimiters.removeLast()
                        if !continuesNote {
                            notes.append(string(note))
                            note.removeAll()
                            appendSpace(&dialogue)
                        }
                    }
                } else if role != nil {
                    dialogue.append(character)
                }
            }
            if continuesNote {
                appendSpace(&note)
            } else {
                appendSpace(&dialogue)
            }
        }

        func flushDialogue() {
            let spoken = ScriptScene.spacesTabs.replaceAll(JText.trim(string(dialogue)), " ")
            if let role, !spoken.isEmpty {
                lines.append(Line(role: role, text: spoken, section: section, notes: notes))
                notes.removeAll()
            }
            dialogue.removeAll()
        }

        func finishSection() {
            if !note.isEmpty {
                notes.append(JText.trim(string(note)))
                note.removeAll()
                noteDelimiters.removeAll()
            }
            flushDialogue()
            if lines.count > sectionStart {
                if !notes.isEmpty {
                    let last = lines[lines.count - 1]
                    lines[lines.count - 1] = Line(role: last.role, text: last.text, section: last.section,
                                                  notes: last.notes + notes)
                }
                sections.append(Section(title: section, start: sectionStart, end: lines.count))
            }
            sectionStart = lines.count
            role = nil
            notes.removeAll()
        }

        func finish() -> ScriptScene {
            finishSection()
            return ScriptScene(lines: lines, sections: sections)
        }

        private func string(_ units: [unichar]) -> String {
            String(utf16CodeUnits: units, count: units.count)
        }

        private func appendSpace(_ value: inout [unichar]) {
            let space = unichar(UInt8(ascii: " "))
            if let last = value.last, last != space {
                value.append(space)
            }
        }
    }

    // MARK: - Writing back

    /// Writes lines back as script text that `parse` reads into the same lines: headings on
    /// their own rows, "ROLE: text" for dialogue and each line's notes in parentheses after it.
    public static func toText(_ lines: [Line]) -> String {
        var text = ""
        var act = ""
        var section = ""
        func blankLine() {
            if !text.isEmpty && !text.hasSuffix("\n\n") {
                text += "\n"
            }
        }
        for line in lines {
            if line.section != section {
                let split = JText.indexOf(line.section, sectionJoin)
                let lineAct = split < 0 ? line.section : JText.sub(line.section, 0, split)
                let scene = split < 0 ? "" : JText.sub(line.section, split + JText.length(sectionJoin))
                let actHeading = split >= 0 || ScriptScene.act.isMatch(JText.fold(lineAct))
                if actHeading && lineAct != act {
                    blankLine()
                    text += lineAct + "\n"
                    act = lineAct
                }
                let own = actHeading ? scene : line.section
                if !own.isEmpty {
                    blankLine()
                    text += own + "\n"
                }
                text += "\n"
                section = line.section
            }
            // The parser gives a line every note that follows it until the next cue.
            text += line.role + ": " + balanced(JText.trim(line.text)) + "\n"
            for note in line.notes {
                let clean = balanced(JText.trim(note))
                if clean.isEmpty {
                    continue
                }
                let wrapped = (clean.hasPrefix("(") && clean.hasSuffix(")"))
                    || (clean.hasPrefix("[") && clean.hasSuffix("]"))
                text += (wrapped ? clean : "(" + clean + ")") + "\n"
            }
        }
        return JText.trim(text)
    }

    /// An unclosed bracket would make the parser read every following row as part of one note,
    /// so brackets that do not pair up are dropped.
    public static func balanced(_ value: String) -> String {
        var open: [Character] = []
        for character in value {
            if character == "(" || character == "[" {
                open.append(character == "(" ? ")" : "]")
            } else if character == ")" || character == "]" {
                if open.last != character {
                    return JText.trim(whitespace.replaceAll(brackets.replaceAll(value, ""), " "))
                }
                open.removeLast()
            }
        }
        return open.isEmpty ? value : JText.trim(whitespace.replaceAll(brackets.replaceAll(value, ""), " "))
    }
}
