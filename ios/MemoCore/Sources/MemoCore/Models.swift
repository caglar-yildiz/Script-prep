import Foundation

/// The language a text is written in; it drives the voices, the AI voice and speech recognition.
public enum ScriptLanguage: String, CaseIterable, Codable, Sendable {
    case turkish = "tr"
    case english = "en"
    case spanish = "es"
    case german = "de"
    case french = "fr"
    case portuguese = "pt"
    case italian = "it"

    /// ISO 639-1 code, also the language name the AI voice expects.
    public var code: String {
        rawValue
    }

    /// BCP 47 tag of the voice and speech recognition locale.
    public var tag: String {
        switch self {
        case .turkish: return "tr-TR"
        case .english: return "en-US"
        case .spanish: return "es-ES"
        case .german: return "de-DE"
        case .french: return "fr-FR"
        case .portuguese: return "pt-BR"
        case .italian: return "it-IT"
        }
    }

    public var locale: Locale {
        Locale(identifier: tag.replacingOccurrences(of: "-", with: "_"))
    }

    /// The string key of the language's name.
    public var labelKey: String {
        switch self {
        case .turkish: return "language_turkish"
        case .english: return "language_english"
        case .spanish: return "language_spanish"
        case .german: return "language_german"
        case .french: return "language_french"
        case .portuguese: return "language_portuguese"
        case .italian: return "language_italian"
        }
    }

    public var sample: String {
        switch self {
        case .turkish: return "Hazırsan sahneyi baştan alalım."
        case .english: return "If you're ready, let's take the scene from the top."
        case .spanish: return "Si estás listo, empecemos la escena desde el principio."
        case .german: return "Wenn du so weit bist, nehmen wir die Szene noch einmal von vorn."
        case .french: return "Si tu es prêt, reprenons la scène depuis le début."
        case .portuguese: return "Se você estiver pronto, vamos começar a cena do início."
        case .italian: return "Se sei pronto, riprendiamo la scena dall'inizio."
        }
    }

    /// Texts saved before languages existed were all Turkish.
    public static func fromCode(_ code: String?) -> ScriptLanguage {
        ScriptLanguage(rawValue: code ?? "") ?? .turkish
    }

    /// The language matching the app's interface, or English when it has no match.
    public static func forLanguageCode(_ code: String?) -> ScriptLanguage {
        ScriptLanguage(rawValue: code ?? "") ?? .english
    }
}

public enum TextCategory: String, CaseIterable, Codable, Sendable {
    case roleRehearsal = "ROLE_REHEARSAL"
    case speech = "SPEECH"
    case poemOrMonologue = "POEM_OR_MONOLOGUE"

    public static func fromSavedValue(_ value: String?) -> TextCategory {
        TextCategory(rawValue: value ?? "") ?? .roleRehearsal
    }

    public var hasRoles: Bool {
        self == .roleRehearsal
    }

    public func parse(_ source: String, narrator: String) -> ScriptScene {
        hasRoles ? ScriptScene.parse(source) : ScriptScene.parseSolo(source, narrator: narrator, byLine: self == .poemOrMonologue)
    }
}

public enum RehearsalMode: String, Codable, Sendable {
    case listen = "LISTEN"
    case practice = "PRACTICE"
}

/// How the actor's own lines are shown while rehearsing.
public enum LineVisibility: String, CaseIterable, Codable, Sendable {
    case full = "FULL"
    case hint = "HINT"
    case initials = "INITIALS"
    case hidden = "HIDDEN"

    private static let word = JRegex("[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*")

    public func visibleText(_ line: String) -> String {
        switch self {
        case .full: return line
        case .hint: return Self.hint(line)
        case .initials: return Self.initials(line)
        case .hidden: return ""
        }
    }

    public var labelKey: String {
        switch self {
        case .full: return "visibility_full"
        case .hint: return "visibility_hint"
        case .initials: return "visibility_initials"
        case .hidden: return "visibility_hidden"
        }
    }

    private static func hint(_ line: String) -> String {
        let words = word.all(line)
        guard let first = words.first else {
            return "…"
        }
        if words.count == 1 {
            return JText.sub(line, 0, first.start() + firstScalarLength(line, at: first.start())) + "…"
        }
        return JText.sub(line, 0, first.end()) + " …"
    }

    private static func initials(_ line: String) -> String {
        var masked = ""
        var position = 0
        for match in word.all(line) {
            let initialEnd = match.start() + firstScalarLength(line, at: match.start())
            masked += JText.sub(line, position, initialEnd)
            if initialEnd < match.end() {
                masked += "·"
            }
            position = match.end()
        }
        return masked + JText.sub(line, position)
    }

    /// UTF-16 length of the code point at an offset (2 for a surrogate pair).
    private static func firstScalarLength(_ line: String, at index: Int) -> Int {
        let unit = JText.unit(line, index)
        return UTF16.isLeadSurrogate(unit) ? 2 : 1
    }
}

public enum RehearsalOptions {
    public static func shouldSpeak(mode: RehearsalMode, selectedRole: String?, lineRole: String) -> Bool {
        mode == .listen || lineRole != selectedRole
    }
}

/// One saved text in the library.
public struct Script: Equatable, Identifiable, Sendable {
    public let id: String
    public let title: String
    public let text: String
    public let category: TextCategory
    public let language: ScriptLanguage

    public init(id: String, title: String, text: String, category: TextCategory = .roleRehearsal,
                language: ScriptLanguage = .turkish) {
        self.id = id
        self.title = title
        self.text = text
        self.category = category
        self.language = language
    }
}
