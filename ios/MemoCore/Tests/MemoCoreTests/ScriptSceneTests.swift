import XCTest
@testable import MemoCore

/// The same cases as the Android `SceneTest`, so both apps read scripts alike.
final class SceneTests: XCTestCase {
    func testReadsColonDashAndStandaloneCues() {
        let scene = ScriptScene.parse("AYŞE: Merhaba.\nMEHMET — Selam.\n\nDOKTOR\nNasılsınız?")

        XCTAssertEqual(["AYŞE", "MEHMET", "DOKTOR"], scene.roles)
        XCTAssertEqual("Nasılsınız?", scene.lines[2].text)
    }

    func testReadsANamePrintedWithAPeriodBeforeTheDash() {
        let scene = ScriptScene.parse("ORGON. — Sormayın.\nM. LOYAL. — Günaydın.")

        XCTAssertEqual(["ORGON", "M. LOYAL"], scene.roles)
        XCTAssertEqual("Sormayın.", scene.lines[0].text)
    }

    func testDoesNotTakeASentenceBeforeADashForAName() {
        let scene = ScriptScene.parse("ALİ: Gitti. — Peki dedim.\nVELİ: Tamam.")

        XCTAssertEqual(["ALİ", "VELİ"], scene.roles)
        XCTAssertEqual("Gitti. — Peki dedim.", scene.lines[0].text)
    }

    func testJoinsWrappedLinesAndKeepsSections() {
        let scene = ScriptScene.parse("I. PERDE\n1. Sahne\nAYŞE: Bir\niki üç.\n(Çıkar.)\nMEHMET: Dört.")

        XCTAssertEqual("Bir iki üç.", scene.lines[0].text)
        XCTAssertEqual("I. PERDE" + ScriptScene.sectionJoin + "1. Sahne", scene.lines[0].section)
        XCTAssertEqual(["(Çıkar.)"], scene.lines[0].notes)
    }

    func testWritesLinesBackToTextThatReadsTheSame() {
        let scene = ScriptScene.parse("SAHNE I\nAYŞE: Bir (güler) iki.\nMEHMET: Üç.\n(Işıklar söner.)")

        let again = ScriptScene.parse(ScriptScene.toText(scene.lines))

        XCTAssertEqual(scene.roles, again.roles)
        XCTAssertEqual(scene.lines.count, again.lines.count)
        for i in 0..<scene.lines.count {
            XCTAssertEqual(scene.lines[i].text, again.lines[i].text)
            XCTAssertEqual(scene.lines[i].notes, again.lines[i].notes)
        }
    }

    func testReadsANameAfterADashAtTheStartOfARow() {
        let scene = ScriptScene.parse("- AHMET: Gel buraya.\n— AYŞE — Geliyorum.\n– Ahmet: Çabuk ol.\n— AYŞE\nTamam.")

        XCTAssertEqual(["AHMET", "AYŞE"], scene.roles)
        XCTAssertEqual(["Gel buraya.", "Geliyorum.", "Çabuk ol.", "Tamam."], scene.lines.map(\.text))
    }

    func testKeepsNovelStyleDialogueWithTheSpeaker() {
        let scene = ScriptScene.parse("AHMET: Bak.\n— Gel buraya.\n— Bak: şurada.")

        XCTAssertEqual(["AHMET"], scene.roles)
        XCTAssertEqual("Bak. — Gel buraya. — Bak: şurada.", scene.lines[0].text)
    }

    func testDoesNotTakeAClockTimeForACue() {
        let scene = ScriptScene.parse("ALİ: Yarın\nSaat 12:30'da gel.\nASKER 2: 3 kişi geldi.")

        XCTAssertEqual(["ALİ", "ASKER 2"], scene.roles)
        XCTAssertEqual("Yarın Saat 12:30'da gel.", scene.lines[0].text)
    }

    func testKeepsLabelsAsDirections() {
        let scene = ScriptScene.parse("I. PERDE\nYer: Bir köy kahvesi.\nAHMET: Çay!\nNot: Işık azalır.\nAYŞE: Geldi.")

        XCTAssertEqual(["AHMET", "AYŞE"], scene.roles)
        XCTAssertEqual(["(Yer: Bir köy kahvesi.)", "(Not: Işık azalır.)"], scene.lines[0].notes)
    }

    func testTakesAMixedCaseDashCueOnlyWhenTheNameRepeats() {
        let once = ScriptScene.parse("ALİ: Ne dedi?\nEvet - dedi Ali sonra.")
        let twice = ScriptScene.parse("Ali - Merhaba.\nVeli - Selam.\nAli - Nasılsın?\nVeli - İyiyim.")

        XCTAssertEqual(["ALİ"], once.roles)
        XCTAssertEqual("Ne dedi? Evet - dedi Ali sonra.", once.lines[0].text)
        XCTAssertEqual(["Ali", "Veli"], twice.roles)
    }

