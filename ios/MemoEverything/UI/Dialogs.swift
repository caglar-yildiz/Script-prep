import MemoCore
import SwiftUI

/// Every dialog that needs more than an alert: choices, forms and lists, as a sheet.
struct DialogSheet: View {
    @EnvironmentObject var model: AppModel
    let item: DialogItem

    var body: some View {
        Group {
            switch item.kind {
            case .message:
                EmptyView()
            case let .choice(title, options, details, selected, pick):
                ChoiceDialog(title: title, options: options, details: details, selected: selected, pick: pick)
            case let .pages(source, category, narrator):
                PagesDialog(source: source, category: category, narrator: narrator)
            case let .lineEdit(index, near, insert):
                LineDialog(index: index, near: near, insert: insert)
            case let .note(index, line):
                NoteDialog(index: index, line: line)
            case let .roles(scene, edit):
                RolesDialog(scene: scene, edit: edit)
            case let .renameRole(scene, from, edit):
                RenameRoleDialog(scene: scene, from: from, edit: edit)
            case let .review(index):
                ReviewDialog(index: index)
            case .overview:
                OverviewDialog()
            case .photoMore:
                PhotoMoreDialog()
            case let .textInput(title, message, value, hint, limit, words, confirm, submit):
                TextInputDialog(title: title, message: message, value: value, hint: hint, limit: limit, words: words,
                                confirm: confirm, submit: submit)
            }
        }
        .presentationDragIndicator(.visible)
        .preferredColorScheme(.dark)
        .tint(Palette.accent)
        .background(Palette.surfaceHigh.ignoresSafeArea())
    }
}

/// The shared frame of a dialog sheet: title, scrolling body and the buttons.
struct DialogFrame<Content: View, Buttons: View>: View {
    let title: String
    var message: String?
    @ViewBuilder let content: () -> Content
    @ViewBuilder let buttons: () -> Buttons

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title)
                .font(.system(size: 20, weight: .medium))
                .foregroundColor(Palette.text)
                .padding(EdgeInsets(top: 24, leading: 24, bottom: 8, trailing: 24))
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    if let message {
                        Styled(message, .body, Palette.textSecondary).padding(.bottom, 12)
                    }
                    content()
                }
                .padding(.horizontal, 24)
                .padding(.vertical, 8)
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            HStack(spacing: 4) {
                Spacer()
                buttons()
            }
            .padding(EdgeInsets(top: 8, leading: 16, bottom: 16, trailing: 16))
        }
        .background(Palette.surfaceHigh.ignoresSafeArea())
    }
}

struct ChoiceDialog: View {
    @EnvironmentObject var model: AppModel
    let title: String
    let options: [String]
    let details: [String]?
    let selected: Int?
    let pick: (Int) -> Void

    var body: some View {
        DialogFrame(title: title) {
            ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                Button {
                    model.dialog = nil
                    DispatchQueue.main.async {
                        pick(index)
                    }
                } label: {
                    HStack(spacing: 16) {
                        if selected != nil {
                            ZStack {
                                Circle().strokeBorder(index == selected ? Palette.accent : Palette.textSecondary,
                                                      lineWidth: 2)
                                if index == selected {
                                    Circle().fill(Palette.accent).padding(5)
                                }
                            }
                            .frame(width: 20, height: 20)
                        }
                        VStack(alignment: .leading, spacing: 2) {
                            Text(option).font(.system(size: 16)).foregroundColor(Palette.text)
                                .multilineTextAlignment(.leading)
                            if let details, index < details.count {
                                Text(details[index]).font(.system(size: 13)).foregroundColor(Palette.textSecondary)
                                    .multilineTextAlignment(.leading)
                            }
                        }
                        Spacer(minLength: 0)
                    }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(index == selected ? .isSelected : [])
            }
        } buttons: {
            MemoButton(L("cancel"), kind: .text) { model.dialog = nil }
        }
        .presentationDetents([.medium, .large])
    }
}

/// A long PDF often holds more than the play: which pages to import.
struct PagesDialog: View {
    @EnvironmentObject var model: AppModel
    let source: PdfSource
    let category: TextCategory
    let narrator: String
    @State private var first = "1"
    @State private var last = ""
    @State private var firstError: String?
    @State private var lastError: String?

