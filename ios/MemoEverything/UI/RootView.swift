import MemoCore
import PhotosUI
import SwiftUI
import UniformTypeIdentifiers

/// The app window: the current screen with its bars, and the dialogs, pickers and messages on top.
struct RootView: View {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase
    @State private var photoSelection: [PhotosPickerItem] = []

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
            screen
                .id(model.screen)
            // SwiftUI shows one file dialog per view, so each picker has its own anchor.
            Color.clear.frame(width: 0, height: 0)
                .fileImporter(isPresented: $model.pickingPdf, allowedContentTypes: [.pdf]) { result in
                    if case .success(let url) = result {
                        model.startImport(url, pdf: true)
                    }
                }
            Color.clear.frame(width: 0, height: 0)
                .fileImporter(isPresented: $model.pickingFile, allowedContentTypes: Self.textTypes) { result in
                    if case .success(let url) = result {
                        model.startImport(url, pdf: false)
                    }
                }
            Color.clear.frame(width: 0, height: 0)
                .fileImporter(isPresented: $model.importingBackup,
                              allowedContentTypes: [.json, .plainText, .data]) { result in
                    if case .success(let url) = result {
                        model.readBackup(url)
                    }
                }
            Color.clear.frame(width: 0, height: 0)
                .fileExporter(isPresented: $model.exportingBackup, document: model.backupToExport,
                              contentType: .json, defaultFilename: model.backupFileName()) { result in
                    model.backupExported(result)
                }
        }
        .overlay(alignment: .bottom) { messages }
        .alert(alertTitle, isPresented: alertShown, presenting: alertButtons) { buttons in
            ForEach(Array(buttons.enumerated()), id: \.offset) { _, button in
                Button(button.label, role: role(button.role)) {
                    run(button.action)
                }
            }
        } message: { _ in
            Text(alertMessage)
        }
        .sheet(item: sheetItem) { item in
            DialogSheet(item: item)
                .environmentObject(model)
        }
        .photosPicker(isPresented: $model.pickingPhotos, selection: $photoSelection,
                      maxSelectionCount: PhotoOcr.maxPhotos + 1, matching: .images)
        .onChange(of: photoSelection) { items in
            guard !items.isEmpty else {
                return
            }
            photoSelection = []
            Task {
                var photos: [Data] = []
                for item in items {
                    if let data = try? await item.loadTransferable(type: Data.self) {
                        photos.append(data)
                    }
                }
                await MainActor.run {
                    if photos.isEmpty {
                        model.notifyKey("photo_import_failed")
                    } else {
                        model.photosPicked(photos)
                    }
                }
            }
        }
        .fullScreenCover(isPresented: $model.takingPhoto) {
            CameraCapture { data in
                model.takingPhoto = false
                model.photoTaken(data)
            }
            .ignoresSafeArea()
        }
        .fullScreenCover(isPresented: $model.ensembleOpen) {
            EnsembleView(shareId: model.ensembleShare, invite: model.ensembleInvite) { outcome in
                model.ensembleOpen = false
                model.ensembleShare = nil
                model.ensembleInvite = nil
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.4) {
                    switch outcome {
                    case .addText:
                        model.startTextForEnsemble()
                    case .rehearse(let id, let role):
                        model.openEnsembleCopy(id, role: role)
                    case .closed:
                        model.objectWillChange.send()
                    }
                }
            }
            .environmentObject(model)
        }
        .fullScreenCover(isPresented: $model.videoOpen) {
            VideoView(setup: model.videoSetup()) {
                model.videoOpen = false
            }
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active {
                model.onForeground()
            } else if phase == .background {
                model.onBackground()
            }
        }
        .onOpenURL { url in
            model.openURL(url)
        }
        .preferredColorScheme(.dark)
        .tint(Palette.accent)
    }

    static let textTypes: [UTType] = [
        .plainText, .text, .utf8PlainText, .xml, .data,
        UTType(filenameExtension: "docx") ?? .data,
        UTType(filenameExtension: "fountain") ?? .data,
        UTType(filenameExtension: "fdx") ?? .data,
    ]

    @ViewBuilder private var screen: some View {
        switch model.screen {
        case .home: HomeScreen()
        case .categories: CategoriesScreen()
        case .source: SourceScreen()
        case .editor: EditorScreen()
        case .sections: SectionsScreen()
        case .setup: SetupScreen()
        case .reader: ReaderScreen()
        case .rehearsal: RehearsalScreen()
        case .voices: VoicesScreen()
        case .settings: SettingsScreen()
        case .premium: PremiumScreen()
        case .lines: LinesScreen()
        case .notes: NotesScreen()
        }
    }

    // MARK: Messages

    @ViewBuilder private var messages: some View {
        VStack(spacing: 8) {
            if let snackbar = model.snackbar {
                HStack(spacing: 0) {
                    Styled(snackbar.message, .body)
                        .lineLimit(2)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if let action = snackbar.action, let run = snackbar.run {
                        MemoButton(action, kind: .text) {
                            model.dismissSnackbar()
                            run()
                        }
                    }
                }
                .padding(EdgeInsets(top: 4, leading: 16, bottom: 4, trailing: 4))
                .frame(minHeight: 52)
                .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.surfaceHighest))
                .shadow(color: .black.opacity(0.4), radius: 8, y: 4)
                .padding(.horizontal, 12)
                .padding(.bottom, model.screen == .home && !model.scripts.isEmpty ? 84 : 12)
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
            if let toast = model.toast {
                Text(toast)
                    .font(.system(size: 14))
                    .foregroundColor(Palette.text)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(Palette.surfaceHighest.opacity(0.97)))
                    .padding(.horizontal, 24)
                    .padding(.bottom, 90)
                    .transition(.opacity)
                    .allowsHitTesting(false)
                    .accessibilityAddTraits(.isStaticText)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: model.toast)
        .animation(.easeInOut(duration: 0.2), value: model.snackbar?.message)
    }

    // MARK: Dialog plumbing

    private var alertItem: DialogItem? {
        if case .message = model.dialog?.kind {
            return model.dialog
        }
        return nil
    }

    private var alertShown: Binding<Bool> {
        Binding(get: { alertItem != nil }, set: { shown in
            if !shown, alertItem != nil {
                model.dialog = nil
            }
        })
    }

    private var alertTitle: String {
        if case .message(let title, _, _) = alertItem?.kind {
            return title ?? ""
        }
        return ""
    }

    private var alertMessage: String {
        if case .message(_, let message, _) = alertItem?.kind {
            return message
        }
        return ""
    }

    private var alertButtons: [DialogButton]? {
        if case .message(_, _, let buttons) = alertItem?.kind {
            return buttons
        }
        return nil
    }

    private func role(_ value: DialogButton.Role) -> ButtonRole? {
        switch value {
        case .normal: return nil
        case .cancel: return .cancel
        case .destructive: return .destructive
        }
    }

    private func run(_ action: @escaping () -> Void) {
        model.dialog = nil
        DispatchQueue.main.async(execute: action)
    }

    private var sheetItem: Binding<DialogItem?> {
        Binding(get: {
            if case .message = model.dialog?.kind {
                return nil
            }
            return model.dialog
        }, set: { value in
            if value == nil, let current = model.dialog {
                if case .message = current.kind {
                    return
                }
                model.dialogDismissed(current)
            }
        })
    }
}

