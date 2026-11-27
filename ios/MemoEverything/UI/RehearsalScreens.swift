import MemoCore
import SwiftUI

/// Chooses a scene of a text with named sections.
struct SectionsScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let full = model.fullScene ?? .empty
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: nil, navigate: model.returnToLibrary) {
                BarAction(icon: "ic_edit", label: "edit_scene", action: model.editLines)
            }
        }, content: {
            PageTitle(overline: model.scriptTitle, title: L("choose_scene"))
            Card(color: Palette.accentContainer, stroke: Palette.accent.opacity(0.25), action: { model.selectSection(-1) }) {
                HStack(spacing: 0) {
                    IconTile(icon: "ic_book", tint: Palette.onAccent, background: Palette.accent, size: 44)
                    VStack(alignment: .leading, spacing: 0) {
                        Styled(L("entire_script"), .subtitle, Palette.onAccentContainer)
                        Styled(P("line_count", full.lines.count), .body, Palette.onAccentContainer).opacity(0.75)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 14)
                    Icon(name: "ic_chevron_right", color: Palette.onAccentContainer, size: 22)
                }
            }
            .padding(.top, 24)

            SectionLabel(key: "sections_label")
            RowGroup {
                ForEach(Array(full.sections.enumerated()), id: \.offset) { index, section in
                    sectionRow(index, section)
                    if index < full.sections.count - 1 {
                        RowDivider(inset: 68)
                    }
                }
            }
            .padding(.top, 10)
        })
    }

    private func sectionRow(_ index: Int, _ section: ScriptScene.Section) -> some View {
        let count = section.end - section.start
        return Button(action: { model.selectSection(index) }) {
            HStack(spacing: 0) {
                Text(String(format: "%02d", index + 1))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundColor(Palette.textSecondary)
                    .frame(width: 38, height: 38)
                    .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.surfaceHighest))
                VStack(alignment: .leading, spacing: 0) {
                    Text(section.title.isEmpty ? L("untitled_scene", index + 1) : section.title)
                        .font(.system(size: 16, weight: .medium))
                        .foregroundColor(Palette.text)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                    Styled(P("line_count", count), .caption, Palette.textTertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.leading, 14)
                Icon(name: "ic_chevron_right", color: Palette.textTertiary, size: 22)
            }
            .padding(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 12))
            .frame(minHeight: 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(PressStyle(shape: ErasedShape(Rectangle()), tint: Color.white.opacity(0.12)))
    }
}

