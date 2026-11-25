import MemoCore
import SwiftUI

/// The plain text editor for a new or saved text.
struct EditorScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let colors = model.categoryColors(model.category)
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: L(model.scriptId == nil ? "editor_new_title" : "editor_title"),
                   navigate: model.leaveEditor) {
                if model.scriptId != nil {
                    BarAction(icon: "ic_delete", label: "action_delete", action: model.deleteCurrent)
                }
            }
        }, content: {
            HStack(spacing: 8) {
                Chip(label: L(model.categoryTitle(model.category)), icon: model.categoryIcon(model.category),
                     foreground: colors.0, background: colors.1)
                Button(action: model.pickEditorLanguage) {
                    Chip(label: L(model.language.labelKey), icon: "ic_translate", foreground: Palette.text,
                         background: Palette.surfaceHighest)
                }
                .buttonStyle(PressStyle(shape: ErasedShape(Capsule())))
                .accessibilityLabel(L("text_language") + ": " + L(model.language.labelKey))
            }
            .padding(.top, 8)

            let unsureLeft = model.reviewItems.count - model.reviewIndex
            if unsureLeft > 0 {
                Card {
                    Styled(P("ocr_review_message", unsureLeft), .body)
                    MemoButton(L("ocr_review_start"), icon: "ic_eye", kind: .text) {
                        model.reviewLine(model.reviewIndex)
                    }
                    .padding(.top, 4)
                }
                .padding(.top, 14)
            }

            if let rest = model.remainingPages {
                Card(color: Palette.accentContainer) {
                    Styled(L("pdf_rest_message", rest.firstPage, rest.lastPage), .body, Palette.onAccentContainer)
                    MemoButton(L("pdf_rest_import"), icon: "ic_pdf", kind: .text, action: model.saveAndImportRest)
                        .padding(.top, 4)
                }
                .padding(.top, 14)
            }

            Styled(L("script_title_label"), .label, Palette.textSecondary).padding(.top, 20)
            MemoField(hint: L("script_title_hint"), text: $model.editorTitle, limit: AppModel.maxTitleLength,
                      error: model.titleError)
                .padding(.top, 8)

            HStack {
                Styled(L("script_label"), .label, Palette.textSecondary)
                Spacer()
                Styled(L("character_counter", formatNumber(Double(JText.length(model.editorText))),
                         formatNumber(Double(AppModel.maxScriptLength))), .caption, Palette.textTertiary)
            }
            .padding(.top, 22)
            ScriptTextEditor(text: $model.editorText, hint: L(model.category.hasRoles ? "script_hint" : "solo_hint"),
                             error: model.textError)
                .padding(.top, 8)

            HStack {
                MemoButton(L("clear_text"), icon: "ic_close", kind: .text, action: model.clearEditorText)
                Spacer()
                MemoButton(L(model.category.hasRoles ? "parse_preview_short" : "solo_preview_short"), icon: "ic_list",
                           kind: .text, action: model.previewEditor)
            }
            .padding(.top, 4)

            Card {
                HStack(alignment: .top, spacing: 12) {
                    Icon(name: "ic_info", color: Palette.textSecondary, size: 20)
                    Styled(L(model.category.hasRoles ? "editor_description" : "solo_description"), .body,
                           Palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            .padding(.top, 12)
        }, footer: {
            FooterBar {
                HStack(spacing: 10) {
                    MemoButton(L("script_save"), kind: .outline) { model.saveEditor(rehearse: false) }
                    MemoButton(L(model.category.hasRoles ? "script_save_and_rehearse" : "solo_save_and_rehearse"),
                               icon: "ic_play", kind: .primary, fill: true) { model.saveEditor(rehearse: true) }
                }
            }
        })
    }
}

