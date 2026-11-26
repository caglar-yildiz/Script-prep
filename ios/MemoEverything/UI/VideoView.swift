import AVFoundation
import MemoCore
import Photos
import SwiftUI

/// What camera practice needs to know about the scene.
struct VideoSetup {
    let title: String
    let lines: [ScriptScene.Line]
    let role: String?
    let hasRoles: Bool
    let language: ScriptLanguage
    let cast: [String: VoiceCast.Part]
    let defaultVoice: String?
    let aiVoice: Bool
    let speed: Float
}

/// Premium camera practice: a self-tape with a teleprompter; partner lines are read aloud.
final class VideoRecorder: NSObject, ObservableObject, AVCaptureFileOutputRecordingDelegate {
    private static let tailSeconds = 2.0

    @Published var position = 0
    @Published var recording = false
    @Published var countdown = 0
    @Published var elapsed = 0
    @Published var front = true
    @Published var partnerAudio = true
    @Published var permissionNeeded = false
    @Published var saved: URL?
    @Published var message: String?

    let setup: VideoSetup
    let session = AVCaptureSession()
    private let output = AVCaptureMovieFileOutput()
    private let queue = DispatchQueue(label: "camera.session")
    private let voice = DeviceVoice()
    private var neural: NeuralVoice?
    private var activeUtterance: String?
    private var utteranceNumber = 0
    private var timer: Timer?
    private var startedAt = Date()
    private var configured = false
    private var showResult = true

    init(setup: VideoSetup) {
        self.setup = setup
        super.init()
        if setup.aiVoice {
            neural = NeuralVoice.shared
            neural?.load()
        }
    }

    func isMine(_ index: Int) -> Bool {
        !setup.hasRoles || setup.lines[index].role == setup.role
    }

    // MARK: Camera

    func start() {
        let video = AVCaptureDevice.authorizationStatus(for: .video)
        let audio = AVCaptureDevice.authorizationStatus(for: .audio)
        if video == .authorized && audio == .authorized {
            permissionNeeded = false
            configure()
            return
        }
        if video == .denied || audio == .denied || video == .restricted || audio == .restricted {
            permissionNeeded = true
            return
        }
        AVCaptureDevice.requestAccess(for: .video) { _ in
            AVCaptureDevice.requestAccess(for: .audio) { _ in
                DispatchQueue.main.async {
                    let granted = AVCaptureDevice.authorizationStatus(for: .video) == .authorized
                        && AVCaptureDevice.authorizationStatus(for: .audio) == .authorized
                    self.permissionNeeded = !granted
                    if granted {
                        self.configure()
                    }
                }
            }
        }
    }

