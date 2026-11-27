import MemoCore
import SwiftUI

/// The library of saved texts, or the welcome screen when there are none yet.
struct HomeScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let scripts = model.scripts
        if scripts.isEmpty {
            empty
        } else {
            ZStack(alignment: .bottomTrailing) {
                ScreenFrame(bar: { bar }) {
                    VStack(alignment: .leading, spacing: 0) {
                        Styled(L("app_name"), .overline, Palette.accent)
                        Styled(L("scripts_tab"), .display).padding(.top, 6)
                        Styled(P("library_count", scripts.count), .body, Palette.textSecondary).padding(.top, 4)
                    }
                    ForEach(Array(scripts.enumerated()), id: \.element.id) { index, entry in
                        ScriptCard(entry: entry).padding(.top, index == 0 ? 20 : 10)
                    }
                    if Ensemble.available {
                        EnsembleCard().padding(.top, 10)
                    }
                    // The library is not empty, so the sample scene is only a small link now.
                    MemoButton(L("sample_button"), icon: "ic_play", kind: .text) { model.openSample() }
                        .padding(.top, 6)
                    // Leave room for the floating create button.
                    Spacer().frame(height: 72)
                }
                MemoButton(L("create_script"), icon: "ic_add", kind: .primary, height: 56) {
                    model.show(.categories)
                }
                .shadow(color: .black.opacity(0.35), radius: 6, y: 3)
                .padding(.trailing, 20)
                .padding(.bottom, 16)
            }
        }
    }

    private var bar: some View {
        TopBar(icon: nil, title: nil, navigate: nil) {
            BarAction(icon: "ic_settings", label: "settings_tab") { model.show(.settings) }
        }
    }

    private var empty: some View {
        ScreenFrame(bar: { bar }, centered: true) {
            VStack(spacing: 0) {
                Spacer(minLength: 24)
                ZStack {
                    RoundedRectangle(cornerRadius: 26, style: .continuous).fill(Palette.surface)
                    Image("memo_mark").resizable().scaledToFit()
                }
                .frame(width: 104, height: 104)
                .accessibilityHidden(true)
                Styled(L("app_name"), .display).multilineTextAlignment(.center).padding(.top, 24)
                Serif(L("memo_tagline"), 19, Palette.textSecondary, italic: true)
                    .multilineTextAlignment(.center).padding(.top, 8)
                Card(padding: EdgeInsets(top: 8, leading: 18, bottom: 8, trailing: 18)) {
                    onboarding("ic_texts", "onboarding_add")
                    onboarding("ic_person", "onboarding_role")
                    onboarding("ic_headphones", "onboarding_rehearse")
                }
                .padding(.top, 32)
                MemoButton(L("create_first_text"), icon: "ic_add", kind: .primary, fill: true) {
                    model.show(.categories)
                }
                .padding(.top, 28)
                MemoButton(L("sample_button"), icon: "ic_play", kind: .text, fill: true) { model.openSample() }
                    .padding(.top, 4)
                if Ensemble.available {
                    MemoButton(L("ensemble_title"), icon: "ic_people", kind: .text, fill: true) { model.openEnsemble() }
                }
                Spacer(minLength: 24)
            }
            .frame(maxWidth: .infinity)
        }
    }

    private func onboarding(_ icon: String, _ key: String) -> some View {
        HStack(spacing: 14) {
            IconTile(icon: icon, tint: Palette.accent, background: Palette.accentContainer, size: 36)
            Styled(L(key), .bodyLarge).frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(.vertical, 10)
    }
}

struct ScriptCard: View {
    @EnvironmentObject var model: AppModel
    let entry: Script

