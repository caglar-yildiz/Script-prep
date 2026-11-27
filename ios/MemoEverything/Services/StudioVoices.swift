import CryptoKit
import Foundation
import MemoCore

/// Premium studio voices: an AI director reads the scene and notes how each line is played, then
/// Gemini voices each partner line with that delivery. Both run through the Memo Everything Worker
/// (worker/), which holds the Gemini key. Each line's audio is generated once and kept on the
/// phone, so later rehearsals play it offline. Same storage rules as the Android app.
final class StudioVoices: ObservableObject {
    static let shared = StudioVoices()
    static let worker = URL(string: "https://memo-studio.cayildiz01.workers.dev")!
    /// Gemini voices given to characters in turn, alternating female and male voices.
    static let voices = ["Kore", "Puck", "Aoede", "Charon", "Leda", "Fenrir", "Zephyr", "Orus",
                         "Despina", "Iapetus", "Callirrhoe", "Enceladus", "Autonoe", "Algenib", "Erinome", "Alnilam"]
    private static let linesPerRequest = 60
    private static let refreshKey = "studio_refresh_token"

    enum Stop {
        case done, cancelled, dailyLimit, monthlyLimit, failed
    }

    private enum Failure: Error {
        case limit(monthly: Bool), refused(String), busy, notSignedIn, cancelled
    }

    @Published private(set) var running = false
    @Published private(set) var done = 0
    @Published private(set) var total = 0

    private let folder: URL
    private let notesFile: URL
    private let lock = NSLock()
    private var notes: [String: Any]?
    private var idToken: String?
    private var idTokenExpires = Date.distantPast
    private var cancelled = false

    private init() {
        folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("studio", isDirectory: true)
        notesFile = folder.appendingPathComponent("notes.json")
    }

    func cancel() {
        lock.lock()
        cancelled = true
        lock.unlock()
    }

    private var isCancelled: Bool {
        lock.lock()
        defer { lock.unlock() }
        return cancelled
    }

    // MARK: - What is ready

    /// The Gemini voice of a character: characters take the voices in turn.
    static func voice(_ scene: ScriptScene, _ role: String) -> String {
        voices[max(0, scene.roles.firstIndex(of: role) ?? 0) % voices.count]
    }

    static func partnerLines(_ scene: ScriptScene, role: String?) -> [ScriptScene.Line] {
        scene.lines.filter { $0.role != role }
    }

    /// The studio audio of a line, or nil when it has not been prepared.
    func audio(_ fullScene: ScriptScene, _ line: ScriptScene.Line, _ language: ScriptLanguage) -> URL? {
        guard let note = note(line, language) else {
            return nil
        }
        let file = audioFile(line, language, Self.voice(fullScene, line.role), note)
        return FileManager.default.fileExists(atPath: file.path) ? file : nil
    }

    func readyCount(_ fullScene: ScriptScene, _ scene: ScriptScene, role: String?, _ language: ScriptLanguage) -> Int {
        Self.partnerLines(scene, role: role).filter { audio(fullScene, $0, language) != nil }.count
    }

    // MARK: - Preparing

    /// Prepares the partner lines of a scene: notes a scene at a time, then each line's audio.
    /// Stops at the first limit or lasting error; what is done stays. `finished` runs on the main thread.
    func prepare(_ fullScene: ScriptScene, _ scene: ScriptScene, role: String?, language: ScriptLanguage,
                 title: String, finished: @escaping (Stop) -> Void) {
        guard !running else {
            return
        }
        running = true
        lock.lock()
        cancelled = false
        lock.unlock()
        total = Self.partnerLines(scene, role: role).count
        done = readyCount(fullScene, scene, role: role, language)
        DispatchQueue.global(qos: .userInitiated).async {
            let reason: Stop
            do {
                reason = try self.run(fullScene, scene, role: role, language: language, title: title)
            } catch Failure.limit(let monthly) {
                reason = monthly ? .monthlyLimit : .dailyLimit
            } catch {
                reason = self.isCancelled ? .cancelled : .failed
            }
            DispatchQueue.main.async {
                self.running = false
                finished(reason)
            }
        }
    }

