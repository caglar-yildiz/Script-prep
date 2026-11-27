import AVFoundation
import Combine
import MemoCore
import SwiftUI
import UIKit

enum Screen: String {
    case home, categories, source, editor, sections, setup, reader, rehearsal, voices, settings, premium, lines, notes
}

/// A button of an alert dialog.
struct DialogButton {
    enum Role {
        case normal, cancel, destructive
    }

    let label: String
    var role: Role = .normal
    var action: () -> Void = {}
}

/// What a sheet or alert shows; the views draw each kind.
struct DialogItem: Identifiable {
    enum Kind {
        case message(title: String?, message: String, buttons: [DialogButton])
        case choice(title: String, options: [String], details: [String]?, selected: Int?, pick: (Int) -> Void)
        case pages(source: PdfSource, category: TextCategory, narrator: String)
        case lineEdit(index: Int, near: ScriptScene.Line?, insert: Bool)
        case note(index: Int, line: ScriptScene.Line)
        case roles(scene: ScriptScene, edit: RoleEdit)
        case renameRole(scene: ScriptScene, from: String, edit: RoleEdit)
        case review(index: Int)
        case overview
        case photoMore
        /// A one-field form: `submit` returns the problem to show, or nil when it is done.
        case textInput(title: String, message: String?, value: String, hint: String, limit: Int,
                       words: Bool, confirm: String, submit: (String) -> String?)
    }

    let id = UUID()
    let kind: Kind
}

/// Applies renamed lines and returns the text as parsed again, or nil when it was not saved.
typealias RoleEdit = (_ lines: [ScriptScene.Line], _ from: String, _ to: String) -> ScriptScene?

/// The pages of an imported PDF that did not fit, to import next as a separate text.
struct RemainingPages {
    let url: URL
    let firstPage: Int
    let lastPage: Int
}

/// The app's state and behaviour: a port of the Android MainActivity, screen by screen.
final class AppModel: ObservableObject {
    static let maxScriptLength = ScriptImport.maxScriptLength
    /// Longer PDFs ask which pages to import.
    static let pageChoiceFrom = 30
    static let maxTitleLength = 80
    static let speeds: [Float] = [0.75, 1, 1.25, 1.5]
    private static let speedKey = "reading_speed"
    private static let voiceKey = "tts_voice"
    private static let handsFreeKey = "hands_free"
    private static let turnCueKey = "turn_cue"
    private static let aiVoiceKey = "ai_voice"
    private static let resumePrefix = "resume_"
    private static let turnCueMillis = 180
    private static let lineSaid = 0.6
    private static let lineClean = 0.8
    private static let listenRetries = 6
    private static let snackbarSeconds = 6.0

    let defaults = UserDefaults.standard
    let library: ScriptLibrary
    let neural = NeuralVoice.shared
    let studio = StudioVoices.shared
    /// Which voice reads the lines: one choice for device, on-device AI and studio voices.
    private static let engineKey = "voice_engine"

    enum Engine: String, CaseIterable {
        case device = "DEVICE", ai = "AI", studio = "STUDIO"
    }
    let billing = Billing()
    private let deviceVoice = DeviceVoice()
    private let handsFree = HandsFree()
    private let turnCue = TurnCue()
    private var cancellables = Set<AnyCancellable>()
    private var parsedCache: [String: ScriptScene] = [:]

    // MARK: Navigation

    @Published var screen: Screen = .home
    /// Where the Premium screen goes back to: Settings, or the screen that asked for Premium.
    var premiumReturn: Screen = .settings
    var editorReturn: Screen = .home
    /// Where the line editor goes back to.
    var linesReturn: Screen = .setup
    @Published var dialog: DialogItem?
    @Published var toast: String?
    @Published var snackbar: (message: String, action: String?, run: (() -> Void)?)?
    private var snackbarToken = 0
    private var toastToken = 0
    @Published var ensembleOpen = false
    @Published var ensembleShare: String?
    @Published var ensembleInvite: String?
    @Published var videoOpen = false
    @Published var exportingBackup = false
    @Published var backupToExport: BackupDocument?
    @Published var importingBackup = false
    @Published var pickingFile: Bool = false
    @Published var pickingPdf: Bool = false
    @Published var pickingPhotos = false
    @Published var takingPhoto = false
    /// The rehearsal is on screen and the app is in the foreground.
    var visible = true

    // MARK: Open text

    @Published var category: TextCategory = .roleRehearsal
    /// Language of the open text.
    @Published var language: ScriptLanguage = .turkish
    /// Whether the user chose the text language, so it is not guessed again.
    var languagePicked = false
    /// Language whose default voice the settings screen is showing.
    @Published var settingsLanguage: ScriptLanguage?
    @Published var scriptId: String?
    @Published var scriptTitle = ""
    @Published var script = ""
    var savedTitle = ""
    var savedScript = ""
    /// The editor's fields, kept here so a redraw keeps what was typed.
    @Published var editorTitle = ""
    @Published var editorText = ""
    @Published var titleError: String?
    @Published var textError: String?
    @Published var fullScene: ScriptScene?
    @Published var scene: ScriptScene = .empty
    @Published var sectionIndex = -1
    @Published var role: String?
    @Published var position = 0
    @Published var mode: RehearsalMode = .practice
    @Published var lineVisibility: LineVisibility = .full
    @Published var revealLine = false
    /// The text being created goes to ensemble rehearsal to be shared once it is saved.
    var shareAfterSave = false

    // MARK: Practice report

    @Published var revealedLines = Set<Int>()
    @Published var retriedLines = Set<Int>()
    @Published var missedLines = Set<Int>()
    @Published var reviewBase: [Int]?

    // MARK: Speech

    @Published var activeUtterance: String?
    private var activePreview: String?
    private var utteranceNumber = 0
    private var autoPlayPending = false
    @Published var playbackFailed = false
    @Published var aiSpeaking = false
    @Published var recordingSpeaking = false
    /// The character voices screen was opened to check the cast before studio voices are prepared.
    @Published var castCheck = false
    @Published var guessingCast = false
    /// Whether the line being read now is a studio voice recording.
    @Published var studioSpeaking = false
    private var recordingPlayer: AVAudioPlayer?
    /// Plays a studio voice sample on the character voices screen.
    private var previewPlayer: AVAudioPlayer?
    private var recordingDelegate: PlayerDone?
    private var castRecordings: EnsembleRules.Voices?
    private var castRecordingsFor: String?

    // MARK: Hands-free

    @Published var listening = false
    @Published var listeningPaused = false
    @Published var heardSoFar = ""
    @Published var partialHeard = ""
    private var listenAttempts = 0
    private lazy var listenerBridge = ListenerBridge(model: self)

    // MARK: Import

    @Published var importing = false
    @Published var importStatus: String?
    private var importCancel = CancelFlag()
    var importCancelled: Bool {
        importCancel.isCancelled
    }
    private var importReview: OcrReview?
    /// Unsure lines of the text open in the editor, and how many of them were already checked.
    @Published var reviewItems: [OcrReview.Item] = []
    @Published var reviewIndex = 0
    /// Pages left after an import that reached the text limit; offered in the editor.
    @Published var remainingPages: RemainingPages?
    /// Page photos taken with the camera for the import in progress, in order.
    @Published var takenPhotos: [Data] = []
    /// Where the editor's cursor is, for finding unsure lines in order.
    var editorCursor = 0

    @Published private(set) var premium = Premium.isActive