/// Practice options: mode, role, how lines show, speed and the Premium extras.
struct SetupScreen: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var neural = NeuralVoice.shared

    var body: some View {
        let scene = model.scene
        let hasRoles = model.category.hasRoles
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: nil, navigate: model.back) {
                BarAction(icon: "ic_book", label: "read_script") { model.show(.reader) }
                BarAction(icon: "ic_edit", label: "edit_scene", action: model.editLines)
                if model.scriptId != nil {
                    BarAction(icon: "ic_delete", label: "action_delete", action: model.deleteCurrent)
                }
            }
        }, content: {
            VStack(alignment: .leading, spacing: 0) {
                Styled(L(model.categoryTitle(model.category)), .overline, Palette.accent)
                Serif(model.scriptTitle, 30).padding(.top, 10)
                if model.hasNamedSections {
                    Styled(model.currentSectionTitle, .bodyLarge, Palette.textSecondary).padding(.top, 6)
                }
                HStack(spacing: 8) {
                    Chip(label: P(hasRoles ? "line_count" : "part_count", scene.lines.count), foreground: Palette.text,
                         background: Palette.white12)
                    if hasRoles {
                        Chip(label: P("role_count", scene.roles.count), foreground: Palette.text, background: Palette.white12)
                    }
                }
                .padding(.top, 18)
            }
            .padding(22)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(HeroWash())
            .padding(.top, 4)

            ResumeCard()

            SectionLabel(key: "mode_label")
            HStack(spacing: 0) {
                modeChoice(.listen, "listen_mode", "ic_headphones")
                modeChoice(.practice, "practice_mode", "ic_mic")
            }
            .padding(4)
            .background(Capsule().fill(Palette.surface))
            .padding(.top, 10)
            let description = hasRoles
                ? (model.mode == .listen ? "listen_description" : "practice_description")
                : (model.mode == .listen ? "solo_listen_description" : "solo_practice_description")
            Styled(L(description), .body, Palette.textSecondary).padding(.horizontal, 4).padding(.top, 12)

            SectionLabel(key: "options_label")
            RowGroup {
                if model.mode == .practice && hasRoles {
                    ListRow(icon: "ic_person", title: L("your_role"), value: model.role, action: model.showRoleOptions)
                    RowDivider()
                }
                if model.mode == .practice {
                    ListRow(icon: "ic_eye", title: L("visibility_title"), value: L(model.lineVisibility.labelKey),
                            action: model.showVisibilityOptions)
                    RowDivider()
                }
                ListRow(icon: "ic_next", title: L("speed_title"), value: model.speedLabel(model.readingSpeed),
                        action: model.showSpeedOptions)
                if model.mode == .practice {
                    RowDivider()
                    ListRow(icon: "ic_volume", title: L("turn_cue_title"),
                            value: L(model.turnCueOn ? "turn_cue_on" : "turn_cue_off"),
                            action: model.toggleTurnCue) { ToggleKnob(on: model.turnCueOn) }
                }
                if model.hasNamedSections {
                    RowDivider()
                    ListRow(icon: "ic_list", title: L("scene_title"), value: model.currentSectionTitle,
                            action: { model.show(.sections) })
                }
            }
            .padding(.top, 10)

            SectionLabel(key: "premium_tab")
            RowGroup {
                VoiceRows()
                if hasRoles {
                    premiumRow("ic_volume", "cast_title", L("cast_value")) { model.show(.voices) }
                    RowDivider()
                }
                if model.mode == .practice {
                    let on = model.handsFreeEnabled
                    if model.premium {
                        ListRow(icon: "ic_mic", title: L("premium_voice_cue"),
                                value: L(on ? "hands_free_on" : "hands_free_off"),
                                action: model.toggleHandsFree) { ToggleKnob(on: on) }
                    } else {
                        premiumRow("ic_mic", "premium_voice_cue", L(on ? "hands_free_on" : "hands_free_off"),
                                   open: model.toggleHandsFree)
                    }
                    RowDivider()
                }
                premiumRow("ic_video", "selftape_title", L("video_value"), open: model.openVideo)
            }
            .padding(.top, 10)
        }, footer: {
            FooterBar {
                MemoButton(L(model.mode == .listen ? "listen_start" : "practice_start"), icon: "ic_play",
                           kind: .primary, fill: true, height: 56, action: model.startRehearsal)
            }
        })
    }

    private func modeChoice(_ destination: RehearsalMode, _ title: String, _ icon: String) -> some View {
        let selected = model.mode == destination
        let color = selected ? Palette.onAccent : Palette.textSecondary
        return Button(action: { model.setMode(destination) }) {
            HStack(spacing: 8) {
                Icon(name: icon, color: color, size: 20)
                Text(L(title)).font(.system(size: 15, weight: .medium)).foregroundColor(color)
            }
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(Capsule().fill(selected ? Palette.accent : Color.clear))
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle(shape: ErasedShape(Capsule())))
        .accessibilityLabel(L(title))
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    /// A row for a Premium feature: opens it on Premium, shows a lock and the paywall otherwise.
    @ViewBuilder private func premiumRow(_ icon: String, _ title: String, _ value: String,
                                         open: @escaping () -> Void) -> some View {
        if model.premium {
            ListRow(icon: icon, title: L(title), value: value, action: open)
        } else {
            ListRow(icon: icon, title: L(title), value: value, action: { model.showPaywall(title) }) { PremiumBadge() }
        }
    }
}

