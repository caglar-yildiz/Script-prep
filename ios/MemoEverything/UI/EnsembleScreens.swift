import AVFoundation
import MemoCore
import SwiftUI
import UIKit

/// The ensemble account actions shared by Settings and the ensemble screen: linking or signing in
/// with Google, changing the name and signing out.
enum EnsembleAccount {
    /// One line saying whether the identity lives on this device only or in a Google account.
    static func status(_ ensemble: Ensemble) -> String {
        guard ensemble.user != nil else {
            return L("ensemble_account_none")
        }
        if ensemble.linked {
            return L("ensemble_account_linked", ensemble.email ?? "")
        }
        return L("ensemble_account_device")
    }

    /// Signs in with Google, or links Google to this device's identity. When the Google account
    /// already has its own identity, asks before switching to it.
    static func connectGoogle(_ model: AppModel, busy: (() -> Void)? = nil,
                              changed: @escaping (Ensemble.Connection?) -> Void) {
        busy?()
        Ensemble.shared.connectGoogle { result in
            switch result {
            case .success(let connection):
                if connection == .linked {
                    model.notifyKey("ensemble_linked")
                }
                changed(connection)
            case .failure(let failure):
                changed(nil)
                if failure == .collision {
                    confirmSwitch(model, busy: busy, changed: changed)
                } else {
                    report(model, failure)
                }
            }
        }
    }

    static func connectGoogle(_ model: AppModel, done: @escaping () -> Void) {
        connectGoogle(model, busy: nil) { _ in done() }
    }