    func testReadsAMixedCaseNameWithAPeriodWhenItRepeats() {
        let scene = ScriptScene.parse("Orgon. — Sormayın.\nDorine. — Zavallı!\nOrgon. — Ne?\nDorine. — Evet.")

        XCTAssertEqual(["Orgon", "Dorine"], scene.roles)
    }

    func testTakesAOnceOnlySpeakerWhenTheTextWritesCuesThatWay() {
        let scene = ScriptScene.parse("Orgon. — Bir.\nCleante. — İki.\nOrgon. — Üç.\nCleante. — Dört.\nDorine. — Beş.")

        XCTAssertEqual(["Orgon", "Cleante", "Dorine"], scene.roles)
    }

    func testWritesAnyRoleBackToTextThatReadsTheSame() {
        let scene = ScriptScene.parse("Ali - Merhaba.\nAli - Nasılsın?\nASKER 2: 3 kişi geldi.\n- VELİ: Gel.")

        XCTAssertEqual(scene.roles, ScriptScene.parse(ScriptScene.toText(scene.lines)).roles)
    }

    func testTakesScreenplayHeadingsOnlyInCapitalsOrWithAPeriod() {
        XCTAssertTrue(ScriptScene.isHeading("İÇ. MUTFAK - GECE"))
        XCTAssertTrue(ScriptScene.isHeading("İç. Mutfak - Gece"))
        XCTAssertTrue(ScriptScene.isHeading("12 EXT. BAHÇE"))
        XCTAssertFalse(ScriptScene.isHeading("İç çeker."))
        XCTAssertFalse(ScriptScene.isHeading("Dış kapı açılır."))

        let scene = ScriptScene.parse("AYŞE: Peki.\nİç çeker.\nAHMET: Ne oldu?")
        XCTAssertEqual("Peki. İç çeker.", scene.lines[0].text)
    }

    func testEndsASceneOnlyWithAClearEndMarker() {
        XCTAssertTrue(ScriptScene.isHeading("SON"))
        XCTAssertTrue(ScriptScene.isHeading("Perde sonu."))
        XCTAssertTrue(ScriptScene.isHeading("I. Perde Sonu"))
        XCTAssertFalse(ScriptScene.isHeading("Son."))
        XCTAssertFalse(ScriptScene.isHeading("Fin."))
    }

    func testClosesABracketLeftOpenAtTheNextHeadingOrCue() {
        let scene = ScriptScene.parse("SAHNE 1\nAHMET: Bir (güler\nSAHNE 2\nAYŞE: İki (oturur\nAHMET: Üç.")

        XCTAssertEqual(2, scene.sections.count)
        XCTAssertEqual(["AHMET", "AYŞE"], scene.roles)
        XCTAssertEqual(["(güler)"], scene.lines[0].notes)
        XCTAssertEqual("Üç.", scene.lines[2].text)
    }

    func testKeepsOnlyTheLinesThatPassATest() {
        let scene = ScriptScene.parse("AYŞE: Bir.\nMEHMET: İki.\nAYŞE: Üç.")
        let ayse = scene.only { $0.role == "AYŞE" }

        XCTAssertEqual(["Bir.", "Üç."], ayse.lines.map(\.text))
        XCTAssertEqual(["AYŞE"], ayse.roles)
    }

    func testReadsTheSampleSceneOfEveryLanguage() {
        let sample = "ECE: Let’s check the lights once more before rehearsal.\nMERT: I’m ready. Where do I enter?\n"
            + "(Ece points to the left side of the stage.)\nECE: From the left. Come in when I close the curtain."
        let scene = ScriptScene.parse(sample)

        XCTAssertEqual(["ECE", "MERT"], scene.roles)
        XCTAssertEqual(["(Ece points to the left side of the stage.)"], scene.lines[1].notes)
    }

    func testSplitsSoloTextsIntoParagraphsOrLines() {
        let speech = ScriptScene.parseSolo("Bir iki\nüç.\n\nDört.", narrator: "Sen", byLine: false)
        let poem = ScriptScene.parseSolo("Bir iki\nüç.\n\nDört.", narrator: "Sen", byLine: true)

        XCTAssertEqual(["Bir iki üç.", "Dört."], speech.lines.map(\.text))
        XCTAssertEqual(["Bir iki", "üç.", "Dört."], poem.lines.map(\.text))
        XCTAssertEqual(["Sen"], poem.roles)
    }

    func testShowsHintsAndInitials() {
        XCTAssertEqual("Merhaba …", LineVisibility.hint.visibleText("Merhaba dünya, nasılsın?"))
        XCTAssertEqual("M…", LineVisibility.hint.visibleText("Merhaba!"))
        XCTAssertEqual("M· d·, n·?", LineVisibility.initials.visibleText("Merhaba dünya, nasılsın?"))
        XCTAssertEqual("", LineVisibility.hidden.visibleText("Merhaba"))
    }
}
