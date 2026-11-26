import XCTest
@testable import MemoCore
#if canImport(Compression)
import Compression
#endif

/// The same cases as the Android `OcrTextTest`, `ScriptFormatsTest` and `TextColumnsTest`.
final class OcrTextTests: XCTestCase {
    private let width = 2000

    /// Lines of one book page, 40 px apart, starting at `left`.
    private func page(_ left: Int, _ texts: String...) -> [OcrText.Line] {
        texts.enumerated().map { i, text in
            let top = 100 + i * 40
            return OcrText.Line(text: text, left: left, top: top, right: left + 800, bottom: top + 30)
        }
    }

    func testReadsTheLeftPageOfASpreadBeforeTheRightOne() {
        var lines = page(80, "12 KUŞLAR", "AYŞE. — Sabah oldu mu?", "Pencereyi aç.", "MEHMET. — Daha erken.", "Uyu biraz.")
        lines += page(1100, "Birinci Perde 13", "AYŞE. — Kuşlar ötüyor.", "MEHMET. — Duyuyorum.",
                      "AYŞE. — Kalk o zaman.", "MEHMET. — Peki.")
        // The recogniser does not promise any order.
        var generator = SeededGenerator(seed: 7)
        lines.shuffle(using: &generator)

        let rows = OcrText.pageRows(lines, width: width)

        XCTAssertEqual(["AYŞE: Sabah oldu mu?", "Pencereyi aç.", "MEHMET: Daha erken.", "Uyu biraz.",
                        "", "AYŞE: Kuşlar ötüyor.", "MEHMET: Duyuyorum.", "AYŞE: Kalk o zaman.", "MEHMET: Peki."], rows)
    }

    func testJoinsPiecesOfOnePrintedLine() {
        var lines = page(80, "Bir", "İki", "Üç", "Dört", "Beş")
        lines.append(OcrText.Line(text: "AYŞE. —", left: 80, top: 300, right: 300, bottom: 330))
        lines.append(OcrText.Line(text: "Geldim.", left: 320, top: 302, right: 600, bottom: 332))

        let rows = OcrText.pageRows(lines, width: width)

        XCTAssertEqual("AYŞE: Geldim.", rows.last)
    }

    func testJoinsAWordSplitAtTheLineEnd() {
        XCTAssertEqual(["ALİ: Bu iş zorbalıkla yürümez."], OcrText.clean(["ALİ. — Bu iş zor-", "balıkla yürümez."]))
    }

    func testKeepsAHyphenBeforeACapital() {
        XCTAssertEqual(["ALİ: Gel-", "Ankara'ya gidelim."], OcrText.clean(["ALİ: Gel-", "Ankara'ya gidelim."]))
    }

    func testRemovesSpecksAroundLines() {
        XCTAssertEqual("ORGON: Ne?", OcrText.cleanRow("© ORGON. — Ne?"))
        XCTAssertEqual("MADAM PERNELLE: Evet.", OcrText.cleanRow("'MADAM PERNELLE, —. Evet."))
        XCTAssertEqual("Allah, Allah!", OcrText.cleanRow(", Allah, Allah! N"))
        XCTAssertEqual("kalk.", OcrText.cleanRow("- kalk."))
    }

    func testRepairsMisreadSceneNumbers() {
        XCTAssertEqual("SAHNE II", OcrText.cleanRow("SAHNE Il"))
        XCTAssertEqual("SAHNE III", OcrText.cleanRow("SAHNE HI"))
        XCTAssertEqual("SAHNE 11", OcrText.cleanRow("SAHNE 11"))
        XCTAssertEqual("SAHNE IV", OcrText.cleanRow("SAHNE IV"))
    }

    func testTurnsACastListIntoANote() {
        XCTAssertEqual(["SAHNE III", "*AYŞE, MEHMET, DOKTOR, KOMŞU.*", "AYŞE: Buyurun."],
                       OcrText.clean(["SAHNE III", "AYŞE, MEHMET,", "DOKTOR;,; KOMŞU.", "AYŞE. — Buyurun."]))
    }

    func testDropsRunningHeadsAndPageNumbers() {
        XCTAssertTrue(OcrText.isRunningHead("84 TARTUFFE"))
        XCTAssertTrue(OcrText.isRunningHead("Perde V Sahne III 85"))
        XCTAssertTrue(OcrText.isRunningHead("- 12 -"))
        XCTAssertFalse(OcrText.isRunningHead("AYŞE: Saat 12 oldu, kalkmamız gerek, geç kalıyoruz artık."))
    }