    private static func confirmSwitch(_ model: AppModel, busy: (() -> Void)?,
                                      changed: @escaping (Ensemble.Connection?) -> Void) {
        model.alert(L("ensemble_switch_title"), L("ensemble_switch_body"), [
            DialogButton(label: L("ensemble_switch_action")) {
                busy?()
                Ensemble.shared.switchToPending { result in
                    switch result {
                    case .success(let connection):
                        changed(connection)
                    case .failure(let failure):
                        changed(nil)
                        report(model, failure)
                    }
                }
            },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    static func askName(_ model: AppModel, done: @escaping () -> Void) {
        model.dialog = DialogItem(kind: .textInput(
            title: L("ensemble_rename"), message: nil, value: Ensemble.shared.name, hint: L("ensemble_name_hint"),
            limit: EnsembleRules.maxNameLength, words: true, confirm: L("ok")) { typed in
                let name = EnsembleRules.cleanName(typed)
                if name.isEmpty {
                    return L("ensemble_name_needed")
                }
                Ensemble.shared.rename(name) { result in
                    if case .failure(let failure) = result {
                        report(model, failure)
                    }
                    done()
                }
                return nil
            })
    }

    /// Only a Google-linked identity can sign out: a device-only one could not come back.
    static func confirmSignOut(_ model: AppModel, done: @escaping () -> Void) {
        model.alert(L("ensemble_sign_out"), L("ensemble_sign_out_body"), [
            DialogButton(label: L("ensemble_sign_out"), role: .destructive) {
                Ensemble.shared.signOut()
                done()
            },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    static func report(_ model: AppModel, _ failure: Ensemble.Failure) {
        if let key = failure.messageKey {
            model.notifyKey(key)
        }
    }
}

/// How the ensemble screen was left.
enum EnsembleOutcome {
    case closed
    case addText
    case rehearse(id: String, role: String?)
}

/// The ensemble screens: sign-in, the list of groups, choosing a text to share and a group's page.
final class EnsembleState: ObservableObject {
    enum Page {
        case list, pick, detail
    }

    @Published var page: Page = .list
    @Published var busy = false
    @Published var entries: [Ensemble.Entry]?
    @Published var production: Ensemble.Production?
    @Published var members: [Ensemble.Member]?
    @Published var signedIn = Ensemble.shared.user != nil
    @Published var recordingFor: (Ensemble.Production, [String])?
    var openId: String?
    var pendingCode: String?
    let recordings = EnsembleRecordings()
}

struct EnsembleView: View {
    @EnvironmentObject var model: AppModel
    @StateObject private var state = EnsembleState()
    @State private var typedName = ""
    @State private var nameError: String?
    let shareId: String?
    let invite: String?
    let finish: (EnsembleOutcome) -> Void
    private let ensemble = Ensemble.shared

    var body: some View {
        ZStack {
            Palette.background.ignoresSafeArea()
                .sheet(item: $multiRoles) { choice in
                    MultiChoiceSheet(title: L("ensemble_choose_roles"), options: choice.roles, chosen: choice.chosen) { picked in
                        multiRoles = nil
                        if let picked {
                            saveRoles(picked)
                        }
                    }
                }
            if !state.signedIn {
                signIn
            } else {
                switch state.page {
                case .list: list
                case .pick: pick
                case .detail: detail
                }
            }
        }
        .overlay(alignment: .bottom) {
            if let toast = model.toast {
                Text(toast)
                    .font(.system(size: 14))
                    .foregroundColor(Palette.text)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Capsule().fill(Palette.surfaceHighest))
                    .padding(.bottom, 40)
                    .allowsHitTesting(false)
            }
        }
        .alert(alertTitle, isPresented: alertShown, presenting: alertButtons) { buttons in
            ForEach(Array(buttons.enumerated()), id: \.offset) { _, button in
                Button(button.label, role: button.role == .cancel ? .cancel : button.role == .destructive ? .destructive : nil) {
                    model.dialog = nil
                    DispatchQueue.main.async(execute: button.action)
                }
            }
        } message: { _ in
            Text(alertMessage)
        }
        .sheet(item: sheetItem) { item in
            DialogSheet(item: item).environmentObject(model)
        }
        .fullScreenCover(isPresented: Binding(get: { state.recordingFor != nil }, set: { if !$0 { state.recordingFor = nil } })) {
            if let pair = state.recordingFor {
                RecordLinesView(production: pair.0, roles: pair.1, name: ensemble.name) {
                    state.recordingFor = nil
                }
                .environmentObject(model)
            }
        }
        .onOpenURL { url in
            if let code = EnsembleRules.inviteCode(url) {
                if ensemble.user == nil {
                    state.pendingCode = code
                    state.objectWillChange.send()
                } else {
                    join(code)
                }
            }
        }
        .onAppear(perform: start)
        .preferredColorScheme(.dark)
    }

    private func start() {
        state.pendingCode = invite
        typedName = ensemble.name
        if let shareId, let script = model.library.list().first(where: { $0.id == shareId }), ensemble.user != nil {
            self.share(script)
        } else if let code = state.pendingCode, ensemble.user != nil {
            join(code)
        } else {
            refreshList()
        }
    }

    // MARK: Dialog plumbing

    private var alertButtons: [DialogButton]? {
        if case .message(_, _, let buttons) = model.dialog?.kind {
            return buttons
        }
        return nil
    }

    private var alertShown: Binding<Bool> {
        Binding(get: { alertButtons != nil }, set: { if !$0, alertButtons != nil { model.dialog = nil } })
    }

    private var alertTitle: String {
        if case .message(let title, _, _) = model.dialog?.kind {
            return title ?? ""
        }
        return ""
    }

    private var alertMessage: String {
        if case .message(_, let message, _) = model.dialog?.kind {
            return message
        }
        return ""
    }

    private var sheetItem: Binding<DialogItem?> {
        Binding(get: {
            if case .message = model.dialog?.kind {
                return nil
            }
            return state.recordingFor == nil ? model.dialog : nil
        }, set: { value in
            if value == nil, let current = model.dialog {
                model.dialogDismissed(current)
            }
        })
    }

    // MARK: Navigation

    private func showList() {
        state.page = .list
        state.production = nil
        state.members = nil
        state.openId = nil
        refreshList()
    }

    private func refreshList() {
        state.signedIn = ensemble.user != nil
        guard state.signedIn else {
            state.entries = nil
            return
        }
        state.busy = true
        ensemble.list { result in
            state.busy = false
            switch result {
            case .success(let entries):
                state.entries = entries
            case .failure(let failure):
                state.entries = []
                EnsembleAccount.report(model, failure)
            }
        }
    }

    private func open(_ productionId: String) {
        state.page = .detail
        state.openId = productionId
        state.busy = true
        ensemble.load(productionId) { result in
            switch result {
            case .success(let production):
                state.production = production
                saveLocalCopy(production)
                loadMembers()
            case .failure(let failure):
                state.busy = false
                EnsembleAccount.report(model, failure)
                showList()
            }
        }
    }

    private func loadMembers() {
        guard let production = state.production else {
            return
        }
        ensemble.members(production.id) { result in
            state.busy = false
            switch result {
            case .success(let members):
                state.members = members
                syncRecordings(nil)
            case .failure(let failure):
                state.members = []
                EnsembleAccount.report(model, failure)
            }
        }
    }

    private func syncRecordings(_ then: (() -> Void)?) {
        guard let id = state.production?.id else {
            return
        }
        state.recordings.sync(id) { _ in
            guard state.production?.id == id else {
                return
            }
            if let then {
                then()
            } else {
                state.objectWillChange.send()
            }
        }
    }

    // MARK: Sign-in

    private var signIn: some View {
        ScreenFrame(bar: { TopBar(icon: "ic_back", title: nil, navigate: { finish(.closed) }) }) {
            PageTitle(overline: L("ensemble_title"),
                      title: L(state.pendingCode == nil ? "ensemble_sign_in_title" : "ensemble_invited_title"),
                      subtitle: L("ensemble_sign_in_body"))
            if state.busy {
                spinner
            } else {
                Styled(L("ensemble_name_label"), .label, Palette.textSecondary).padding(.top, 28)
                MemoField(hint: L("ensemble_name_hint"), text: $typedName, limit: EnsembleRules.maxNameLength,
                          capitalization: .words, error: nameError)
                    .padding(.top, 8)
                MemoButton(L(state.pendingCode == nil ? "ensemble_start" : "ensemble_join_action"), icon: "ic_next",
                           kind: .primary, fill: true, action: startIdentity)
                    .padding(.top, 20)
                Styled(L("ensemble_device_note"), .caption, Palette.textTertiary).padding(.top, 14)
                SectionLabel(key: "ensemble_returning")
                MemoButton(L("ensemble_sign_in"), icon: "ic_person", kind: .tonal, fill: true, action: connectGoogle)
                    .padding(.top, 10)
                Styled(L("ensemble_privacy_note"), .caption, Palette.textTertiary).padding(.top, 16)
            }
        }
    }

    private var spinner: some View {
        ProgressView().tint(Palette.accent).scaleEffect(1.4).frame(maxWidth: .infinity).padding(.top, 40)
    }

    private func startIdentity() {
        let name = EnsembleRules.cleanName(typedName)
        if name.isEmpty {
            nameError = L("ensemble_name_needed")
            return
        }
        nameError = nil
        state.busy = true
        ensemble.start(name: name) { result in
            state.busy = false
            switch result {
            case .success:
                afterSignIn()
            case .failure(let failure):
                EnsembleAccount.report(model, failure)
            }
        }
    }

    private func afterSignIn() {
        state.signedIn = true
        if let code = state.pendingCode {
            join(code)
        } else {
            showList()
        }
    }

    private func connectGoogle() {
        let linking = ensemble.user != nil
        EnsembleAccount.connectGoogle(model, busy: { state.busy = true }) { connection in
            state.busy = false
            state.signedIn = ensemble.user != nil
            if connection == .signedIn && !linking {
                afterSignIn()
            } else if connection == .signedIn {
                showList()
            } else {
                state.objectWillChange.send()
            }
        }
    }

    private func accountChanged() {
        state.signedIn = ensemble.user != nil
        if !state.signedIn {
            state.entries = nil
            state.page = .list
        } else if state.production != nil {
            loadMembers()
        } else {
            state.objectWillChange.send()
        }
    }

    private func showAccount() {
        var buttons = [DialogButton(label: L("ensemble_rename")) {
            EnsembleAccount.askName(model) { accountChanged() }
        }]
        if ensemble.linked {
            buttons.append(DialogButton(label: L("ensemble_sign_out"), role: .destructive) {
                EnsembleAccount.confirmSignOut(model) { accountChanged() }
            })
        } else {
            buttons.append(DialogButton(label: L("ensemble_link")) { connectGoogle() })
        }
        buttons.append(DialogButton(label: L("cancel"), role: .cancel))
        model.alert(ensemble.name.isEmpty ? L("ensemble_account") : ensemble.name, EnsembleAccount.status(ensemble), buttons)
    }

    // MARK: List

    private var list: some View {
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: nil, navigate: { finish(.closed) }) {
                BarAction(icon: "ic_more", label: "ensemble_account", action: showAccount)
            }
        }) {
            PageTitle(overline: L("app_name"), title: L("ensemble_title"))
            VStack(spacing: 10) {
                MemoButton(L("ensemble_share"), icon: "ic_add", kind: .primary, fill: true) { state.page = .pick }
                MemoButton(L("ensemble_join"), icon: "ic_people", kind: .tonal, fill: true, action: askCode)
            }
            .padding(.top, 24)
            SectionLabel(key: "ensemble_groups")
            if state.busy || state.entries == nil {
                spinner
            } else if state.entries!.isEmpty {
                Styled(L("ensemble_empty"), .bodyLarge, Palette.textSecondary).padding(.top, 10)
            } else {
                ForEach(Array(state.entries!.enumerated()), id: \.element.id) { index, entry in
                    Card(action: { open(entry.id) }) {
                        HStack(spacing: 0) {
                            IconTile(icon: "ic_people", tint: Palette.accent, background: Palette.accentContainer, size: 44)
                            Text(entry.title.isEmpty ? L("imported_title") : entry.title)
                                .font(.system(size: 16, weight: .medium))
                                .foregroundColor(Palette.text)
                                .lineLimit(2)
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .padding(.leading, 14)
                            Icon(name: "ic_chevron_right", color: Palette.textTertiary, size: 22)
                        }
                    }
                    .padding(.top, index == 0 ? 10 : 8)
                }
            }
        }
    }

    private func askCode() {
        model.dialog = DialogItem(kind: .textInput(
            title: L("ensemble_join"), message: nil, value: "", hint: L("ensemble_join_hint"), limit: 12,
            words: false, confirm: L("ensemble_join_action")) { code in
                DispatchQueue.main.async { join(code) }
                return nil
            })
    }

    private func join(_ code: String) {
        state.pendingCode = nil
        state.busy = true
        ensemble.join(code) { result in
            state.busy = false
            switch result {
            case .success(let productionId):
                open(productionId)
            case .failure(let failure):
                EnsembleAccount.report(model, failure)
                refreshList()
            }
        }
    }

    // MARK: Share a text

    private var pick: some View {
        ScreenFrame(bar: { TopBar(icon: "ic_back", title: L("ensemble_share"), navigate: showList) }) {
            PageTitle(title: L("ensemble_pick_text"), subtitle: L("ensemble_pick_text_detail"))
            if state.busy {
                spinner
            } else {
                let shareable = model.library.list().filter {
                    !$0.id.hasPrefix(EnsembleRules.localPrefix) && $0.category.hasRoles && !ScriptScene.parse($0.text).roles.isEmpty
                }
                if shareable.isEmpty {
                    noTexts
                } else {
                    ForEach(Array(shareable.enumerated()), id: \.element.id) { index, script in
                        let parsed = ScriptScene.parse(script.text)
                        Card(action: { share(script) }) {
                            Styled(script.title.isEmpty ? L("imported_title") : script.title, .subtitle)
                            Styled(P("role_count", parsed.roles.count) + " · " + P("line_count", parsed.lines.count),
                                   .body, Palette.textSecondary)
                                .padding(.top, 3)
                        }
                        .padding(.top, index == 0 ? 24 : 8)
                    }
                    MemoButton(L("ensemble_add_text"), icon: "ic_add", kind: .tonal, fill: true) { finish(.addText) }
                        .padding(.top, 16)
                }
            }
        }
    }

    private var noTexts: some View {
        VStack(alignment: .leading, spacing: 0) {
            Card(padding: EdgeInsets(top: 20, leading: 20, bottom: 20, trailing: 20)) {
                IconTile(icon: "ic_texts", tint: Palette.accent, background: Palette.accentContainer, size: 48)
                Styled(L("ensemble_no_texts_title"), .title).padding(.top, 16)
                Styled(L("ensemble_no_texts"), .body, Palette.textSecondary).padding(.top, 6)
                MemoButton(L("ensemble_add_text"), icon: "ic_add", kind: .primary, fill: true) { finish(.addText) }
                    .padding(.top, 20)
                MemoButton(L("ensemble_share_sample"), icon: "ic_play", kind: .tonal, fill: true, action: shareSample)
                    .padding(.top, 10)
            }
            .padding(.top, 24)
            Styled(L("ensemble_invited_question"), .body, Palette.textSecondary).padding(.top, 24)
            MemoButton(L("ensemble_join"), icon: "ic_people", kind: .text, action: askCode).padding(.top, 4)
        }
    }

    private func shareSample() {
        share(Script(id: "sample", title: L("sample_title"), text: L("sample_script"), category: .roleRehearsal,
                     language: Strings.interfaceLanguage))
    }

    private func share(_ script: Script) {
        let suggested = script.title.isEmpty ? L("imported_title") : script.title
        askGroupName(L("ensemble_group_name_title"), suggested) { name in
            create(script, name)
        }
    }

    private func askGroupName(_ title: String, _ suggested: String, _ chosen: @escaping (String) -> Void) {
        model.dialog = DialogItem(kind: .textInput(
            title: title, message: nil, value: suggested, hint: "", limit: 80, words: false, confirm: L("ok")) { typed in
                let name = JText.collapse(JText.trim(typed))
                if name.isEmpty {
                    return L("ensemble_group_name_needed")
                }
                DispatchQueue.main.async { chosen(name) }
                return nil
            })
    }

    private func create(_ script: Script, _ title: String) {
        state.busy = true
        ensemble.create(title: title, text: script.text, category: script.category, language: script.language) { result in
            state.busy = false
            switch result {
            case .success(let productionId):
                model.library.rekey(script.id, to: EnsembleRules.localPrefix + productionId, title: title)
                open(productionId)
            case .failure(let failure):
                EnsembleAccount.report(model, failure)
            }
        }
    }

    // MARK: Group

    private var isOwner: Bool {
        guard let production = state.production, let uid = ensemble.uid else {
            return false
        }
        return uid == production.ownerUid
    }

    private var me: Ensemble.Member? {
        guard let uid = ensemble.uid else {
            return nil
        }
        return state.members?.first { $0.uid == uid }
    }

    private func memberName(_ member: Ensemble.Member) -> String {
        let name = member.name.isEmpty ? L("ensemble_unnamed") : member.name
        return me?.uid == member.uid ? L("ensemble_you", name) : name
    }

    private var detail: some View {
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: nil, navigate: showList) {
                if state.production != nil && state.members != nil && !state.busy {
                    BarAction(icon: "ic_more", label: "ensemble_group_actions", action: showGroupActions)
                }
            }
        }) {
            if let production = state.production, let members = state.members, !state.busy {
                groupContent(production, members)
            } else {
                spinner
            }
        }
    }

    @ViewBuilder private func groupContent(_ production: Ensemble.Production, _ members: [Ensemble.Member]) -> some View {
        PageTitle(overline: L("ensemble_title"), title: production.title)
        Card(color: Palette.accentContainer) {
            Styled(L("ensemble_invite_code"), .overline, Palette.onAccentContainer)
            Text(production.inviteCode)
                .font(.system(size: 32, weight: .medium))
                .tracking(8)
                .foregroundColor(Palette.onAccentContainer)
                .textSelection(.enabled)
                .padding(.top, 6)
            Styled(L("ensemble_invite_detail"), .body, Palette.onAccentContainer).opacity(0.8).padding(.top, 4)
            HStack(spacing: 8) {
                ShareLink(item: L("ensemble_share_message", production.title, production.inviteCode) + "\n"
                          + EnsembleRules.invitePage + production.inviteCode) {
                    Text(L("ensemble_send_code"))
                        .font(.system(size: 15, weight: .medium))
                        .foregroundColor(Palette.onAccent)
                        .padding(.horizontal, 20)
                        .frame(minHeight: 52)
                        .background(Capsule().fill(Palette.accent))
                }
                MemoButton(L("ensemble_copy_code"), kind: .text) {
                    UIPasteboard.general.string = production.inviteCode
                    model.notifyKey("ensemble_code_copied")
                }
            }
            .padding(.top, 14)
        }
        .padding(.top, 20)

        let parsed = production.category.parse(production.text, narrator: L("solo_role"))
        let mine = me?.roles ?? []
        SectionLabel(key: "ensemble_my_roles")
        RowGroup {
            ListRow(icon: "ic_person", title: L("ensemble_choose_roles"),
                    value: mine.isEmpty ? L("ensemble_no_roles") : mine.joined(separator: ", "),
                    action: { chooseRoles(parsed.roles, mine) })
            if !mine.isEmpty {
                let own = EnsembleRules.ownLines(parsed, roles: mine)
                let done = own.filter {
                    state.recordings.mine(production.id, EnsembleRules.lineKey(role: $0.line.role, text: $0.line.text)) != nil
                }.count
                RowDivider()
                ListRow(icon: "ic_mic", title: L("record_title"), value: L("record_progress", done, own.count),
                        action: { state.recordingFor = (production, mine) })
            }
        }
        .padding(.top, 10)
        MemoButton(L("ensemble_rehearse"), icon: "ic_play", kind: .primary, fill: true) {
            rehearse(mine.first)
        }
        .padding(.top, 14)
        Styled(L("ensemble_recordings_hint"), .caption, Palette.textTertiary).padding(.top, 10)

        SectionLabel(key: "ensemble_roles")
        Card(padding: EdgeInsets(top: 6, leading: 16, bottom: 6, trailing: 16)) {
            ForEach(Array(parsed.roles.enumerated()), id: \.offset) { index, role in
                let actors = members.filter { $0.roles.contains(role) }.map(memberName)
                if index > 0 {
                    RowDivider(inset: 0)
                }
                HStack(alignment: .top) {
                    Styled(role, .subtitle).frame(maxWidth: .infinity, alignment: .leading)
                    Styled(actors.isEmpty ? L("ensemble_role_open") : actors.joined(separator: ", "), .body,
                           actors.isEmpty ? Palette.textTertiary : Palette.textSecondary)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                }
                .padding(.vertical, 12)
            }
        }
        .padding(.top, 10)

        Styled(P("ensemble_member_count", members.count), .overline, Palette.textTertiary).padding(.top, 28)
        RowGroup {
            ForEach(members, id: \.uid) { member in
                let roles = member.roles.isEmpty ? L("ensemble_no_roles") : member.roles.joined(separator: ", ")
                ListRow(icon: "ic_person", title: memberName(member),
                        value: member.owner ? L("ensemble_owner") + " · " + roles : roles, action: nil)
            }
        }
        .padding(.top, 10)
    }

    private func chooseRoles(_ roles: [String], _ mine: [String]) {
        multiRoles = RoleChoice(roles: roles, chosen: Set(mine))
    }

    @State private var multiRoles: RoleChoice?

    private func saveRoles(_ roles: [String]) {
        guard let production = state.production else {
            return
        }
        state.busy = true
        ensemble.setRoles(production.id, roles: roles) { result in
            if case .failure(let failure) = result {
                state.busy = false
                EnsembleAccount.report(model, failure)
            } else {
                loadMembers()
            }
        }
    }

    private func showGroupActions() {
        guard let production = state.production else {
            return
        }
        let owner = isOwner
        var buttons: [DialogButton] = []
        if owner {
            buttons.append(DialogButton(label: L("ensemble_rename_group")) {
                askGroupName(L("ensemble_rename_group"), production.title) { renameGroup($0) }
            })
            buttons.append(DialogButton(label: L("ensemble_delete"), role: .destructive) { confirmLeaveOrDelete(owner) })
        } else {
            buttons.append(DialogButton(label: L("ensemble_leave"), role: .destructive) { confirmLeaveOrDelete(owner) })
        }
        buttons.append(DialogButton(label: L("cancel"), role: .cancel))
        model.alert(production.title, "", buttons)
    }

    private func confirmLeaveOrDelete(_ owner: Bool) {
        guard let production = state.production else {
            return
        }
        model.alert(nil, L(owner ? "ensemble_delete_confirm" : "ensemble_leave_confirm", production.title), [
            DialogButton(label: L(owner ? "ensemble_delete" : "ensemble_leave"), role: .destructive) {
                leaveOrDelete(owner)
            },
            DialogButton(label: L("cancel"), role: .cancel),
        ])
    }

    private func renameGroup(_ name: String) {
        guard let target = state.production else {
            return
        }
        state.busy = true
        ensemble.rename(production: target.id, title: name) { result in
            state.busy = false
            if case .failure(let failure) = result {
                EnsembleAccount.report(model, failure)
            } else {
                open(target.id)
            }
        }
    }

    private func leaveOrDelete(_ owner: Bool) {
        guard let target = state.production else {
            return
        }
        state.busy = true
        let callback: Ensemble.Callback<Void> = { result in
            state.busy = false
            switch result {
            case .success:
                model.library.rekey(EnsembleRules.localPrefix + target.id, to: UUID().uuidString.lowercased(),
                                    title: target.title)
                state.recordings.forget(target.id)
                showList()
            case .failure(let failure):
                EnsembleAccount.report(model, failure)
            }
        }
        if owner {
            ensemble.delete(target, callback)
        } else {
            ensemble.leave(target.id, callback)
        }
    }

    /// Keeps the shared text in the library, so rehearsals work offline.
    private func saveLocalCopy(_ value: Ensemble.Production) {
        if let uid = ensemble.uid, uid == value.ownerUid {
            for script in model.library.list() where !script.id.hasPrefix(EnsembleRules.localPrefix) && script.text == value.text {
                if model.library.list().contains(where: { $0.id == EnsembleRules.localPrefix + value.id }) {
                    model.library.delete(script.id)
                } else {
                    model.library.rekey(script.id, to: EnsembleRules.localPrefix + value.id, title: value.title)
                }
            }
        }
        model.library.put(id: EnsembleRules.localPrefix + value.id, title: value.title, text: value.text,
                          category: value.category, language: value.language)
    }

    private func rehearse(_ role: String?) {
        guard let production = state.production else {
            return
        }
        state.busy = true
        syncRecordings {
            state.busy = false
            saveLocalCopy(production)
            finish(.rehearse(id: EnsembleRules.localPrefix + production.id, role: role))
        }
    }
}

