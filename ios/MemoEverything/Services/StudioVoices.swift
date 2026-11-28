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
        case done, partial, cancelled, dailyLimit, monthlyLimit, scriptLimit, extraScript, premiumRequired, failed
    }

    private enum Failure: Error {
        case limit(Stop), refused(String), busy, notSignedIn, cancelled
    }

    @Published private(set) var running = false
    @Published private(set) var done = 0
    @Published private(set) var total = 0
    /// Lines the last preparation could not voice; they are tried again next time.
    @Published private(set) var skippedLines = 0

    private let folder: URL
    private let notesFile: URL
    private let lock = NSLock()
    private var notes: [String: Any]?
    private var idToken: String?
    private var idTokenExpires = Date.distantPast
    private var cancelled = false
    /// Headers of the last answer on this thread: lines are voiced several at a time.
    private var lastHeaders: [AnyHashable: Any] {
        get { Thread.current.threadDictionary["memo.studio.headers"] as? [AnyHashable: Any] ?? [:] }
        set { Thread.current.threadDictionary["memo.studio.headers"] = newValue }
    }
    /// How the last line on this thread was voiced: model, route, style and text the voice was given.
    private var lastVoiced: [String: Any] {
        get { Thread.current.threadDictionary["memo.studio.voiced"] as? [String: Any] ?? [:] }
        set { Thread.current.threadDictionary["memo.studio.voiced"] = newValue }
    }
    private let saveLock = NSLock()

    /// One scene to prepare: what it is, and who to tell how it goes.
    private struct Job {
        let key: String
        let fullScene: ScriptScene
        let scene: ScriptScene
        let language: ScriptLanguage
        let title: String
        let script: String
        let casting: Casting
        let finished: (Stop) -> Void
    }

    private var queue: [Job] = []
    /// The scene being prepared and those waiting their turn, for each screen to show its own state.
    @Published private(set) var currentKey: String?
    @Published private(set) var waitingKeys: [String] = []
    /// Requests at once: Google Cloud took six voices at a time without complaint on CI.
    private static let linesAtOnce = 6
    private static let partsAtOnce = 3
    private static let voicedPrefix = "voiced:"

    private init() {
        folder = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("studio", isDirectory: true)
        notesFile = folder.appendingPathComponent("notes.json")
    }

    /// Runs of lines with no note yet, at most a part long.
    private func undirected(_ lines: [ScriptScene.Line], _ language: ScriptLanguage) -> [[ScriptScene.Line]] {
        var runs: [[ScriptScene.Line]] = []
        var run: [ScriptScene.Line] = []
        for line in lines {
            if note(line, language) == nil {
                run.append(line)
                if run.count >= Self.linesPerRequest {
                    runs.append(run)
                    run = []
                }
            } else if !run.isEmpty {
                runs.append(run)
                run = []
            }
        }
        if !run.isEmpty {
            runs.append(run)
        }
        return runs
    }

    /// Whether the scene being prepared was asked to stop and is finishing what is on the way.
    var stopping: Bool {
        running && isCancelled
    }

    /// Requests on the way, so stopping does not wait for them.
    private static let tasksLock = NSLock()
    private static var openTasks: [Int: URLSessionTask] = [:]

    /// Stops the scene being prepared and forgets the ones waiting; requests on the way are cut off.
    func cancel() {
        lock.lock()
        cancelled = true
        let dropped = queue
        queue = []
        lock.unlock()
        Self.tasksLock.lock()
        let open = Array(Self.openTasks.values)
        Self.tasksLock.unlock()
        open.forEach { $0.cancel() }
        objectWillChange.send()
        DispatchQueue.main.async {
            self.waitingKeys = []
            dropped.forEach { $0.finished(.cancelled) }
        }
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
    /// Prepares a scene: the play's reading, notes in parts, then each line's audio, several at a
    /// time. A scene asked while another is being prepared waits its turn. `key` names the scene so
    /// each screen shows its own progress; `script` is hashed before it leaves the phone.
    /// `finished` runs on the main thread.
    func prepare(key: String, _ fullScene: ScriptScene, _ scene: ScriptScene, language: ScriptLanguage, title: String,
                 script: String, casting: @escaping Casting, finished: @escaping (Stop) -> Void) {
        guard currentKey != key, !waitingKeys.contains(key) else {
            return
        }
        let job = Job(key: key, fullScene: fullScene, scene: scene, language: language, title: title,
                      script: Self.hash(script), casting: casting, finished: finished)
        if running {
            lock.lock()
            queue.append(job)
            lock.unlock()
            waitingKeys.append(key)
            return
        }
        start(job)
    }

    /// Main thread.
    private func start(_ job: Job) {
        running = true
        currentKey = job.key
        lock.lock()
        cancelled = false
        lock.unlock()
        total = job.scene.lines.count
        done = readyCount(job.scene, job.language, casting: job.casting)
        DispatchQueue.global(qos: .userInitiated).async {
            let reason: Stop
            do {
                reason = try self.run(job.fullScene, job.scene, language: job.language, title: job.title,
                                      script: job.script, casting: job.casting)
            } catch Failure.limit(let stop) {
                reason = stop
            } catch {
                reason = self.isCancelled ? .cancelled : .failed
            }
            DispatchQueue.main.async {
                job.finished(reason)
                self.lock.lock()
                let next = reason == .cancelled || self.queue.isEmpty ? nil : self.queue.removeFirst()
                self.lock.unlock()
                if let next {
                    self.waitingKeys.removeAll { $0 == next.key }
                    self.start(next)
                } else {
                    self.running = false
                    self.currentKey = nil
                }
            }
        }
    }

    private func run(_ fullScene: ScriptScene, _ scene: ScriptScene, language: ScriptLanguage, title: String,
                     script: String, casting: Casting) throws -> Stop {
        let lines = scene.lines
        sendSubscription()
        // The director first reads the whole play once; each part is then directed knowing it.
        var reading = analysis(fullScene, language, script: script)
        if reading == nil {
            do {
                reading = try analyze(fullScene, language, title: title, script: script)
            } catch Failure.limit(let stop) {
                throw Failure.limit(stop)
            } catch {
                // Directed scene by scene without the reading.
            }
        }
        // Parts and lines go several at a time. One that fails is skipped and tried again next time;
        // only failures in a row stop preparing, as the connection or the service is then down.
        let failures = Failures()
        let readingForParts = reading
        let parts = OperationQueue()
        parts.maxConcurrentOperationCount = Self.partsAtOnce
        // Notes: only lines that have none, in runs of up to a part, each with the lines before it for
        // context. Lines already directed keep their notes, so their audio stays theirs.
        for chunk in undirected(lines, language) {
            parts.addOperation {
                _ = failures.attempt(cancelled: { self.isCancelled }) {
                    try self.direct(chunk, before: Self.linesBefore(fullScene, chunk[0]),
                                    context: Self.contextFor(readingForParts, section: chunk[0].section), language, title, script)
                }
            }
        }
        parts.waitUntilAllOperationsAreFinished()
        try failures.rethrow()
        if isCancelled {
            return .cancelled
        }

        let voices = OperationQueue()
        voices.maxConcurrentOperationCount = Self.linesAtOnce
        let readyLock = NSLock()
        var ready = readyCount(scene, language, casting: casting)
        for line in lines {
            guard let note = note(line, language) else {
                continue
            }
            // The director's genders can change the automatic cast, so the voice is asked now.
            let voice = casting(line.role)
            if audio(line, language, voice: voice) != nil {
                continue
            }
            voices.addOperation {
                let voiced = failures.attempt(cancelled: { self.isCancelled }) {
                    let target = self.audioFile(line, language, voice, note, "wav")
                    let audio = try self.speak(note, language, voice, script)
                    try FileManager.default.createDirectory(at: self.folder, withIntermediateDirectories: true)
                    try audio.write(to: target, options: [.atomic])
                    self.lock.lock()
                    var stored = self.loadNotes()
                    stored[Self.voicedPrefix + target.lastPathComponent] = self.lastVoiced
                    self.notes = stored
                    self.lock.unlock()
                    try self.saveNotes(stored)
                }
                if voiced {
                    readyLock.lock()
                    ready += 1
                    let now = ready
                    readyLock.unlock()
                    DispatchQueue.main.async {
                        self.done = now
                    }
                }
            }
        }
        voices.waitUntilAllOperationsAreFinished()
        try failures.rethrow()
        if isCancelled {
            return .cancelled
        }
        let skipped = lines.count - readyCount(scene, language, casting: casting)
        DispatchQueue.main.async {
            self.skippedLines = skipped
        }
        return skipped > 0 ? .partial : .done
    }

    private static func linesJson(_ lines: [ScriptScene.Line]) -> [[String: Any]] {
        lines.map { ["role": $0.role, "text": $0.text, "notes": $0.notes.map { String($0.prefix(300)) }] }
    }

    private func direct(_ chunk: [ScriptScene.Line], before: [ScriptScene.Line], context: String,
                        _ language: ScriptLanguage, _ title: String, _ script: String) throws {
        let body: [String: Any] = [
            "language": language.code,
            "title": title,
            "script": script,
            "lines": Self.linesJson(chunk),
            "before": Self.linesJson(before),
            "context": context,
        ]
        let data = try post("/v1/direct", body)
        guard let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
              let directions = answer["lines"] as? [[String: Any]] else {
            throw Failure.busy
        }
        lock.lock()
        var stored = loadNotes()
        for (line, direction) in zip(chunk, directions) where stored[Self.noteKey(line, language)] == nil {
            // A note is never replaced here: the line's audio belongs to it.
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

    /// Writes the notes as they are now; writers take turns, so the file never goes back to an older state.
    private func saveNotes(_ snapshot: [String: Any]) throws {
        saveLock.lock()
        defer { saveLock.unlock() }
        lock.lock()
        let latest = notes ?? snapshot
        lock.unlock()
        try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try JSONSerialization.data(withJSONObject: latest).write(to: notesFile, options: [.atomic])
    }

    private static func putGender(_ notes: inout [String: Any], _ role: String, _ gender: String) {
        if !role.isEmpty && (gender == "female" || gender == "male") {
            notes[genderKey(role)] = gender
        }
    }

    private static func genderKey(_ role: String) -> String {
        "gender:" + role.trimmingCharacters(in: .whitespaces).lowercased()
    }

    private func header(_ name: String) -> String {
        for (key, value) in lastHeaders where (key as? String)?.caseInsensitiveCompare(name) == .orderedSame {
            let text = value as? String ?? ""
            return text.removingPercentEncoding ?? text
        }
        return ""
    }

    // MARK: - What the director decided

    /// The director's note of a line: emotion, pace, subtext, segments; nil until it is directed.
    func noteOf(_ line: ScriptScene.Line, _ language: ScriptLanguage) -> [String: Any]? {
        note(line, language)
    }

    /// How a line's audio in the given voice was made (model, route, the style and text the voice was
    /// given), or nil for audio made before this was kept, or not made yet.
    func voicedOf(_ line: ScriptScene.Line, _ language: ScriptLanguage, voice: String) -> [String: Any]? {
        guard let audio = audio(line, language, voice: voice) else {
            return nil
        }
        lock.lock()
        defer { lock.unlock() }
        return loadNotes()[Self.voicedPrefix + audio.lastPathComponent] as? [String: Any]
    }

    // MARK: - Reading the whole play

    private static let analysisPrefix = "analysis:"
    private static let contextLines = 30
    private static let maxPlayCharacters = 590_000

    /// The key of a play's reading: the script and its words, so an edited script is read again.
    private static func analysisKey(_ fullScene: ScriptScene, _ language: ScriptLanguage, _ script: String) -> String {
        let words = ([language.code, script] + fullScene.lines.map { $0.role + ":" + $0.text }).joined(separator: "\n")
        return analysisPrefix + hash(words)
    }

    /// The director's reading of the play, or nil until it has been read.
    func analysis(_ fullScene: ScriptScene, _ language: ScriptLanguage, script: String) -> [String: Any]? {
        lock.lock()
        defer { lock.unlock() }
        return loadNotes()[Self.analysisKey(fullScene, language, script)] as? [String: Any]
    }

    /// The app's id of a script, as the Worker sees it.
    static func scriptId(_ castKey: String) -> String {
        hash(castKey)
    }

    /// The play as the director reads it: "## SCENE:" where a named scene starts, then
    /// "ROLE: text (direction)". A very long play is cut at the Worker's limit.
    static func playText(_ fullScene: ScriptScene) -> String {
        var play = ""
        var section: String?
        for line in fullScene.lines {
            if line.section != section {
                section = line.section
                if !line.section.isEmpty {
                    play += "\n## SCENE: " + line.section + "\n"
                }
            }
            play += line.role + ": " + line.text
            for note in line.notes {
                play += " " + (note.hasPrefix("(") || note.hasPrefix("[") ? note : "(" + note + ")")
            }
            play += "\n"
            if play.count > maxPlayCharacters {
                break
            }
        }
        return String(play.prefix(maxPlayCharacters))
    }

    /// Asks the director to read the whole play; keeps the reading and the characters' genders.
    @discardableResult
    private func analyze(_ fullScene: ScriptScene, _ language: ScriptLanguage, title: String,
                         script: String) throws -> [String: Any] {
        let data = try post("/v1/analyze", body: Data(Self.playText(fullScene).utf8), type: "text/plain; charset=utf-8",
                            headers: ["X-Memo-Language": language.code,
                                      "X-Memo-Title": title.addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? "",
                                      "X-Memo-Script": script])
        guard let reading = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] else {
            throw Failure.busy
        }
        lock.lock()
        var stored = loadNotes()
        stored[Self.analysisKey(fullScene, language, script)] = reading
        for character in reading["characters"] as? [[String: Any]] ?? [] {
            Self.putGender(&stored, character["role"] as? String ?? "", character["gender"] as? String ?? "")
        }
        notes = stored
        let snapshot = stored
        lock.unlock()
        try saveNotes(snapshot)
        return reading
    }

    /// Reads the play before the cast is checked, so its genders fill in the cast, and keeps the
    /// reading for directing. Falls back to asking for the genders alone. `done` runs on the main thread.
    func analyzeFirst(_ fullScene: ScriptScene, _ language: ScriptLanguage, title: String, castKey: String,
                      done: @escaping () -> Void) {
        if !running {
            lock.lock()
            cancelled = false
            lock.unlock()
        }
        let script = Self.scriptId(castKey)
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                if self.analysis(fullScene, language, script: script) == nil {
                    try self.analyze(fullScene, language, title: title, script: script)
                }
                DispatchQueue.main.async(execute: done)
            } catch {
                DispatchQueue.main.async {
                    self.guessGenders(fullScene, language, title: title, done: done)
                }
            }
        }
    }

    /// The lines just before a line in the whole play, for the director's context.
    private static func linesBefore(_ fullScene: ScriptScene, _ first: ScriptScene.Line) -> [ScriptScene.Line] {
        guard let index = fullScene.lines.firstIndex(of: first), index > 0 else {
            return []
        }
        return Array(fullScene.lines[max(0, index - contextLines)..<index])
    }

    /// The part of the director's reading that a scene needs: the play, its people, this scene.
    static func contextFor(_ reading: [String: Any]?, section: String) -> String {
        guard let reading else {
            return ""
        }
        var context = ""
        if let summary = reading["summary"] as? String, !summary.isEmpty {
            context += "Story: " + summary + "\n"
        }
        // Built line by line with plain types: one long expression is too slow for the type checker.
        let characters = reading["characters"] as? [[String: Any]] ?? []
        if !characters.isEmpty {
            context += "Characters:\n"
            for character in characters {
                let role = character["role"] as? String ?? ""
                let gender = character["gender"] as? String ?? ""
                let age = character["age"] as? String ?? ""
                let description = character["description"] as? String ?? ""
                context += "- \(role) (\(gender), \(age)): \(description)\n"
            }
        }
        let relationships = reading["relationships"] as? [String] ?? []
        if !relationships.isEmpty {
            context += "Relationships:\n"
            for relationship in relationships {
                context += "- \(relationship)\n"
            }
        }
        if let scene = sceneOf(reading, section: section) {
            let name = section.isEmpty ? "" : " (\(section))"
            let summary = scene["summary"] as? String ?? ""
            context += "This scene\(name): \(summary)\n"
            let atmosphere = scene["atmosphere"] as? String ?? ""
            if !atmosphere.isEmpty {
                context += "Atmosphere everyone carries: \(atmosphere)\n"
            }
            let moods = scene["moods"] as? [[String: Any]] ?? []
            if !moods.isEmpty {
                context += "State of mind in this scene:\n"
                for mood in moods {
                    let role = mood["role"] as? String ?? ""
                    let feeling = mood["mood"] as? String ?? ""
                    context += "- \(role): \(feeling)\n"
                }
            }
        }
        return String(context.prefix(15_000))
    }

    /// The reading of one scene: the one with this name, or the only one.
    static func sceneOf(_ reading: [String: Any], section: String) -> [String: Any]? {
        let scenes = reading["scenes"] as? [[String: Any]] ?? []
        let wanted = section.trimmingCharacters(in: .whitespaces).lowercased()
        return scenes.first { ($0["scene"] as? String ?? "").trimmingCharacters(in: .whitespaces).lowercased() == wanted }
            ?? (scenes.count == 1 ? scenes[0] : nil)
    }

    /// Forgets the director's notes and audio of a scene's lines, and the reading of the play, so they
    /// are made again (with the reading) the next time the scene is prepared.
    func forget(_ fullScene: ScriptScene, _ scene: ScriptScene, _ language: ScriptLanguage, castKey: String) {
        lock.lock()
        var stored = loadNotes()
        for line in scene.lines {
            if let note = stored[Self.noteKey(line, language)] as? [String: Any], note["byActor"] as? Bool == true {
                // The actor's own fix stays, with its audio.
                continue
            }
            if let note = stored[Self.noteKey(line, language)] as? [String: Any] {
                for voice in Self.castVoices {
                    for fileExtension in ["wav", "m4a"] {
                        let file = audioFile(line, language, voice, note, fileExtension)
                        stored.removeValue(forKey: Self.voicedPrefix + file.lastPathComponent)
                        try? FileManager.default.removeItem(at: file)
                    }
                }
            }
            stored.removeValue(forKey: Self.noteKey(line, language))
        }
        stored.removeValue(forKey: Self.analysisKey(fullScene, language, Self.scriptId(castKey)))
        notes = stored
        let snapshot = stored
        lock.unlock()
        try? saveNotes(snapshot)
    }

    // MARK: - What the user bought

    /// The App Store transaction ids of the user's Premium, sent to the Worker before preparing.
    var subscriptionTransactions: [String] = [] {
        didSet {
            if subscriptionTransactions != oldValue {
                entitlementSentAt = .distantPast
            }
        }
    }
    private var entitlementSentAt = Date.distantPast

    /// Tells the Worker about the user's Premium at most every hour; it checks with the App Store.
    private func sendSubscription() {
        let transactions = subscriptionTransactions
        guard !transactions.isEmpty, Date().timeIntervalSince(entitlementSentAt) > 3600 else {
            return
        }
        if (try? post("/v1/entitlement", ["platform": "ios", "subscriptions": transactions, "extras": [String]()])) != nil {
            entitlementSentAt = Date()
        }
    }

    /// Has the Worker check and count paid extra scripts; `done` gets the ids it counted, on the main thread.
    func redeemExtras(_ ids: [String], done: @escaping ([String]) -> Void) {
        let subscriptions = subscriptionTransactions
        fixer.async {
            var accepted: [String] = []
            if let data = try? self.post("/v1/entitlement", ["platform": "ios", "subscriptions": subscriptions,
                                                             "extras": ids]),
               let answer = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                accepted = answer["accepted"] as? [String] ?? []
            }
            DispatchQueue.main.async { done(accepted) }
        }
    }

    // MARK: - Fixing one line

    /// Tries per line, so a line cannot be asked for again and again without end.
    static let fixesPerLine = 5
    private static let fixesPrefix = "fixes:"
    private let fixer = DispatchQueue(label: "studio-fix")

    /// A new take of one line: its direction and audio, kept aside until the actor chooses it.
    struct Take {
        let note: [String: Any]
        let audio: URL
        let voice: String
        let voiced: [String: Any]
    }

    func fixesLeft(_ line: ScriptScene.Line, _ language: ScriptLanguage) -> Int {
        lock.lock()
        defer { lock.unlock() }
        let used = (loadNotes()[Self.fixesPrefix + Self.noteKey(line, language)] as? NSNumber)?.intValue ?? 0
        return max(0, Self.fixesPerLine - used)
    }

    /// Has the director direct one line again the way the actor asked, and voices it, as a take the
    /// actor can hear before keeping it. `done` runs on the main thread, with the take or why it failed.
    func fix(_ fullScene: ScriptScene, _ line: ScriptScene.Line, _ language: ScriptLanguage, title: String,
             castKey: String, voice: String, wishes: [String], request: String, word: String, say: String,
             done: @escaping (Result<Take, Error>) -> Void) {
        let script = Self.scriptId(castKey)
        fixer.async {
            do {
                if !self.running {
                    self.lock.lock()
                    self.cancelled = false
                    self.lock.unlock()
                }
                self.lock.lock()
                var stored = self.loadNotes()
                let key = Self.fixesPrefix + Self.noteKey(line, language)
                stored[key] = ((stored[key] as? NSNumber)?.intValue ?? 0) + 1
                self.notes = stored
                self.lock.unlock()
                try self.saveNotes(stored)
                let sayings: [[String: String]] = word.isEmpty || say.isEmpty ? [] : [["word": word, "say": say]]
                let body: [String: Any] = [
                    "language": language.code, "title": title, "script": script,
                    "lines": Self.linesJson([line]), "before": Self.linesJson(Self.linesBefore(fullScene, line)),
                    "context": Self.contextFor(self.analysis(fullScene, language, script: script), section: line.section),
                    "current": self.note(line, language) ?? [:],
                    "wishes": wishes, "request": request, "pronunciations": sayings,
                ]
                let data = try self.post("/v1/revise", body)
                guard let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any],
                      var note = answer["line"] as? [String: Any] else {
                    throw Failure.busy
                }
                note["byActor"] = true
                note["request"] = request
                let audio = try self.speak(note, language, voice, script)
                let take = FileManager.default.temporaryDirectory.appendingPathComponent("studio-take-\(UUID().uuidString).wav")
                try audio.write(to: take, options: [.atomic])
                let made = Take(note: note, audio: take, voice: voice, voiced: self.lastVoiced)
                DispatchQueue.main.async { done(.success(made)) }
            } catch {
                DispatchQueue.main.async { done(.failure(error)) }
            }
        }
    }

    /// Why a fix failed, as the message key to show.
    static func fixFailureKey(_ error: Error) -> String {
        if let failure = error as? Failure, case .limit(let stop) = failure {
            return stop == .scriptLimit || stop == .extraScript ? "studio_script_limit"
                : stop == .premiumRequired ? "studio_value" : "studio_monthly_limit"
        }
        return "fix_failed"
    }

    /// Keeps a take: it becomes the line's direction and audio, and the old audio goes.
    func keep(_ take: Take, _ line: ScriptScene.Line, _ language: ScriptLanguage) {
        lock.lock()
        var stored = loadNotes()
        if let old = stored[Self.noteKey(line, language)] as? [String: Any] {
            for fileExtension in ["wav", "m4a"] {
                let previous = audioFile(line, language, take.voice, old, fileExtension)
                stored.removeValue(forKey: Self.voicedPrefix + previous.lastPathComponent)
                try? FileManager.default.removeItem(at: previous)
            }
        }
        stored[Self.noteKey(line, language)] = take.note
        let target = audioFile(line, language, take.voice, take.note, "wav")
        stored[Self.voicedPrefix + target.lastPathComponent] = take.voiced
        notes = stored
        lock.unlock()
        try? FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
        try? FileManager.default.removeItem(at: target)
        try? FileManager.default.copyItem(at: take.audio, to: target)
        try? saveNotes(stored)
        discard(take)
        objectWillChange.send()
    }

    func discard(_ take: Take) {
        try? FileManager.default.removeItem(at: take.audio)
    }

    /// Whether the director has given a gender for every character.
    func knowsGenders(_ roles: [String]) -> Bool {
        genders(roles).count >= roles.count
    }

    /// Asks the director for the characters' likely genders before the cast is chosen, so women and
    /// men start with voices of their gender. Quiet on failure: the cast screen works without it.
    /// `done` runs on the main thread.
    func guessGenders(_ scene: ScriptScene, _ language: ScriptLanguage, title: String, done: @escaping () -> Void) {
        if !running {
            lock.lock()
            cancelled = false
            lock.unlock()
        }
        DispatchQueue.global(qos: .userInitiated).async {
            // The opening of the scene, within the Worker's size limits, is enough to tell.
            var lines: [[String: Any]] = []
            var characters = 0
            for line in scene.lines.prefix(80) where characters <= 12_000 {
                characters += min(line.text.count, 400)
                lines.append(["role": line.role, "text": String(line.text.prefix(400)),
                              "notes": line.notes.map { String($0.prefix(300)) }])
            }
            let body: [String: Any] = ["language": language.code, "title": title, "lines": lines]
            if let data = try? self.post("/v1/cast", body),
               let answer = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] {
                self.lock.lock()
                var stored = self.loadNotes()
                for member in answer["cast"] as? [[String: Any]] ?? [] {
                    Self.putGender(&stored, member["role"] as? String ?? "", member["gender"] as? String ?? "")
                }
                self.notes = stored
                let snapshot = stored
                self.lock.unlock()
                try? self.saveNotes(snapshot)
            }
            DispatchQueue.main.async(execute: done)
        }
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
            "undertone": note["undertone"] as? String ?? "",
            "segments": note["segments"] as? [Any] ?? [],
            "pronunciations": note["pronunciations"] as? [Any] ?? [],
        ]
        let data = try post("/v1/speak", body)
        guard let answer = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw Failure.busy
        }
        lastVoiced = ["model": answer["modelVersion"] as? String ?? answer["model"] as? String ?? "",
                      "source": header("X-Memo-Source"), "style": header("X-Memo-Style"),
                      "text": header("X-Memo-Text"), "voice": voice, "at": Date().timeIntervalSince1970]
        // Gemini's interaction: steps of model output whose content holds the audio.
        let steps = (answer["steps"] as? [[String: Any]] ?? []).flatMap { $0["content"] as? [[String: Any]] ?? [] }
            .filter { $0["type"] as? String == "audio" }
            .map { (data: $0["data"] as? String, type: $0["mime_type"] as? String ?? "") }
        // An older voice's answer: candidates whose parts carry raw PCM.
        let candidates = ((((answer["candidates"] as? [[String: Any]])?.first?["content"] as? [String: Any])?["parts"]
            as? [[String: Any]]) ?? []).compactMap { $0["inlineData"] as? [String: Any] }
            .map { (data: $0["data"] as? String, type: $0["mimeType"] as? String ?? "") }
        for part in steps + candidates {
            if let base64 = part.data, let audio = Data(base64Encoded: base64) {
                let type = part.type
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
        try post(path, body: try JSONSerialization.data(withJSONObject: body), type: "application/json", headers: [:])
    }

    private func post(_ path: String, body: Data, type: String, headers: [String: String]) throws -> Data {
        var last: Error = Failure.busy
        for attempt in 0..<4 {
            if isCancelled {
                throw Failure.cancelled
            }
            var request = URLRequest(url: Self.worker.appendingPathComponent(path), timeoutInterval: 240)
            request.httpMethod = "POST"
            request.setValue(type, forHTTPHeaderField: "Content-Type")
            request.setValue("Bearer " + (try token(fresh: attempt > 0)), forHTTPHeaderField: "Authorization")
            for (name, value) in headers {
                request.setValue(value, forHTTPHeaderField: name)
            }
            request.httpBody = body
            let (data, status, headers) = try Self.send(request)
            switch status {
            case 200:
                lastHeaders = headers
                return data
            case 429:
                let answer = String(decoding: data, as: UTF8.self)
                throw Failure.limit(answer.contains("script_limit") ? .scriptLimit
                    : answer.contains("monthly_limit") ? .monthlyLimit : .dailyLimit)
            case 401:
                idToken = nil
                last = Failure.notSignedIn
            case 402:
                throw Failure.limit(String(decoding: data, as: UTF8.self).contains("extra_script_needed")
                    ? .extraScript : .premiumRequired)
            case 500... where String(decoding: data, as: UTF8.self).contains("without audio"):
                // The voice gave no audio for this line; asking again would not change that.
                throw Failure.refused(String(decoding: data, as: UTF8.self))
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
            let (data, status, _) = try Self.send(request)
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
        let (data, status, _) = try Self.send(request)
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
    private static func send(_ request: URLRequest) throws -> (Data, Int, [AnyHashable: Any]) {
        let semaphore = DispatchSemaphore(value: 0)
        var result: (Data, Int, [AnyHashable: Any])?
        var failure: Error?
        let task = URLSession.shared.dataTask(with: request) { data, response, error in
            if let error {
                failure = error
            } else {
                let http = response as? HTTPURLResponse
                result = (data ?? Data(), http?.statusCode ?? 0, http?.allHeaderFields ?? [:])
            }
            semaphore.signal()
        }
        tasksLock.lock()
        openTasks[task.taskIdentifier] = task
        tasksLock.unlock()
        task.resume()
        semaphore.wait()
        tasksLock.lock()
        openTasks.removeValue(forKey: task.taskIdentifier)
        tasksLock.unlock()
        if let failure {
            throw failure
        }
        return result ?? (Data(), 0, [:])
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

    static func isLimit(_ error: Error) -> Bool {
        if let failure = error as? Failure, case .limit(_) = failure {
            return true
        }
        return false
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

/// Failures of work done side by side: a limit, or failures in a row, end the run for all.
private final class Failures {
    private let lock = NSLock()
    private var inARow = 0
    private var fatal: Error?
    private static let limit = 3

    /// Runs one piece unless the run is over; true when it worked.
    func attempt(cancelled: () -> Bool, _ work: () throws -> Void) -> Bool {
        lock.lock()
        let over = fatal != nil
        lock.unlock()
        if over || cancelled() {
            return false
        }
        do {
            try work()
            lock.lock()
            inARow = 0
            lock.unlock()
            return true
        } catch {
            lock.lock()
            inARow += 1
            if StudioVoices.isLimit(error) || inARow >= Self.limit {
                fatal = fatal ?? error
            }
            lock.unlock()
            return false
        }
    }

    func rethrow() throws {
        lock.lock()
        let error = fatal
        lock.unlock()
        if let error {
            throw error
        }
    }
}