/// Practice options row: which voice reads the lines, with the next step to improve it.
struct AiVoiceRow: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var neural = NeuralVoice.shared

    var body: some View {
        if !NeuralVoice.isSupported {
            ListRow(icon: "ic_star", title: L("ai_voice_title"), value: L("ai_voice_unsupported"), action: nil)
        } else if !model.premium {
            ListRow(icon: "ic_star", title: L("ai_voice_title"), value: L("ai_voice_off"),
                    action: { model.showPaywall("ai_voice_title") }) { PremiumBadge() }
        } else {
            switch neural.state {
            case .notInstalled:
                ListRow(icon: "ic_star", title: L("ai_voice_title"), value: L("ai_voice_tap_download", model.downloadMegabytes),
                        action: model.confirmAiDownload) {
                    Chip(label: L("ai_voice_download_confirm"), foreground: Palette.onAccentContainer,
                         background: Palette.accentContainer)
                }
            case .downloading:
                DownloadProgress()
            case .failed:
                ListRow(icon: "ic_star", title: L("ai_voice_failed"),
                        value: L("ai_voice_failed_detail", neural.lastError) + "\n" + L("ai_voice_retry"),
                        action: neural.retry)
            default:
                ListRow(icon: "ic_star", title: L("ai_voice_title"), value: L("ai_voice_on"), action: model.chooseVoiceEngine)
            }
        }
    }
}

/// Practice options: the one choice of voice, then what that voice needs (download, preparing).
struct VoiceRows: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var studio = StudioVoices.shared
    @ObservedObject var neural = NeuralVoice.shared

    var body: some View {
        let engine = model.voiceEngine
        ListRow(icon: "ic_star", title: L("voice_engine_title"), value: model.voiceEngineValue,
                action: model.chooseVoiceEngine)
        RowDivider()
        if engine == .ai && model.premium && NeuralVoice.isSupported
            && ![.installed, .loading, .ready].contains(neural.state) {
            AiVoiceRow()
            RowDivider()
        }
        if engine == .studio && model.category.hasRoles {
            let total = model.studioTotal
            let done = model.studioReady
            if !model.premium {
                if model.scriptId != nil {
                    ListRow(icon: "ic_star", title: L("studio_title"), value: L("studio_value"),
                            action: { model.showPaywall("studio_title") }) { PremiumBadge() }
                    RowDivider()
                }
            } else if studio.running {
                ListRow(icon: "ic_star", title: L("studio_title"), value: L("studio_progress", done, total), action: nil) {
                    MemoButton(L("studio_stop"), kind: .text) { studio.cancel() }
                }
                RowDivider()
            }
            if model.premium && !studio.running && done > 0 {
                ListRow(icon: "ic_list", title: L("notes_title"), value: L("notes_value"), action: { model.show(.notes) })
                RowDivider()
            }
            if model.premium && !studio.running && done < total {
                ListRow(icon: "ic_star", title: L("studio_prepare"),
                        value: (done == 0 ? L("studio_none") : L("studio_ready", done, total)) + "\n" + L("studio_allowance"),
                        action: model.startStudio)
                RowDivider()
            }
        }
    }
}

/// Continue where the last rehearsal of this text stopped.
struct ResumeCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        if let saved = model.savedPosition, let full = model.fullScene {
            let target = full.forSection(saved.section)
            let detail = resumeDetail(saved, target)
            Card(color: Palette.accentContainer, stroke: Palette.accent.opacity(0.5), action: { model.resume(saved) }) {
                HStack(spacing: 0) {
                    IconTile(icon: "ic_play", tint: Palette.onAccent, background: Palette.accent, size: 44)
                    VStack(alignment: .leading, spacing: 0) {
                        Styled(L("resume_title"), .subtitle, Palette.onAccentContainer)
                        Styled(detail, .body, Palette.onAccentContainer).opacity(0.8).padding(.top, 2)
                        Text(target.lines[saved.position].text)
                            .font(.system(size: 14, design: .serif))
                            .foregroundColor(Palette.onAccentContainer)
                            .opacity(0.7)
                            .lineLimit(2)
                            .padding(.top, 4)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.leading, 14)
                    Icon(name: "ic_chevron_right", color: Palette.onAccentContainer, size: 22)
                }
            }
            .padding(.top, 12)
        }
    }

    private func resumeDetail(_ saved: AppModel.SavedPosition, _ target: ScriptScene) -> String {
        var detail = L("line_progress", saved.position + 1, target.lines.count)
        if saved.section >= 0 {
            detail += " · " + model.sectionTitle(saved.section)
        }
        if model.category.hasRoles && saved.mode == .practice && target.roles.contains(saved.role) {
            detail += " · " + saved.role
        }
        return detail
    }
}

