import AVFoundation
import Speech

protocol HandsFreeListener: AnyObject {
    func onListening()
    func onPartial(_ text: String)
    func onHeard(_ text: String)
    /// Nothing recognisable was said before the recogniser gave up.
    func onSilence()
    func onFailure(permanent: Bool)
}

/// Premium: listens while the actor says a line so the rehearsal can move on by itself.
final class HandsFree {
    typealias Listener = HandsFreeListener

    /// Like Android's complete-silence length: this long without new words ends the line.
    private static let silenceSeconds = 1.8
    /// Nothing said at all for this long counts as silence.
    private static let noSpeechSeconds = 6.0

    private let engine = AVAudioEngine()
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private weak var listener: Listener?
    private var token = 0
    private var best = ""
    private var timer: Timer?

    static var isAvailable: Bool {
        SFSpeechRecognizer()?.isAvailable ?? false
    }

    static var isAuthorized: Bool {
        SFSpeechRecognizer.authorizationStatus() == .authorized
            && AVAudioSession.sharedInstance().recordPermission == .granted
    }

    /// Asks for speech recognition and the microphone; `done` runs on the main thread.
    static func requestPermission(_ done: @escaping (Bool) -> Void) {
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else {
                DispatchQueue.main.async { done(false) }
                return
            }
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                DispatchQueue.main.async { done(granted) }
            }
        }
    }

    var isListening: Bool {
        listener != nil
    }

    func start(_ target: Listener, locale: Locale) {
        stop()
        token += 1
        let current = token
        listener = target
        best = ""
        guard Self.isAuthorized else {
            listener = nil
            target.onFailure(permanent: true)
            return
        }
        guard let recognizer = SFSpeechRecognizer(locale: locale), recognizer.isAvailable else {
            listener = nil
            target.onFailure(permanent: false)
            return
        }
        AudioSessions.usePlayAndRecord()
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        if recognizer.supportsOnDeviceRecognition {
            // Prefer offline recognition, like Android's EXTRA_PREFER_OFFLINE.
            request.requiresOnDeviceRecognition = true
        }
        self.request = request
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        guard format.sampleRate > 0 else {
            listener = nil
            target.onFailure(permanent: false)
            return
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { buffer, _ in
            request.append(buffer)
        }
        do {
            engine.prepare()
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            listener = nil
            target.onFailure(permanent: false)
            return
        }
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            DispatchQueue.main.async {
                self?.handle(result: result, error: error, token: current)
            }
        }
        target.onListening()
        restartTimer(Self.noSpeechSeconds, token: current)
    }

    private func handle(result: SFSpeechRecognitionResult?, error: Error?, token current: Int) {
        guard current == token, let target = listener else {
            return
        }
        if let result {
            let text = result.bestTranscription.formattedString
            if !text.isEmpty && text != best {
                best = text
                target.onPartial(text)
                restartTimer(Self.silenceSeconds, token: current)
            }
            if result.isFinal {
                finish(token: current)
                return
            }
        }
        if error != nil {
            if best.isEmpty {
                end()
                target.onSilence()
            } else {
                finish(token: current)
            }
        }
    }

    private func restartTimer(_ seconds: Double, token current: Int) {
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { [weak self] _ in
            self?.finish(token: current)
        }
    }

    private func finish(token current: Int) {
        guard current == token, let target = listener else {
            return
        }
        let heard = best
        end()
        if heard.isEmpty {
            target.onSilence()
        } else {
            target.onHeard(heard)
        }
    }

    private func end() {
        listener = nil
        timer?.invalidate()
        timer = nil
        task?.cancel()
        task = nil
        request?.endAudio()
        request = nil
        if engine.isRunning {
            engine.stop()
        }
        engine.inputNode.removeTap(onBus: 0)
    }

    func stop() {
        token += 1
        end()
    }
}