    func openSettings() {
        if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
        }
    }

    private func configure() {
        let front = self.front
        queue.async {
            AudioSessions.usePlayAndRecord()
            self.session.beginConfiguration()
            self.session.sessionPreset = .hd1920x1080
            for input in self.session.inputs {
                self.session.removeInput(input)
            }
            let position: AVCaptureDevice.Position = front ? .front : .back
            if let camera = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: position)
                ?? AVCaptureDevice.default(for: .video),
               let input = try? AVCaptureDeviceInput(device: camera), self.session.canAddInput(input) {
                self.session.addInput(input)
            } else {
                DispatchQueue.main.async {
                    self.message = L("video_camera_failed")
                }
            }
            if let microphone = AVCaptureDevice.default(for: .audio),
               let input = try? AVCaptureDeviceInput(device: microphone), self.session.canAddInput(input) {
                self.session.addInput(input)
            }
            if !self.configured, self.session.canAddOutput(self.output) {
                self.session.addOutput(self.output)
                self.configured = true
            }
            if let connection = self.output.connection(with: .video) {
                if connection.isVideoOrientationSupported {
                    connection.videoOrientation = .portrait
                }
                if connection.isVideoMirroringSupported {
                    connection.isVideoMirrored = front
                }
            }
            self.session.automaticallyConfiguresApplicationAudioSession = false
            self.session.commitConfiguration()
            if !self.session.isRunning {
                self.session.startRunning()
            }
        }
    }

    func switchCamera() {
        guard !recording else {
            return
        }
        front.toggle()
        configure()
    }

    func stop() {
        countdown = 0
        if recording {
            stopRecording(showResult: false)
        }
        stopSpeech()
        queue.async {
            if self.session.isRunning {
                self.session.stopRunning()
            }
        }
    }

    // MARK: Recording

    func toggleRecording() {
        if countdown > 0 {
            return
        }
        if recording {
            stopRecording(showResult: true)
        } else if permissionNeeded {
            start()
        } else {
            count(3)
        }
    }

    private func count(_ value: Int) {
        countdown = value
        if value == 0 {
            startRecording()
            return
        }
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
            if self.countdown == value {
                self.count(value - 1)
            }
        }
    }

    private func startRecording() {
        let file = FileManager.default.temporaryDirectory
            .appendingPathComponent("selftape-\(Int(Date().timeIntervalSince1970)).mov")
        position = 0
        recording = true
        startedAt = Date()
        elapsed = 0
        timer = Timer.scheduledTimer(withTimeInterval: 0.5, repeats: true) { [weak self] _ in
            guard let self else {
                return
            }
            self.elapsed = Int(Date().timeIntervalSince(self.startedAt))
        }
        showResult = true
        queue.async {
            self.output.startRecording(to: file, recordingDelegate: self)
        }
        readIfPartner()
    }

    func stopRecording(showResult: Bool) {
        self.showResult = showResult
        recording = false
        timer?.invalidate()
        timer = nil
        stopSpeech()
        queue.async {
            if self.output.isRecording {
                self.output.stopRecording()
            }
        }
        position = 0
    }

    func fileOutput(_ output: AVCaptureFileOutput, didFinishRecordingTo outputFileURL: URL,
                    from connections: [AVCaptureConnection], error: Error?) {
        let finished = error == nil || ((error as NSError?)?.userInfo[AVErrorRecordingSuccessfullyFinishedKey] as? Bool ?? false)
        guard finished else {
            DispatchQueue.main.async {
                self.message = L("video_record_failed")
            }
            try? FileManager.default.removeItem(at: outputFileURL)
            return
        }
        save(outputFileURL)
    }

    /// Keeps the video in Photos, in a "Memo Everything" album.
    private func save(_ file: URL) {
        PHPhotoLibrary.requestAuthorization(for: .readWrite) { status in
            guard status == .authorized || status == .limited else {
                DispatchQueue.main.async {
                    self.message = L("photo_library_denied")
                    self.saved = file
                }
                return
            }
            // The album is looked up or made first: changes cannot be nested.
            let album = status == .authorized ? Self.album() : nil
            PHPhotoLibrary.shared().performChanges({
                guard let request = PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: file) else {
                    return
                }
                if let album {
                    let change = PHAssetCollectionChangeRequest(for: album)
                    if let placeholder = request.placeholderForCreatedAsset {
                        change?.addAssets([placeholder] as NSArray)
                    }
                }
            }) { ok, _ in
                DispatchQueue.main.async {
                    if ok {
                        if self.showResult {
                            self.saved = file
                        }
                    } else {
                        self.message = L("video_record_failed")
                    }
                }
            }
        }
    }

    private static func album() -> PHAssetCollection? {
        let name = "Memo Everything"
        let options = PHFetchOptions()
        options.predicate = NSPredicate(format: "title = %@", name)
        if let existing = PHAssetCollection.fetchAssetCollections(with: .album, subtype: .any, options: options).firstObject {
            return existing
        }
        var identifier: String?
        try? PHPhotoLibrary.shared().performChangesAndWait {
            identifier = PHAssetCollectionChangeRequest.creationRequestForAssetCollection(withTitle: name)
                .placeholderForCreatedAssetCollection.localIdentifier
        }
        guard let identifier else {
            return nil
        }
        return PHAssetCollection.fetchAssetCollections(withLocalIdentifiers: [identifier], options: nil).firstObject
    }

    // MARK: Prompter

    func advance() {
        guard recording, position < setup.lines.count else {
            return
        }
        stopSpeech()
        position += 1
        if position >= setup.lines.count {
            DispatchQueue.main.asyncAfter(deadline: .now() + Self.tailSeconds) {
                if self.recording {
                    self.stopRecording(showResult: true)
                }
            }
        } else {
            readIfPartner()
            prefetchNext()
        }
    }

    func togglePartnerAudio() {
        partnerAudio.toggle()
        message = nil
        if !partnerAudio {
            stopSpeech()
        } else if recording {
            readIfPartner()
        }
    }

    private func readIfPartner() {
        guard recording, position < setup.lines.count, !isMine(position), partnerAudio else {
            return
        }
        let line = setup.lines[position]
        let part = setup.cast[line.role]
        utteranceNumber += 1
        let id = "video-\(utteranceNumber)"
        activeUtterance = id
        let finished: (Bool) -> Void = { ok in
            if id == self.activeUtterance {
                self.activeUtterance = nil
                if ok {
                    self.advance()
                }
            }
        }
        if let neural, neural.isReady {
            neural.speak(line.text, language: setup.language.code, speaker: part?.speaker ?? 0,
                         speed: (part?.pace.rate ?? 1) * setup.speed, done: finished)
            prefetchNext()
            return
        }
        guard DeviceVoice.isAvailable(setup.language) else {
            return
        }
        let chosen = part.flatMap { $0.voice.isEmpty ? nil : $0.voice } ?? setup.defaultVoice
        voice.speak(line.text, language: setup.language, voice: chosen, pitch: part?.tone.pitch ?? 1,
                    rate: (part?.pace.rate ?? 1) * setup.speed, done: finished)
        // The voice plays while the camera records, so the session must stay in record mode.
        AudioSessions.usePlayAndRecord()
    }

    private func stopSpeech() {
        if activeUtterance != nil {
            activeUtterance = nil
            voice.stop()
            neural?.stop()
        }
    }

    private func prefetchNext() {
        guard let neural, neural.isReady else {
            return
        }
        var i = position + 1
        while i < setup.lines.count {
            if !isMine(i) {
                let part = setup.cast[setup.lines[i].role]
                neural.prefetch(setup.lines[i].text, language: setup.language.code, speaker: part?.speaker ?? 0,
                                speed: (part?.pace.rate ?? 1) * setup.speed)
                return
            }
            i += 1
        }
    }
}