/// The whole scene as a manuscript.
struct ReaderScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ScreenFrame(bar: { TopBar(icon: "ic_back", title: model.scriptTitle, navigate: { model.show(.setup) }) }) {
            Text(transcript)
                .lineSpacing(5)
                .textSelection(.enabled)
                .padding(.top, 8)
        }
    }

    private var transcript: AttributedString {
        var text = AttributedString()
        var previousSection = ""
        for line in model.scene.lines {
            if !line.section.isEmpty && line.section != previousSection {
                if !text.characters.isEmpty {
                    text += AttributedString("\n")
                }
                var heading = AttributedString(line.section.uppercased(with: Strings.locale) + "\n\n")
                heading.font = .system(size: 13, weight: .medium)
                heading.foregroundColor = Palette.textTertiary
                text += heading
                previousSection = line.section
            }
            for note in line.notes {
                var direction = AttributedString(note + "\n")
                direction.font = .system(size: 19, design: .serif).italic()
                direction.foregroundColor = Palette.textTertiary
                text += direction
            }
            if model.category.hasRoles {
                var who = AttributedString(line.role + "\n")
                who.font = .system(size: 14, weight: .medium)
                who.foregroundColor = line.role == model.role ? Palette.accent : Palette.violet
                text += who
            }
            var spoken = AttributedString(line.text + "\n\n")
            spoken.font = .system(size: 19, design: .serif)
            spoken.foregroundColor = Palette.text
            text += spoken
        }
        return text
    }
}

/// The rehearsal: one line at a time, partner lines read aloud.
struct RehearsalScreen: View {
    @EnvironmentObject var model: AppModel
    @ObservedObject var neural = NeuralVoice.shared

    var body: some View {
        let total = model.scene.lines.count
        let title = model.reviewBase != nil ? L("results_struggled")
            : model.hasNamedSections ? model.currentSectionTitle : model.scriptTitle
        ScreenFrame(bar: {
            VStack(spacing: 0) {
                TopBar(icon: "ic_close", title: title, navigate: { model.show(.setup) }) {
                    if total > 0 {
                        BarAction(icon: "ic_list", label: "overview_title") {
                            model.dialog = DialogItem(kind: .overview)
                        }
                    }
                    if model.mode == .practice && model.position < total {
                        let on = model.handsFreeEnabled
                        BarAction(icon: on ? "ic_mic" : "ic_mic_off", label: on ? "hands_free_disable" : "hands_free_enable",
                                  action: model.toggleHandsFree)
                    }
                    Text(L("line_progress", min(model.position + 1, total), total))
                        .font(.system(size: 14, weight: .medium))
                        .foregroundColor(Palette.textSecondary)
                        .padding(.leading, 8)
                        .padding(.trailing, 12)
                        .accessibilityLabel(L("line_counter", min(model.position + 1, total), total))
                }
                ProgressLine(done: model.position, total: total)
                    .padding(.horizontal, 20)
                    .padding(.bottom, 8)
            }
        }, centered: model.position >= total, content: {
            if model.position >= total {
                CompletedView(total: total)
            } else {
                lineContent
            }
        }, footer: {
            if model.position >= total {
                FooterBar {
                    HStack(spacing: 10) {
                        MemoButton(L("back_to_setup_short"), kind: .outline) { model.show(.setup) }
                        MemoButton(L("restart"), icon: "ic_replay", kind: .primary, fill: true) {
                            model.resetReport()
                            model.moveTo(0, autoPlay: true)
                        }
                    }
                }
            } else {
                controls
            }
        })
        .onAppear(perform: model.updateIdleTimer)
    }