    var body: some View {
        let shared = entry.id.hasPrefix(EnsembleRules.localPrefix)
        let colors = shared ? (Palette.violet, Palette.violetContainer) : model.categoryColors(entry.category)
        Card(padding: EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 4), action: { model.openSavedScript(entry) }) {
            HStack(spacing: 0) {
                IconTile(icon: shared ? "ic_people" : model.categoryIcon(entry.category), tint: colors.0,
                         background: colors.1, size: 48)
                VStack(alignment: .leading, spacing: 3) {
                    Text(entry.title.isEmpty ? L("imported_title") : entry.title)
                        .font(.system(size: 17, weight: .medium))
                        .foregroundColor(Palette.text)
                        .lineLimit(2)
                    Text(model.scriptSummary(entry))
                        .font(.system(size: 14))
                        .foregroundColor(Palette.textSecondary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 14)
                Menu {
                    Button(L("action_practice")) { model.openSavedScript(entry) }
                    Button(L("edit_scene")) { model.editSaved(entry) }
                    Button(L("action_delete"), role: .destructive) { model.confirmDelete(entry) }
                } label: {
                    Icon(name: "ic_more", color: Palette.textSecondary, size: 24)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .accessibilityLabel(L("text_actions", entry.title))
            }
        }
        .contextMenu {
            Button(L("action_practice")) { model.openSavedScript(entry) }
            Button(L("edit_scene")) { model.editSaved(entry) }
            Button(L("action_delete"), role: .destructive) { model.confirmDelete(entry) }
        }
    }
}

/// Entry to ensemble rehearsal, shown when this build carries a Firebase project.
struct EnsembleCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        Card(action: { model.openEnsemble() }) {
            HStack(spacing: 0) {
                IconTile(icon: "ic_people", tint: Palette.violet, background: Palette.violetContainer, size: 44)
                VStack(alignment: .leading, spacing: 2) {
                    Styled(L("ensemble_title"), .subtitle)
                    Styled(L("ensemble_home_detail"), .body, Palette.textTertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 14)
                Icon(name: "ic_chevron_right", color: Palette.textTertiary, size: 22)
            }
        }
    }
}

// MARK: - New text

struct CategoriesScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ScreenFrame(bar: { TopBar(icon: "ic_close", title: nil, navigate: model.returnToLibrary) }) {
            PageTitle(overline: L("step_counter", 1, 2), title: L("choose_text_category"))
            option(.roleRehearsal, "category_role_details").padding(.top, 24)
            option(.speech, "category_speech_details").padding(.top, 12)
            option(.poemOrMonologue, "category_poem_details").padding(.top, 12)
        }
    }

    private func option(_ value: TextCategory, _ detail: String) -> some View {
        let colors = model.categoryColors(value)
        return Card(padding: EdgeInsets(top: 18, leading: 16, bottom: 18, trailing: 12),
                    action: { model.chooseCategory(value) }) {
            HStack(spacing: 0) {
                IconTile(icon: model.categoryIcon(value), tint: colors.0, background: colors.1, size: 52)
                VStack(alignment: .leading, spacing: 3) {
                    Styled(L(model.categoryTitle(value)), .title)
                    Styled(L(detail), .body, Palette.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 16)
                Icon(name: "ic_chevron_right", color: Palette.textTertiary, size: 22)
            }
        }
    }
}

struct SourceScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let colors = model.categoryColors(model.category)
        ScreenFrame(bar: { TopBar(icon: "ic_back", title: nil, navigate: { model.show(.categories) }) }) {
            PageTitle(overline: L("step_counter", 2, 2), title: L("choose_text_source"))
            Chip(label: L(model.categoryTitle(model.category)), icon: model.categoryIcon(model.category),
                 foreground: colors.0, background: colors.1)
                .padding(.top, 14)
            if model.importing {
                Card(padding: EdgeInsets(top: 20, leading: 18, bottom: 20, trailing: 18)) {
                    HStack(spacing: 16) {
                        ProgressView().tint(Palette.accent).frame(width: 28, height: 28)
                        Styled(model.importStatus ?? L("importing"), .subtitle)
                            .frame(maxWidth: .infinity, alignment: .leading)
                    }
                }
                .padding(.top, 24)
                if !model.importCancelled {
                    MemoButton(L("cancel"), icon: "ic_close", kind: .text) { model.cancelImport() }
                        .padding(.top, 8)
                }
            } else {
                HStack(alignment: .top, spacing: 12) {
                    tile("ic_pdf", "create_import_pdf", "source_pdf_details") { model.pickingPdf = true }
                    tile("ic_photo_camera", "create_import_photo", "source_photo_details") { model.choosePhotoSource() }
                }
                .padding(.top, 24)
                HStack(alignment: .top, spacing: 12) {
                    tile("ic_file", "create_import_txt", "source_txt_details") { model.pickingFile = true }
                    tile("ic_paste", "create_paste", "source_paste_details") { model.pasteFromClipboard() }
                }
                .padding(.top, 12)
                HStack(alignment: .top, spacing: 12) {
                    tile("ic_edit", "create_write", "source_write_details") {
                        model.openEditor(id: nil, title: "", text: "")
                    }
                    Color.clear.frame(maxWidth: .infinity, maxHeight: 1)
                }
                .padding(.top, 12)
            }
        }
    }

    private func tile(_ icon: String, _ title: String, _ detail: String, _ action: @escaping () -> Void) -> some View {
        Card(padding: EdgeInsets(top: 18, leading: 16, bottom: 18, trailing: 16), action: action) {
            IconTile(icon: icon, tint: Palette.accent, background: Palette.accentContainer, size: 44)
            Styled(L(title), .subtitle).padding(.top, 16)
            Styled(L(detail), .body, Palette.textSecondary).padding(.top, 4)
            Spacer(minLength: 0)
        }
        .frame(minHeight: 148)
        .frame(maxWidth: .infinity)
    }
}

