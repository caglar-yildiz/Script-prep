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
    /// The Gemini voice of each character voice of the cast: speakers 0–4 are female and 5–9 male,
    /// like the on-device AI voices. The Worker's sample voice pack uses the same table.
    static let castVoices = ["Kore", "Aoede", "Leda", "Sulafat", "Gacrux", "Charon", "Puck", "Orus", "Algenib", "Iapetus"]

    /// The Gemini voice of a character, from the script's cast.
    typealias Casting = (String) -> String
    private static let linesPerRequest = 60
    private static let refreshKey = "studio_refresh_token"

    enum Stop {
        case done, cancelled, dailyLimit, monthlyLimit, scriptLimit, failed
    }

    private enum Failure: Error {
        case limit(Stop), refused(String), busy, notSignedIn, cancelled
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

    /// The Gemini voice of a character voice of the cast.
    static func voice(of part: VoiceCast.Part?) -> String {
        castVoices[max(0, min(castVoices.count - 1, part?.speaker ?? 0))]
    }

    /// The sample of a studio voice that comes with the app.
    static func previewURL(_ language: ScriptLanguage, _ voice: String) -> URL? {
        Bundle.main.url(forResource: "studio-voice-\(language.code)-\(voice)", withExtension: "m4a")
    }

    /// The studio audio of a line in the given voice, or nil when it has not been prepared.
    func audio(_ line: ScriptScene.Line, _ language: ScriptLanguage, voice: String) -> URL? {
        guard let note = note(line, language) else {
            return nil
        }
        return ["wav", "m4a"].map { audioFile(line, language, voice, note, $0) }
            .first { FileManager.default.fileExists(atPath: $0.path) }
    }

    /// How many lines of the scene have studio audio. Every line is prepared, the actor's own too, so
    /// listening, another part and a change of role all play studio voices.
    func readyCount(_ scene: ScriptScene, _ language: ScriptLanguage, casting: Casting) -> Int {
        scene.lines.filter { audio($0, language, voice: casting($0.role)) != nil }.count
    }

    // MARK: - Preparing

    /// Prepares the partner lines of a scene: notes a scene at a time, then each line's audio.
    /// Stops at the first limit or lasting error; what is done stays. `finished` runs on the main thread.
    /// `script` is an id of the script, so the Worker can count the scripts of the month; it is hashed
    /// before it leaves the phone.
    func prepare(_ scene: ScriptScene, language: ScriptLanguage, title: String, script: String,
                 casting: @escaping Casting, finished: @escaping (Stop) -> Void) {
        guard !running else {
            return
        }
        running = true
        lock.lock()
        cancelled = false
        lock.unlock()
        total = scene.lines.count
        done = readyCount(scene, language, casting: casting)
        let scriptId = Self.hash(script)
        DispatchQueue.global(qos: .userInitiated).async {
            let reason: Stop
            do {
                reason = try self.run(scene, language: language, title: title, script: scriptId, casting: casting)
            } catch Failure.limit(let stop) {
                reason = stop
            } catch {
                reason = self.isCancelled ? .cancelled : .failed
            }
            DispatchQueue.main.async {
                self.running = false
                finished(reason)
            }
        }
    }

    private func run(_ scene: ScriptScene, language: ScriptLanguage, title: String, script: String,
                     casting: Casting) throws -> Stop {
        let lines = scene.lines
        var start = 0
        while start < lines.count {
            let chunk = Array(lines[start..<min(lines.count, start + Self.linesPerRequest)])
            start += Self.linesPerRequest
            if !chunk.contains(where: { note($0, language) == nil }) {
                continue
            }
            if isCancelled {
                return .cancelled
            }
            try direct(chunk, language, title, script)
        }
        for line in lines {
            if isCancelled {
                return .cancelled
            }
            guard let note = note(line, language) else {
                continue
            }
            // Asked line by line: the director's genders can change the automatic cast.
            let voice = casting(line.role)
            if audio(line, language, voice: voice) != nil {
                continue
            }
            let target = audioFile(line, language, voice, note, "wav")
            let audio = try speak(note, language, voice, script)
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try audio.write(to: target, options: [.atomic])
            let ready = readyCount(scene, language, casting: casting)
            DispatchQueue.main.async {
                self.done = ready
            }
        }
        return .done
    }

    private func direct(_ chunk: [ScriptScene.Line], _ language: ScriptLanguage, _ title: String,
                        _ script: String) throws {
        let body: [String: Any] = [
            "language": language.code,
            "title": title,
            "script": script,
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
        for member in answer["cast"] as? [[String: Any]] ?? [] {
            Self.putGender(&stored, member["role"] as? String ?? "", member["gender"] as? String ?? "")
        }
        notes = stored
        let snapshot = stored
        lock.unlock()
        try saveNotes(snapshot)
    }

    private func saveNotes(_ snapshot: [String: Any]) throws {
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: snapshot).write(to: notesFile, options: [.atomic])
    }

    private static func putGender(_ notes: inout [String: Any], _ role: String, _ gender: String) {
        if !role.isEmpty && (gender == "female" || gender == "male") {
            notes[genderKey(role)] = gender
        }
    }

    private static func genderKey(_ role: String) -> String {
        "gender:" + role.trimmingCharacters(in: .whitespaces).lowercased()
    }

    /// The genders the director gave the characters ("female" or "male"), for the automatic cast.
    func genders(_ roles: [String]) -> [String: String] {
        lock.lock()
        defer { lock.unlock() }
        let stored = loadNotes()
        var genders: [String: String] = [:]
        for role in roles {
            if let gender = stored[Self.genderKey(role)] as? String {
                genders[role] = gender
            }
        }
        return genders
    }

    // MARK: - The sample scene

    /// Copies the voice pack of the sample scene from the app (made by the Worker's
    /// scripts/sample-pack.ts), so the first scene plays with studio voices without preparing it.
    /// Only lines that match the scene are taken; it is done once per language and pack.
    func installSample(_ scene: ScriptScene, _ language: ScriptLanguage) {
        guard let index = Bundle.main.url(forResource: "studio-sample-" + language.code, withExtension: "json"),
              let data = try? Data(contentsOf: index),
              let pack = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
              let lines = pack["lines"] as? [[String: Any]] else {
            return
        }
        let doneKey = "sample_pack_" + language.code
        let stamp = "\(pack["version"] ?? 0)/\(lines.count)/\(data.count)"
        if UserDefaults.standard.string(forKey: doneKey) == stamp {
            return
        }
        lock.lock()
        var stored = loadNotes()
        for (role, gender) in pack["cast"] as? [String: String] ?? [:] {
            Self.putGender(&stored, role, gender)
        }
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        for entry in lines {
            guard let role = entry["role"] as? String, let text = entry["text"] as? String,
                  let voice = entry["voice"] as? String, let note = entry["note"] as? [String: Any],
                  let name = entry["audio"] as? String, !name.isEmpty,
                  let source = Bundle.main.url(forResource: (name as NSString).deletingPathExtension, withExtension: "m4a") else {
                continue
            }
            let key = EnsembleRules.lineKey(role: role, text: text)
            guard let line = scene.lines.first(where: { EnsembleRules.lineKey(role: $0.role, text: $0.text) == key }) else {
                continue
            }
            stored[Self.noteKey(line, language)] = note
            let target = audioFile(line, language, voice, note, "m4a")
            try? FileManager.default.removeItem(at: target)
            try? FileManager.default.copyItem(at: source, to: target)
        }
        notes = stored
        let snapshot = stored
        lock.unlock()
        if (try? saveNotes(snapshot)) != nil {
            UserDefaults.standard.set(stamp, forKey: doneKey)
        }
    }

    private func speak(_ note: [String: Any], _ language: ScriptLanguage, _ voice: String,
                       _ script: String) throws -> Data {
        let body: [String: Any] = [
            "language": language.code,
            "script": script,
            "voice": voice,
            "emotion": note["emotion"] as? String ?? "neutral",
            "pace": note["pace"] as? String ?? "normal",
            "segments": note["segments"] as? [Any] ?? [],
            "pronunciations": note["pronunciations"] as? [Any] ?? [],
        ]
        let data = try post("/v1/speak", body)
        // Gemini's interaction: steps of model output whose content holds the audio.
        guard let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let steps = answer["steps"] as? [[String: Any]] else {
            throw Failure.busy
        }
        for part in steps.flatMap({ $0["content"] as? [[String: Any]] ?? [] }) where part["type"] as? String == "audio" {
            if let base64 = part["data"] as? String, let audio = Data(base64Encoded: base64) {
                let type = part["mime_type"] as? String ?? ""
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
                let answer = String(decoding: data, as: UTF8.self)
                throw Failure.limit(answer.contains("script_limit") ? .scriptLimit
                    : answer.contains("monthly_limit") ? .monthlyLimit : .dailyLimit)
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

    static func hash(_ value: String) -> String {
        SHA256.hash(data: Data(value.utf8)).prefix(16).map { String(format: "%02x", $0) }.joined()
    }

    /// A line's audio changes with its words, its note and its voice; each combination is one file.
    private func audioFile(_ line: ScriptScene.Line, _ language: ScriptLanguage, _ voice: String,
                           _ note: [String: Any], _ fileExtension: String) -> URL {
        let noteText = (try? JSONSerialization.data(withJSONObject: note, options: [.sortedKeys]))
            .map { String(decoding: $0, as: UTF8.self) } ?? ""
        let digest = SHA256.hash(data: Data("\(language.code)\n\(voice)\n\(line.text)\n\(noteText)".utf8))
        let name = digest.prefix(16).map { String(format: "%02x", $0) }.joined()
        return folder.appendingPathComponent(name + "." + fileExtension)
    }
}
