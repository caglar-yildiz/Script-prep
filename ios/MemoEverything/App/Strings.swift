import Foundation
import MemoCore

/// The app's own language choice, like Android's per-app language: the phone language or one of
/// the seven translations, applied without restarting.
enum AppLanguage: String, CaseIterable {
    case system = ""
    case turkish = "tr"
    case english = "en"
    case spanish = "es"
    case german = "de"
    case french = "fr"
    case portuguese = "pt"
    case italian = "it"

    private static let key = "app_language"

    var labelKey: String {
        switch self {
        case .system: return "language_system"
        case .turkish: return "language_turkish"
        case .english: return "language_english"
        case .spanish: return "language_spanish"
        case .german: return "language_german"
        case .french: return "language_french"
        case .portuguese: return "language_portuguese"
        case .italian: return "language_italian"
        }
    }

    static var current: AppLanguage {
        AppLanguage(rawValue: UserDefaults.standard.string(forKey: key) ?? "") ?? .system
    }

    func apply() {
        UserDefaults.standard.set(rawValue, forKey: Self.key)
        Strings.reload()
    }
}

enum Strings {
    private(set) static var bundle: Bundle = load()
    private(set) static var locale: Locale = currentLocale()

    static func reload() {
        bundle = load()
        locale = currentLocale()
    }

    /// The translation folder in use: the chosen language, else the phone's best match.
    static var languageCode: String {
        let chosen = AppLanguage.current
        if chosen != .system {
            return chosen.rawValue
        }
        let preferred = Bundle.preferredLocalizations(from: Bundle.main.localizations.filter { $0 != "Base" },
                                                      forPreferences: Locale.preferredLanguages).first ?? "en"
        return String(preferred.prefix(2))
    }

    private static func load() -> Bundle {
        let chosen = AppLanguage.current
        guard chosen != .system else {
            return .main
        }
        let folder = chosen == .portuguese ? "pt-BR" : chosen.rawValue
        if let path = Bundle.main.path(forResource: folder, ofType: "lproj"), let bundle = Bundle(path: path) {
            return bundle
        }
        return .main
    }

    private static func currentLocale() -> Locale {
        let chosen = AppLanguage.current
        if chosen == .system {
            return .current
        }
        return Locale(identifier: chosen == .portuguese ? "pt_BR" : chosen.rawValue)
    }

    /// The interface language mapped to a text language.
    static var interfaceLanguage: ScriptLanguage {
        ScriptLanguage.forLanguageCode(languageCode)
    }
}

/// A translated string, formatted like Android's `getString(id, args...)`.
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = Strings.bundle.localizedString(forKey: key, value: nil, table: nil)
    if args.isEmpty {
        return format
    }
    return String(format: format, locale: Strings.locale, arguments: args)
}

/// A plural string, like Android's `getQuantityString(id, count, count)`.
func P(_ key: String, _ count: Int) -> String {
    let format = Strings.bundle.localizedString(forKey: key, value: nil, table: nil)
    return String(format: format, locale: Strings.locale, arguments: [count])
}

/// A number written for the interface language.
func formatNumber(_ value: Double, maxFractionDigits: Int = 0) -> String {
    let formatter = NumberFormatter()
    formatter.locale = Strings.locale
    formatter.numberStyle = .decimal
    formatter.maximumFractionDigits = maxFractionDigits
    return formatter.string(from: NSNumber(value: value)) ?? String(value)
}