struct CameraPreview: UIViewRepresentable {
    let session: AVCaptureSession

    func makeUIView(context: Context) -> PreviewView {
        let view = PreviewView()
        view.previewLayer.session = session
        view.previewLayer.videoGravity = .resizeAspectFill
        return view
    }

    func updateUIView(_ view: PreviewView, context: Context) {}

    final class PreviewView: UIView {
        override class var layerClass: AnyClass {
            AVCaptureVideoPreviewLayer.self
        }

        var previewLayer: AVCaptureVideoPreviewLayer {
            // swiftlint:disable:next force_cast
            layer as! AVCaptureVideoPreviewLayer
        }
    }
}

struct VideoView: View {
    @StateObject private var recorder: VideoRecorder
    @State private var sharing: SharedFile?
    let close: () -> Void

    init(setup: VideoSetup, close: @escaping () -> Void) {
        _recorder = StateObject(wrappedValue: VideoRecorder(setup: setup))
        self.close = close
    }

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            CameraPreview(session: recorder.session).ignoresSafeArea()
            VStack(spacing: 0) {
                top
                Spacer()
                bottom
            }
            if recorder.countdown > 0 {
                Text("\(recorder.countdown)")
                    .font(.system(size: 96, weight: .medium))
                    .foregroundColor(.white)
                    .shadow(color: .black, radius: 8)
            }
            if recorder.permissionNeeded {
                Card {
                    Styled(L("video_permission"), .bodyLarge).multilineTextAlignment(.center)
                        .frame(maxWidth: .infinity)
                    MemoButton(L("video_allow"), kind: .primary, action: recorder.openSettings)
                        .frame(maxWidth: .infinity)
                        .padding(.top, 16)
                }
                .padding(.horizontal, 24)
            }
        }
        .onAppear {
            UIApplication.shared.isIdleTimerDisabled = true
            recorder.start()
        }
        .onDisappear {
            UIApplication.shared.isIdleTimerDisabled = false
            recorder.stop()
        }
        .alert(L("video_saved_title"), isPresented: Binding(get: { recorder.saved != nil && recorder.message == nil },
                                                            set: { if !$0 { recorder.saved = nil } })) {
            Button(L("video_share")) {
                sharing = recorder.saved.map { SharedFile(url: $0) }
                recorder.saved = nil
            }
            Button(L("video_retake"), role: .cancel) { recorder.saved = nil }
            Button(L("action_close")) {
                recorder.saved = nil
                close()
            }
        } message: {
            Text(L("video_saved_message"))
        }
        .alert(recorder.message ?? "", isPresented: Binding(get: { recorder.message != nil },
                                                             set: { if !$0 { recorder.message = nil } })) {
            Button(L("ok"), role: .cancel) {}
        }
        .sheet(item: $sharing) { file in
            ActivityView(items: [file.url])
        }
        .preferredColorScheme(.dark)
        .statusBarHidden(false)
    }

    private var top: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                IconButton(icon: "ic_close", label: L("action_close"), tint: .white, background: Color.black.opacity(0.2)) {
                    close()
                }
                Text(recorder.setup.title)
                    .font(.system(size: 16, weight: .medium))
                    .foregroundColor(.white)
                    .lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if recorder.recording {
                    Chip(label: String(format: "● %02d:%02d", recorder.elapsed / 60, recorder.elapsed % 60),
                         foreground: .white, background: Color(hex: 0xD93A3A, alpha: 0.8))
                }
            }
            prompter
        }
        .padding(EdgeInsets(top: 8, leading: 12, bottom: 24, trailing: 12))
        .background(LinearGradient(colors: [Color.black.opacity(0.8), .clear], startPoint: .top, endPoint: .bottom)
            .ignoresSafeArea())
    }

    private var prompter: some View {
        let lines = recorder.setup.lines
        let index = recorder.position
        return Button(action: { if recorder.recording { recorder.advance() } }) {
            VStack(alignment: .leading, spacing: 0) {
                if index >= lines.count {
                    Styled(L("video_finished_role"), .overline, Palette.accent)
                    Serif(L("video_finished_line"), 23, .white).padding(.top, 6)
                } else {
                    let mine = recorder.isMine(index)
                    let line = lines[index]
                    Styled(recorder.setup.hasRoles ? (mine ? L("your_turn", line.role) : line.role) : L("solo_your_turn"),
                           .overline, mine ? Palette.accent : Palette.violet)
                    Text(line.text)
                        .font(.system(size: 23, weight: mine ? .bold : .regular, design: .serif))
                        .foregroundColor(.white.opacity(mine ? 1 : 0.9))
                        .lineLimit(7)
                        .multilineTextAlignment(.leading)
                        .padding(.top, 6)
                    if index + 1 < lines.count {
                        let next = recorder.setup.hasRoles ? lines[index + 1].role + " · " + lines[index + 1].text
                            : lines[index + 1].text
                        Text(L("video_up_next", next))
                            .font(.system(size: 14))
                            .foregroundColor(.white.opacity(0.7))
                            .lineLimit(2)
                            .padding(.top, 10)
                    }
                }
            }
            .padding(EdgeInsets(top: 14, leading: 18, bottom: 16, trailing: 18))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(Color.black.opacity(0.7)))
        }
        .buttonStyle(.plain)
    }

    private var bottom: some View {
        VStack(spacing: 12) {
            if recorder.recording {
                MemoButton(L("video_next_line"), icon: "ic_next", kind: .primary, action: recorder.advance)
            }
            Text(L(recorder.recording ? "video_hint_recording" : "video_hint"))
                .font(.system(size: 14))
                .foregroundColor(Palette.textSecondary)
                .multilineTextAlignment(.center)
            HStack(spacing: 36) {
                IconButton(icon: "ic_switch_camera", label: L("video_switch_camera"), tint: .white,
                           background: Color.white.opacity(0.25), size: 52, action: recorder.switchCamera)
                    .opacity(recorder.recording ? 0.4 : 1)
                    .disabled(recorder.recording)
                Button(action: recorder.toggleRecording) {
                    ZStack {
                        Circle().strokeBorder(Color.white, lineWidth: 4)
                        RoundedRectangle(cornerRadius: recorder.recording ? 8 : 29, style: .continuous)
                            .fill(Color(hex: 0xE5484D))
                            .frame(width: recorder.recording ? 30 : 58, height: recorder.recording ? 30 : 58)
                    }
                    .frame(width: 76, height: 76)
                    .animation(.easeInOut(duration: 0.2), value: recorder.recording)
                }
                .accessibilityLabel(L(recorder.recording ? "video_stop" : "video_record"))
                IconButton(icon: recorder.partnerAudio ? "ic_volume" : "ic_volume_off",
                           label: L(recorder.partnerAudio ? "video_partner_on" : "video_partner_off"), tint: .white,
                           background: Color.white.opacity(0.25), size: 52, action: recorder.togglePartnerAudio)
                    .opacity(recorder.setup.hasRoles ? 1 : 0)
                    .disabled(!recorder.setup.hasRoles)
            }
            .padding(.top, 4)
        }
        .padding(EdgeInsets(top: 32, leading: 20, bottom: 20, trailing: 20))
        .frame(maxWidth: .infinity)
        .background(LinearGradient(colors: [Color.black.opacity(0.8), .clear], startPoint: .bottom, endPoint: .top)
            .ignoresSafeArea())
    }
}

/// A file handed to the share sheet.
struct SharedFile: Identifiable {
    let id = UUID()
    let url: URL
}

/// The system share sheet.
struct ActivityView: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