// MARK: - Settings

struct SettingsScreen: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var neural = NeuralVoice.shared

    var body: some View {
        let target = model.settingsLanguage ?? Strings.interfaceLanguage
        ScreenFrame(bar: { TopBar(icon: "ic_back", title: L("settings_tab"), navigate: model.returnToLibrary) }) {
            SectionLabel(key: "premium_tab")
            RowGroup(color: model.premium ? Palette.accentContainer : Palette.surface) {
                ListRow(icon: "ic_star", title: L("app_name") + " " + L("premium_tab"),
                        value: L(model.premium ? "premium_active_chip" : "premium_see_plans"),
                        action: model.openPremium)
            }
            .padding(.top, 10)

            SectionLabel(key: "settings_general")
            RowGroup {
                ListRow(icon: "ic_translate", title: L("language_title"), value: L(AppLanguage.current.labelKey),
                        action: model.showLanguageOptions)
            }
            .padding(.top, 10)

            EnsembleAccountSection()

            SectionLabel(key: "voice_settings")
            Styled(L("voice_settings_description"), .body, Palette.textSecondary).padding(.top, 8)
            RowGroup {
                ListRow(icon: "ic_translate", title: L("voice_language"), value: L(target.labelKey), action: {
                    model.chooseLanguage(target) { model.settingsLanguage = $0 }
                })
                RowDivider()
                if !model.deviceVoiceAvailable(target) {
                    ListRow(icon: "ic_volume", title: L("voice_settings"), value: L("speech_unavailable"), action: nil)
                } else {
                    let selected = model.selectedVoiceIndex(target)
                    let current = selected == 0 ? L("voice_default")
                        : model.voiceOptionLabel(selected, model.offlineVoices(target)[selected - 1])
                    if model.offlineVoices(target).count <= 1 {
                        ListRow(icon: "ic_volume", title: L("voice_choose"),
                                value: current + "\n" + L("voice_no_alternatives"), action: nil)
                    } else {
                        ListRow(icon: "ic_volume", title: L("voice_choose"), value: current, action: model.showVoiceOptions)
                    }
                    RowDivider()
                    ListRow(icon: "ic_play", title: L("voice_preview"), value: target.sample, action: model.previewVoice)
                }
            }
            .padding(.top, 12)

            AiVoiceSettings()

            SectionLabel(key: "backup_title")
            Styled(L("backup_description"), .body, Palette.textSecondary).padding(.top, 8)
            RowGroup {
                ListRow(icon: "ic_file", title: L("backup_export"), value: P("library_count", model.scripts.count),
                        action: model.startBackupExport)
                RowDivider()
                ListRow(icon: "ic_texts", title: L("backup_import"), value: L("backup_import_detail"),
                        action: model.startBackupImport)
            }
            .padding(.top, 12)

            SectionLabel(key: "settings_about")
            RowGroup {
                ListRow(icon: "ic_info", title: L("settings_version"), value: model.appVersion, action: nil)
                RowDivider()
                ListRow(icon: "ic_book", title: L("licenses_title"), value: L("licenses_value"), action: model.showLicenses)
            }
            .padding(.top, 10)
        }
    }
}

/// Settings: the AI voice download, switch, preview and removal.
struct AiVoiceSettings: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var neural = NeuralVoice.shared

    var body: some View {
        let size = L("ai_voice_size", model.downloadMegabytes)
        SectionLabel(key: "ai_voice_title")
        Styled(L("ai_voice_description"), .body, Palette.textSecondary).padding(.top, 8)
        RowGroup {
            if !NeuralVoice.isSupported {
                ListRow(icon: "ic_star", title: L("ai_voice_title"), value: L("ai_voice_unsupported"), action: nil)
            } else if !model.premium {
                ListRow(icon: "ic_star", title: L("ai_voice_title"), value: size,
                        action: { model.showPaywall("ai_voice_title") }) { PremiumBadge() }
            } else {
                switch neural.state {
                case .notInstalled:
                    ListRow(icon: "ic_star", title: L("ai_voice_download"), value: size, action: model.confirmAiDownload)
                case .downloading:
                    DownloadProgress()
                case .failed:
                    ListRow(icon: "ic_star", title: L("ai_voice_failed"),
                            value: L("ai_voice_failed_detail", neural.lastError) + "\n" + L("ai_voice_retry"),
                            action: neural.retry)
                default:
                    ListRow(icon: "ic_star", title: L("voice_engine_title"),
                            value: L(model.voiceEngine == .device ? "voice_engine_device"
                                     : model.voiceEngine == .ai ? "ai_voice_title" : "studio_title"),
                            action: model.chooseVoiceEngine)
                    RowDivider()
                    ListRow(icon: "ic_play", title: L("voice_preview"),
                            value: neural.isReady ? L("ai_voice_preview_detail", model.aiVoiceName(0)) : L("ai_voice_loading"),
                            action: { model.previewAiVoice(0) })
                    RowDivider()
                    ListRow(icon: "ic_delete", title: L("ai_voice_delete"), value: size, action: model.confirmAiDelete)
                }
            }
        }
        .padding(.top, 12)
    }
}

