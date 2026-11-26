import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// The parts of ensemble rehearsal that do not need Firebase: invite codes, line identity and
/// which recorded take plays for a line.
public enum EnsembleRules {
    /// Letters and digits that cannot be confused when read aloud or typed.
    public static let codeAlphabet = Array("ABCDEFGHJKLMNPQRSTUVWXYZ23456789")
    public static let codeLength = 6
    /// Prefix of the local library copy of a production, so rehearsal works offline.
    public static let localPrefix = "ensemble:"
    public static let maxNameLength = 40
    /// Audio per Firestore document; the rules allow up to 900 KB.
    public static let partBytes = 800_000
    /// Ten minutes at 32 kbit/s is about 2.4 MB, three parts.
    public static let maxMillis = 10 * 60_000
    public static let maxBytes = 6 * partBytes
    public static let bitRate = 32_000
    public static let invitePage = "https://caglar-yildiz.github.io/Script-prep/join/?c="

    public static func newCode() -> String {
        var generator = SystemRandomNumberGenerator()
        return String((0..<codeLength).map { _ in codeAlphabet[Int.random(in: 0..<codeAlphabet.count, using: &generator)] })
    }

    /// Upper-cases a typed code and drops spaces, dashes and other separators.
    public static func normalizeCode(_ raw: String) -> String {
        String(String.UnicodeScalarView(JText.upperRoot(raw).unicodeScalars.filter(JText.isLetterOrDigit)))
    }

    public static func cleanName(_ name: String?) -> String {
        let clean = JText.collapse(JText.trim(name ?? ""))
        return JText.length(clean) > maxNameLength ? JText.trim(JText.sub(clean, 0, maxNameLength)) : clean
    }

    /// The invite code in a link: memoeverything://join/CODE or the web page's ?c=CODE.
    public static func inviteCode(_ url: URL?) -> String? {
        guard let url else {
            return nil
        }
        var code: String?
        if url.host == "join" {
            code = url.pathComponents.last(where: { $0 != "/" })
        } else {
            code = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems?.first(where: { $0.name == "c" })?.value
        }
        let normalized = normalizeCode(code ?? "")
        return normalized.count == codeLength ? normalized : nil
    }

    /// Identifies a line by its character and words; spacing and letter case do not count.
    public static func lineKey(role: String, text: String) -> String {
        let normalized = JText.lowerRoot(JText.trim(role)) + "\n" + JText.lowerRoot(JText.collapse(JText.trim(text)))
        #if canImport(CryptoKit)
        let digest = SHA256.hash(data: Data(normalized.utf8))
        return digest.prefix(10).map { String(format: "%02x", $0) }.joined()
        #else
        return String(normalized.hashValue, radix: 16)
        #endif
    }

    /// The actor's own lines to record, each with the cue line before it, without repeats.
    public static func ownLines(_ scene: ScriptScene, roles: [String]) -> [(line: ScriptScene.Line, cue: ScriptScene.Line?)] {
        var result: [(line: ScriptScene.Line, cue: ScriptScene.Line?)] = []
        var seen = Set<String>()
        for (i, line) in scene.lines.enumerated() {
            guard roles.contains(line.role), seen.insert(lineKey(role: line.role, text: line.text)).inserted else {
                continue
            }
            var cue: ScriptScene.Line?
            var j = i - 1
            while j >= 0 {
                if !roles.contains(scene.lines[j].role) {
                    cue = scene.lines[j]
                    break
                }
                j -= 1
            }
            result.append((line, cue))
        }
        return result
    }

    /// The recordings a rehearsal plays. Each character keeps one voice: the member who recorded
    /// most of that character's lines, falling back to anyone who recorded a line they did not.
    public struct Voices {
        /// line key → uid → file
        private var recordings: [String: [String: URL]] = [:]
        /// character → uid whose recordings play first
        private var preferred: [String: String] = [:]

        public init() {}

        public static func load(directory: URL, scene: ScriptScene) -> Voices {
            var voices = Voices()
            let files = (try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)) ?? []
            for file in files {
                let name = file.lastPathComponent
                guard name.hasSuffix(".m4a"), let split = name.firstIndex(of: "_"), split != name.startIndex else {
                    continue
                }
                let key = String(name[..<split])
                let uid = String(name[name.index(after: split)..<name.index(name.endIndex, offsetBy: -4)])
                voices.recordings[key, default: [:]][uid] = file
            }
            var counts: [String: [String: Int]] = [:]
            for line in scene.lines {
                if let takes = voices.recordings[lineKey(role: line.role, text: line.text)] {
                    for uid in takes.keys {
                        counts[line.role, default: [:]][uid, default: 0] += 1
                    }
                }
            }
            for (role, byUid) in counts {
                var best: String?
                var most = 0
                for (uid, count) in byUid where count > most || (count == most && best != nil && uid < best!) {
                    best = uid
                    most = count
                }
                voices.preferred[role] = best
            }
            return voices
        }

        public var isEmpty: Bool {
            recordings.isEmpty
        }

        public func find(_ line: ScriptScene.Line) -> URL? {
            guard let takes = recordings[lineKey(role: line.role, text: line.text)], !takes.isEmpty else {
                return nil
            }
            let take = preferred[line.role].flatMap { takes[$0] } ?? takes.sorted(by: { $0.key < $1.key }).first!.value
            return FileManager.default.fileExists(atPath: take.path) ? take : nil
        }
    }
}