    func testUnifiesRoleNamesMisreadByOneLetter() {
        let text = "*AYŞE, MEHMET*\nMEHMET: Bir.\nMEHMFT: İki.\nMEHMET: Üç.\nAYŞE: Dört.\nAYSE: Beş."

        let scene = ScriptScene.parse(OcrText.unifyRoles(text))

        XCTAssertEqual(["MEHMET", "AYŞE"], scene.roles)
    }

    func testKeepsDifferentShortNames() {
        let text = "ALİ: Bir.\nALİ: İki.\nVELİ: Üç."

        XCTAssertEqual(text, OcrText.unifyRoles(text))
    }

    func testFindsARecognisedLineInTheCleanedText() {
        let text = "AYŞE: Bu iş zorbalıkla yürümez.\nMEHMET: Neden?\nAYŞE: Bilmem."

        XCTAssertEqual(0, OcrText.locate(text, "AYŞE. — Bu iş zor-", from: 0)?.0)
        let found = OcrText.locate(text, "© MEHMET. — Neden?", from: 0)!
        XCTAssertEqual("MEHMET: Neden?", JText.sub(text, found.0, found.1))
        XCTAssertNil(OcrText.locate(text, "Hiç olmayan bir satır", from: 0))
    }

    func testFindsALineWhoseCueWasRenamed() {
        let text = "CLÉANTE: Sakın boş bulunayım demeyin."

        let found = OcrText.locate(text, "CLBANTE. — Sakın boş bulunayım demeyin.", from: 0)!

        XCTAssertEqual("Sakın boş bulunayım demeyin.", JText.sub(text, found.0, found.1))
    }

    func testPrefersTheMatchAfterThePreviousOne() {
        let text = "ALİ: Evet.\nVELİ: Evet."

        XCTAssertEqual(JText.lastIndexOf(text, "Evet."), OcrText.locate(text, "Evet.", from: 6)?.0)
        XCTAssertEqual(JText.indexOf(text, "Evet."), OcrText.locate(text, "Evet.", from: 0)?.0)
    }
}

final class ScriptFormatsTests: XCTestCase {
    private let namespace = "xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\""

    private func run(_ text: String, bold: Bool, italic: Bool) -> String {
        let style = (bold ? "<w:b/>" : "") + (italic ? "<w:i/>" : "")
        return "<w:r>" + (style.isEmpty ? "" : "<w:rPr>" + style + "</w:rPr>")
            + "<w:t xml:space=\"preserve\">" + text + "</w:t></w:r>"
    }

