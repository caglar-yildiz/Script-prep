import AVFoundation
import CryptoKit
import Foundation
import MemoCore
import Network
import SherpaOnnx

/// Premium: natural voices in many languages, generated on the phone with Supertonic 3 through
/// sherpa-onnx. The model (about 145 MB) is downloaded on request and runs offline afterwards.
final class NeuralVoice: NSObject, ObservableObject, URLSessionDownloadDelegate {
    enum State {
        case notInstalled, downloading, installed, loading, ready, failed
    }

    static let shared = NeuralVoice()
    static let speakers = VoiceCast.speakers
    static let sessionIdentifier = "com.memoeverything.app.ai-voice"

    private static let folderName = "supertonic-3-tr"
    private static let source = "https://huggingface.co/csukuangfj2/"
        + "sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4/"
    private static let files: [(name: String, size: Int64, sha256: String)] = [
        ("duration_predictor.int8.onnx", 3_700_147, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        ("text_encoder.int8.onnx", 36_416_150, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        ("vector_estimator.int8.onnx", 78_400_833, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        ("vocoder.int8.onnx", 25_991_073, "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
        ("tts.json", 8_253, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        ("unicode_indexer.bin", 262_144, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        ("voice.bin", 517_168, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
    ]
    /// Diffusion steps: 8 matches the published samples; fewer is faster but rougher.
    private static let steps = 8
    private static let cacheSize = 16
    /// Downloads in progress, kept so a download started earlier is known after a relaunch.
    private static let downloadsKey = "ai_voice_downloads"

    @Published private(set) var state: State = .notInstalled
    @Published private(set) var lastError = ""
    @Published private(set) var downloaded: Int64 = 0
    @Published private(set) var waitingForNetwork = false

    /// Called by the app delegate when iOS wakes the app for finished background downloads.
    var backgroundCompletion: (() -> Void)?

    private let folder: URL
    private let synthesis = DispatchQueue(label: "ai-voice.synthesis", qos: .userInitiated)
    private let lock = NSLock()
    private var playToken = 0
    private var cache: [String: ([Float], Int)] = [:]
    private var cacheOrder: [String] = []
    private var engine: SherpaOnnxOfflineTtsWrapper?
    private var sampleRate = 44_100
    private var cancelDownloadRequested = false
    private var progressByFile: [String: Int64] = [:]
    private var failedFiles: [String: String] = [:]
    private let audio = AVAudioEngine()
    private let player = AVAudioPlayerNode()
    private let monitor = NWPathMonitor()
    private lazy var session: URLSession = {
        let configuration = URLSessionConfiguration.background(withIdentifier: Self.sessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        configuration.isDiscretionary = false
        configuration.allowsCellularAccess = true
        return URLSession(configuration: configuration, delegate: self, delegateQueue: nil)
    }()

    override private init() {
        let support = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        folder = support.appendingPathComponent(Self.folderName, isDirectory: true)
        super.init()
        audio.attach(player)
        if isInstalled() {
            state = .installed
        } else if !(UserDefaults.standard.stringArray(forKey: Self.downloadsKey) ?? []).isEmpty {
            // A download started earlier kept running in the background.
            state = .downloading
            reconnect()
        }
        monitor.pathUpdateHandler = { [weak self] path in
            DispatchQueue.main.async {
                self?.waitingForNetwork = path.status != .satisfied
            }
        }
        monitor.start(queue: DispatchQueue(label: "ai-voice.network"))
    }

    /// The bundled native libraries run on every iPhone and iPad this app supports.
    static var isSupported: Bool {
        true
    }

    static var downloadBytes: Int64 {
        files.reduce(0) { $0 + $1.size }
    }

    var isReady: Bool {
        state == .ready
    }

    /// Download progress from 0 to 100.
    var progress: Int {
        Int(min(100, downloaded * 100 / Self.downloadBytes))
    }

    private func isInstalled() -> Bool {
        guard FileManager.default.fileExists(atPath: folder.appendingPathComponent(".complete").path) else {
            return false
        }
        return Self.files.allSatisfy { size(of: folder.appendingPathComponent($0.name)) == $0.size }
    }

    private func size(of url: URL) -> Int64 {
        ((try? FileManager.default.attributesOfItem(atPath: url.path))?[.size] as? NSNumber)?.int64Value ?? -1
    }

    private func setState(_ value: State) {
        if Thread.isMainThread {
            state = value
        } else {
            DispatchQueue.main.async {
                self.state = value
            }
        }
    }

    private func fail(_ reason: String) {
        DispatchQueue.main.async {
            self.lastError = reason
            self.state = .failed
        }
    }

    // MARK: - Download

    func download() {
        guard state == .notInstalled || state == .failed else {
            return
        }
        cancelDownloadRequested = false
        failedFiles.removeAll()
        progressByFile.removeAll()
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            var values = URLResourceValues()
            values.isExcludedFromBackup = true
            var excluded = folder
            try excluded.setResourceValues(values)
        } catch {
            fail(describe(error))
            return
        }
        let needed = Self.files.filter { size(of: folder.appendingPathComponent($0.name)) != $0.size }
        let free = ((try? folder.resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]))?
            .volumeAvailableCapacityForImportantUsage) ?? Int64.max
        if free < needed.reduce(Int64(0), { $0 + $1.size }) + 32 * 1024 * 1024 {
            fail("Not enough free space")
            return
        }
        state = .downloading
        let neededNames = needed.map { $0.name }
        downloaded = Self.files.filter { !neededNames.contains($0.name) }.reduce(Int64(0)) { $0 + $1.size }
        if needed.isEmpty {
            finishDownloads()
            return
        }
        UserDefaults.standard.set(neededNames, forKey: Self.downloadsKey)
        for file in needed {
            guard let url = URL(string: Self.source + file.name) else {
                continue
            }
            let task = session.downloadTask(with: url)
            task.taskDescription = file.name
            task.resume()
        }
    }

    /// Picks up downloads that continued while the app was closed.
    private func reconnect() {
        session.getAllTasks { tasks in
            let running = tasks.filter { $0.state == .running || $0.state == .suspended }
            if running.isEmpty {
                // Everything finished, or failed, while the app was away; check the files.
                self.finishDownloads()
            }
        }
    }

    /// After a failure: loads an installed model again, otherwise resumes the download.
    func retry() {
        guard state == .failed else {
            return
        }
        if isInstalled() {
            state = .installed
            load()
        } else {
            download()
        }
    }

    func cancelDownload() {
        cancelDownloadRequested = true
        session.getAllTasks { tasks in
            tasks.forEach { $0.cancel() }
        }
        UserDefaults.standard.removeObject(forKey: Self.downloadsKey)
        state = .notInstalled
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didWriteData bytesWritten: Int64,
                    totalBytesWritten: Int64, totalBytesExpectedToWrite: Int64) {
        guard let name = downloadTask.taskDescription else {
            return
        }
        lock.lock()
        progressByFile[name] = totalBytesWritten
        let running = progressByFile.values.reduce(0, +)
        lock.unlock()
        let finished = Self.files.filter { !pendingNames().contains($0.name) }.reduce(Int64(0)) { $0 + $1.size }
        DispatchQueue.main.async {
            self.downloaded = min(Self.downloadBytes, finished + running)
        }
    }

    func urlSession(_ session: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {
        guard let name = downloadTask.taskDescription else {
            return
        }
        let target = folder.appendingPathComponent(name + ".download")
        if let response = downloadTask.response as? HTTPURLResponse, !(200...299).contains(response.statusCode) {
            lock.lock()
            failedFiles[name] = "HTTP \(response.statusCode) for \(name)"
            lock.unlock()
            return
        }
        do {
            try FileManager.default.createDirectory(at: folder, withIntermediateDirectories: true)
            try? FileManager.default.removeItem(at: target)
            try FileManager.default.moveItem(at: location, to: target)
        } catch {
            lock.lock()
            failedFiles[name] = describe(error)
            lock.unlock()
        }
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        guard let name = task.taskDescription else {
            return
        }
        if let error, !cancelDownloadRequested {
            lock.lock()
            failedFiles[name] = describe(error)
            lock.unlock()
        }
        var pending = pendingNames()
        pending.removeAll { $0 == name }
        UserDefaults.standard.set(pending, forKey: Self.downloadsKey)
        if pending.isEmpty && !cancelDownloadRequested {
            finishDownloads()
        }
    }

    func urlSessionDidFinishEvents(forBackgroundURLSession session: URLSession) {
        DispatchQueue.main.async {
            self.backgroundCompletion?()
            self.backgroundCompletion = nil
        }
    }

    private func pendingNames() -> [String] {
        UserDefaults.standard.stringArray(forKey: Self.downloadsKey) ?? []
    }

    /// Verifies every finished file before the model counts as installed.
    private func finishDownloads() {
        synthesis.async {
            self.lock.lock()
            let failure = self.failedFiles.values.first
            self.lock.unlock()
            UserDefaults.standard.removeObject(forKey: Self.downloadsKey)
            if let failure {
                self.fail(failure)
                return
            }
            do {
                for file in Self.files {
                    let target = self.folder.appendingPathComponent(file.name)
                    let done = self.folder.appendingPathComponent(file.name + ".download")
                    if self.size(of: target) == file.size && !FileManager.default.fileExists(atPath: done.path) {
                        continue
                    }
                    guard try Self.sha256(done) == file.sha256 else {
                        try? FileManager.default.removeItem(at: done)
                        throw NSError(domain: "NeuralVoice", code: 1,
                                      userInfo: [NSLocalizedDescriptionKey: "Checksum mismatch for " + file.name])
                    }
                    try? FileManager.default.removeItem(at: target)
                    try FileManager.default.moveItem(at: done, to: target)
                }
                FileManager.default.createFile(atPath: self.folder.appendingPathComponent(".complete").path,
                                               contents: Data())
                DispatchQueue.main.async {
                    self.downloaded = Self.downloadBytes
                    self.state = .installed
                }
            } catch {
                self.fail(self.describe(error))
            }
        }
    }

    private static func sha256(_ url: URL) throws -> String {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var hasher = SHA256()
        while let chunk = try handle.read(upToCount: 1 << 20), !chunk.isEmpty {
            hasher.update(data: chunk)
        }
        return hasher.finalize().map { String(format: "%02x", $0) }.joined()
    }

    private func describe(_ error: Error) -> String {
        let nsError = error as NSError
        return "\(nsError.domain) \(nsError.code): \(nsError.localizedDescription)"
    }

    /// Removes the model and frees its memory.
    func delete() {
        stop()
        cancelDownloadRequested = true
        session.getAllTasks { tasks in
            tasks.forEach { $0.cancel() }
        }
        UserDefaults.standard.removeObject(forKey: Self.downloadsKey)
        synthesis.async {
            self.engine = nil
            self.clearCache()
            try? FileManager.default.removeItem(at: self.folder)
            DispatchQueue.main.async {
                self.downloaded = 0
                self.state = .notInstalled
            }
        }
    }

    // MARK: - Engine

    /// Loads the model into memory in the background; observers hear when it is ready.
    func load() {
        guard state == .installed else {
            return
        }
        state = .loading
        synthesis.async {
            autoreleasepool {
                self.createEngine()
            }
        }
    }

    /// Must run on the synthesis queue. The C strings of the config live until the pool drains.
    private func createEngine() {
        do {
            let path = { (name: String) in self.folder.appendingPathComponent(name).path }
            let supertonic = sherpaOnnxOfflineTtsSupertonicModelConfig(
                durationPredictor: path("duration_predictor.int8.onnx"),
                textEncoder: path("text_encoder.int8.onnx"),
                vectorEstimator: path("vector_estimator.int8.onnx"),
                vocoder: path("vocoder.int8.onnx"),
                ttsJson: path("tts.json"),
                unicodeIndexer: path("unicode_indexer.bin"),
                voiceStyle: path("voice.bin"))
            let threads = max(1, min(4, ProcessInfo.processInfo.activeProcessorCount / 2))
            let model = sherpaOnnxOfflineTtsModelConfig(numThreads: threads, debug: 0, provider: "cpu",
                                                        supertonic: supertonic)
            var config = sherpaOnnxOfflineTtsConfig(model: model)
            let wrapper = withUnsafePointer(to: &config) { SherpaOnnxOfflineTtsWrapper(config: $0) }
            guard wrapper.tts != nil else {
                self.fail("Could not load the voice model")
                return
            }
            self.engine = wrapper
            self.sampleRate = Int(wrapper.sampleRate)
            self.setState(.ready)
        }
    }

    /// Frees the model memory; it can be loaded again later.
    func unload() {
        stop()
        synthesis.async {
            self.engine = nil
            self.clearCache()
            DispatchQueue.main.async {
                if self.state == .ready || self.state == .loading {
                    self.state = self.isInstalled() ? .installed : .notInstalled
                }
            }
        }
    }

    private static func key(_ text: String, _ language: String, _ speaker: Int, _ speed: Float) -> String {
        "\(language)|\(speaker)|\(speed)|\(text)"
    }

    private func clearCache() {
        lock.lock()
        cache.removeAll()
        cacheOrder.removeAll()
        lock.unlock()
    }

    private func cached(_ key: String) -> ([Float], Int)? {
        lock.lock()
        defer { lock.unlock() }
        if let value = cache[key] {
            cacheOrder.removeAll { $0 == key }
            cacheOrder.append(key)
            return value
        }
        return nil
    }

    /// Must run on the synthesis queue.
    private func generate(_ text: String, _ language: String, _ speaker: Int, _ speed: Float) -> ([Float], Int)? {
        let key = Self.key(text, language, speaker, speed)
        if let value = cached(key) {
            return value
        }
        guard let engine else {
            return nil
        }
        let config = SherpaOnnxGenerationConfigSwift(speed: speed, sid: max(0, min(Self.speakers - 1, speaker)),
                                                     numSteps: Self.steps, extra: ["lang": language])
        let audio = engine.generateWithConfig(text: text, config: config, callback: nil, arg: nil)
        let samples = audio.samples
        let rate = Int(audio.sampleRate) > 0 ? Int(audio.sampleRate) : sampleRate
        lock.lock()
        cache[key] = (samples, rate)
        cacheOrder.append(key)
        while cacheOrder.count > Self.cacheSize {
            cache.removeValue(forKey: cacheOrder.removeFirst())
        }
        lock.unlock()
        return (samples, rate)
    }

    /// Generates a line ahead of time so it plays without delay.
    func prefetch(_ text: String, language: String, speaker: Int, speed: Float) {
        guard state == .ready, cached(Self.key(text, language, speaker, speed)) == nil else {
            return
        }
        synthesis.async {
            _ = self.generate(text, language, speaker, speed)
        }
    }

    /// Speaks a line; `done` runs on the main thread with false on failure, and not at all once
    /// playback was stopped.
    func speak(_ text: String, language: String, speaker: Int, speed: Float, done: @escaping (Bool) -> Void) {
        lock.lock()
        playToken += 1
        let token = playToken
        lock.unlock()
        synthesis.async {
            let generated = self.generate(text, language, speaker, speed)
            guard self.isCurrent(token) else {
                return
            }
            guard let (samples, rate) = generated, !samples.isEmpty else {
                DispatchQueue.main.async {
                    if self.isCurrent(token) {
                        done(false)
                    }
                }
                return
            }
            DispatchQueue.main.async {
                self.play(samples, rate: rate, token: token, done: done)
            }
        }
    }

    private func isCurrent(_ token: Int) -> Bool {
        lock.lock()
        defer { lock.unlock() }
        return token == playToken
    }

    private func play(_ samples: [Float], rate: Int, token: Int, done: @escaping (Bool) -> Void) {
        guard isCurrent(token),
              let format = AVAudioFormat(standardFormatWithSampleRate: Double(rate), channels: 1),
              let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: AVAudioFrameCount(samples.count)) else {
            done(false)
            return
        }
        buffer.frameLength = AVAudioFrameCount(samples.count)
        samples.withUnsafeBufferPointer { source in
            buffer.floatChannelData![0].update(from: source.baseAddress!, count: samples.count)
        }
        AudioSessions.usePlayback()
        audio.disconnectNodeOutput(player)
        audio.connect(player, to: audio.mainMixerNode, format: format)
        do {
            if !audio.isRunning {
                try audio.start()
            }
        } catch {
            done(false)
            return
        }
        player.stop()
        player.scheduleBuffer(buffer, at: nil, options: [], completionCallbackType: .dataPlayedBack) { [weak self] _ in
            DispatchQueue.main.async {
                if self?.isCurrent(token) == true {
                    done(true)
                }
            }
        }
        player.play()
    }

    /// Stops the current line; its callback will not run.
    func stop() {
        lock.lock()
        playToken += 1
        lock.unlock()
        if Thread.isMainThread {
            player.stop()
        } else {
            DispatchQueue.main.async {
                self.player.stop()
            }
        }
    }
}

