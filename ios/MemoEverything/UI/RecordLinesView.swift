import AVFoundation
import MemoCore
import SwiftUI

/// Records the actor's own lines of a shared text, each after its cue line.
final class LineRecorder: NSObject, ObservableObject, AVAudioRecorderDelegate {
    enum State {
        case idle, recording, playing
    }

    struct Take {
        let line: ScriptScene.Line
        let cue: ScriptScene.Line?
        let key: String
    }

    private static let minimumSeconds = 0.4

    @Published var state: State = .idle
    @Published var index = 0
    @Published var elapsed: TimeInterval = 0
    @Published var uploading = Set<String>()
    @Published var message: String?
    let takes: [Take]
    let productionId: String
    let title: String
    let name: String
    private let recordings = EnsembleRecordings()
    private var recorder: AVAudioRecorder?
    private var player: AVAudioPlayer?
    private var playerDone: PlayerDone?
    private var timer: Timer?
    private var started = Date()
    private let file = FileManager.default.temporaryDirectory.appendingPathComponent("take.m4a")

    init(production: Ensemble.Production, roles: [String], name: String) {
        productionId = production.id
        title = production.title
        self.name = name
        let scene = production.category.parse(production.text, narrator: L("solo_role"))
        takes = EnsembleRules.ownLines(scene, roles: roles).map {
            Take(line: $0.line, cue: $0.cue, key: EnsembleRules.lineKey(role: $0.line.role, text: $0.line.text))
        }
        super.init()
        index = takes.firstIndex { recordings.mine(productionId, $0.key) == nil } ?? 0
    }

    func mine(_ take: Take) -> URL? {
        recordings.mine(productionId, take.key)
    }

    var recordedCount: Int {
        takes.filter { mine($0) != nil }.count
    }

    func go(_ target: Int) {
        stopPlaying()
        index = target
    }

    func startRecording() {
        let session = AVAudioSession.sharedInstance()
        guard session.recordPermission == .granted else {
            session.requestRecordPermission { granted in
                DispatchQueue.main.async {
                    if granted {
                        self.startRecording()
                    } else {
                        self.message = L("record_permission")
                    }
                }
            }
            return
        }
        stopPlaying()
        try? FileManager.default.removeItem(at: file)
        do {
            try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker, .allowBluetooth])
            try session.setActive(true)
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatMPEG4AAC,
                AVSampleRateKey: 44_100,
                AVNumberOfChannelsKey: 1,
                AVEncoderBitRateKey: EnsembleRules.bitRate,
            ]
            let recorder = try AVAudioRecorder(url: file, settings: settings)
            recorder.delegate = self
            guard recorder.record(forDuration: TimeInterval(EnsembleRules.maxMillis) / 1000) else {
                throw NSError(domain: "LineRecorder", code: 1)
            }
            self.recorder = recorder
        } catch {
            message = L("record_failed")
            return
        }
        started = Date()
        elapsed = 0
        state = .recording
        timer = Timer.scheduledTimer(withTimeInterval: 0.25, repeats: true) { [weak self] _ in
            guard let self else {
                return
            }
            self.elapsed = Date().timeIntervalSince(self.started)
        }
    }

    func audioRecorderDidFinishRecording(_ recorder: AVAudioRecorder, successfully flag: Bool) {
        // The ten-minute limit was reached.
        if state == .recording {
            DispatchQueue.main.async {
                self.stopRecording(keep: true)
            }
        }
    }

    func stopRecording(keep: Bool) {
        guard state == .recording else {
            return
        }
        let seconds = Date().timeIntervalSince(started)
        recorder?.stop()
        recorder = nil
        timer?.invalidate()
        timer = nil
        state = .idle
        guard keep, seconds >= Self.minimumSeconds else {
            if keep {
                message = L("record_too_short")
            }
            return
        }
        let take = takes[index]
        guard let audio = try? Data(contentsOf: file) else {
            message = L("record_failed")
            return
        }
        if audio.count > EnsembleRules.maxBytes {
            message = L("record_too_long")
            return
        }
        uploading.insert(take.key)
        recordings.save(productionId, key: take.key, role: take.line.role, audio: audio,
                        millis: min(Int(seconds * 1000), EnsembleRules.maxMillis), name: name) { ok in
            self.uploading.remove(take.key)
            if !ok {
                self.message = L("record_upload_failed")
            }
            self.objectWillChange.send()
        }
    }

    func togglePlay(_ url: URL) {
        if state == .playing {
            stopPlaying()
            return
        }
        AudioSessions.usePlayback()
        guard let player = try? AVAudioPlayer(contentsOf: url) else {
            message = L("speech_failed")
            return
        }
        let done = PlayerDone { [weak self] _ in
            self?.stopPlaying()
        }
        player.delegate = done
        playerDone = done
        self.player = player
        player.play()
        state = .playing
    }

    func stopPlaying() {
        player?.stop()
        player = nil
        playerDone = nil
        if state == .playing {
            state = .idle
        }
    }

    func delete(_ take: Take) {
        stopPlaying()
        recordings.delete(productionId, key: take.key) { ok in
            if !ok {
                self.message = L("record_upload_failed")
            }
        }
        objectWillChange.send()
    }

    static func clock(_ seconds: TimeInterval) -> String {
        let whole = Int(seconds)
        return String(format: "%d:%02d", whole / 60, whole % 60)
    }
}