/// Download status that updates itself while the files arrive.
struct DownloadProgress: View {
    @ObservedObject var neural = NeuralVoice.shared

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ListRow(icon: "ic_star", title: L("ai_voice_title"), value: L("ai_voice_keep_open"), action: nil) {
                MemoButton(L("cancel"), kind: .text) { neural.cancelDownload() }
            }
            Styled(neural.waitingForNetwork ? L("ai_voice_waiting") : L("ai_voice_downloading", neural.progress),
                   .label, Palette.accent)
                .padding(.horizontal, 16)
                .padding(.bottom, 6)
            ProgressLine(done: neural.progress, total: 100)
                .padding(.horizontal, 16)
                .padding(.bottom, 12)
        }
    }
}

/// Settings: the ensemble identity, Google linking and sign-in, name and sign-out.
struct EnsembleAccountSection: View {
    @EnvironmentObject var model: AppModel
    @State private var refresh = 0

    var body: some View {
        if Ensemble.available {
            let ensemble = Ensemble.shared
            SectionLabel(key: "ensemble_account_section")
            Styled(EnsembleAccount.status(ensemble), .body, Palette.textSecondary).padding(.top, 8)
                .id(refresh)
            RowGroup {
                if ensemble.user == nil {
                    ListRow(icon: "ic_people", title: L("ensemble_title"), value: L("ensemble_home_detail"),
                            action: { model.openEnsemble() })
                    RowDivider()
                    ListRow(icon: "ic_person", title: L("ensemble_sign_in"), value: L("ensemble_returning"), action: {
                        EnsembleAccount.connectGoogle(model) { refresh += 1 }
                    })
                } else {
                    let name = ensemble.name
                    ListRow(icon: "ic_person", title: L("ensemble_rename"),
                            value: name.isEmpty ? L("ensemble_unnamed") : name, action: {
                        EnsembleAccount.askName(model) { refresh += 1 }
                    })
                    RowDivider()
                    if ensemble.linked {
                        ListRow(icon: "ic_close", title: L("ensemble_sign_out"), value: ensemble.email, action: {
                            EnsembleAccount.confirmSignOut(model) { refresh += 1 }
                        })
                    } else {
                        ListRow(icon: "ic_people", title: L("ensemble_link"), value: L("ensemble_link_detail"), action: {
                            EnsembleAccount.connectGoogle(model) { refresh += 1 }
                        })
                    }
                }
            }
            .padding(.top, 10)
            .id(refresh)
        }
    }
}

// MARK: - Premium

struct PremiumScreen: View {
    @EnvironmentObject var model: AppModel

    private var billing: Billing {
        model.billing
    }