    private func document(_ body: String) -> Data {
        Data(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document " + namespace + "><w:body>" + body
            + "</w:body></w:document>").utf8)
    }

    private var body: String {
        "<w:p>" + run("1. Sahne", bold: false, italic: false) + "</w:p>"
            + "<w:p>" + run("AYŞE", bold: true, italic: false) + run(" Kapıyı kim çaldı?", bold: false, italic: false) + "</w:p>"
            + "<w:p>" + run("(Pencereye gider.)", bold: false, italic: true) + "</w:p>"
            + "<w:p/>"
            + "<w:p>" + run("Mehmet:", bold: true, italic: false) + run(" Ben", bold: false, italic: false)
            + run(" geldim.", bold: false, italic: false) + "</w:p>"
            + "<w:p>" + run("Bir", bold: false, italic: false)
            + "<w:r><w:rPr><w:b w:val=\"0\"/></w:rPr><w:t> iki</w:t></w:r></w:p>"
    }

    private let expectedWord = "1. Sahne\n**AYŞE** Kapıyı kim çaldı?\n*(Pencereye gider.)*\n\n**Mehmet:** Ben geldim.\nBir iki"

    func testReadsBoldNamesAndItalicDirectionsFromWord() throws {
        let text = try ScriptFormats.docx(ZipBuilder.archive([("[Content_Types].xml", Data("<Types/>".utf8), false),
                                                              ("word/document.xml", document(body), false)]))

        XCTAssertEqual(expectedWord, text)
        let scene = ScriptScene.parse(text)
        XCTAssertEqual(["AYŞE", "Mehmet"], scene.roles)
        XCTAssertEqual("Ben geldim. Bir iki", scene.lines[1].text)
        XCTAssertEqual(.docx, ScriptFormats.sniff(ZipBuilder.archive([("a", Data(), false)])))
    }

    #if canImport(Compression)
    func testReadsACompressedWordDocument() throws {
        let text = try ScriptFormats.docx(ZipBuilder.archive([("word/document.xml", document(body), true)]))

        XCTAssertEqual(expectedWord, text)
    }
    #endif

    func testReadsAFountainScreenplay() {
        let source = "Title: Kuşlar\nAuthor: Biri\n\n"
            + "INT. MUTFAK - SABAH\n\n"
            + "Ayşe pencereyi açar. *Kuşlar* ötüyor.\n\n"
            + "AYŞE\n(fısıltıyla)\nSabah oldu.\nKalk artık.\n\n"
            + "MEHMET (CONT'D)\nDaha erken.\n\n"
            + "@McCLANE\nYippee.\n\n"
            + "CUT TO:\n\n"
            + "/* boneyard */[[a note]]\n"
            + "> SON <\n"

        let text = ScriptFormats.fountain(source)

        XCTAssertEqual("INT. MUTFAK - SABAH\n(Ayşe pencereyi açar. Kuşlar ötüyor.)\n\n"
            + "AYŞE:\n(fısıltıyla)\nSabah oldu.\nKalk artık.\n\nMEHMET:\nDaha erken.\n\nMcCLANE:\nYippee.\n(SON)", text)
        let scene = ScriptScene.parse(text)
        XCTAssertEqual(["AYŞE", "MEHMET", "McCLANE"], scene.roles)
        XCTAssertEqual("Sabah oldu. Kalk artık.", scene.lines[0].text)
        // Directions before a line are kept with it, in order.
        XCTAssertEqual(["(Ayşe pencereyi açar. Kuşlar ötüyor.)", "(fısıltıyla)"], scene.lines[0].notes)
    }

    func testReadsAFinalDraftScript() throws {
        let xml = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\" ?>"
            + "<FinalDraft DocumentType=\"Script\" Version=\"5\"><Content>"
            + "<Paragraph Type=\"ScriptScene Heading\"><Text>INT. MUTFAK - SABAH</Text></Paragraph>"
            + "<Paragraph Type=\"Action\"><Text>Ayşe pencereyi </Text><Text Style=\"Bold\">açar</Text><Text>.</Text></Paragraph>"
            + "<Paragraph Type=\"Character\"><Text>AYŞE</Text></Paragraph>"
            + "<Paragraph Type=\"Parenthetical\"><Text>(fısıltıyla)</Text></Paragraph>"
            + "<Paragraph Type=\"Dialogue\"><Text>Sabah oldu.</Text></Paragraph>"
            + "<Paragraph Type=\"Character\"><Text>MEHMET (CONT'D)</Text></Paragraph>"
            + "<Paragraph Type=\"Dialogue\"><Text>Daha erken.</Text></Paragraph>"
            + "<Paragraph Type=\"Transition\"><Text>CUT TO:</Text></Paragraph>"
            + "</Content><TitlePage><Content><Paragraph><Text>Kuşlar</Text></Paragraph></Content></TitlePage>"
            + "</FinalDraft>"

        let text = try ScriptFormats.fdx(Data(xml.utf8))

        XCTAssertEqual("INT. MUTFAK - SABAH\n(Ayşe pencereyi açar.)\n\nAYŞE:\n(fısıltıyla)\nSabah oldu.\n\nMEHMET:\nDaha erken.",
                       text)
        XCTAssertEqual(["AYŞE", "MEHMET"], ScriptScene.parse(text).roles)
        XCTAssertEqual(.fdx, ScriptFormats.sniff(Data(xml.utf8)))
    }

    func testChoosesTheFormatFromTheFileName() {
        XCTAssertEqual(.docx, ScriptFormats.kindOf("Oyun.DOCX"))
        XCTAssertEqual(.fountain, ScriptFormats.kindOf("film.fountain"))
        XCTAssertEqual(.fdx, ScriptFormats.kindOf("film.fdx"))
        XCTAssertEqual(.text, ScriptFormats.kindOf("oyun.txt"))
        XCTAssertEqual(.text, ScriptFormats.kindOf(nil))
    }
}

final class TextColumnsTests: XCTestCase {
    private let width: Float = 842

    private func words(_ edges: Float...) -> [(Float, Float)] {
        stride(from: 0, to: edges.count, by: 2).map { (edges[$0], edges[$0 + 1]) }
    }