    @ViewBuilder private var lineContent: some View {
        let line = model.scene.lines[model.position]
        let myTurn = model.isMyTurn
        let hasRoles = model.category.hasRoles
        if myTurn && model.position > 0 && hasRoles {
            let cue = model.scene.lines[model.position - 1]
            VStack(alignment: .leading, spacing: 4) {
                Styled(L("cue_label", cue.role), .caption, Palette.textTertiary)
                Serif(cue.text, 15, Palette.textSecondary).lineLimit(3)
            }
            .padding(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 16))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(Palette.surface))
            .padding(.top, 12)
        }
        let turn = hasRoles
            ? L(myTurn ? "your_turn" : model.mode == .listen ? "reading_role" : "partner_turn", line.role)
            : L(myTurn ? "solo_your_turn" : "listen_mode")
        Group {
            if myTurn {
                Chip(label: turn, icon: "ic_mic", foreground: Palette.onAccentContainer, background: Palette.accentContainer)
            } else {
                Chip(label: turn, icon: "ic_volume", foreground: Palette.violet, background: Palette.violetContainer)
            }
        }
        .padding(.top, myTurn && model.position > 0 ? 24 : 20)

        if !line.notes.isEmpty {
            Serif(line.notes.joined(separator: "\n"), 15, Palette.textTertiary, italic: true).padding(.top, 14)
        }

        let shown = myTurn && !model.revealLine ? model.lineVisibility.visibleText(line.text) : line.text
        VStack(alignment: .leading, spacing: 0) {
            Serif(shown.isEmpty ? L("line_hidden") : shown, 24, shown.isEmpty ? Palette.textTertiary : Palette.text,
                  italic: shown.isEmpty)
                .lineSpacing(6)
                .frame(maxWidth: .infinity, alignment: .leading)
            if myTurn && !model.revealLine && model.lineVisibility != .full {
                MemoButton(L("line_reveal"), icon: "ic_eye", kind: .tonal, action: model.reveal)
                    .padding(.top, 20)
            }
        }
        .padding(EdgeInsets(top: 24, leading: 22, bottom: 24, trailing: 22))
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(Palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous)
            .strokeBorder(myTurn ? Palette.accent.opacity(0.5) : Palette.outlineSoft, lineWidth: 1))
        .padding(.top, 12)

        if myTurn && model.handsFreeEnabled {
            listeningPanel.padding(.top, 14)
        } else {
            Text(myTurn ? L("speak_then_continue") : model.speechMessage)
                .font(.system(size: 14))
                .foregroundColor(Palette.textSecondary)
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity)
                .padding(.top, 18)
        }
        // A long wait for the actor's next line: jump to three lines before it to hear the cues.
        let nextLine = model.mode == .practice && !myTurn ? model.nextTurn() : -1
        if nextLine - model.position > 3 {
            let away = nextLine - model.position
            MemoButton(P("jump_to_turn", away), icon: "ic_next", kind: .tonal, fill: true) {
                model.moveTo(nextLine - 3, autoPlay: true)
            }
            .padding(.top, 16)
        }
    }

    private var listeningPanel: some View {
        HStack(spacing: 12) {
            Icon(name: model.listeningPaused ? "ic_mic_off" : "ic_mic",
                 color: model.listeningPaused ? Palette.textSecondary : Palette.accent, size: 22)
            Styled(model.listeningText, .body, model.listeningPaused ? Palette.textSecondary : Palette.onAccentContainer)
                .frame(maxWidth: .infinity, alignment: .leading)
            if model.listeningPaused {
                MemoButton(L("listen_again"), kind: .text, action: model.listenIfNeeded)
            }
        }
        .padding(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 8))
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous)
            .fill(model.listeningPaused ? Palette.surface : Palette.accentContainer))
    }

    private var controls: some View {
        let myTurn = model.isMyTurn
        let playing = model.activeUtterance != nil
        return FooterBar {
            HStack(spacing: 10) {
                IconButton(icon: "ic_previous", label: L("previous_line"),
                           tint: model.position > 0 ? Palette.text : Palette.textTertiary,
                           background: Palette.surfaceHighest, size: 56, action: model.previousLine)
                    .opacity(model.position > 0 ? 1 : 0.5)
                    .disabled(model.position == 0)
                if !myTurn && model.canPlay {
                    IconButton(icon: playing ? "ic_stop" : "ic_play", label: L(playing ? "stop_line" : "play_line"),
                               tint: Palette.accent, background: Palette.accentContainer, size: 56, action: model.togglePlay)
                }
                MemoButton(L("next_line"), icon: "ic_next", kind: .primary, fill: true, height: 56) {
                    model.moveTo(model.position + 1, autoPlay: true)
                }
            }
        }
    }
}