    var body: some View {
        let pages = source.pageCount
        DialogFrame(title: L("pdf_pages_title"), message: L("pdf_pages_message", pages, PdfSource.maxRange)) {
            HStack(alignment: .top, spacing: 12) {
                VStack(alignment: .leading, spacing: 6) {
                    Styled(L("pdf_pages_first"), .label, Palette.textSecondary)
                    MemoField(hint: L("pdf_pages_first"), text: $first, limit: 4, keyboard: .numberPad, error: firstError)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Styled(L("pdf_pages_last"), .label, Palette.textSecondary)
                    MemoField(hint: L("pdf_pages_last"), text: $last, limit: 4, keyboard: .numberPad, error: lastError)
                }
            }
            .padding(.top, 4)
        } buttons: {
            MemoButton(L("cancel"), kind: .text) { model.pagesCancelled(source) }
            MemoButton(L("pdf_pages_import"), kind: .text) {
                let start = Int(JText.trim(first)) ?? -1
                let end = Int(JText.trim(last)) ?? -1
                firstError = nil
                lastError = nil
                if start < 1 || start > pages {
                    firstError = L("pdf_pages_invalid", pages)
                } else if end < start || end > pages {
                    lastError = L("pdf_pages_invalid", pages)
                } else if end - start + 1 > PdfSource.maxRange {
                    lastError = L("pdf_pages_too_many", PdfSource.maxRange)
                } else {
                    model.pagesChosen(source, first: start, last: end, category: category, narrator: narrator)
                }
            }
        }
        .onAppear {
            last = String(min(pages, PdfSource.maxRange))
        }
        .interactiveDismissDisabled()
        .presentationDetents([.medium, .large])
    }
}

/// Changes a line, or adds one in at a place.
struct LineDialog: View {
    @EnvironmentObject var model: AppModel
    let index: Int
    let near: ScriptScene.Line?
    let insert: Bool
    @State private var role = ""
    @State private var text = ""
    @State private var notes = ""
    @State private var roleError: String?
    @State private var textError: String?

    var body: some View {
        DialogFrame(title: L(insert ? "line_add" : "line_edit")) {
            Styled(L("line_role"), .label, Palette.textSecondary)
            MemoField(hint: L("line_role"), text: $role, limit: 40, capitalization: .characters, error: roleError)
                .padding(.top, 6)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 6) {
                    ForEach(model.fullScene?.roles ?? [], id: \.self) { name in
                        Button(action: { role = name }) {
                            Chip(label: name, foreground: Palette.text, background: Palette.surfaceHighest)
                        }
                        .buttonStyle(PressStyle(shape: ErasedShape(Capsule())))
                    }
                }
            }
            .padding(.top, 8)
            Styled(L("line_text"), .label, Palette.textSecondary).padding(.top, 16)
            MemoField(hint: L("line_text"), text: $text, multiline: true, minLines: 3, serif: true, limit: 4000,
                      error: textError)
                .padding(.top, 6)
            Styled(L("line_notes"), .label, Palette.textSecondary).padding(.top, 16)
            MemoField(hint: L("line_note"), text: $notes, multiline: true, minLines: 2, limit: 2000)
                .padding(.top, 6)
        } buttons: {
            MemoButton(L("cancel"), kind: .text) { model.dialog = nil }
            MemoButton(L("script_save"), kind: .text) {
                roleError = nil
                textError = nil
                if let problem = model.saveLine(index: index, near: near, insert: insert, role: role, text: text,
                                                notes: notes) {
                    if problem.field == 0 {
                        roleError = problem.message
                    } else if problem.field == 1 {
                        textError = problem.message
                    }
                }
            }
        }
        .onAppear {
            role = insert ? model.suggestedRole(index) : near?.role ?? ""
            text = insert ? "" : near?.text ?? ""
            notes = insert ? "" : near?.notes.joined(separator: "\n") ?? ""
        }
        .presentationDetents([.large])
    }
}

