import Foundation

/// Premium: a distinct voice, tone and pace for every character of a text.
public enum VoiceCast {
    public static let speakers = 10

    public enum Tone: String, CaseIterable, Sendable {
        case low = "LOW"
        case normal = "NORMAL"
        case high = "HIGH"

        public var pitch: Float {
            switch self {
            case .low: return 0.8
            case .normal: return 1
            case .high: return 1.2
            }
        }
    }

    public enum Pace: String, CaseIterable, Sendable {
        case slow = "SLOW"
        case normal = "NORMAL"
        case fast = "FAST"

        public var rate: Float {
            switch self {
            case .slow: return 0.85
            case .normal: return 1
            case .fast: return 1.15
            }
        }
    }

    /// How one character sounds. An empty voice name means the default device voice; the speaker
    /// is the character's voice when the on-device AI voice is used.
    public struct Part: Equatable, Sendable {
        public var voice: String
        public var speaker: Int
        public var tone: Tone
        public var pace: Pace

        public init(voice: String, speaker: Int, tone: Tone, pace: Pace) {
            self.voice = voice
            self.speaker = speaker
            self.tone = tone
            self.pace = pace
        }
    }

    private static let prefix = "voice_cast_"
    private static let toneCycle: [Tone] = [.normal, .low, .high]
    /// AI speakers in turns of female (0–4) and male (5–9) so neighbouring characters differ.
    private static let aiOrder = [0, 5, 1, 6, 2, 7, 3, 8, 4, 9]

    /// Gives every character a different voice where enough voices are installed and varies the
    /// tone so that characters sharing a voice still sound apart.
    public static func automatic(roles: [String], voices: [String]) -> [String: Part] {
        var cast: [String: Part] = [:]
        for (i, role) in roles.enumerated() {
            let voice = voices.isEmpty || i == 0 ? "" : voices[(i - 1) % voices.count]
            let round = voices.isEmpty ? i : i / (voices.count + 1)
            cast[role] = Part(voice: voice, speaker: aiOrder[i % aiOrder.count],
                              tone: toneCycle[round % toneCycle.count], pace: .normal)
        }
        return cast
    }

    /// The saved cast completed with automatic choices for characters added since.
    public static func load(_ defaults: UserDefaults, key: String, roles: [String], voices: [String]) -> [String: Part] {
        let saved = fromJson(defaults.string(forKey: prefix + key))
        let automatic = automatic(roles: roles, voices: voices)
        var cast: [String: Part] = [:]
        for role in roles {
            cast[role] = saved[role] ?? automatic[role]
        }
        return cast
    }

    public static func save(_ defaults: UserDefaults, key: String, cast: [String: Part], order: [String]) {
        defaults.set(toJson(cast, order: order), forKey: prefix + key)
    }

    /// The stored cast of a text as saved, for backups; nil when it has none.
    public static func raw(_ defaults: UserDefaults, key: String) -> String? {
        defaults.string(forKey: prefix + key)
    }

    public static func putRaw(_ defaults: UserDefaults, key: String, json: String) {
        defaults.set(json, forKey: prefix + key)
    }

    public static func reset(_ defaults: UserDefaults, key: String) {
        defaults.removeObject(forKey: prefix + key)
    }

    public static func toJson(_ cast: [String: Part], order: [String]? = nil) -> String {
        var object: [String: Any] = [:]
        for (role, part) in cast {
            object[role] = ["voice": part.voice, "speaker": part.speaker, "tone": part.tone.rawValue,
                            "pace": part.pace.rawValue]
        }
        guard let data = try? JSONSerialization.data(withJSONObject: object, options: [.sortedKeys]) else {
            return "{}"
        }
        return String(decoding: data, as: UTF8.self)
    }

    public static func fromJson(_ value: String?) -> [String: Part] {
        var cast: [String: Part] = [:]
        guard let value, !value.isEmpty, let data = value.data(using: .utf8),
              let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return cast
        }
        for (role, entry) in object.sorted(by: { $0.key < $1.key }) {
            guard let part = entry as? [String: Any] else {
                continue
            }
            let speaker = (part["speaker"] as? NSNumber)?.intValue ?? cast.count % speakers
            cast[role] = Part(voice: part["voice"] as? String ?? "",
                              speaker: max(0, min(speakers - 1, speaker)),
                              tone: Tone(rawValue: part["tone"] as? String ?? "") ?? .normal,
                              pace: Pace(rawValue: part["pace"] as? String ?? "") ?? .normal)
        }
        return cast
    }
}

/// Premium hands-free cues: how much of a line was heard.
public enum LineCoverage {
    private static let word = JRegex("[\\p{L}\\p{N}]+")
    private static let marks = JRegex("\\p{M}+")

    /// Share of the expected words that were heard, ignoring case, accents and order.
    public static func coverage(expected: String, heard: String, locale: Locale) -> Double {
        let wanted = words(expected, locale)
        if wanted.isEmpty {
            return 1
        }
        var available: [String: Int] = [:]
        for word in words(heard, locale) {
            available[word, default: 0] += 1
        }
        var matched = 0
        for word in wanted {
            if let count = available[word], count > 0 {
                available[word] = count - 1
                matched += 1
            }
        }
        return Double(matched) / Double(wanted.count)
    }

    static func words(_ text: String, _ locale: Locale) -> [String] {
        // Accents and the Turkish dotless i are folded so small recognition differences still match.
        let folded = marks.replaceAll(text.lowercased(with: locale).decomposedStringWithCanonicalMapping, "")
            .replacingOccurrences(of: "ı", with: "i")
        return word.all(folded).map { $0.string(0) }
    }
}
