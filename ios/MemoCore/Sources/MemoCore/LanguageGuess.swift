import Foundation

/// Guesses which supported language a text is written in from its common words and letters.
public enum LanguageGuess {
    private static let word = JRegex("[\\p{L}']+")
    private static let minimumWords = 12
    private static let words: [ScriptLanguage: Set<String>] = [
        .turkish: list("ve bir bu da de ne ben sen o için ile mi mı mu mü değil var yok gibi çok ama "
            + "şey daha nasıl neden evet hayır benim beni seni sana bana şimdi hiç kadar sonra önce çünkü"),
        .english: list("the and you to of is it that in what i me my your be have not this are do "
            + "we with for on just no yes was he she they will can don't i'm it's"),
        .spanish: list("el la que de y en los las un una no es por con para lo se me te mi tu pero "
            + "qué sí está como yo del al muy esto eso porque también"),
        .german: list("der die das und ich du nicht ist ein eine zu mit sie es wir was auf ja nein "
            + "mein dich mir sich den dem aber auch noch hier bin bist"),
        .french: list("le la les et je tu ne pas est un une de des que qui il elle nous vous mais "
            + "oui non mon ton ce c'est dans pour avec suis j'ai qu'il"),
        .portuguese: list("o a os as e que não um uma de do da em eu você se com para por mas sim "
            + "meu isso está ele ela no na tudo também muito"),
        .italian: list("il lo la i gli le e che non un una di da in io tu è sono ma sì mio questo "
            + "per con mi ti cosa perché del della anche come"),
    ]
    private static let letters: [(ScriptLanguage, String)] = [
        (.turkish, "ğşıİ"),
        (.spanish, "ñ¿¡"),
        (.german, "ß"),
        (.french, "èêëàâîïôûœ"),
        (.portuguese, "ãõ"),
        (.italian, "ìò"),
    ]

    private static func list(_ value: String) -> Set<String> {
        Set(value.split(separator: " ").map(String.init))
    }

    /// The most likely language, or nil when the text is too short or too mixed to tell.
    public static func guess(_ text: String) -> ScriptLanguage? {
        var scores: [ScriptLanguage: Double] = [:]
        for language in ScriptLanguage.allCases {
            scores[language] = 0
        }
        var total = 0
        for match in word.all(text) {
            let found = JText.lowerRoot(match.string(0)).replacingOccurrences(of: "’", with: "'")
            total += 1
            for (language, set) in words where set.contains(found) {
                scores[language, default: 0] += 1
            }
        }
        if total < minimumWords {
            return nil
        }
        // Letters only one language uses are strong hints; each counts like a few common words.
        for unit in text.utf16 {
            let letter = String(utf16CodeUnits: [unit], count: 1)
            let lower = letter.lowercased()
            for (language, set) in letters where set.contains(letter) || (lower.utf16.count == 1 && set.contains(lower)) {
                scores[language, default: 0] += 0.5
            }
        }
        var best: ScriptLanguage?
        var first = 0.0
        var second = 0.0
        // Same order as the Android enum, so ties resolve the same way.
        for language in ScriptLanguage.allCases {
            let score = scores[language] ?? 0
            if score > first {
                second = first
                first = score
                best = language
            } else if score > second {
                second = score
            }
        }
        let clear = first >= 3 && first >= Double(total) * 0.08 && first >= second * 1.3
        return clear ? best : nil
    }
}