    func testFindsTheGutterOfABookSpread() {
        let rows = (0..<10).map { _ in words(40, 120, 130, 380, 460, 560, 570, 800) }

        let gutter = TextColumns.gutter(rows, width: width)

        XCTAssertTrue(gutter > 380 && gutter < 460)
    }

    func testKeepsOneColumnOfShortLines() {
        var rows = (0..<10).map { _ in words(60, 130, 140, 300) }
        // A page number on the right is not a second column.
        rows.append(words(780, 800))

        XCTAssertEqual(-1, TextColumns.gutter(rows, width: width))
    }

    func testKeepsOneColumnWhenLinesCrossTheMiddle() {
        let rows = (0..<10).map { i in i % 3 == 0 ? words(60, 300, 310, 700) : words(60, 300, 460, 700) }

        XCTAssertEqual(-1, TextColumns.gutter(rows, width: width))
    }

    func testNeedsEnoughLines() {
        XCTAssertEqual(-1, TextColumns.gutter([words(40, 300, 500, 800)], width: width))
    }
}

final class LibraryTests: XCTestCase {
    func testBackupRoundTripKeepsTextsAndVoices() throws {
        let defaults = UserDefaults(suiteName: "memo-tests-\(UUID().uuidString)")!
        let library = ScriptLibrary(defaults)
        let id = library.save(id: nil, title: "Bir", text: "ALİ: Selam.", category: .roleRehearsal, language: .turkish)
        library.save(id: nil, title: "İki", text: "Konuşma", category: .speech, language: .english)
        VoiceCast.save(defaults, key: id, cast: ["ALİ": VoiceCast.Part(voice: "", speaker: 3, tone: .high, pace: .fast)],
                       order: ["ALİ"])
        let backup = try library.exportJson()

        let other = UserDefaults(suiteName: "memo-tests-\(UUID().uuidString)")!
        let count = try ScriptLibrary(other).importJson(backup)

        XCTAssertEqual(2, count)
        XCTAssertEqual(["İki", "Bir"], ScriptLibrary(other).list().map(\.title))
        XCTAssertEqual(.speech, ScriptLibrary(other).list()[0].category)
        XCTAssertEqual(3, VoiceCast.load(other, key: id, roles: ["ALİ"], voices: [])["ALİ"]?.speaker)
    }

    func testReadsAnAndroidBackup() throws {
        let android = """
        {"app":"memo-everything","version":1,"texts":[{"id":"abc","title":"Oyun","text":"AYŞE: Merhaba.",
        "category":"POEM_OR_MONOLOGUE","language":"de"}],"voiceCasts":{}}
        """
        let defaults = UserDefaults(suiteName: "memo-tests-\(UUID().uuidString)")!

        XCTAssertEqual(1, try ScriptLibrary(defaults).importJson(android))
        let script = ScriptLibrary(defaults).list()[0]
        XCTAssertEqual("abc", script.id)
        XCTAssertEqual(.poemOrMonologue, script.category)
        XCTAssertEqual(.german, script.language)
    }

    func testRekeysAndRestores() {
        let defaults = UserDefaults(suiteName: "memo-tests-\(UUID().uuidString)")!
        let library = ScriptLibrary(defaults)
        let first = library.save(id: nil, title: "A", text: "x", category: .speech, language: .turkish)
        library.save(id: nil, title: "B", text: "y", category: .speech, language: .turkish)

        XCTAssertTrue(library.rekey(first, to: "ensemble:42", title: "A2"))
        XCTAssertEqual(["B", "A2"], library.list().map(\.title))
        let removed = library.list()[1]
        let index = library.delete(removed.id)
        library.restore(removed, at: index)
        XCTAssertEqual(["B", "A2"], library.list().map(\.title))
    }
}

final class RulesTests: XCTestCase {
    func testGuessesTheTextLanguage() {
        XCTAssertEqual(.turkish, LanguageGuess.guess("Ben bu akşam sana gelmek istiyorum ama çok yorgunum, "
            + "belki yarın daha iyi olur, ne dersin? Evet, bence de öyle."))
        XCTAssertEqual(.english, LanguageGuess.guess("I think that you are right and we should go now, "
            + "it is getting late and we have to be home soon."))
        XCTAssertNil(LanguageGuess.guess("Kısa bir metin."))
    }

    func testHearsMostOfALine() {
        let locale = Locale(identifier: "tr_TR")
        XCTAssertEqual(1, LineCoverage.coverage(expected: "Işık söndü mü?", heard: "isik sondu mu", locale: locale))
        XCTAssertEqual(0.5, LineCoverage.coverage(expected: "Bir iki üç dört", heard: "bir üç", locale: locale))
    }