struct NoteDialog: View {
    @EnvironmentObject var model: AppModel
    let index: Int
    let line: ScriptScene.Line
    @State private var note = ""

    var body: some View {
        DialogFrame(title: L("line_note_below")) {
            MemoField(hint: L("line_note"), text: $note, multiline: true, minLines: 2, limit: 2000)
        } buttons: {
            MemoButton(L("cancel"), kind: .text) { model.dialog = nil }
            MemoButton(L("script_save"), kind: .text) { model.addNote(index: index, line: line, note: note) }
        }
        .presentationDetents([.medium])
    }
}

/// Lists the characters with their line counts.
struct RolesDialog: View {
    @EnvironmentObject var model: AppModel
    let scene: ScriptScene
    let edit: RoleEdit

    var body: some View {
        DialogFrame(title: L("roles_edit")) {
            ForEach(scene.roles, id: \.self) { name in
                Button {
                    model.dialog = DialogItem(kind: .renameRole(scene: scene, from: name, edit: edit))
                } label: {
                    HStack {
                        Text(model.roleCountLabel(scene, name)).font(.system(size: 16)).foregroundColor(Palette.text)
                        Spacer()
                        Icon(name: "ic_chevron_right", color: Palette.textTertiary, size: 20)
                    }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } buttons: {
            MemoButton(L("action_close"), kind: .text) { model.dialog = nil }
        }
        .presentationDetents([.medium, .large])
    }
}

/// Fixes a character's name, or merges it into another character.
struct RenameRoleDialog: View {
    @EnvironmentObject var model: AppModel
    let scene: ScriptScene
    let from: String
    let edit: RoleEdit
    @State private var name = ""
    @State private var error: String?

    var body: some View {
        let others = scene.roles.filter { $0 != from }
        DialogFrame(title: from, message: L("role_rename_hint")) {
            MemoField(hint: L("line_role"), text: $name, limit: 40, capitalization: .characters, error: error)
            if !others.isEmpty {
                Styled(L("role_merge_into"), .label, Palette.textSecondary).padding(.top, 16)
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 6) {
                        ForEach(others, id: \.self) { other in
                            Button(action: { name = other }) {
                                Chip(label: other, foreground: Palette.text, background: Palette.surfaceHighest)
                            }
                            .buttonStyle(PressStyle(shape: ErasedShape(Capsule())))
                        }
                    }
                }
                .padding(.top, 8)
            }
        } buttons: {
            MemoButton(L("cancel"), kind: .text) {
                model.dialog = DialogItem(kind: .roles(scene: scene, edit: edit))
            }
            MemoButton(L("script_save"), kind: .text) {
                error = model.renameRole(scene, from: from, typed: name, edit: edit)
            }
        }
        .onAppear { name = from }
        .presentationDetents([.medium, .large])
    }
}

/// An unsure line next to its picture from the page.
struct ReviewDialog: View {
    @EnvironmentObject var model: AppModel
    let index: Int
    @State private var text = ""
    @State private var found: (Int, Int)?
    @State private var original = ""
    @State private var ready = false

    var body: some View {
        let item = model.reviewItems[index]
        DialogFrame(title: L("ocr_review_title", index + 1, model.reviewItems.count)) {
            Styled(L("ocr_review_page", item.page), .label, Palette.textSecondary)
            if let image = UIImage(contentsOfFile: item.image.path) {
                Image(uiImage: image)
                    .resizable()
                    .scaledToFit()
                    .background(Color.white)
                    .padding(.top, 8)
                    .accessibilityLabel(L("ocr_review_picture"))
            }
            MemoField(hint: L("line_text"), text: $text, multiline: true, serif: true, limit: 4000)
                .disabled(found == nil)
                .padding(.top, 12)
            if ready && found == nil {
                Styled(L("ocr_review_missing"), .body, Palette.textSecondary).padding(.top, 8)
            }
        } buttons: {
            MemoButton(L("action_close"), kind: .text) { model.dialog = nil }
            MemoButton(L("ocr_review_skip"), kind: .text) { model.reviewSkip(index, found: found) }
            MemoButton(L(found == nil ? "ocr_review_next" : "ocr_review_save"), kind: .text) {
                model.reviewSave(index, found: found, original: original, fixed: text)
            }
        }
        .onAppear {
            original = model.editorText
            found = model.locateReview(item)
            if let found {
                text = JText.sub(original, found.0, found.1)
            } else {
                text = item.text
            }
            ready = true
        }
        .presentationDetents([.large])
    }
}