    private func run(_ fullScene: ScriptScene, _ scene: ScriptScene, role: String?, language: ScriptLanguage,
                     title: String) throws -> Stop {
        let lines = scene.lines
        var start = 0
        while start < lines.count {
            let chunk = Array(lines[start..<min(lines.count, start + Self.linesPerRequest)])
            start += Self.linesPerRequest
            if !chunk.contains(where: { $0.role != role && note($0, language) == nil }) {
                continue
            }
            if isCancelled {
                return .cancelled
            }
            try direct(chunk, language, title)
        }
        for line in Self.partnerLines(scene, role: role) {
            if isCancelled {
                return .cancelled
            }
            guard let note = note(line, language) else {
                continue
            }
            let voice = Self.voice(fullScene, line.role)
            let target = audioFile(line, language, voice, note)
            if FileManager.default.fileExists(atPath: target.path) {
                continue
            }
            let audio = try speak(note, language, voice)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try audio.write(to: target, options: [.atomic])
            let ready = readyCount(fullScene, scene, role: role, language)
            DispatchQueue.main.async {
                self.done = ready
            }
        }
        return .done
    }

    private func direct(_ chunk: [ScriptScene.Line], _ language: ScriptLanguage, _ title: String) throws {
        let body: [String: Any] = [
            "language": language.code,
            "title": title,
            "lines": chunk.map { ["role": $0.role, "text": $0.text, "notes": $0.notes] },
        ]
        let data = try post("/v1/direct", body)
        guard let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let directions = answer["lines"] as? [[String: Any]] else {
            throw Failure.busy
        }
        lock.lock()
        var stored = loadNotes()
        for (line, direction) in zip(chunk, directions) {
            stored[Self.noteKey(line, language)] = direction
        }
        notes = stored
        let snapshot = stored
        lock.unlock()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: snapshot).write(to: notesFile, options: [.atomic])
    }

    private func speak(_ note: [String: Any], _ language: ScriptLanguage, _ voice: String) throws -> Data {
        let body: [String: Any] = [
            "language": language.code,
            "voice": voice,
            "emotion": note["emotion"] as? String ?? "neutral",
            "pace": note["pace"] as? String ?? "normal",
            "segments": note["segments"] as? [Any] ?? [],
            "pronunciations": note["pronunciations"] as? [Any] ?? [],
        ]
        let data = try post("/v1/speak", body)
        guard let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let candidates = answer["candidates"] as? [[String: Any]],
              let content = candidates.first?["content"] as? [String: Any],
              let parts = content["parts"] as? [[String: Any]] else {
            throw Failure.busy
        }
        for part in parts {
            if let inline = part["inlineData"] as? [String: Any], let base64 = inline["data"] as? String,
               let audio = Data(base64Encoded: base64) {
                let type = inline["mimeType"] as? String ?? ""
                if type.contains("wav") || audio.starts(with: Array("RIFF".utf8)) {
                    return audio
                }
                let rate = Int(type.components(separatedBy: "rate=").last ?? "") ?? 24_000
                return Self.wav(audio, rate: rate)
            }
        }
        throw Failure.busy
    }

    /// Raw 16-bit mono PCM wrapped as a WAV file, so the audio player can play it.
    static func wav(_ pcm: Data, rate: Int) -> Data {
        var data = Data()
        func append<T>(_ value: T) {
            withUnsafeBytes(of: value) { data.append(contentsOf: $0) }
        }
        data.append(contentsOf: Array("RIFF".utf8))
        append(UInt32(36 + pcm.count).littleEndian)
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        append(UInt32(16).littleEndian)
        append(UInt16(1).littleEndian)
        append(UInt16(1).littleEndian)
        append(UInt32(rate).littleEndian)
        append(UInt32(rate * 2).littleEndian)
        append(UInt16(2).littleEndian)
        append(UInt16(16).littleEndian)
        data.append(contentsOf: Array("data".utf8))
        append(UInt32(pcm.count).littleEndian)
        data.append(pcm)
        return data
    }

    // MARK: - Requests

    /// Posts to the Worker, retrying a busy service a few times. Runs on a background queue.
    private func post(_ path: String, _ body: [String: Any]) throws -> Data {
        var last: Error = Failure.busy
        for attempt in 0..<4 {
            if isCancelled {
                throw Failure.cancelled
            }
            var request = URLRequest(url: Self.worker.appendingPathComponent(path), timeoutInterval: 120)
            request.httpMethod = "POST"
            request.setValue("application/json", forHTTPHeaderField: "Content-Type")
            request.setValue("Bearer " + (try token(fresh: attempt > 0)), forHTTPHeaderField: "Authorization")
            request.httpBody = try JSONSerialization.data(withJSONObject: body)
            let (data, status) = try Self.send(request)
            switch status {
            case 200:
                return data
            case 429:
                throw Failure.limit(monthly: String(decoding: data, as: UTF8.self).contains("monthly_limit"))
            case 401:
                idToken = nil
                last = Failure.notSignedIn
            case 500...:
                last = Failure.busy
            default:
                throw Failure.refused(String(decoding: data, as: UTF8.self))
            }
            Thread.sleep(forTimeInterval: 2 * pow(2, Double(attempt)))
        }
        throw last
    }

    /// A Firebase ID token for an identity of its own, kept with a refresh token. It is separate from
    /// ensemble rehearsal, which asks the user's name before signing in.
    private func token(fresh: Bool) throws -> String {
        if !fresh, let idToken, Date() < idTokenExpires.addingTimeInterval(-60) {
            return idToken
        }
        guard let key = Bundle.main.object(forInfoDictionaryKey: "MemoFirebaseApiKey") as? String, !key.isEmpty else {
            throw Failure.notSignedIn
        }
        if let refresh = UserDefaults.standard.string(forKey: Self.refreshKey) {
            var request = URLRequest(url: URL(string: "https://securetoken.googleapis.com/v1/token?key=" + key)!)
            request.httpMethod = "POST"
            request.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")
            let encoded = refresh.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? refresh
            request.httpBody = Data("grant_type=refresh_token&refresh_token=\(encoded)".utf8)
            let (data, status) = try Self.send(request)
            if status == 200, let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
               let id = answer["id_token"] as? String, let next = answer["refresh_token"] as? String {
                UserDefaults.standard.set(next, forKey: Self.refreshKey)
                idToken = id
                idTokenExpires = Date().addingTimeInterval(Double(answer["expires_in"] as? String ?? "3600") ?? 3600)
                return id
            }
        }
        var request = URLRequest(url: URL(string: "https://identitytoolkit.googleapis.com/v1/accounts:signUp?key=" + key)!)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = Data(#"{"returnSecureToken":true}"#.utf8)
        let (data, status) = try Self.send(request)
        guard status == 200, let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let id = answer["idToken"] as? String, let refresh = answer["refreshToken"] as? String else {
            throw Failure.notSignedIn
        }
        UserDefaults.standard.set(refresh, forKey: Self.refreshKey)
        idToken = id
        idTokenExpires = Date().addingTimeInterval(Double(answer["expiresIn"] as? String ?? "3600") ?? 3600)
        return id
    }

    /// A blocking request for the background queue.
    private static func send(_ request: URLRequest) throws -> (Data, Int) {
        let semaphore = DispatchSemaphore(value: 0)
        var result: (Data, Int)?
        var failure: Error?
        URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                failure = error
            } else {
                result = (data ?? Data(), (response as? HTTPURLResponse)?.statusCode ?? 0)
            }
            semaphore.signal()
        }.resume()
        semaphore.wait()
        if let failure {
            throw failure
        }
        return result ?? (Data(), 0)
    }

    // MARK: - Storage

    /// Must be called with the lock held.
    private func loadNotes() -> [String: Any] {
        if let notes {
            return notes
        }
        let loaded = (try? Data(contentsOf: notesFile)).flatMap {
            try? JSONSerialization.jsonObject(with: $0) as? [String: Any]
        } ?? [:]
        notes = loaded
        return loaded
    }

    private func note(_ line: ScriptScene.Line, _ language: ScriptLanguage) -> [String: Any]? {
        lock.lock()
        defer { lock.unlock() }
        return loadNotes()[Self.noteKey(line, language)] as? [String: Any]
    }

    private static func noteKey(_ line: ScriptScene.Line, _ language: ScriptLanguage) -> String {
        language.code + ":" + EnsembleRules.lineKey(role: line.role, text: line.text)
    }

    /// A line's audio changes with its words, its note and its voice; each combination is one file.
    private func audioFile(_ line: ScriptScene.Line, _ language: ScriptLanguage, _ voice: String,
                           _ note: [String: Any]) -> URL {
        let noteText = (try? JSONSerialization.data(withJSONObject: note, options: [.sortedKeys]))
            .map { String(decoding: $0, as: UTF8.self) } ?? ""
        let digest = SHA256.hash(data: Data("\(language.code)\n\(voice)\n\(line.text)\n\(noteText)".utf8))
        let name = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
        return folder.appendingPathComponent(name + ".wav")
    }
}