/// A tall serif text area for the script, with the cursor position kept for line review.
struct ScriptTextEditor: View {
    @EnvironmentObject var model: AppModel
    @Binding var text: String
    let hint: String
    var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack(alignment: .topLeading) {
                if text.isEmpty {
                    Text(hint)
                        .font(.system(size: 16, design: .serif))
                        .foregroundColor(Palette.textTertiary)
                        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
                        .allowsHitTesting(false)
                }
                TextEditor(text: limited)
                    .font(.system(size: 16, design: .serif))
                    .foregroundColor(Palette.text)
                    .lineSpacing(4)
                    .scrollContentBackground(.hidden)
                    .focused($focused)
                    .padding(EdgeInsets(top: 6, leading: 11, bottom: 6, trailing: 11))
                    .frame(minHeight: 260)
            }
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.surfaceHigh))
            .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(focused ? Palette.accent : (error == nil ? Palette.outlineSoft : Palette.danger),
                              lineWidth: focused ? 2 : 1))
            if let error {
                Styled(error, .caption, Palette.danger)
            }
        }
    }

    private var limited: Binding<String> {
        Binding(get: { text }, set: { value in
            let ns = value as NSString
            text = ns.length > AppModel.maxScriptLength ? ns.substring(to: AppModel.maxScriptLength) : value
        })
    }
}

/// Role texts edited line by line.
struct LinesScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let lines = model.fullScene?.lines ?? []
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: L("lines_title"), navigate: { model.show(model.linesReturn) }) {
                BarAction(icon: "ic_people", label: "roles_edit", action: model.editSavedRoles)
                BarAction(icon: "ic_texts", label: "edit_as_text", action: model.editCurrent)
            }
        }, content: {
            Styled(L("lines_hint"), .body, Palette.textSecondary).padding(.top, 4)
            LazyVStack(alignment: .leading, spacing: 0) {
                ForEach(Array(lines.enumerated()), id: \.offset) { index, line in
                    if !line.section.isEmpty && (index == 0 || lines[index - 1].section != line.section) {
                        Styled(line.section, .overline, Palette.textTertiary).padding(.top, 24)
                    }
                    LineCard(index: index, line: line).padding(.top, 8)
                }
            }
        }, footer: {
            FooterBar {
                MemoButton(L("line_add"), icon: "ic_add", kind: .primary, fill: true) {
                    model.dialog = DialogItem(kind: .lineEdit(index: lines.count, near: nil, insert: true))
                }
            }
        })
    }
}

struct LineCard: View {
    @EnvironmentObject var model: AppModel
    let index: Int
    let line: ScriptScene.Line

    var body: some View {
        let mine = line.role == model.role
        Card(color: mine ? Palette.accentContainer : Palette.surface,
             padding: EdgeInsets(top: 10, leading: 16, bottom: 12, trailing: 4),
             action: { edit(insert: false, at: index) }) {
            HStack(spacing: 0) {
                Styled(line.role, .label, mine ? Palette.accent : Palette.violet)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Menu {
                    menuItems
                } label: {
                    Icon(name: "ic_more", color: Palette.textSecondary, size: 24)
                        .frame(width: 40, height: 40)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(L("text_actions", line.role))
            }
            Serif(line.text, 16, mine ? Palette.onAccentContainer : Palette.text)
                .padding(.trailing, 12)
            ForEach(Array(line.notes.enumerated()), id: \.offset) { _, note in
                Serif(note, 14, Palette.textTertiary, italic: true)
                    .padding(.top, 4)
                    .padding(.trailing, 12)
            }
        }
        .contextMenu { menuItems }
    }

    @ViewBuilder private var menuItems: some View {
        Button(L("line_edit")) { edit(insert: false, at: index) }
        Button(L("line_add_below")) { edit(insert: true, at: index + 1) }
        Button(L("line_note_below")) { model.dialog = DialogItem(kind: .note(index: index, line: line)) }
        Button(L("line_add_above")) { edit(insert: true, at: index) }
        Button(L("line_delete"), role: .destructive) { model.deleteLine(index) }
    }

    private func edit(insert: Bool, at target: Int) {
        model.dialog = DialogItem(kind: .lineEdit(index: target, near: line, insert: insert))
    }
}