/// The frame every screen uses: a top bar, scrolling content, an optional footer and overlay.
struct ScreenFrame<Bar: View, Content: View, Footer: View>: View {
    @ViewBuilder var bar: () -> Bar
    var scrolls = true
    var centered = false
    @ViewBuilder var content: () -> Content
    @ViewBuilder var footer: () -> Footer

    var body: some View {
        VStack(spacing: 0) {
            bar()
            if scrolls {
                ScrollView {
                    VStack(alignment: centered ? .center : .leading, spacing: 0, content: content)
                        .padding(EdgeInsets(top: 8, leading: 20, bottom: 28, trailing: 20))
                        .frame(maxWidth: 680, alignment: .leading)
                        .frame(maxWidth: .infinity)
                }
                .scrollDismissesKeyboard(.interactively)
            } else {
                VStack(alignment: centered ? .center : .leading, spacing: 0, content: content)
                    .padding(EdgeInsets(top: 8, leading: 20, bottom: 28, trailing: 20))
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            footer()
        }
        .memoBackground()
    }
}

extension ScreenFrame where Footer == EmptyView {
    init(@ViewBuilder bar: @escaping () -> Bar, scrolls: Bool = true, centered: Bool = false,
         @ViewBuilder content: @escaping () -> Content) {
        self.bar = bar
        self.scrolls = scrolls
        self.centered = centered
        self.content = content
        self.footer = { EmptyView() }
    }
}

/// The surface along the bottom edge that holds a screen's main buttons.
struct FooterBar<Content: View>: View {
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(spacing: 0, content: content)
            .padding(EdgeInsets(top: 12, leading: 20, bottom: 12, trailing: 20))
            .frame(maxWidth: .infinity)
            .background(Palette.surface.ignoresSafeArea(edges: .bottom))
            .overlay(alignment: .top) {
                Rectangle().fill(Palette.outlineSoft).frame(height: 1)
            }
    }
}