    func testCleansInviteCodes() {
        XCTAssertEqual("ABC234", EnsembleRules.normalizeCode(" abc-234 "))
        XCTAssertEqual("ABC234", EnsembleRules.inviteCode(URL(string: "memoeverything://join/abc234")))
        XCTAssertEqual("ABC234", EnsembleRules.inviteCode(URL(string: "https://caglar-yildiz.github.io/Script-prep/join/?c=ABC234")))
        XCTAssertNil(EnsembleRules.inviteCode(URL(string: "memoeverything://join/ABC")))
        XCTAssertEqual(6, EnsembleRules.newCode().count)
    }

    func testLineKeyIgnoresSpacingAndCase() {
        XCTAssertEqual(EnsembleRules.lineKey(role: "AYŞE", text: "Merhaba   dünya"),
                       EnsembleRules.lineKey(role: "ayşe ", text: "merhaba dünya"))
        XCTAssertEqual(20, EnsembleRules.lineKey(role: "A", text: "b").count)
    }

    func testAssignsDifferentVoices() {
        let cast = VoiceCast.automatic(roles: ["A", "B", "C"], voices: ["v1", "v2"])

        XCTAssertEqual("", cast["A"]?.voice)
        XCTAssertEqual("v1", cast["B"]?.voice)
        XCTAssertEqual([0, 5, 1], ["A", "B", "C"].map { cast[$0]!.speaker })
    }
}

/// A repeatable shuffle for the page-order test.
struct SeededGenerator: RandomNumberGenerator {
    private var state: UInt64

    init(seed: UInt64) {
        state = seed
    }

    mutating func next() -> UInt64 {
        state = state &* 6_364_136_223_846_793_005 &+ 1_442_695_040_888_963_407
        return state
    }
}

/// Writes small ZIP archives for the Word tests.
enum ZipBuilder {
    static func archive(_ files: [(String, Data, Bool)]) -> Data {
        var output = Data()
        var central = Data()
        for (name, content, deflate) in files {
            let stored = deflate ? compress(content) : content
            let method: UInt16 = deflate ? 8 : 0
            let offset = UInt32(output.count)
            let nameBytes = Data(name.utf8)
            output.append(le32(0x0403_4b50))
            output.append(le16(20)); output.append(le16(0)); output.append(le16(method))
            output.append(le16(0)); output.append(le16(0)); output.append(le32(0))
            output.append(le32(UInt32(stored.count))); output.append(le32(UInt32(content.count)))
            output.append(le16(UInt16(nameBytes.count))); output.append(le16(0))
            output.append(nameBytes); output.append(stored)

            central.append(le32(0x0201_4b50))
            central.append(le16(20)); central.append(le16(20)); central.append(le16(0)); central.append(le16(method))
            central.append(le16(0)); central.append(le16(0)); central.append(le32(0))
            central.append(le32(UInt32(stored.count))); central.append(le32(UInt32(content.count)))
            central.append(le16(UInt16(nameBytes.count))); central.append(le16(0)); central.append(le16(0))
            central.append(le16(0)); central.append(le16(0)); central.append(le32(0)); central.append(le32(offset))
            central.append(nameBytes)
        }
        let centralOffset = UInt32(output.count)
        output.append(central)
        output.append(le32(0x0605_4b50))
        output.append(le16(0)); output.append(le16(0))
        output.append(le16(UInt16(files.count))); output.append(le16(UInt16(files.count)))
        output.append(le32(UInt32(central.count))); output.append(le32(centralOffset)); output.append(le16(0))
        return output
    }

    private static func compress(_ data: Data) -> Data {
        #if canImport(Compression)
        let source = [UInt8](data)
        var target = [UInt8](repeating: 0, count: source.count + 1024)
        let count = compression_encode_buffer(&target, target.count, source, source.count, nil, COMPRESSION_ZLIB)
        return Data(target[0..<count])
        #else
        return data
        #endif
    }

    private static func le16(_ value: UInt16) -> Data {
        Data([UInt8(value & 0xff), UInt8(value >> 8)])
    }

    private static func le32(_ value: UInt32) -> Data {
        Data([UInt8(value & 0xff), UInt8((value >> 8) & 0xff), UInt8((value >> 16) & 0xff), UInt8(value >> 24)])
    }
}
