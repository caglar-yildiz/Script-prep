import AVFoundation
import MemoCore

/// The phone's own text-to-speech voices, which read partner lines like Android's TextToSpeech.
final class DeviceVoice: NSObject, AVSpeechSynthesizerDelegate {
    private let synthesizer = AVSpeechSynthesizer()
    private var finished: [ObjectIdentifier: (Bool) -> Void] = [:]

    override init() {
        super.init()
        synthesizer.delegate = self
    }

    /// Every installed voice of a language, in a stable order the settings numbering follows.
    static func voices(_ language: ScriptLanguage) -> [AVSpeechSynthesisVoice] {
        AVSpeechSynthesisVoice.speechVoices()
            .filter { $0.language.hasPrefix(language.code) }
            .sorted { $0.identifier < $1.identifier }
    }

    static func isAvailable(_ language: ScriptLanguage) -> Bool {
        !voices(language).isEmpty || AVSpeechSynthesisVoice(language: language.tag) != nil
    }

    /// Reads a line; `done` runs on the main thread with false when it was cut short by an error.
    func speak(_ text: String, language: ScriptLanguage, voice: String?, pitch: Float, rate: Float,
               done: @escaping (Bool) -> Void) {
        stop()
        AudioSessions.usePlayback()
        let utterance = AVSpeechUtterance(string: text)
        if let voice, !voice.isEmpty, let chosen = AVSpeechSynthesisVoice(identifier: voice) {
            utterance.voice = chosen
        } else {
            utterance.voice = AVSpeechSynthesisVoice(language: language.tag)
                ?? Self.voices(language).first
        }
        utterance.pitchMultiplier = max(0.5, min(2, pitch))
        utterance.rate = Self.rate(rate)
        finished[ObjectIdentifier(utterance)] = done
        synthesizer.speak(utterance)
    }

    func stop() {
        let pending = finished
        finished.removeAll()
        if synthesizer.isSpeaking {
            synthesizer.stopSpeaking(at: .immediate)
        }
        _ = pending
    }

    /// Android's speech rate 1.0 is the normal pace; AVSpeech's normal pace is its default rate.
    static func rate(_ android: Float) -> Float {
        let value = AVSpeechUtteranceDefaultSpeechRate * powf(max(0.1, android), 0.75)
        return max(AVSpeechUtteranceMinimumSpeechRate, min(AVSpeechUtteranceMaximumSpeechRate, value))
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didFinish utterance: AVSpeechUtterance) {
        complete(utterance, ok: true)
    }

    func speechSynthesizer(_ synthesizer: AVSpeechSynthesizer, didCancel utterance: AVSpeechUtterance) {
        finished.removeValue(forKey: ObjectIdentifier(utterance))
    }

    private func complete(_ utterance: AVSpeechUtterance, ok: Bool) {
        guard let done = finished.removeValue(forKey: ObjectIdentifier(utterance)) else {
            return
        }
        DispatchQueue.main.async {
            done(ok)
        }
    }
}

/// Audio session settings shared by the voices, the microphone and the camera.
enum AudioSessions {
    static func usePlayback() {
        let session = AVAudioSession.sharedInstance()
        if session.category == .playAndRecord {
            return
        }
        try? session.setCategory(.playback, mode: .spokenAudio, options: [.duckOthers])
        try? session.setActive(true)
    }

    /// Lines are read aloud and the microphone listens in turn, through the speaker.
    static func usePlayAndRecord() {
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.playAndRecord, mode: .default,
                                 options: [.defaultToSpeaker, .allowBluetooth, .allowBluetoothA2DP, .duckOthers])
        try? session.setActive(true)
    }

    static func release() {
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }
}

/// The short beep when it is the actor's turn.
final class TurnCue {
    private var player: AVAudioPlayer?

    func play(millis: Int) {
        if player == nil {
            player = try? AVAudioPlayer(data: Self.beep(millis: millis))
            player?.volume = 0.7
        }
        player?.currentTime = 0
        player?.play()
    }

    /// A soft 880 Hz tone with short fades, as a WAV file.
    private static func beep(millis: Int) -> Data {
        let rate = 44_100
        let count = rate * millis / 1000
        var samples = [Int16](repeating: 0, count: count)
        let fade = rate / 100
        for i in 0..<count {
            let envelope = min(1, Double(min(i, count - i)) / Double(fade))
            samples[i] = Int16(sin(2 * Double.pi * 880 * Double(i) / Double(rate)) * 0.5 * envelope * Double(Int16.max))
        }
        var data = Data()
        func append<T>(_ value: T) {
            withUnsafeBytes(of: value) { data.append(contentsOf: $0) }
        }
        data.append(contentsOf: Array("RIFF".utf8))
        append(UInt32(36 + count * 2).littleEndian)
        data.append(contentsOf: Array("WAVEfmt ".utf8))
        append(UInt32(16).littleEndian)
        append(UInt16(1).littleEndian)
        append(UInt16(1).littleEndian)
        append(UInt32(rate).littleEndian)
        append(UInt32(rate * 2).littleEndian)
        append(UInt16(2).littleEndian)
        append(UInt16(16).littleEndian)
        data.append(contentsOf: Array("data".utf8))
        append(UInt32(count * 2).littleEndian)
        for sample in samples {
            append(sample.littleEndian)
        }
        return data
    }
}