/// Every line of the scene; tapping one rehearses from there.
struct OverviewDialog: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let lines = model.scene.lines
        VStack(alignment: .leading, spacing: 0) {
            Text(L("overview_title"))
                .font(.system(size: 20, weight: .medium))
                .foregroundColor(Palette.text)
                .padding(EdgeInsets(top: 24, leading: 24, bottom: 4, trailing: 24))
            Styled(L("overview_hint"), .body, Palette.textSecondary).padding(.horizontal, 24).padding(.bottom, 8)
            ScrollViewReader { proxy in
                ScrollView {
                    LazyVStack(spacing: 4) {
                        ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                            row(index, line).id(index)
                        }
                    }
                    .padding(.horizontal, 12)
                    .padding(.vertical, 8)
                }
                .onAppear {
                    proxy.scrollTo(max(0, model.position - 2), anchor: .top)
                }
            }
            HStack {
                Spacer()
                MemoButton(L("action_close"), kind: .text) { model.dialog = nil }
            }
            .padding(16)
        }
        .background(Palette.surfaceHigh.ignoresSafeArea())
        .presentationDetents([.large])
    }

    private func row(_ index: Int, _ line: ScriptScene.Line) -> some View {
        let mine = model.mode == .practice && line.role == model.role
        let current = index == model.position
        return Button {
            model.dialog = nil
            DispatchQueue.main.async {
                model.moveTo(index, autoPlay: true)
            }
        } label: {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 0) {
                    Styled(String(index + 1), .caption, Palette.textTertiary).frame(width: 34, alignment: .leading)
                    Styled(line.role, .label, mine ? Palette.accent : Palette.violet)
                    Spacer(minLength: 0)
                }
                Text(line.text)
                    .font(.system(size: 14))
                    .foregroundColor(mine ? Palette.onAccentContainer : Palette.textSecondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.leading)
                    .padding(.leading, 34)
            }
            .padding(EdgeInsets(top: 10, leading: 14, bottom: 10, trailing: 14))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(mine ? Palette.accentContainer : .clear))
            .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(current ? Palette.accent : .clear, lineWidth: 2))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// After a page photo: take the next page, or read the text from the photos so far.
struct PhotoMoreDialog: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let count = model.takenPhotos.count
        DialogFrame(title: P("photo_taken", count), message: L("photo_more_message")) {
            EmptyView()
        } buttons: {
            MemoButton(L("cancel"), kind: .text) {
                model.takenPhotos.removeAll()
                model.dialog = nil
            }
            if count < PhotoOcr.maxPhotos {
                MemoButton(L("photo_more"), kind: .text) {
                    model.dialog = nil
                    model.takePhoto()
                }
            }
            MemoButton(L("photo_read"), kind: .text) {
                let photos = model.takenPhotos
                model.dialog = nil
                model.startPhotoImport(photos)
            }
        }
        .interactiveDismissDisabled()
        .presentationDetents([.height(260)])
    }
}

/// A one-field form, such as a name or an invite code.
struct TextInputDialog: View {
    @EnvironmentObject var model: AppModel
    let title: String
    let message: String?
    let value: String
    let hint: String
    let limit: Int
    let words: Bool
    let confirm: String
    let submit: (String) -> String?
    @State private var text = ""
    @State private var error: String?

    var body: some View {
        DialogFrame(title: title, message: message) {
            MemoField(hint: hint, text: $text, limit: limit, capitalization: words ? .words : .characters, error: error)
        } buttons: {
            MemoButton(L("cancel"), kind: .text) { model.dialog = nil }
            MemoButton(confirm, kind: .text) {
                error = submit(text)
                if error == nil {
                    model.dialog = nil
                }
            }
        }
        .onAppear { text = value }
        .presentationDetents([.medium])
    }
}