    var body: some View {
        let active = model.premium
        ScreenFrame(bar: { TopBar(icon: "ic_back", title: L("premium_tab"), navigate: { model.show(model.premiumReturn) }) }) {
            VStack(alignment: .leading, spacing: 0) {
                HStack {
                    IconTile(icon: active ? "ic_check" : "ic_star", tint: Palette.accent,
                             background: Palette.accentContainer, size: 44)
                    Spacer()
                    if active {
                        Chip(label: L("premium_active_chip"), foreground: Palette.onAccentContainer,
                             background: Palette.accentContainer)
                    }
                }
                Styled(L(active ? "premium_active_title" : "premium_headline"), .headline).padding(.top, 18)
                Styled(L(active ? "premium_active_description" : "premium_description"), .bodyLarge,
                       Palette.textSecondary).padding(.top, 6)
                if Premium.isPurchased {
                    MemoButton(L("billing_manage"), kind: .outline) { billing.manageSubscription() }
                        .padding(.top, 20)
                }
            }
            .padding(EdgeInsets(top: 22, leading: 22, bottom: 24, trailing: 22))
            .background(HeroWash())
            .padding(.top, 20)

            if !active {
                plans
            }

            SectionLabel(key: "premium_features")
            RowGroup {
                feature("ic_volume", "premium_voices", "premium_voices_description", divider: true)
                feature("ic_mic", "premium_voice_cue", "premium_voice_cue_description", divider: true)
                feature("ic_insights", "premium_review", "premium_review_description", divider: true)
                feature("ic_video", "premium_video", "premium_video_description", divider: false)
            }
            .padding(.top, 10)
            if active {
                Styled(L("premium_where"), .body, Palette.textTertiary).padding(.top, 12)
            }

            if Premium.canSwitchForTesting {
                let testing = Premium.isTesting
                Card {
                    HStack(spacing: 12) {
                        Icon(name: "ic_info", color: Palette.textSecondary, size: 20)
                        Styled(L("premium_test_title"), .subtitle).frame(maxWidth: .infinity, alignment: .leading)
                    }
                    Styled(L("premium_test_description"), .body, Palette.textSecondary).padding(.top, 6)
                    MemoButton(L(testing ? "premium_switch_off" : "premium_switch_on"), kind: .tonal) {
                        model.setPremiumTesting(!testing)
                    }
                    .padding(.top, 12)
                }
                .padding(.top, 24)
            }
        }
    }

    private func feature(_ icon: String, _ title: String, _ detail: String, divider: Bool) -> some View {
        VStack(spacing: 0) {
            HStack(alignment: .top, spacing: 14) {
                IconTile(icon: icon, tint: Palette.accent, background: Palette.surfaceHighest, size: 40)
                VStack(alignment: .leading, spacing: 2) {
                    Styled(L(title), .subtitle)
                    Styled(L(detail), .body, Palette.textSecondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            if divider {
                RowDivider()
            }
        }
    }

    /// The subscription plans from the App Store, or why they cannot be shown.
    @ViewBuilder private var plans: some View {
        SectionLabel(key: "premium_choose_plan")
        if billing.pending {
            Styled(L("billing_pending"), .bodyLarge, Palette.onAccentContainer).padding(.top, 10)
        }
        let yearly = billing.plan(Billing.yearlyProduct)
        let monthly = billing.plan(Billing.monthlyProduct)
        if yearly == nil && monthly == nil {
            let loading = billing.status == .connecting
            Card {
                HStack(spacing: 14) {
                    if loading {
                        ProgressView().tint(Palette.accent).frame(width: 24, height: 24)
                    }
                    Styled(L(loading ? "billing_loading" : "billing_unavailable"), .body, Palette.textSecondary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    if !loading {
                        MemoButton(L("billing_retry"), kind: .text) { billing.connect() }
                    }
                }
            }
            .padding(.top, 10)
        } else {
            if let yearly {
                planCard(yearly, "plan_yearly", highlight: true).padding(.top, 10)
            }
            if let monthly {
                planCard(monthly, "plan_monthly", highlight: false).padding(.top, 10)
            }
        }
        MemoButton(L("billing_restore"), kind: .text) {
            billing.restore()
            model.notifyKey("billing_loading")
        }
        .padding(.top, 6)
        Styled(L("billing_terms"), .caption, Palette.textTertiary).padding(.horizontal, 4).padding(.top, 2)
    }

    private func planCard(_ plan: Billing.Plan, _ name: String, highlight: Bool) -> some View {
        let trial = model.trialLabel(plan)
        return Card(color: highlight ? Palette.accentContainer : Palette.surface,
                    stroke: highlight ? Palette.accent.opacity(0.5) : Palette.outlineSoft,
                    padding: EdgeInsets(top: 16, leading: 18, bottom: 16, trailing: 12),
                    action: { model.purchase(plan) }) {
            HStack(spacing: 0) {
                VStack(alignment: .leading, spacing: 4) {
                    HStack(spacing: 8) {
                        Styled(L(name), .title, highlight ? Palette.onAccentContainer : Palette.text)
                        if highlight {
                            Chip(label: L("plan_best_value"), foreground: Palette.onAccent, background: Palette.accent)
                        }
                    }
                    Styled(model.priceLabel(plan), .bodyLarge, highlight ? Palette.onAccentContainer : Palette.textSecondary)
                    if let trial {
                        Styled(trial, .label, Palette.accent)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                Icon(name: "ic_chevron_right", color: highlight ? Palette.onAccentContainer : Palette.textTertiary, size: 22)
            }
        }
        .accessibilityLabel(L(name) + ", " + model.priceLabel(plan) + (trial.map { ", " + $0 } ?? ""))
    }
}

/// Gives views that are not handed the model a way to reach long-lived services.
enum AppModelHolder {
    static let shared = AppModel()
}