    init() {
        library = ScriptLibrary(defaults)
        neural.objectWillChange.receive(on: DispatchQueue.main).sink { [weak self] _ in
            DispatchQueue.main.async {
                self?.onNeuralVoiceChanged()
            }
        }.store(in: &cancellables)
        billing.onChange = { [weak self] _ in
            self?.onBillingChanged()
        }
        studio.objectWillChange.receive(on: DispatchQueue.main).sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)
        billing.objectWillChange.receive(on: DispatchQueue.main).sink { [weak self] _ in
            self?.objectWillChange.send()
        }.store(in: &cancellables)
    }

    var narrator: String {
        L("solo_role")
    }

    // MARK: - Lifecycle

    func onForeground() {
        visible = true
        // Subscriptions can change outside the app, for example a cancellation in Settings.
        billing.connect()
        refreshPremium()
        if screen == .rehearsal {
            DispatchQueue.main.async {
                self.listenIfNeeded()
            }
        }
    }

    func onBackground() {
        visible = false
        cancelSpeech()
        stopListening()
        updateIdleTimer()
    }

    func refreshPremium() {
        premium = Premium.isActive
    }

    // MARK: - Messages

    func notify(_ message: String) {
        toastToken += 1
        let token = toastToken
        toast = message
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) {
            if self.toastToken == token {
                self.toast = nil
            }
        }
    }

    func notifyKey(_ key: String) {
        notify(L(key))
    }

    func showSnackbar(_ message: String, action: String?, run: (() -> Void)?) {
        snackbarToken += 1
        let token = snackbarToken
        snackbar = (message, action, run)
        DispatchQueue.main.asyncAfter(deadline: .now() + Self.snackbarSeconds) {
            if self.snackbarToken == token {
                self.snackbar = nil
            }
        }
    }

    func dismissSnackbar() {
        snackbarToken += 1
        snackbar = nil
    }

    func alert(_ title: String?, _ message: String, _ buttons: [DialogButton]) {
        dialog = DialogItem(kind: .message(title: title, message: message, buttons: buttons))
    }

    func choose(_ title: String, _ options: [String], details: [String]? = nil, selected: Int?,
                _ pick: @escaping (Int) -> Void) {
        dialog = DialogItem(kind: .choice(title: title, options: options, details: details, selected: selected, pick: pick))
    }

    /// Shows the next dialog once the current sheet has gone away.
    func then(_ open: @escaping () -> Void) {
        dialog = nil
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.45, execute: open)
    }

    // MARK: - Navigation

    func show(_ target: Screen) {
        if target != .voices {
            castCheck = false
        }
        if target == .home || target == .categories {
            // Leaving the text creation screens: a text made later is not for the cast.
            shareAfterSave = false
        }
        cancelSpeech()
        stopListening()
        if target != .rehearsal && reviewBase != nil {
            reviewBase = nil
            scene = fullScene?.forSection(sectionIndex) ?? .empty
        }
        screen = target
        updateIdleTimer()
    }

    func returnToLibrary() {
        show(.home)
    }

    /// Android's back button, used by the top bar's back arrow where it goes "back".
    func back() {
        switch screen {
        case .rehearsal, .reader, .voices, .notes:
            show(.setup)
        case .source:
            show(.categories)
        case .editor:
            leaveEditor()
        case .setup where hasNamedSections:
            show(.sections)
        case .lines:
            show(linesReturn)
        case .premium:
            show(premiumReturn)
        case .home:
            break
        default:
            returnToLibrary()
        }
    }

    /// Opens the Premium screen; back returns to the screen it was opened from.
    func openPremium() {
        premiumReturn = screen == .premium ? premiumReturn : screen
        show(.premium)
    }

    /// Like a video player, keeps the screen on while a rehearsal runs.
    func updateIdleTimer() {
        UIApplication.shared.isIdleTimerDisabled = screen == .rehearsal && position < scene.lines.count && visible
    }

    // MARK: - Library

    var scripts: [Script] {
        library.list()
    }

    func parsed(_ entry: Script) -> ScriptScene {
        let key = "\(entry.id):\(entry.category.rawValue):\(entry.text.utf16.count):\(entry.text.hashValue)"
        if let cached = parsedCache[key] {
            return cached
        }
        let scene = entry.category.parse(entry.text, narrator: narrator)
        if parsedCache.count > 32 {
            parsedCache.removeAll()
        }
        parsedCache[key] = scene
        return scene
    }

    func scriptSummary(_ entry: Script) -> String {
        let scene = parsed(entry)
        var summary = L(entry.id.hasPrefix(EnsembleRules.localPrefix) ? "ensemble_title" : categoryTitle(entry.category))
        if entry.language != Strings.interfaceLanguage {
            summary += " · " + L(entry.language.labelKey)
        }
        summary += " · "
        if entry.category.hasRoles {
            summary += P("line_count", scene.lines.count)
            if !scene.roles.isEmpty {
                summary += " · " + P("role_count", scene.roles.count)
            }
        } else {
            summary += P("part_count", scene.lines.count)
        }
        return summary
    }

    func categoryTitle(_ value: TextCategory) -> String {
        switch value {
        case .roleRehearsal: return "category_role"
        case .speech: return "category_speech"
        case .poemOrMonologue: return "category_poem"
        }
    }

    func categoryIcon(_ value: TextCategory) -> String {
        switch value {
        case .roleRehearsal: return "ic_people"
        case .speech: return "ic_mic"
        case .poemOrMonologue: return "ic_quote"
        }
    }

    func categoryColors(_ value: TextCategory) -> (Color, Color) {
        switch value {
        case .roleRehearsal: return (Palette.accent, Palette.accentContainer)
        case .speech: return (Palette.teal, Palette.tealContainer)
        case .poemOrMonologue: return (Palette.violet, Palette.violetContainer)
        }
    }

    func openSample() {
        category = .roleRehearsal
        openSavedScript(Script(id: "", title: L("sample_title"), text: L("sample_script"), category: .roleRehearsal,
                               language: Strings.interfaceLanguage), sample: true)
        if let fullScene {
            studio.installSample(fullScene, language)
            objectWillChange.send()
        }
    }

    func openSavedScript(_ entry: Script, sample: Bool = false) {
        category = entry.category
        language = entry.language
        let parsed = parseScript(entry.text)
        let id: String? = sample ? nil : entry.id
        if parsed.roles.isEmpty {
            openEditor(id: id, title: entry.title, text: entry.text)
            return
        }
        scriptId = id
        scriptTitle = entry.title
        script = entry.text
        beginScript(parsed)
    }

    func parseScript(_ value: String) -> ScriptScene {
        category.parse(value, narrator: narrator)
    }

    func editSaved(_ entry: Script) {
        category = entry.category
        language = entry.language
        openEditor(id: entry.id, title: entry.title, text: entry.text)
    }

    /// Adds a text with characters to share with the cast: paste, type or import it.
    func startTextForEnsemble() {
        category = .roleRehearsal
        shareAfterSave = true
        show(.source)
    }

    func openEnsemble(share: String? = nil) {
        ensembleShare = share
        ensembleOpen = true
    }

    /// Opens the local copy of a shared text, starting with the actor's first role.
    func openEnsembleCopy(_ id: String, role ensembleRole: String?) {
        // The ensemble screen has just synced; read the recordings again.
        castRecordings = nil
        guard let entry = library.list().first(where: { $0.id == id }) else {
            return
        }
        if let ensembleRole {
            role = ensembleRole
        }
        openSavedScript(entry)
    }

    // MARK: - Delete

    func confirmDelete(_ entry: Script) {
        let name = entry.title.isEmpty ? L("imported_title") : entry.title
        alert(L("delete_title", name), L("delete_message"), [
            DialogButton(label: L("action_delete"), role: .destructive) { self.deleteScript(entry) },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    func deleteCurrent() {
        if let entry = library.list().first(where: { $0.id == scriptId }) {
            confirmDelete(entry)
        }
    }

    func deleteScript(_ entry: Script) {
        let index = library.delete(entry.id)
        guard index >= 0 else {
            return
        }
        if entry.id == scriptId {
            scriptId = nil
            scriptTitle = ""
            script = ""
            savedTitle = ""
            savedScript = ""
            fullScene = nil
            editorReturn = .home
        }
        let name = entry.title.isEmpty ? L("imported_title") : entry.title
        returnToLibrary()
        showSnackbar(L("text_deleted", name), action: L("undo")) {
            self.library.restore(entry, at: index)
            self.objectWillChange.send()
        }
    }

    // MARK: - Settings

    var readingSpeed: Float {
        let value = defaults.object(forKey: Self.speedKey) == nil ? 1 : defaults.float(forKey: Self.speedKey)
        return value > 0.5 && value < 2 ? value : 1
    }

    func speedLabel(_ value: Float) -> String {
        let name = value < 0.9 ? "speed_slow" : value < 1.1 ? "speed_normal" : value < 1.4 ? "speed_fast" : "speed_faster"
        return L(name) + " · " + formatNumber(Double(value), maxFractionDigits: 2) + "×"
    }

    func showSpeedOptions() {
        var selected = 1
        for (i, speed) in Self.speeds.enumerated() where abs(speed - readingSpeed) < 0.01 {
            selected = i
        }
        choose(L("speed_title"), Self.speeds.map(speedLabel), selected: selected) { index in
            self.defaults.set(Self.speeds[index], forKey: Self.speedKey)
            self.objectWillChange.send()
        }
    }

    var turnCueOn: Bool {
        defaults.object(forKey: Self.turnCueKey) == nil ? true : defaults.bool(forKey: Self.turnCueKey)
    }

    func toggleTurnCue() {
        defaults.set(!turnCueOn, forKey: Self.turnCueKey)
        objectWillChange.send()
    }

    /// Turkish keeps the original key so earlier choices survive.
    private static func voiceKey(_ target: ScriptLanguage) -> String {
        target == .turkish ? voiceKey : voiceKey + "_" + target.code
    }

    func offlineVoices(_ target: ScriptLanguage) -> [AVSpeechSynthesisVoice] {
        DeviceVoice.voices(target)
    }

    func selectedVoiceIndex(_ target: ScriptLanguage) -> Int {
        let name = defaults.string(forKey: Self.voiceKey(target)) ?? ""
        if let index = offlineVoices(target).firstIndex(where: { $0.identifier == name }) {
            return index + 1
        }
        return 0
    }

    func defaultVoice(_ target: ScriptLanguage) -> String? {
        let index = selectedVoiceIndex(target)
        return index > 0 ? offlineVoices(target)[index - 1].identifier : nil
    }

    func deviceVoiceAvailable(_ target: ScriptLanguage) -> Bool {
        DeviceVoice.isAvailable(target)
    }

    func voiceOptionLabel(_ index: Int, _ voice: AVSpeechSynthesisVoice) -> String {
        L("voice_option", index) + " · " + voice.name
    }

    func showVoiceOptions() {
        let target = settingsLanguage ?? Strings.interfaceLanguage
        let voices = offlineVoices(target)
        let labels = [L("voice_default")] + voices.enumerated().map { voiceOptionLabel($0.offset + 1, $0.element) }
        choose(L("voice_choose"), labels, selected: selectedVoiceIndex(target)) { choice in
            self.cancelSpeech()
            if choice == 0 {
                self.defaults.removeObject(forKey: Self.voiceKey(target))
            } else {
                self.defaults.set(voices[choice - 1].identifier, forKey: Self.voiceKey(target))
            }
            self.objectWillChange.send()
        }
    }

    func previewVoice() {
        let target = settingsLanguage ?? Strings.interfaceLanguage
        cancelSpeech()
        let id = "preview-\(nextUtterance())"
        activePreview = id
        deviceVoice.speak(target.sample, language: target, voice: defaultVoice(target), pitch: 1, rate: 1) { ok in
            self.finishSpeech(id, failed: !ok)
        }
    }

    func chooseLanguage(_ current: ScriptLanguage, _ pick: @escaping (ScriptLanguage) -> Void) {
        let options = ScriptLanguage.allCases
        choose(L("text_language"), options.map { L($0.labelKey) }, selected: options.firstIndex(of: current)) { index in
            if options[index] != current {
                pick(options[index])
            }
        }
    }

    func showLanguageOptions() {
        let options = AppLanguage.allCases
        choose(L("language_title"), options.map { L($0.labelKey) },
               selected: options.firstIndex(of: AppLanguage.current)) { index in
            options[index].apply()
            self.parsedCache.removeAll()
            self.objectWillChange.send()
        }
    }

    var appVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
    }

    func showLicenses() {
        alert(L("licenses_title"), L("licenses_text"), [DialogButton(label: L("ok"), role: .cancel)])
    }

    // MARK: - Backup

    func backupFileName() -> String {
        let formatter = DateFormatter()
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return "memo-everything-" + formatter.string(from: Date()) + ".json"
    }

    func backupDocument() -> BackupDocument? {
        guard let json = try? library.exportJson() else {
            notifyKey("backup_write_failed")
            return nil
        }
        return BackupDocument(text: json)
    }

    func backupExported(_ result: Result<URL, Error>) {
        switch result {
        case .success:
            notify(P("backup_exported", library.list().count))
        case .failure:
            notifyKey("backup_write_failed")
        }
    }

    func readBackup(_ url: URL) {
        DispatchQueue.global(qos: .userInitiated).async {
            let accessing = url.startAccessingSecurityScopedResource()
            defer {
                if accessing {
                    url.stopAccessingSecurityScopedResource()
                }
            }
            do {
                let data = try Data(contentsOf: url)
                if data.count > 64 * 1024 * 1024 {
                    throw ImportError.lengthLimit
                }
                let count = try self.library.importJson(String(decoding: data, as: UTF8.self))
                DispatchQueue.main.async {
                    self.parsedCache.removeAll()
                    self.notify(P("backup_imported", count))
                    self.objectWillChange.send()
                }
            } catch {
                DispatchQueue.main.async {
                    self.notifyKey("backup_read_failed")
                }
            }
        }
    }

    // MARK: - New text flow

    func chooseCategory(_ value: TextCategory) {
        category = value
        language = Strings.interfaceLanguage
        show(.source)
    }

    func pasteFromClipboard() {
        let value = UIPasteboard.general.string
        if let value, JText.length(value) > Self.maxScriptLength {
            notifyKey("import_too_long")
            return
        }
        openEditor(id: nil, title: "", text: value ?? "")
        savedScript = ""
        if value?.isEmpty ?? true {
            notifyKey("empty_clipboard")
        }
    }

    // MARK: - Import

    /// Reads a picked file. For a PDF, `firstPage` and `lastPage` (from 1) choose the pages; 0
    /// reads a short PDF whole and asks which pages of a long one to read.
    func startImport(_ url: URL, pdf: Bool, firstPage: Int = 0, lastPage: Int = 0) {
        importReview = pdf ? OcrReview() : nil
        let selectedCategory = category
        let narrator = self.narrator
        beginImport()
        let cancel = importCancel
        runImport(failure: pdf ? "pdf_import_failed" : "import_failed") {
            if !pdf {
                let result = try ScriptImport.readText(url, lengthLimit: Self.maxScriptLength)
                self.deliverImport(result, pdf: nil, category: selectedCategory, narrator: narrator)
                return
            }
            let source = try PdfSource.open(url, cancel: cancel)
            let pages = source.pageCount
            if firstPage > 0 {
                try self.readPdfPages(url, source, min(firstPage, pages), min(lastPage, pages), selectedCategory, narrator)
            } else if pages > Self.pageChoiceFrom {
                DispatchQueue.main.async {
                    self.choosePages(url, source, selectedCategory, narrator)
                }
            } else {
                try self.readPdfPages(url, source, 1, pages, selectedCategory, narrator)
            }
        }
    }

    private func beginImport() {
        importing = true
        importStatus = nil
        importCancel = CancelFlag()
    }

    private func runImport(failure: String, _ step: @escaping () throws -> Void) {
        DispatchQueue.global(qos: .userInitiated).async {
            do {
                try step()
            } catch ImportError.cancelled {
                DispatchQueue.main.async { self.finishImport() }
            } catch ImportError.lengthLimit {
                DispatchQueue.main.async { self.failImport("import_too_long") }
            } catch ImportError.noText {
                DispatchQueue.main.async {
                    self.failImport(failure == "photo_import_failed" ? "photo_no_text" : "pdf_no_text")
                }
            } catch {
                DispatchQueue.main.async { self.failImport(failure) }
            }
        }
    }

    /// Runs on the import thread.
    private func readPdfPages(_ url: URL, _ source: PdfSource, _ first: Int, _ last: Int,
                              _ selectedCategory: TextCategory, _ narrator: String) throws {
        defer { source.close() }
        let result = try PdfScriptReader.read(source, first: first, last: last, lengthLimit: Self.maxScriptLength,
                                              progress: { done, total in
                                                  DispatchQueue.main.async {
                                                      self.showImportStatus(L("importing_scanned", done + 1, total))
                                                  }
                                              }, cancel: importCancel, review: importReview)
        deliverImport(result, pdf: url, category: selectedCategory, narrator: narrator)
    }

    /// A long PDF often holds more than the play, or the play starts late: ask which pages to read.
    private func choosePages(_ url: URL, _ source: PdfSource, _ selectedCategory: TextCategory, _ narrator: String) {
        if importCancelled || screen != .source || category != selectedCategory {
            source.close()
            finishImport()
            return
        }
        pendingPdfURL = url
        dialog = DialogItem(kind: .pages(source: source, category: selectedCategory, narrator: narrator))
    }

    private var pendingPdfURL: URL?

    func pagesChosen(_ source: PdfSource, first: Int, last: Int, category selectedCategory: TextCategory,
                     narrator: String) {
        dialog = nil
        guard let url = pendingPdfURL else {
            source.close()
            finishImport()
            return
        }
        runImport(failure: "pdf_import_failed") {
            try self.readPdfPages(url, source, first, last, selectedCategory, narrator)
        }
    }

    func pagesCancelled(_ source: PdfSource) {
        dialog = nil
        source.close()
        finishImport()
    }

    /// Runs on the import thread: parses the result and opens it in the editor.
    private func deliverImport(_ result: ImportResult, pdf: URL?, category selectedCategory: TextCategory,
                               narrator: String) {
        let isPdf = pdf != nil || result.photos || result.converted
        let preview = isPdf ? selectedCategory.parse(result.text, narrator: narrator) : nil
        // Only unsure lines that made it into the text are worth checking.
        let unsure = (importReview?.items ?? []).filter { OcrText.locate(result.text, $0.text, from: 0) != nil }
        DispatchQueue.main.async {
            let wanted = !self.importCancelled
            self.finishImport()
            guard wanted, self.screen == .source, self.category == selectedCategory else {
                return
            }
            self.reviewItems = unsure
            self.reviewIndex = 0
            self.remainingPages = pdf != nil && result.cutShort
                ? RemainingPages(url: pdf!, firstPage: result.lastPage + 1, lastPage: result.requestedLastPage) : nil
            let title = JText.trim(result.title)
            self.openEditor(id: nil, title: title.isEmpty ? L("imported_title") : title, text: result.text)
            self.savedScript = ""
            self.savedTitle = ""
            if let preview {
                self.showParsePreview(preview, notice: self.importNotice(result))
            }
        }
    }

    /// What the preview should say about scanned pages and pages left for another import.
    private func importNotice(_ result: ImportResult) -> String {
        var notice = ""
        if result.cutShort {
            notice += L("pdf_pages_cut_short", result.firstPage, result.lastPage, result.lastPage + 1,
                        result.requestedLastPage)
        }
        if result.scannedPages > 0 {
            if !notice.isEmpty {
                notice += "\n\n"
            }
            notice += P(result.photos ? "photo_pages_notice" : "scanned_pages_notice", result.scannedPages)
        }
        return notice
    }

    func cancelImport() {
        importCancel.cancel()
        importStatus = L("import_cancelling")
    }

    private func showImportStatus(_ status: String) {
        if !importCancelled {
            importStatus = status
        }
    }

    private func finishImport() {
        importing = false
        importStatus = nil
    }

    private func failImport(_ message: String) {
        finishImport()
        notifyKey(message)
    }

    // MARK: Photos

    func photoTaken(_ data: Data?) {
        if let data {
            takenPhotos.append(data)
        }
        if !takenPhotos.isEmpty {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                self.dialog = DialogItem(kind: .photoMore)
            }
        }
    }

    func photosPicked(_ photos: [Data]) {
        var picked = photos
        if picked.count > PhotoOcr.maxPhotos {
            notify(L("photo_too_many", PhotoOcr.maxPhotos))
            picked = Array(picked.prefix(PhotoOcr.maxPhotos))
        }
        if !picked.isEmpty {
            startPhotoImport(picked)
        }
    }

    func startPhotoImport(_ photos: [Data]) {
        importReview = OcrReview()
        let selectedCategory = category
        let narrator = self.narrator
        let title = L("photo_title")
        takenPhotos.removeAll()
        beginImport()
        if screen != .source {
            show(.source)
        }
        let cancel = importCancel
        runImport(failure: "photo_import_failed") {
            let result = try PhotoOcr.read(photos, title: title, lengthLimit: Self.maxScriptLength,
                                           progress: { done, total in
                                               DispatchQueue.main.async {
                                                   self.showImportStatus(L("importing_photo", done + 1, total))
                                               }
                                           }, cancel: cancel, review: self.importReview)
            self.deliverImport(result, pdf: nil, category: selectedCategory, narrator: narrator)
        }
    }

    // MARK: - Editor

    func openEditor(id: String?, title: String, text: String) {
        scriptId = id
        scriptTitle = title
        script = text
        savedTitle = title
        savedScript = text
        editorTitle = title
        editorText = text
        titleError = nil
        textError = nil
        editorReturn = .home
        languagePicked = id != nil
        let detected = id == nil ? LanguageGuess.guess(text) : nil
        if let detected {
            language = detected
        }
        show(.editor)
        if let detected, detected != Strings.interfaceLanguage {
            notify(L("language_detected", L(detected.labelKey)))
        }
    }

    /// Edits the open text and comes back to the current screen afterwards.
    func editCurrent() {
        savedTitle = scriptTitle
        savedScript = script
        editorTitle = scriptTitle
        editorText = script
        titleError = nil
        textError = nil
        editorReturn = screen
        show(.editor)
    }

    func pickEditorLanguage() {
        chooseLanguage(language) { picked in
            self.language = picked
            self.languagePicked = true
        }
    }

    var editorChanged: Bool {
        editorText != savedScript || editorTitle != savedTitle
    }

    func leaveEditor() {
        if !editorChanged {
            exitEditor()
            return
        }
        alert(L("discard_title"), L("discard_message"), [
            DialogButton(label: L("discard_confirm"), role: .destructive) { self.exitEditor() },
            DialogButton(label: L("keep_editing"), role: .cancel),
        ])
    }

    private func exitEditor() {
        remainingPages = nil
        reviewItems = []
        if editorReturn != .home && fullScene != nil {
            show(editorReturn)
        } else {
            returnToLibrary()
        }
    }

    func clearEditorText() {
        let previous = editorText
        if previous.isEmpty {
            return
        }
        editorText = ""
        showSnackbar(L("text_cleared"), action: L("undo")) {
            self.editorText = previous
        }
    }

    func previewEditor() {
        showParsePreview(parseScript(editorText))
    }

    func showParsePreview(_ parsed: ScriptScene, notice: String = "") {
        let scanned = notice.isEmpty ? "" : notice + "\n\n"
        if !category.hasRoles {
            alert(L("solo_preview_title"), scanned + L("solo_preview_count", parsed.lines.count),
                  [DialogButton(label: L("ok"), role: .cancel)])
            return
        }
        var summary = scanned + L("parse_preview_counts", parsed.sections.count, parsed.lines.count) + "\n\n"
        if parsed.roles.isEmpty {
            summary += L("invalid_scene")
        } else {
            summary += L("parse_preview_roles", parsed.roles.joined(separator: ", "))
        }
        for section in parsed.sections where !section.title.isEmpty {
            summary += "\n" + section.title
        }
        var buttons = [DialogButton(label: L("ok"), role: .cancel)]
        if screen == .editor && !parsed.roles.isEmpty {
            buttons.append(DialogButton(label: L("roles_edit")) {
                self.then { self.editEditorRoles() }
            })
        }
        alert(L("parse_preview"), summary, buttons)
    }

    /// Shows an unsure line next to its picture from the page, to correct it in the text.
    func reviewLine(_ index: Int) {
        guard screen == .editor else {
            return
        }
        if index >= reviewItems.count {
            reviewIndex = reviewItems.count
            notifyKey("ocr_review_done")
            return
        }
        reviewIndex = index
        dialog = DialogItem(kind: .review(index: index))
    }

    /// Where an unsure line is in the editor text, from the cursor on.
    func locateReview(_ item: OcrReview.Item) -> (Int, Int)? {
        OcrText.locate(editorText, item.text, from: max(0, editorCursor))
    }

    func reviewSave(_ index: Int, found: (Int, Int)?, original: String, fixed: String) {
        if let found {
            let clean = JRegex("[ \\t]+").replaceAll(JText.trim(fixed), " ")
            if !clean.isEmpty && editorText == original {
                editorText = (editorText as NSString).replacingCharacters(in: NSRange(location: found.0,
                                                                                    length: found.1 - found.0),
                                                                          with: clean)
                editorCursor = found.0 + JText.length(clean)
            }
        }
        then { self.reviewLine(index + 1) }
    }

    func reviewSkip(_ index: Int, found: (Int, Int)?) {
        if let found {
            editorCursor = found.1
        }
        then { self.reviewLine(index + 1) }
    }

    /// Saves this part of a long PDF and imports the pages that did not fit as the next text.
    func saveAndImportRest() {
        let title = JText.trim(editorTitle)
        let candidate = JText.trim(editorText)
        guard let rest = remainingPages, !title.isEmpty, !candidate.isEmpty else {
            saveEditor(rehearse: false)
            return
        }
        if scriptId == nil && !languagePicked, let detected = LanguageGuess.guess(candidate) {
            language = detected
        }
        library.save(id: scriptId, title: title, text: candidate, category: category, language: language)
        notifyKey("saved")
        remainingPages = nil
        reviewItems = []
        scriptId = nil
        show(.source)
        startImport(rest.url, pdf: true, firstPage: rest.firstPage, lastPage: rest.lastPage)
    }

    func saveEditor(rehearse: Bool) {
        remainingPages = nil
        reviewItems = []
        titleError = nil
        textError = nil
        let title = JText.trim(editorTitle)
        if title.isEmpty {
            titleError = L("script_name_required")
            return
        }
        let candidate = JText.trim(editorText)
        if candidate.isEmpty {
            textError = L("script_text_required")
            return
        }
        let parsed = parseScript(candidate)
        if (rehearse || shareAfterSave) && parsed.roles.isEmpty {
            textError = L(category.hasRoles ? "invalid_scene" : "script_text_required")
            return
        }
        if scriptId == nil && !languagePicked, let detected = LanguageGuess.guess(candidate) {
            language = detected
        }
        scriptId = library.save(id: scriptId, title: title, text: candidate, category: category, language: language)
        scriptTitle = title
        script = candidate
        savedTitle = title
        savedScript = candidate
        editorTitle = title
        editorText = candidate
        UIApplication.shared.sendAction(#selector(UIResponder.resignFirstResponder), to: nil, from: nil, for: nil)
        if shareAfterSave {
            shareAfterSave = false
            let id = scriptId
            returnToLibrary()
            openEnsemble(share: id)
            return
        }
        if rehearse || (editorReturn != .home && !parsed.roles.isEmpty) {
            if !rehearse {
                notifyKey("saved")
            }
            beginScript(parsed)
        } else {
            notifyKey("saved")
            returnToLibrary()
        }
    }

    // MARK: - Sections

    func beginScript(_ parsed: ScriptScene) {
        fullScene = parsed
        sectionIndex = -1
        scene = parsed
        ensureRole()
        show(hasNamedSections ? .sections : .setup)
    }

    var hasNamedSections: Bool {
        fullScene?.sections.contains { !$0.title.isEmpty } ?? false
    }

    func selectSection(_ index: Int) {
        sectionIndex = index
        scene = fullScene?.forSection(index) ?? .empty
        ensureRole()
        show(.setup)
    }

    func ensureRole() {
        if !scene.roles.isEmpty && !(role.map(scene.roles.contains) ?? false) {
            role = scene.roles[0]
        }
    }

    func sectionTitle(_ index: Int) -> String {
        guard let fullScene, index >= 0, index < fullScene.sections.count else {
            return L("entire_script")
        }
        let title = fullScene.sections[index].title
        return title.isEmpty ? L("untitled_scene", index + 1) : title
    }

    var currentSectionTitle: String {
        sectionTitle(sectionIndex)
    }

    // MARK: - Setup

    func showRoleOptions() {
        choose(L("choose_practice_role"), scene.roles, selected: role.flatMap(scene.roles.firstIndex(of:))) { index in
            self.role = self.scene.roles[index]
        }
    }

    func showVisibilityOptions() {
        let values = LineVisibility.allCases
        choose(L("visibility_choose"), values.map { L($0.labelKey) },
               selected: values.firstIndex(of: lineVisibility)) { index in
            self.lineVisibility = values[index]
        }
    }

    func setMode(_ value: RehearsalMode) {
        mode = value
    }

    func startRehearsal() {
        ensureRole()
        resetReport()
        screen = .rehearsal
        moveTo(0, autoPlay: true)
    }

    // MARK: - Resume

    private var resumeKey: String {
        Self.resumePrefix + (scriptId ?? "sample")
    }

    /// Saves where the rehearsal is, so the text can continue from there later; a finished scene clears it.
    private func rememberPosition() {
        guard screen == .rehearsal, reviewBase == nil else {
            return
        }
        if position >= scene.lines.count {
            defaults.removeObject(forKey: resumeKey)
            return
        }
        if position <= 0 {
            // Starting over keeps the old place until the new run moves past the first line.
            return
        }
        let saved: [String: Any] = ["section": sectionIndex, "position": position, "total": scene.lines.count,
                                    "role": role ?? "", "mode": mode.rawValue]
        if let data = try? JSONSerialization.data(withJSONObject: saved) {
            defaults.set(String(decoding: data, as: UTF8.self), forKey: resumeKey)
        }
    }

    struct SavedPosition {
        let section: Int
        let position: Int
        let role: String
        let mode: RehearsalMode
    }

    /// The saved place in this text, or nil when there is none or the text no longer has it.
    var savedPosition: SavedPosition? {
        guard let value = defaults.string(forKey: resumeKey), let fullScene, let data = value.data(using: .utf8),
              let saved = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        let section = (saved["section"] as? NSNumber)?.intValue ?? -1
        if section >= fullScene.sections.count {
            return nil
        }
        let lines = fullScene.forSection(section).lines.count
        let position = (saved["position"] as? NSNumber)?.intValue ?? 0
        guard position > 0 && position < lines else {
            return nil
        }
        return SavedPosition(section: section, position: position, role: saved["role"] as? String ?? "",
                             mode: saved["mode"] as? String == "LISTEN" ? .listen : .practice)
    }

    func resume(_ saved: SavedPosition) {
        guard let fullScene else {
            return
        }
        let target = fullScene.forSection(saved.section)
        sectionIndex = saved.section
        scene = target
        reviewBase = nil
        mode = saved.mode
        if target.roles.contains(saved.role) {
            role = saved.role
        }
        ensureRole()
        resetReport()
        screen = .rehearsal
        moveTo(saved.position, autoPlay: true)
    }

    // MARK: - Line editor

    /// Role texts open line by line; speeches and poems keep the plain text editor.
    func editLines() {
        if !category.hasRoles {
            editCurrent()
            return
        }
        linesReturn = screen
        show(.lines)
    }

    /// A new line most likely belongs to whoever answers the line before it.
    func suggestedRole(_ index: Int) -> String {
        let lines = fullScene?.lines ?? []
        if index > 0 && index - 2 >= 0 && index - 2 < lines.count {
            return lines[index - 2].role
        }
        return role ?? ""
    }

    /// Reuses the spelling of a character that already exists, whatever case was typed.
    func existingRole(_ name: String) -> String {
        for existing in fullScene?.roles ?? [] where existing.lowercased(with: language.locale)
            == name.lowercased(with: language.locale) {
            return existing
        }
        return name
    }

    /// Saves a line from the line dialog; returns the problem to show, or nil when it was saved.
    func saveLine(index: Int, near: ScriptScene.Line?, insert: Bool, role typedRole: String, text: String,
                  notes: String) -> (field: Int, message: String)? {
        let name = JText.collapse(JText.trim(typedRole))
        let spoken = JText.collapse(JText.trim(text))
        if !ScriptScene.isRoleName(name) {
            return (0, L("line_role_invalid"))
        }
        if spoken.isEmpty {
            return (1, L("line_text_required"))
        }
        let noteList = notes.components(separatedBy: "\n").map(JText.trim).filter { !$0.isEmpty }
        var lines = fullScene?.lines ?? []
        let section = near?.section ?? lines.last?.section ?? ""
        let changed = ScriptScene.Line(role: existingRole(name), text: spoken, section: section, notes: noteList)
        if insert {
            lines.insert(changed, at: max(0, min(index, lines.count)))
        } else {
            lines[index] = changed
        }
        if saveLines(lines) {
            dialog = nil
            return nil
        }
        return (2, "")
    }

    func addNote(index: Int, line: ScriptScene.Line, note: String) {
        let clean = JText.collapse(JText.trim(note))
        dialog = nil
        guard !clean.isEmpty, var lines = fullScene?.lines else {
            return
        }
        lines[index] = ScriptScene.Line(role: line.role, text: line.text, section: line.section, notes: line.notes + [clean])
        _ = saveLines(lines)
    }

    func deleteLine(_ index: Int) {
        let before = script
        guard var lines = fullScene?.lines else {
            return
        }
        lines.remove(at: index)
        if saveLines(lines) {
            showSnackbar(L("line_deleted"), action: L("undo")) {
                _ = self.saveText(before)
            }
        }
    }

    /// Writes edited lines back as the text, saves it and keeps the rehearsal state in step.
    @discardableResult
    func saveLines(_ lines: [ScriptScene.Line]) -> Bool {
        if lines.isEmpty {
            notifyKey("invalid_scene")
            return false
        }
        return saveText(ScriptScene.toText(lines))
    }

    @discardableResult
    func saveText(_ text: String) -> Bool {
        if JText.length(text) > Self.maxScriptLength {
            notifyKey("import_too_long")
            return false
        }
        let title = JText.trim(scriptTitle).isEmpty ? L("sample_title") : scriptTitle
        scriptId = library.save(id: scriptId, title: title, text: text, category: category, language: language)
        scriptTitle = title
        script = text
        savedTitle = title
        savedScript = text
        let parsed = parseScript(text)
        fullScene = parsed
        if sectionIndex >= parsed.sections.count {
            sectionIndex = -1
        }
        scene = parsed.forSection(sectionIndex)
        reviewBase = nil
        position = min(position, scene.lines.count)
        ensureRole()
        parsedCache.removeAll()
        return true
    }

    // MARK: - Characters

    /// Renames or merges characters in the text open in the editor, before it is saved.
    func editEditorRoles() {
        showRoles(parseScript(editorText)) { lines, from, to in
            let before = self.editorText
            let text = ScriptScene.toText(lines)
            if JText.length(text) > Self.maxScriptLength {
                self.notifyKey("import_too_long")
                return nil
            }
            self.editorText = text
            self.script = text
            self.showSnackbar(L("role_renamed", from, to), action: L("undo")) {
                self.editorText = before
                self.script = before
            }
            return self.parseScript(text)
        }
    }

    /// Renames or merges characters in the saved text; the voice of a renamed character stays.
    func editSavedRoles() {
        guard let fullScene else {
            return
        }
        showRoles(fullScene) { lines, from, to in
            let before = self.script
            let previousRole = self.role
            let merged = self.fullScene?.roles.contains(to) ?? false
            let voice = self.currentCast()[from]
            if from == self.role {
                self.role = to
            }
            if !self.saveLines(lines) {
                self.role = previousRole
                return nil
            }
            if !merged, let voice {
                var cast = self.currentCast()
                cast[to] = voice
                self.saveCast(cast)
            }
            self.showSnackbar(L("role_renamed", from, to), action: L("undo")) {
                self.role = previousRole
                _ = self.saveText(before)
            }
            return self.fullScene
        }
    }

    /// Lists the characters with their line counts; tapping one renames it or merges it into another.
    func showRoles(_ parsed: ScriptScene?, _ edit: @escaping RoleEdit) {
        guard let parsed, !parsed.roles.isEmpty else {
            notifyKey("invalid_scene")
            return
        }
        dialog = DialogItem(kind: .roles(scene: parsed, edit: edit))
    }

    func roleCountLabel(_ parsed: ScriptScene, _ name: String) -> String {
        let count = parsed.lines.filter { $0.role == name }.count
        return name + " · " + P("line_count", count)
    }

    func renameRole(_ parsed: ScriptScene, from: String, typed: String, edit: @escaping RoleEdit) -> String? {
        let clean = JText.collapse(JText.trim(typed))
        if !ScriptScene.isRoleName(clean) {
            return L("line_role_invalid")
        }
        // Typing another character's name in any case merges into that character's spelling.
        var to = clean
        for name in parsed.roles where name != from
            && name.lowercased(with: language.locale) == clean.lowercased(with: language.locale) {
            to = name
        }
        if to == from {
            then { self.showRoles(parsed, edit) }
            return nil
        }
        let lines = parsed.lines.map { line in
            line.role == from ? ScriptScene.Line(role: to, text: line.text, section: line.section, notes: line.notes) : line
        }
        dialog = nil
        if let updated = edit(lines, from, to), updated.roles.count > 1 {
            then { self.showRoles(updated, edit) }
        }
        return nil
    }

    // MARK: - Character voices

    var castKey: String {
        scriptId ?? "sample"
    }

    var castVoiceNames: [String] {
        DeviceVoice.voices(language).map(\.identifier)
    }

    func currentCast() -> [String: VoiceCast.Part] {
        let roles = fullScene?.roles ?? []
        return VoiceCast.load(defaults, key: castKey, roles: roles, voices: castVoiceNames, genders: studio.genders(roles))
    }

    func saveCast(_ cast: [String: VoiceCast.Part]) {
        VoiceCast.save(defaults, key: castKey, cast: cast, order: fullScene?.roles ?? [])
        objectWillChange.send()
    }

    func updateCast(_ character: String, _ part: VoiceCast.Part) {
        var cast = currentCast()
        cast[character] = part
        saveCast(cast)
    }

    func resetCast() {
        VoiceCast.reset(defaults, key: castKey)
        objectWillChange.send()
    }

    func voiceLabel(_ name: String) -> String {
        if name.isEmpty {
            return L("voice_default")
        }
        let voices = DeviceVoice.voices(language)
        if let index = voices.firstIndex(where: { $0.identifier == name }) {
            return voiceOptionLabel(index + 1, voices[index])
        }
        return L("voice_default")
    }

    /// Premium AI voices are named and marked with a star; the names follow the app language.
    func aiVoiceName(_ speaker: Int) -> String {
        "★ " + L("ai_voice_names_\(max(0, min(NeuralVoice.speakers - 1, speaker)))")
    }

    /// Speakers 0–4 are Supertonic's female voices F1–F5, 5–9 the male voices M1–M5.
    func aiVoiceGender(_ speaker: Int) -> String {
        L(speaker < NeuralVoice.speakers / 2 ? "voice_female" : "voice_male")
    }

    func aiVoiceTrait(_ speaker: Int) -> String {
        L("ai_voice_traits_\(max(0, min(NeuralVoice.speakers - 1, speaker)))")
    }

    /// "Kore · Female · Firm": a studio voice with its gender and character.
    func studioVoiceLabel(_ speaker: Int) -> String {
        let index = max(0, min(StudioVoices.castVoices.count - 1, speaker))
        return StudioVoices.castVoices[index] + " · " + aiVoiceGender(index) + " · " + L("studio_voice_traits_\(index)")
    }

    /// The studio voice follows the character's speaker, so choosing it also sets the gender.
    func chooseStudioVoice(_ character: String) {
        let speakers = Array(0..<StudioVoices.castVoices.count)
        choose(L("cast_studio_voice_for", character), speakers.map(studioVoiceLabel),
               selected: currentCast()[character]?.speaker) { index in
            guard var part = self.currentCast()[character] else {
                return
            }
            part.speaker = index
            self.updateCast(character, part)
            self.previewStudioVoice(character, StudioVoices.castVoices[index])
        }
    }

    /// Plays how a character sounds with a studio voice: their own prepared line when there is one,
    /// otherwise the voice's sample that comes with the app.
    func previewStudioVoice(_ character: String, _ voice: String) {
        cancelSpeech()
        let prepared = fullScene?.lines.lazy.filter { $0.role == character }
            .compactMap { self.studio.audio($0, self.language, voice: voice) }.first
        guard let url = prepared ?? StudioVoices.previewURL(language, voice),
              let player = try? AVAudioPlayer(contentsOf: url) else {
            notifyKey("speech_failed")
            return
        }
        previewPlayer = player
        player.play()
    }

    func chooseCharacterVoice(_ character: String) {
        if useAiVoice {
            let speakers = Array(0..<NeuralVoice.speakers)
            choose(L("cast_voice_for", character), speakers.map(aiVoiceName),
                   details: speakers.map { aiVoiceGender($0) + " · " + aiVoiceTrait($0) },
                   selected: currentCast()[character]?.speaker) { index in
                guard var part = self.currentCast()[character] else {
                    return
                }
                part.speaker = index
                self.updateCast(character, part)
                self.previewCharacter(character)
            }
            return
        }
        let voices = DeviceVoice.voices(language)
        let current = currentCast()[character]?.voice ?? ""
        let labels = [L("voice_default")] + voices.map { voiceLabel($0.identifier) }
        let selected = (voices.firstIndex { $0.identifier == current }).map { $0 + 1 } ?? 0
        choose(L("cast_voice_for", character), labels, selected: selected) { index in
            guard var part = self.currentCast()[character] else {
                return
            }
            part.voice = index == 0 ? "" : voices[index - 1].identifier
            self.updateCast(character, part)
            self.previewCharacter(character)
        }
    }

    func previewCharacter(_ character: String) {
        if studioCastShown {
            previewStudioVoice(character, StudioVoices.voice(of: currentCast()[character]))
            return
        }
        if !deviceVoiceAvailable(language) && !useAiVoice {
            return
        }
        let sample = fullScene?.lines.first { $0.role == character }?.text ?? language.sample
        guard let part = currentCast()[character] else {
            return
        }
        if useAiVoice {
            previewAiVoice(language, speaker: part.speaker, speed: part.pace.rate, text: sample)
            return
        }
        cancelSpeech()
        let id = "preview-\(nextUtterance())"
        activePreview = id
        deviceVoice.speak(sample, language: language, voice: part.voice.isEmpty ? defaultVoice(language) : part.voice,
                          pitch: part.tone.pitch, rate: part.pace.rate) { ok in
            self.finishSpeech(id, failed: !ok)
        }
    }

    // MARK: - Premium

    var handsFreeEnabled: Bool {
        premium && defaults.bool(forKey: Self.handsFreeKey) && HandsFree.isAuthorized
    }

    func showPaywall(_ feature: String) {
        alert(L(feature), L("paywall_message", L(feature)), [
            DialogButton(label: L("paywall_open")) { self.openPremium() },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    func setPremiumTesting(_ active: Bool) {
        Premium.setTesting(active)
        refreshPremium()
        if !active {
            stopListening()
        }
        notifyKey(active ? "premium_now_on" : "premium_now_off")
    }

    private func onBillingChanged() {
        refreshPremium()
        if !premium {
            stopListening()
        }
    }

    func purchase(_ plan: Billing.Plan) {
        Task { @MainActor in
            if !(await billing.purchase(plan)) {
                notifyKey("billing_failed")
            }
            refreshPremium()
        }
    }

    func priceLabel(_ plan: Billing.Plan) -> String {
        guard let period = plan.period, period.0 == 1 else {
            return plan.price
        }
        switch period.1 {
        case "W": return L("price_per_week", plan.price)
        case "M": return L("price_per_month", plan.price)
        case "Y": return L("price_per_year", plan.price)
        default: return plan.price
        }
    }

    func trialLabel(_ plan: Billing.Plan) -> String? {
        guard let trial = plan.trial else {
            return nil
        }
        switch trial.1 {
        case "D": return P("trial_days", trial.0)
        case "W": return P("trial_weeks", trial.0)
        case "M": return P("trial_months", trial.0)
        default: return nil
        }
    }

    // MARK: - Studio voices

    /// The chosen voice; studio voices are the default, and until a scene is prepared the AI or device
    /// voice reads.
    var voiceEngine: Engine {
        Engine(rawValue: defaults.string(forKey: Self.engineKey) ?? "") ?? .studio
    }

    /// Whether the studio voice choices apply here: on Premium, or in the sample scene for everyone.
    var studioCastShown: Bool {
        voiceEngine == .studio && (premium || scriptId == nil)
    }

    func chooseVoiceEngine() {
        let titles = ["voice_engine_device", "ai_voice_title", "studio_title"]
        let details = ["voice_engine_device_detail", "voice_engine_ai_detail", "voice_engine_studio_detail"]
        let marked = details.enumerated().map { index, key -> String in
            index > 0 && !premium && !(index == 2 && scriptId == nil) ? L(key) + " · " + L("premium_tab") : L(key)
        }
        choose(L("voice_engine_title"), titles.map { L($0) }, details: marked,
               selected: Engine.allCases.firstIndex(of: voiceEngine)) { index in
            self.pickVoiceEngine(Engine.allCases[index])
        }
    }

    func pickVoiceEngine(_ engine: Engine) {
        if engine == .ai {
            guard premium else {
                showPaywall("ai_voice_title")
                return
            }
            guard NeuralVoice.isSupported else {
                notifyKey("ai_voice_unsupported")
                return
            }
            defaults.set(true, forKey: Self.aiVoiceKey)
            if neural.state == .notInstalled {
                confirmAiDownload()
            } else {
                neural.load()
            }
        }
        if engine == .studio && !premium && scriptId != nil {
            showPaywall("studio_title")
            return
        }
        defaults.set(engine.rawValue, forKey: Self.engineKey)
        objectWillChange.send()
    }

    /// The engine row's value: the chosen voice and how far its preparation is.
    var voiceEngineValue: String {
        switch voiceEngine {
        case .device: return L("voice_engine_device") + "\n" + L("voice_engine_device_detail")
        case .ai: return L("ai_voice_title") + "\n" + L("voice_engine_ai_detail")
        case .studio:
            let detail = premium && category.hasRoles ? L("studio_ready", studioReady, studioTotal)
                : scriptId == nil && studioReady > 0 ? L("studio_sample") : L("voice_engine_studio_detail")
            return L("studio_title") + "\n" + detail
        }
    }

    /// The studio voice audio of a line, when it is prepared and switched on. The sample scene's
    /// come with the app and play for everyone, so the studio voices can be heard before buying.
    func studioAudio(_ line: ScriptScene.Line) -> URL? {
        guard premium || scriptId == nil, voiceEngine == .studio, fullScene != nil else {
            return nil
        }
        return studio.audio(line, language, voice: studioCasting()(line.role))
    }

    /// Each character's studio voice follows their voice in the cast (female or male). Reads the saved
    /// cast only, so it can be asked from the preparing queue.
    func studioCasting() -> StudioVoices.Casting {
        let roles = fullScene?.roles ?? []
        let key = castKey
        let defaults = defaults
        let studio = studio
        return { character in
            StudioVoices.voice(of: VoiceCast.load(defaults, key: key, roles: roles, voices: [],
                                                  genders: studio.genders(roles))[character])
        }
    }

    var studioTotal: Int {
        scene.lines.count
    }

    var studioReady: Int {
        guard fullScene != nil else {
            return 0
        }
        return studio.running ? studio.done : studio.readyCount(scene, language, casting: studioCasting())
    }

    // MARK: - Director's notes

    /// A line's note and voicing as plain text, for the notes screen and for sharing.
    func noteText(_ line: ScriptScene.Line) -> String {
        var text = line.notes.map { L("notes_script_direction", $0) + "\n" }.joined()
        guard let note = studio.noteOf(line, language) else {
            return text + L("notes_not_directed")
        }
        text += L("notes_delivery", note["emotion"] as? String ?? "", note["pace"] as? String ?? "") + "\n"
        if let subtext = note["note"] as? String, !subtext.isEmpty {
            text += L("notes_subtext", subtext) + "\n"
        }
        for segment in note["segments"] as? [[String: Any]] ?? [] {
            let style = segment["style"] as? String ?? ""
            text += "• [" + (segment["volume"] as? String ?? "") + (style.isEmpty ? "" : ", " + style) + "] "
                + (segment["text"] as? String ?? "")
            let pause = (segment["pauseAfterMs"] as? NSNumber)?.intValue ?? 0
            text += (pause > 0 ? " (\(pause) ms)" : "") + "\n"
        }
        for saying in note["pronunciations"] as? [[String: Any]] ?? [] {
            text += L("notes_pronounce", saying["word"] as? String ?? "", saying["say"] as? String ?? "") + "\n"
        }
        let voice = studioCasting()(line.role)
        guard let voiced = studio.voicedOf(line, language, voice: voice) else {
            return text + L(studio.audio(line, language, voice: voice) == nil ? "notes_not_voiced" : "notes_voiced_unknown", voice)
        }
        text += L("notes_voiced", voiced["voice"] as? String ?? voice, voiced["model"] as? String ?? "?",
                  voiced["source"] as? String ?? "?")
        if let style = voiced["style"] as? String, !style.isEmpty {
            text += "\n" + L("notes_style", style)
        }
        if let sent = voiced["text"] as? String, !sent.isEmpty {
            text += "\n" + L("notes_sent", sent)
        }
        return text
    }

    /// What the director made of the play and of this scene, or why there is nothing yet.
    var readingText: String {
        guard let fullScene,
              let reading = studio.analysis(fullScene, language, script: StudioVoices.scriptId(castKey)) else {
            return L("notes_no_reading")
        }
        let context = StudioVoices.contextFor(reading, section: scene.lines.first?.section ?? "")
            .trimmingCharacters(in: .whitespacesAndNewlines)
        return context.isEmpty ? L("notes_no_reading") : context
    }

    /// Directs and voices the scene again, after the play is read again.
    func confirmRedirect() {
        alert(L("notes_redirect"), L("notes_redirect_message"), [
            DialogButton(label: L("notes_redirect")) {
                guard let fullScene = self.fullScene else {
                    return
                }
                self.studio.forget(fullScene, self.scene, self.language, castKey: self.castKey)
                self.show(.setup)
                self.prepareStudio()
            },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    /// Every line's note, to share.
    var notesText: String {
        L("notes_title") + " · " + scriptTitle + " · " + language.code + "\n\n"
            + L("notes_reading") + ":\n" + readingText + "\n\n"
            + scene.lines.map { $0.role + ": " + $0.text + "\n" + noteText($0) }.joined(separator: "\n\n")
    }

    private var castConfirmedKey: String {
        "studio_cast_confirmed_" + castKey
    }

    /// Preparing studio voices starts on the character voices screen: each voice is fixed once
    /// prepared, so the user first checks that women and men have voices of their gender. The
    /// director's gender guesses fill in the cast while the screen opens.
    func startStudio() {
        guard let fullScene else {
            return
        }
        if defaults.bool(forKey: castConfirmedKey) {
            confirmStudio()
            return
        }
        if studio.analysis(fullScene, language, script: StudioVoices.scriptId(castKey)) == nil && !guessingCast {
            guessingCast = true
            studio.analyzeFirst(fullScene, language, title: scriptTitle, castKey: castKey) {
                self.guessingCast = false
                self.objectWillChange.send()
            }
        }
        show(.voices)
        castCheck = true
    }

    /// Keeps the checked cast, so later guesses cannot change a voice, and goes on to prepare.
    func confirmCastAndPrepare() {
        saveCast(currentCast())
        defaults.set(true, forKey: castConfirmedKey)
        show(.setup)
        confirmStudio()
    }

    func confirmStudio() {
        alert(L("studio_prepare"), L("studio_value") + "\n\n" + L("studio_privacy"), [
            DialogButton(label: L("studio_start")) { self.prepareStudio() },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    func prepareStudio() {
        guard let fullScene, !studio.running else {
            return
        }
        studio.prepare(fullScene, scene, language: language, title: scriptTitle, script: castKey,
                       casting: studioCasting()) { reason in
            switch reason {
            case .dailyLimit: self.notifyKey("studio_daily_limit")
            case .monthlyLimit: self.notifyKey("studio_monthly_limit")
            case .scriptLimit: self.notifyKey("studio_script_limit")
            case .failed: self.notifyKey("studio_failed")
            default: break
            }
            self.objectWillChange.send()
        }
    }

    // MARK: - AI voice

    /// Whether lines are read by the on-device AI voice instead of the device voice.
    /// With studio voices chosen, the AI voice (when the user has it) reads lines not yet prepared.
    var useAiVoice: Bool {
        guard premium, NeuralVoice.isSupported, defaults.bool(forKey: Self.aiVoiceKey), voiceEngine != .device else {
            return false
        }
        switch neural.state {
        case .installed, .loading, .ready, .failed: return true
        default: return false
        }
    }

    var downloadMegabytes: Int {
        Int((Double(NeuralVoice.downloadBytes) / 1_000_000).rounded())
    }

    func confirmAiDownload() {
        alert(L("ai_voice_download"), L("ai_voice_download_message", downloadMegabytes), [
            DialogButton(label: L("ai_voice_download_confirm")) {
                self.defaults.set(true, forKey: Self.aiVoiceKey)
                self.defaults.set(Engine.ai.rawValue, forKey: Self.engineKey)
                self.neural.download()
                self.objectWillChange.send()
            },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    func confirmAiDelete() {
        alert(L("ai_voice_delete"), L("ai_voice_delete_message"), [
            DialogButton(label: L("action_delete"), role: .destructive) {
                self.defaults.set(false, forKey: Self.aiVoiceKey)
                if self.voiceEngine == .ai {
                    self.defaults.set(Engine.device.rawValue, forKey: Self.engineKey)
                }
                self.neural.delete()
            },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    private func onNeuralVoiceChanged() {
        let state = neural.state
        if screen == .rehearsal && autoPlayPending && (state == .ready || state == .failed) {
            autoPlayPending = false
            if state == .failed {
                notifyKey("ai_voice_failed")
            }
            playCurrent()
            return
        }
        if state == .ready && screen == .rehearsal {
            prefetchAhead()
        }
        objectWillChange.send()
    }

    func previewAiVoice(_ speaker: Int) {
        let target = settingsLanguage ?? Strings.interfaceLanguage
        previewAiVoice(target, speaker: speaker, speed: 1, text: target.sample)
    }

    func previewAiVoice(_ target: ScriptLanguage, speaker: Int, speed: Float, text: String) {
        if !neural.isReady {
            neural.load()
            notifyKey("ai_voice_loading")
            return
        }
        cancelSpeech()
        let id = "preview-\(nextUtterance())"
        activePreview = id
        neural.speak(text, language: target.code, speaker: speaker, speed: speed) { ok in
            self.finishSpeech(id, failed: !ok)
        }
    }

    // MARK: - Rehearsal

    private func nextUtterance() -> Int {
        utteranceNumber += 1
        return utteranceNumber
    }

    var isMyTurn: Bool {
        position < scene.lines.count
            && !RehearsalOptions.shouldSpeak(mode: mode, selectedRole: role, lineRole: scene.lines[position].role)
    }

    /// The next line the actor says after the current one, or -1 when there is none.
    func nextTurn() -> Int {
        var i = position + 1
        while i < scene.lines.count {
            if !RehearsalOptions.shouldSpeak(mode: mode, selectedRole: role, lineRole: scene.lines[i].role) {
                return i
            }
            i += 1
        }
        return -1
    }

    func playCurrent() {
        guard visible, screen == .rehearsal, position < scene.lines.count else {
            return
        }
        let line = scene.lines[position]
        guard RehearsalOptions.shouldSpeak(mode: mode, selectedRole: role, lineRole: line.role) else {
            return
        }
        if let recorded = castRecording(line), playRecording(recorded, studioLine: false) {
            return
        }
        if let directed = studioAudio(line), playRecording(directed, studioLine: true) {
            return
        }
        if useAiVoice && neural.state != .failed {
            playWithAiVoice(line)
            return
        }
        if deviceVoiceAvailable(language) {
            cancelSpeech()
            stopListening()
            playbackFailed = false
            aiSpeaking = false
            recordingSpeaking = false
            studioSpeaking = false
            let part = premium ? currentCast()[line.role] : nil
            let id = "line-\(nextUtterance())"
            activeUtterance = id
            let voice = part.flatMap { $0.voice.isEmpty ? nil : $0.voice } ?? defaultVoice(language)
            deviceVoice.speak(line.text, language: language, voice: voice, pitch: part?.tone.pitch ?? 1,
                              rate: (part?.pace.rate ?? 1) * readingSpeed) { ok in
                self.finishSpeech(id, failed: !ok)
            }
        }
    }

    func finishSpeech(_ id: String, failed: Bool) {
        if id == activePreview {
            activePreview = nil
            if failed {
                notifyKey("speech_failed")
            }
            return
        }
        guard id == activeUtterance else {
            return
        }
        activeUtterance = nil
        if failed {
            playbackFailed = true
        } else {
            moveTo(position + 1, autoPlay: true)
        }
    }

    func cancelSpeech() {
        autoPlayPending = false
        releaseRecordingPlayer()
        if activeUtterance != nil || activePreview != nil {
            activeUtterance = nil
            activePreview = nil
            deviceVoice.stop()
            neural.stop()
        }
    }

    func moveTo(_ target: Int, autoPlay: Bool) {
        cancelSpeech()
        stopListening()
        position = target
        rememberPosition()
        revealLine = false
        playbackFailed = false
        updateIdleTimer()
        if autoPlay && position < scene.lines.count
            && RehearsalOptions.shouldSpeak(mode: mode, selectedRole: role, lineRole: scene.lines[position].role) {
            playCurrent()
        } else {
            prefetchAhead()
            if cueTurn() {
                // Let the beep finish before the microphone starts listening.
                DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(Self.turnCueMillis + 150)) {
                    self.listenIfNeeded()
                }
            } else {
                listenIfNeeded()
            }
        }
    }

    func togglePlay() {
        if activeUtterance == nil {
            playCurrent()
        } else {
            cancelSpeech()
        }
    }

    func previousLine() {
        guard position > 0 else {
            return
        }
        markRetry(position - 1)
        markRetry(position)
        moveTo(position - 1, autoPlay: false)
    }

    func reveal() {
        revealLine = true
        revealedLines.insert(position)
    }

    /// The cast member's recording of a line in a shared text, if one is on this device.
    func castRecording(_ line: ScriptScene.Line) -> URL? {
        guard let scriptId, scriptId.hasPrefix(EnsembleRules.localPrefix), let fullScene else {
            return nil
        }
        if castRecordings == nil || castRecordingsFor != scriptId {
            let production = String(scriptId.dropFirst(EnsembleRules.localPrefix.count))
            castRecordings = EnsembleRules.Voices.load(directory: EnsembleRecordings.directory(production), scene: fullScene)
            castRecordingsFor = scriptId
        }
        return castRecordings?.find(line)
    }

    /// Plays a recorded line, a cast member's or a studio voice's; returns false when it cannot, so a
    /// voice reads it instead.
    private func playRecording(_ file: URL, studioLine: Bool) -> Bool {
        cancelSpeech()
        stopListening()
        playbackFailed = false
        aiSpeaking = false
        let id = "line-\(nextUtterance())"
        AudioSessions.usePlayback()
        guard let player = try? AVAudioPlayer(contentsOf: file) else {
            return false
        }
        player.enableRate = true
        player.rate = readingSpeed
        let delegate = PlayerDone { [weak self] ok in
            self?.releaseRecordingPlayer()
            self?.finishSpeech(id, failed: !ok)
        }
        player.delegate = delegate
        recordingDelegate = delegate
        recordingPlayer = player
        activeUtterance = id
        recordingSpeaking = !studioLine
        studioSpeaking = studioLine
        player.play()
        return true
    }

    private func releaseRecordingPlayer() {
        recordingPlayer?.stop()
        recordingPlayer = nil
        recordingDelegate = nil
    }

    /// Beeps when the rehearsal reaches one of the actor's own lines.
    private func cueTurn() -> Bool {
        guard mode == .practice, visible, screen == .rehearsal, isMyTurn, turnCueOn else {
            return false
        }
        turnCue.play(millis: Self.turnCueMillis)
        return true
    }

    private func playWithAiVoice(_ line: ScriptScene.Line) {
        if !neural.isReady {
            autoPlayPending = true
            neural.load()
            return
        }
        cancelSpeech()
        stopListening()
        playbackFailed = false
        let id = "line-\(nextUtterance())"
        activeUtterance = id
        aiSpeaking = true
        recordingSpeaking = false
        studioSpeaking = false
        let part = currentCast()[line.role]
        neural.speak(line.text, language: language.code, speaker: part?.speaker ?? 0,
                     speed: (part?.pace.rate ?? 1) * readingSpeed) { ok in
            self.finishSpeech(id, failed: !ok)
        }
        prefetchAhead()
    }

    /// Prepares the next partner lines while the current one plays or the actor speaks.
    private func prefetchAhead() {
        guard useAiVoice, neural.isReady, screen == .rehearsal else {
            return
        }
        let cast = currentCast()
        var found = 0
        var i = position + 1
        while i < scene.lines.count && found < 2 {
            let line = scene.lines[i]
            if RehearsalOptions.shouldSpeak(mode: mode, selectedRole: role, lineRole: line.role) {
                let part = cast[line.role]
                neural.prefetch(line.text, language: language.code, speaker: part?.speaker ?? 0,
                                speed: (part?.pace.rate ?? 1) * readingSpeed)
                found += 1
            }
            i += 1
        }
    }

    var speechMessage: String {
        if activeUtterance == nil && useAiVoice && !neural.isReady && neural.state != .failed {
            return L("ai_voice_loading")
        }
        if activeUtterance != nil {
            if recordingSpeaking {
                return L("ensemble_recording_playing")
            }
            if studioSpeaking {
                return L("studio_playing")
            }
            if aiSpeaking {
                return L("ai_voice_playing")
            }
            return L(mode == .listen ? "reading_playing" : "playing")
        }
        if playbackFailed {
            return L("speech_failed")
        }
        if !deviceVoiceAvailable(language) {
            return L("speech_unavailable")
        }
        return L(mode == .listen ? "reading_ready" : "speech_ready")
    }

    var canPlay: Bool {
        deviceVoiceAvailable(language) || useAiVoice
            || (position < scene.lines.count && (castRecording(scene.lines[position]) != nil
                || studioAudio(scene.lines[position]) != nil))
    }

    // MARK: - Practice report

    func resetReport() {
        revealedLines.removeAll()
        retriedLines.removeAll()
        missedLines.removeAll()
    }

    private func markRetry(_ index: Int) {
        if mode == .practice && index >= 0 && index < scene.lines.count
            && !RehearsalOptions.shouldSpeak(mode: mode, selectedRole: role, lineRole: scene.lines[index].role) {
            retriedLines.insert(index)
        }
    }

    var struggledLines: [Int] {
        revealedLines.union(retriedLines).union(missedLines).filter { $0 < scene.lines.count }.sorted()
    }

    /// Practises only the struggled lines, each preceded by its cue line.
    func startReview(_ struggled: [Int]) {
        let base = reviewBase
        var keep = Set<Int>()
        for index in struggled {
            let original = base?[index] ?? index
            keep.insert(original)
            if original > 0 {
                keep.insert(original - 1)
            }
        }
        let sorted = keep.sorted()
        reviewBase = sorted
        scene = (fullScene?.forSection(sectionIndex) ?? .empty).subset(sorted)
        resetReport()
        moveTo(0, autoPlay: true)
    }

    // MARK: - Hands-free cues

    func toggleHandsFree() {
        if !premium {
            showPaywall("premium_voice_cue")
            return
        }
        if handsFreeEnabled {
            defaults.set(false, forKey: Self.handsFreeKey)
            stopListening()
            objectWillChange.send()
            return
        }
        if !HandsFree.isAvailable {
            notifyKey("hands_free_unavailable")
            return
        }
        HandsFree.requestPermission { granted in
            if granted {
                self.defaults.set(true, forKey: Self.handsFreeKey)
                self.objectWillChange.send()
                self.listenIfNeeded()
            } else {
                self.notifyKey("hands_free_permission")
            }
        }
    }

    func listenIfNeeded() {
        guard handsFreeEnabled, visible, screen == .rehearsal, isMyTurn else {
            return
        }
        heardSoFar = ""
        partialHeard = ""
        listenAttempts = 0
        listeningPaused = false
        startListening()
    }

    private func startListening() {
        listenerBridge.target = position
        handsFree.start(listenerBridge, locale: language.locale)
    }

    fileprivate func listenerStale(_ target: Int) -> Bool {
        target != position || screen != .rehearsal || !handsFreeEnabled || !visible
    }

    fileprivate func onListening(_ target: Int) {
        guard !listenerStale(target) else {
            return
        }
        listening = true
        listeningPaused = false
        partialHeard = ""
    }

    fileprivate func onPartial(_ target: Int, _ text: String) {
        if !listenerStale(target) {
            partialHeard = text
        }
    }

    fileprivate func onHeard(_ target: Int, _ text: String) {
        listening = false
        guard !listenerStale(target) else {
            return
        }
        heardSoFar = JText.trim(heardSoFar + " " + text)
        partialHeard = ""
        let said = LineCoverage.coverage(expected: scene.lines[target].text, heard: heardSoFar, locale: language.locale)
        if said >= Self.lineSaid {
            if said < Self.lineClean {
                missedLines.insert(target)
            }
            moveTo(target + 1, autoPlay: true)
        } else {
            listenAttempts += 1
            if listenAttempts < Self.listenRetries {
                startListening()
            } else {
                missedLines.insert(target)
                pauseListening()
            }
        }
    }

    fileprivate func onSilence(_ target: Int) {
        listening = false
        guard !listenerStale(target) else {
            return
        }
        listenAttempts += 1
        if listenAttempts < Self.listenRetries {
            startListening()
        } else {
            pauseListening()
        }
    }

    fileprivate func onFailure(_ target: Int, permanent: Bool) {
        listening = false
        guard !listenerStale(target) else {
            return
        }
        if permanent {
            defaults.set(false, forKey: Self.handsFreeKey)
            notifyKey("hands_free_permission")
            objectWillChange.send()
            return
        }
        listenAttempts += 1
        if listenAttempts < Self.listenRetries {
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.7) {
                if !self.listenerStale(target) {
                    self.startListening()
                }
            }
        } else {
            pauseListening()
        }
    }

    private func pauseListening() {
        listening = false
        listeningPaused = true
    }

    func stopListening() {
        listening = false
        listeningPaused = false
        handsFree.stop()
    }

    var listeningText: String {
        if listeningPaused {
            return L("listening_paused")
        }
        let heard = JText.trim(heardSoFar + " " + partialHeard)
        return heard.isEmpty ? L("listening") : L("listening") + "\n" + L("heard", heard)
    }
}

/// Receives the hands-free callbacks for the line that was current when listening started.
private final class ListenerBridge: HandsFreeListener {
    weak var model: AppModel?
    var target = 0

    init(model: AppModel) {
        self.model = model
    }

    func onListening() {
        model?.onListening(target)
    }

    func onPartial(_ text: String) {
        model?.onPartial(target, text)
    }

    func onHeard(_ text: String) {
        model?.onHeard(target, text)
    }

    func onSilence() {
        model?.onSilence(target)
    }

    func onFailure(permanent: Bool) {
        model?.onFailure(target, permanent: permanent)
    }
}

/// Tells when a recorded line has finished playing.
final class PlayerDone: NSObject, AVAudioPlayerDelegate {
    private let done: (Bool) -> Void

    init(_ done: @escaping (Bool) -> Void) {
        self.done = done
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        DispatchQueue.main.async {
            self.done(flag)
        }
    }

    func audioPlayerDecodeErrorDidOccur(_ player: AVAudioPlayer, error: Error?) {
        DispatchQueue.main.async {
            self.done(false)
        }
    }
}