struct RecordLinesView: View {
    @StateObject private var recorder: LineRecorder
    @State private var confirmDelete = false
    let close: () -> Void

    init(production: Ensemble.Production, roles: [String], name: String, close: @escaping () -> Void) {
        _recorder = StateObject(wrappedValue: LineRecorder(production: production, roles: roles, name: name))
        self.close = close
    }

    var body: some View {
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: L("record_title"), navigate: {
                recorder.stopRecording(keep: false)
                recorder.stopPlaying()
                close()
            })
        }, content: {
            if recorder.takes.isEmpty {
                Styled(L("record_no_lines"), .bodyLarge, Palette.textSecondary).padding(.top, 16)
            } else {
                takeContent
            }
        }, footer: {
            if !recorder.takes.isEmpty {
                footer
            }
        })
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            recorder.stopRecording(keep: false)
            recorder.stopPlaying()
        }
        .alert(L("record_delete_confirm"), isPresented: $confirmDelete) {
            Button(L("action_delete"), role: .destructive) {
                recorder.delete(recorder.takes[recorder.index])
            }
            Button(L("cancel"), role: .cancel) {}
        }
        .alert(recorder.message ?? "", isPresented: Binding(get: { recorder.message != nil },
                                                             set: { if !$0 { recorder.message = nil } })) {
            Button(L("ok"), role: .cancel) {}
        }
        .preferredColorScheme(.dark)
    }

    @ViewBuilder private var takeContent: some View {
        let take = recorder.takes[recorder.index]
        let recorded = recorder.recordedCount
        Styled(recorder.title, .overline, Palette.accent).padding(.top, 8)
        Styled(L("record_progress", recorded, recorder.takes.count), .body, Palette.textSecondary).padding(.top, 6)
        ProgressLine(done: recorded, total: recorder.takes.count, height: 6).padding(.top, 10)
        if let cue = take.cue {
            Card {
                Styled(L("record_cue", cue.role), .overline, Palette.textTertiary)
                Serif(cue.text, 17, Palette.textSecondary).padding(.top, 6)
            }
            .padding(.top, 20)
        }
        Card(color: Palette.accentContainer) {
            Styled(L("record_your_line", take.line.role, recorder.index + 1, recorder.takes.count), .overline,
                   Palette.onAccentContainer)
            Serif(take.line.text, 22).padding(.top, 8)
        }
        .padding(.top, take.cue == nil ? 20 : 10)
        let has = recorder.mine(take) != nil
        let status = recorder.state == .recording ? L("record_recording")
            : recorder.uploading.contains(take.key) ? L("record_uploading") : L(has ? "record_saved" : "record_missing")
        Chip(label: status, foreground: has || recorder.state == .recording ? Palette.teal : Palette.textSecondary,
             background: has || recorder.state == .recording ? Palette.tealContainer : Palette.surfaceHighest)
            .padding(.top, 14)
    }

    private var footer: some View {
        let take = recorder.takes[recorder.index]
        let mine = recorder.mine(take)
        return FooterBar {
            if recorder.state == .recording {
                Text(L("record_timer", LineRecorder.clock(recorder.elapsed),
                       LineRecorder.clock(TimeInterval(EnsembleRules.maxMillis) / 1000)))
                    .font(.system(size: 18, weight: .medium))
                    .foregroundColor(Palette.danger)
                    .frame(maxWidth: .infinity)
                MemoButton(L("record_stop"), icon: "ic_stop", kind: .primary, fill: true) {
                    recorder.stopRecording(keep: true)
                }
                .padding(.top, 10)
            } else {
                if let mine {
                    let playing = recorder.state == .playing
                    HStack(spacing: 8) {
                        MemoButton(L(playing ? "stop_line" : "record_listen"), icon: playing ? "ic_stop" : "ic_play",
                                   kind: .tonal, fill: true) { recorder.togglePlay(mine) }
                        MemoButton(L("record_again"), icon: "ic_mic", kind: .primary, fill: true,
                                   action: recorder.startRecording)
                    }
                    MemoButton(L("action_delete"), icon: "ic_delete", kind: .text) { confirmDelete = true }
                        .padding(.top, 4)
                } else {
                    MemoButton(L("record_start"), icon: "ic_mic", kind: .primary, fill: true, action: recorder.startRecording)
                }
                HStack {
                    MemoButton(L("previous_line"), icon: "ic_previous", kind: .text,
                               action: recorder.index > 0 ? { recorder.go(recorder.index - 1) } : nil)
                        .opacity(recorder.index > 0 ? 1 : 0.4)
                        .frame(maxWidth: .infinity)
                    MemoButton(L("record_next"), icon: "ic_next", kind: .text,
                               action: recorder.index < recorder.takes.count - 1 ? { recorder.go(recorder.index + 1) } : nil)
                        .opacity(recorder.index < recorder.takes.count - 1 ? 1 : 0.4)
                        .frame(maxWidth: .infinity)
                }
                .padding(.top, 6)
            }
        }
    }
}