/// The end of a rehearsal, with the Premium results of a practice run.
struct CompletedView: View {
    @EnvironmentObject var model: AppModel
    let total: Int

    var body: some View {
        let results = model.mode == .practice && total > 0
        VStack(spacing: 0) {
            if !results {
                Spacer(minLength: 40)
            }
            IconTile(icon: "ic_check", tint: Palette.onAccent, background: Palette.accent, size: 88)
                .padding(.top, results ? 16 : 0)
            Styled(L("completed_title"), .headline).multilineTextAlignment(.center).padding(.top, 24)
            Styled(L("completed_detail", total), .bodyLarge, Palette.textSecondary)
                .multilineTextAlignment(.center).padding(.top, 8)
            if total > 0 {
                MemoButton(L("previous_line"), icon: "ic_previous", kind: .text) {
                    model.moveTo(model.position - 1, autoPlay: false)
                }
                .padding(.top, 12)
            }
            if results {
                if model.premium {
                    ResultsCard().padding(.top, 24)
                } else {
                    RowGroup {
                        ListRow(icon: "ic_insights", title: L("premium_review"), value: L("premium_review_description"),
                                action: { model.showPaywall("premium_review") }) { PremiumBadge() }
                    }
                    .padding(.top, 24)
                }
            } else {
                Spacer(minLength: 40)
            }
        }
        .frame(maxWidth: .infinity)
    }
}

/// Premium: what went wrong in this run and a shortcut to practise just those lines.
struct ResultsCard: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        let struggled = model.struggledLines
        Card(padding: EdgeInsets(top: 18, leading: 18, bottom: 18, trailing: 18)) {
            Styled(L("results_title"), .overline, Palette.accent)
            HStack(spacing: 8) {
                stat(P("hint_count", model.revealedLines.count))
                stat(P("retry_count", model.retriedLines.count))
                if model.handsFreeEnabled || !model.missedLines.isEmpty {
                    stat(P("missed_count", model.missedLines.count))
                }
            }
            .padding(.top, 12)
            if struggled.isEmpty {
                Styled(L("results_clean"), .bodyLarge).padding(.top, 16)
            } else {
                Styled(L("results_struggled"), .subtitle).padding(.top, 18)
                ForEach(struggled.prefix(6), id: \.self) { index in
                    let line = model.scene.lines[index]
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(spacing: 6) {
                            if model.category.hasRoles {
                                Text(line.role).font(.system(size: 12, weight: .medium)).foregroundColor(Palette.accent)
                            }
                            Spacer()
                            if model.revealedLines.contains(index) {
                                reason("reason_hint")
                            }
                            if model.retriedLines.contains(index) {
                                reason("reason_retry")
                            }
                            if model.missedLines.contains(index) {
                                reason("reason_missed")
                            }
                        }
                        Serif(line.text, 16).lineLimit(3)
                    }
                    .padding(EdgeInsets(top: 12, leading: 14, bottom: 12, trailing: 14))
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(Palette.surfaceHigh))
                    .padding(.top, 8)
                }
                MemoButton(L("results_practice", struggled.count), icon: "ic_replay", kind: .primary, fill: true) {
                    model.startReview(struggled)
                }
                .padding(.top, 16)
            }
        }
    }

    private func stat(_ label: String) -> some View {
        Chip(label: label, foreground: Palette.text, background: Palette.surfaceHighest)
    }

    private func reason(_ key: String) -> some View {
        Chip(label: L(key), foreground: Palette.onAccentContainer, background: Palette.accentContainer)
    }
}