/// The roles offered in the multi-choice sheet.
struct RoleChoice: Identifiable {
    let id = UUID()
    let roles: [String]
    let chosen: Set<String>
}

/// Choosing several roles at once.
struct MultiChoiceSheet: View {
    let title: String
    let options: [String]
    @State var chosen: Set<String>
    let done: ([String]?) -> Void

    var body: some View {
        DialogFrame(title: title) {
            ForEach(options, id: \.self) { option in
                Button {
                    if chosen.contains(option) {
                        chosen.remove(option)
                    } else {
                        chosen.insert(option)
                    }
                } label: {
                    HStack(spacing: 16) {
                        ZStack {
                            RoundedRectangle(cornerRadius: 4).strokeBorder(chosen.contains(option) ? Palette.accent
                                : Palette.textSecondary, lineWidth: 2)
                            if chosen.contains(option) {
                                RoundedRectangle(cornerRadius: 4).fill(Palette.accent)
                                Image(systemName: "checkmark").font(.system(size: 12, weight: .bold))
                                    .foregroundColor(Palette.onAccent)
                            }
                        }
                        .frame(width: 20, height: 20)
                        Text(option).font(.system(size: 16)).foregroundColor(Palette.text)
                        Spacer()
                    }
                    .padding(.vertical, 12)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        } buttons: {
            MemoButton(L("cancel"), kind: .text) { done(nil) }
            MemoButton(L("ok"), kind: .text) { done(options.filter(chosen.contains)) }
        }
        .preferredColorScheme(.dark)
        .presentationDetents([.medium, .large])
    }
}