/// A top app bar with a navigation icon, an optional title and trailing actions.
struct TopBar<Actions: View>: View {
    var icon: String?
    var title: String?
    var navigate: (() -> Void)?
    @ViewBuilder var actions: () -> Actions

    var body: some View {
        HStack(spacing: 0) {
            if let icon, let navigate {
                IconButton(icon: icon, label: L(icon == "ic_close" ? "action_close" : "action_back"),
                           tint: Palette.text, action: navigate)
            }
            Text(title ?? "")
                .font(.system(size: 18, weight: .medium))
                .foregroundColor(Palette.text)
                .lineLimit(1)
                .truncationMode(.tail)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, icon == nil ? 12 : 4)
            actions()
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
        .frame(minHeight: 60)
    }
}

extension TopBar where Actions == EmptyView {
    init(icon: String?, title: String?, navigate: (() -> Void)?) {
        self.icon = icon
        self.title = title
        self.navigate = navigate
        actions = { EmptyView() }
    }
}

/// A trailing action of the top bar.
struct BarAction: View {
    let icon: String
    let label: String
    let action: () -> Void

    var body: some View {
        IconButton(icon: icon, label: L(label), tint: Palette.textSecondary, action: action)
    }
}

struct PageTitle: View {
    var overline: String?
    let title: String
    var subtitle: String?

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            if let overline {
                Styled(overline, .overline, Palette.accent)
                    .padding(.top, 12)
            }
            Styled(title, .display)
                .padding(.top, overline == nil ? 16 : 6)
            if let subtitle {
                Styled(subtitle, .bodyLarge, Palette.textSecondary)
                    .padding(.top, 8)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

struct SectionLabel: View {
    let key: String

    var body: some View {
        Styled(L(key), .overline, Palette.textTertiary)
            .padding(.top, 28)
    }
}

/// A rounded list of rows separated by inset dividers.
struct RowGroup<Content: View>: View {
    var color: Color = Palette.surface
    @ViewBuilder let content: () -> Content

    var body: some View {
        Card(color: color, padding: EdgeInsets(top: 4, leading: 0, bottom: 4, trailing: 0), content: content)
    }
}

/// A document picked or saved through the Files app: the backup file.
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] {
        [.json]
    }

    let text: String

    init(text: String) {
        self.text = text
    }

    init(configuration: ReadConfiguration) throws {
        text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

/// Takes one page photo with the camera.
struct CameraCapture: UIViewControllerRepresentable {
    let done: (Data?) -> Void

    func makeUIViewController(context: Context) -> UIImagePickerController {
        let picker = UIImagePickerController()
        picker.sourceType = .camera
        picker.delegate = context.coordinator
        return picker
    }

    func updateUIViewController(_ controller: UIImagePickerController, context: Context) {}

    func makeCoordinator() -> Coordinator {
        Coordinator(done: done)
    }

    final class Coordinator: NSObject, UIImagePickerControllerDelegate, UINavigationControllerDelegate {
        let done: (Data?) -> Void

        init(done: @escaping (Data?) -> Void) {
            self.done = done
        }

        func imagePickerController(_ picker: UIImagePickerController,
                                   didFinishPickingMediaWithInfo info: [UIImagePickerController.InfoKey: Any]) {
            let image = info[.originalImage] as? UIImage
            done(image.flatMap { $0.jpegData(compressionQuality: 0.9) })
        }

        func imagePickerControllerDidCancel(_ picker: UIImagePickerController) {
            done(nil)
        }
    }
}