/// Premium: a voice, tone and pace for every character.
struct VoicesScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        // One voice reads the lines, so only its choices are shown: studio, AI or device voices.
        let studioCast = model.studioCastShown
        let ai = !studioCast && model.useAiVoice
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: L("cast_title"), navigate: { model.show(.setup) }) {
                BarAction(icon: "ic_replay", label: "cast_auto", action: model.resetCast)
            }
        }, content: {
            if model.castCheck && studioCast {
                Card(color: Palette.accentContainer, stroke: Palette.accent.opacity(0.25)) {
                    Styled(L("cast_check_title"), .subtitle, Palette.onAccentContainer)
                    Styled(L(model.guessingCast ? "cast_check_guessing" : "cast_check_body"), .body,
                           Palette.onAccentContainer).padding(.top, 4)
                }
                .padding(.top, 8)
            }
            Styled(L(studioCast ? "cast_intro_studio" : ai ? "cast_intro_ai" : "cast_intro"), .body, Palette.textSecondary)
                .padding(.top, 8)
            if !studioCast && !ai && !model.deviceVoiceAvailable(model.language) {
                Styled(L("speech_unavailable"), .bodyLarge).padding(.top, 20)
            } else {
                let cast = model.currentCast()
                ForEach(model.fullScene?.roles ?? [], id: \.self) { character in
                    if let part = cast[character] {
                        characterCard(character, part, ai: ai, studio: studioCast).padding(.top, 12)
                    }
                }
            }
        }, footer: {
            if model.castCheck && studioCast {
                FooterBar {
                    MemoButton(L("cast_check_confirm"), icon: "ic_check", kind: .primary, fill: true, height: 56,
                               action: model.confirmCastAndPrepare)
                        .disabled(model.guessingCast)
                        .opacity(model.guessingCast ? 0.5 : 1)
                }
            }
        })
    }

    private func characterCard(_ character: String, _ part: VoiceCast.Part, ai: Bool, studio: Bool) -> some View {
        Card(padding: EdgeInsets(top: 10, leading: 16, bottom: 16, trailing: 8)) {
            HStack(spacing: 6) {
                Text(character).font(.system(size: 18, weight: .medium)).foregroundColor(Palette.text).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if character == model.role && model.mode == .practice {
                    Chip(label: L("cast_you"), foreground: Palette.onAccentContainer, background: Palette.accentContainer)
                }
                IconButton(icon: "ic_play", label: L("cast_preview", character), tint: Palette.accent) {
                    model.previewCharacter(character)
                }
            }
            HStack(spacing: 0) {
                Styled(L("cast_voice"), .label, Palette.textSecondary).frame(width: 56, alignment: .leading)
                MemoButton(studio ? model.studioVoiceLabel(part.speaker)
                              : ai ? model.aiVoiceName(part.speaker) + " · " + model.aiVoiceGender(part.speaker)
                              : model.voiceLabel(part.voice), icon: ai || studio ? nil : "ic_volume", kind: .tonal,
                           fill: true, height: 44) {
                    if studio {
                        model.chooseStudioVoice(character)
                    } else {
                        model.chooseCharacterVoice(character)
                    }
                }
            }
            .padding(.trailing, 8)
            .padding(.top, 4)
            if ai {
                Styled(model.aiVoiceTrait(part.speaker), .caption, Palette.textTertiary).padding(.leading, 56).padding(.top, 4)
            }
            // The director sets each line's pace, so studio voices have no tone or pace to choose.
            if !ai && !studio {
                optionRow("cast_tone", ["tone_low", "tone_normal", "tone_high"],
                          VoiceCast.Tone.allCases.firstIndex(of: part.tone) ?? 1) { index in
                    var changed = part
                    changed.tone = VoiceCast.Tone.allCases[index]
                    model.updateCast(character, changed)
                }
                .padding(.top, 12)
            }
            if !studio {
                optionRow("cast_pace", ["pace_slow", "pace_normal", "pace_fast"],
                          VoiceCast.Pace.allCases.firstIndex(of: part.pace) ?? 1) { index in
                    var changed = part
                    changed.pace = VoiceCast.Pace.allCases[index]
                    model.updateCast(character, changed)
                }
                .padding(.top, 10)
            }
        }
    }

    private func optionRow(_ label: String, _ options: [String], _ selected: Int,
                           _ pick: @escaping (Int) -> Void) -> some View {
        HStack(spacing: 0) {
            Styled(L(label), .label, Palette.textSecondary).frame(width: 56, alignment: .leading)
            HStack(spacing: 0) {
                ForEach(Array(options.enumerated()), id: \.offset) { index, option in
                    let on = index == selected
                    Button(action: { if !on { pick(index) } }) {
                        Text(L(option))
                            .font(.system(size: 14, weight: .medium))
                            .foregroundColor(on ? Palette.onAccent : Palette.textSecondary)
                            .lineLimit(1)
                            .frame(maxWidth: .infinity, minHeight: 38)
                            .background(Capsule().fill(on ? Palette.accent : Color.clear))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(PressStyle(shape: ErasedShape(Capsule())))
                    .accessibilityAddTraits(on ? .isSelected : [])
                }
            }
            .padding(3)
            .background(Capsule().fill(Palette.surfaceHigh))
        }
        .padding(.trailing, 8)
    }
}

/// Every line of the scene with what the director decided and how its voice was made.
struct NotesScreen: View {
    @EnvironmentObject var model: AppModel

    var body: some View {
        ScreenFrame(bar: {
            TopBar(icon: "ic_back", title: L("notes_title"), navigate: { model.show(.setup) }) {
                ShareLink(item: model.notesText) {
                    Icon(name: "ic_share", color: Palette.textSecondary, size: 24).frame(width: 48, height: 48)
                }
                .accessibilityLabel(L("notes_share"))
            }
        }, content: {
            Styled(L("notes_intro"), .body, Palette.textSecondary).padding(.top, 8)
            Card(color: Palette.accentContainer, stroke: Palette.accent.opacity(0.25),
                 padding: EdgeInsets(top: 12, leading: 16, bottom: 14, trailing: 16)) {
                Styled(L("notes_reading"), .subtitle, Palette.onAccentContainer)
                Styled(model.readingText, .caption, Palette.onAccentContainer).padding(.top, 6).textSelection(.enabled)
            }
            .padding(.top, 10)
            ForEach(Array(model.scene.lines.enumerated()), id: \.offset) { _, line in
                Card(padding: EdgeInsets(top: 12, leading: 16, bottom: 14, trailing: 16)) {
                    Styled(line.role, .label, Palette.accent)
                    Styled(line.text, .bodyLarge, Palette.text).padding(.top, 2)
                    Styled(model.noteText(line), .caption, Palette.textSecondary).padding(.top, 8)
                        .textSelection(.enabled)
                }
                .padding(.top, 10)
            }
        }, footer: {
            if !StudioVoices.shared.running {
                FooterBar {
                    MemoButton(L("notes_redirect"), icon: "ic_replay", kind: .tonal, fill: true, height: 56,
                               action: model.confirmRedirect)
                }
            }
        })
    }
}
