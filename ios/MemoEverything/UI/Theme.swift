import SwiftUI

/// The Android app's colors, type scale and components, drawn with SwiftUI.
enum Palette {
    static let background = Color(hex: 0x111015)
    static let surface = Color(hex: 0x1B1A21)
    static let surfaceHigh = Color(hex: 0x25232D)
    static let surfaceHighest = Color(hex: 0x312E3A)
    static let outline = Color(hex: 0x3A3645)
    static let outlineSoft = Color(hex: 0x292631)
    static let text = Color(hex: 0xF5F2F8)
    static let textSecondary = Color(hex: 0xB4ADBF)
    static let textTertiary = Color(hex: 0x827B8D)
    static let accent = Color(hex: 0xF4A98A)
    static let onAccent = Color(hex: 0x2B1610)
    static let accentContainer = Color(hex: 0x3B2924)
    static let onAccentContainer = Color(hex: 0xFFD8C9)
    static let violet = Color(hex: 0xC9B6F2)
    static let violetContainer = Color(hex: 0x2C2640)
    static let teal = Color(hex: 0x8FD3C1)
    static let tealContainer = Color(hex: 0x1C312D)
    static let danger = Color(hex: 0xFF8F86)
    static let white12 = Color.white.opacity(0.2)
}

extension Color {
    init(hex: UInt32, alpha: Double = 1) {
        self.init(.sRGB, red: Double((hex >> 16) & 0xff) / 255, green: Double((hex >> 8) & 0xff) / 255,
                  blue: Double(hex & 0xff) / 255, opacity: alpha)
    }
}

enum TextType {
    case display, headline, title, subtitle, bodyLarge, body, label, caption, overline

    var size: CGFloat {
        switch self {
        case .display: return 32
        case .headline: return 26
        case .title: return 18
        case .subtitle, .bodyLarge: return 16
        case .body, .label: return 14
        case .caption, .overline: return 12
        }
    }

    var medium: Bool {
        switch self {
        case .display, .headline, .title, .subtitle, .label, .overline: return true
        default: return false
        }
    }

    var tracking: CGFloat {
        switch self {
        case .label, .caption: return 0.01 * size
        case .overline: return 0.12 * size
        default: return 0
        }
    }
}

struct Styled: View {
    let value: String
    let type: TextType
    let color: Color

    init(_ value: String, _ type: TextType, _ color: Color = Palette.text) {
        self.value = value
        self.type = type
        self.color = color
    }

    var body: some View {
        Text(type == .overline ? value.uppercased() : value)
            .font(.system(size: type.size, weight: type.medium ? .medium : .regular))
            .tracking(type.tracking)
            .foregroundColor(color)
            .lineSpacing(type == .body || type == .bodyLarge ? 3 : 0)
            .fixedSize(horizontal: false, vertical: true)
    }
}

struct Serif: View {
    let value: String
    let size: CGFloat
    let color: Color
    var italic = false

    init(_ value: String, _ size: CGFloat, _ color: Color = Palette.text, italic: Bool = false) {
        self.value = value
        self.size = size
        self.color = color
        self.italic = italic
    }

    var body: some View {
        Text(value)
            .font(.system(size: size, design: .serif).italic(italic))
            .foregroundColor(color)
            .lineSpacing(4)
            .fixedSize(horizontal: false, vertical: true)
    }
}

extension Font {
    func italic(_ on: Bool) -> Font {
        on ? italic() : self
    }
}

/// An Android vector icon from the asset catalog, tinted.
struct Icon: View {
    let name: String
    var color: Color = Palette.text
    var size: CGFloat = 24

    var body: some View {
        Image(name)
            .renderingMode(.template)
            .resizable()
            .scaledToFit()
            .foregroundColor(color)
            .frame(width: size, height: size)
            .accessibilityHidden(true)
    }
}

/// A rounded square holding a centered icon.
struct IconTile: View {
    let icon: String
    let tint: Color
    let background: Color
    var size: CGFloat = 40

    var body: some View {
        RoundedRectangle(cornerRadius: size * 0.32, style: .continuous)
            .fill(background)
            .frame(width: size, height: size)
            .overlay(Icon(name: icon, color: tint, size: size * 0.5))
    }
}

/// A circular touch target with an icon and an accessible label.
struct IconButton: View {
    let icon: String
    let label: String
    var tint: Color = Palette.text
    var background: Color?
    var size: CGFloat = 48
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                Circle().fill(background ?? Color.clear)
                Icon(name: icon, color: tint, size: 24)
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
        }
        .buttonStyle(PressStyle(shape: ErasedShape(Circle())))
        .accessibilityLabel(label)
    }
}

/// Darkens or lightens a control while pressed, like Android's ripple.
struct PressStyle: ButtonStyle {
    var shape = ErasedShape(RoundedRectangle(cornerRadius: 20, style: .continuous))
    var tint = Color.white.opacity(0.13)

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .overlay(shape.fill(configuration.isPressed ? tint : Color.clear))
    }
}

struct ErasedShape: Shape {
    private let path: (CGRect) -> Path

    init<S: Shape>(_ shape: S) {
        path = { shape.path(in: $0) }
    }

    func path(in rect: CGRect) -> Path {
        path(rect)
    }
}

enum ButtonKind {
    case primary, tonal, outline, text, danger
}

struct MemoButton: View {
    let label: String
    var icon: String?
    var kind: ButtonKind = .primary
    var fill = false
    var height: CGFloat = 52
    let action: (() -> Void)?

    init(_ label: String, icon: String? = nil, kind: ButtonKind = .primary, fill: Bool = false,
         height: CGFloat = 52, action: (() -> Void)?) {
        self.label = label
        self.icon = icon
        self.kind = kind
        self.fill = fill
        self.height = height
        self.action = action
    }

    private var content: Color {
        switch kind {
        case .primary: return Palette.onAccent
        case .tonal, .outline: return Palette.text
        case .danger: return Palette.danger
        case .text: return Palette.accent
        }
    }

    var body: some View {
        Button(action: { action?() }) {
            HStack(spacing: 8) {
                if let icon {
                    Icon(name: icon, color: content, size: 20)
                }
                Text(label)
                    .font(.system(size: 15, weight: .medium))
                    .tracking(0.15)
                    .foregroundColor(content)
                    .lineLimit(1)
                    .truncationMode(.tail)
            }
            .padding(.leading, kind == .text ? 12 : 20)
            .padding(.trailing, kind == .text ? 12 : 22)
            .frame(minHeight: height)
            .frame(maxWidth: fill ? .infinity : nil)
            .background(background)
            .contentShape(Capsule())
        }
        .buttonStyle(PressStyle(shape: ErasedShape(Capsule()),
                                tint: kind == .primary ? Color.black.opacity(0.2) : Color.white.opacity(0.18)))
        .disabled(action == nil)
        .accessibilityLabel(label)
    }

    @ViewBuilder private var background: some View {
        switch kind {
        case .primary: Capsule().fill(Palette.accent)
        case .tonal: Capsule().fill(Palette.surfaceHighest)
        case .outline: Capsule().strokeBorder(Palette.outline, lineWidth: 1)
        case .danger: Capsule().strokeBorder(Palette.danger.opacity(0.4), lineWidth: 1)
        case .text: Color.clear
        }
    }
}

struct Chip: View {
    let label: String
    var icon: String?
    let foreground: Color
    let background: Color

    var body: some View {
        HStack(spacing: 6) {
            if let icon {
                Icon(name: icon, color: foreground, size: 16)
            }
            Text(label)
                .font(.system(size: icon == nil ? 12 : 13, weight: .medium))
                .tracking(0.12)
                .foregroundColor(foreground)
                .lineLimit(1)
        }
        .padding(.leading, 10)
        .padding(.trailing, icon == nil ? 10 : 12)
        .padding(.vertical, icon == nil ? 5 : 6)
        .background(Capsule().fill(background))
    }
}

/// A rounded container; tappable when an action is supplied.
struct Card<Content: View>: View {
    var color: Color = Palette.surface
    var stroke: Color = Palette.outlineSoft
    var padding = EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)
    var action: (() -> Void)?
    @ViewBuilder let content: () -> Content

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: 20, style: .continuous)
        let card = VStack(alignment: .leading, spacing: 0, content: content)
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(shape.fill(color))
            .overlay(shape.strokeBorder(stroke, lineWidth: 1))
            .clipShape(shape)
        if let action {
            Button(action: action) {
                card.contentShape(shape)
            }
            .buttonStyle(PressStyle())
        } else {
            card
        }
    }
}

/// A settings-style row: icon tile, title and value, and a chevron or another trailing view.
struct ListRow<Trailing: View>: View {
    let icon: String
    let title: String
    var value: String?
    var action: (() -> Void)?
    @ViewBuilder var trailing: () -> Trailing

    var body: some View {
        let row = HStack(spacing: 0) {
            IconTile(icon: icon, tint: Palette.accent, background: Palette.surfaceHighest, size: 40)
            VStack(alignment: .leading, spacing: 2) {
                Styled(title, .subtitle)
                if let value, !value.isEmpty {
                    Styled(value, .body, Palette.textSecondary)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.leading, 14)
            .padding(.trailing, 8)
            trailing()
        }
        .padding(EdgeInsets(top: 12, leading: 16, bottom: 12, trailing: 12))
        .frame(minHeight: 64)
        .contentShape(Rectangle())
        if let action {
            Button(action: action) {
                row
            }
            .buttonStyle(PressStyle(shape: ErasedShape(Rectangle()), tint: Color.white.opacity(0.12)))
        } else {
            row
        }
    }
}

extension ListRow where Trailing == AnyView {
    init(icon: String, title: String, value: String?, action: (() -> Void)?) {
        self.icon = icon
        self.title = title
        self.value = value
        self.action = action
        let tappable = action != nil
        trailing = {
            AnyView(Group {
                if tappable {
                    Icon(name: "ic_chevron_right", color: Palette.textTertiary, size: 22)
                }
            })
        }
    }
}

/// A compact on/off switch drawn to match the theme.
struct ToggleKnob: View {
    let on: Bool

    var body: some View {
        ZStack(alignment: on ? .trailing : .leading) {
            Capsule()
                .fill(on ? Palette.accent : Palette.surfaceHighest)
                .overlay(Capsule().strokeBorder(on ? Color.clear : Palette.outline, lineWidth: 1))
            Circle()
                .fill(on ? Palette.onAccent : Palette.textSecondary)
                .frame(width: 20, height: 20)
                .padding(.horizontal, 4)
        }
        .frame(width: 48, height: 28)
        .accessibilityHidden(true)
    }
}

/// A small "Premium" badge with a lock, used on features the free plan cannot open.
struct PremiumBadge: View {
    var body: some View {
        Chip(label: L("premium_tab"), icon: "ic_lock", foreground: Palette.onAccentContainer,
             background: Palette.accentContainer)
            .accessibilityHidden(true)
    }
}

struct RowDivider: View {
    var inset: CGFloat = 70

    var body: some View {
        Rectangle()
            .fill(Palette.outlineSoft)
            .frame(height: 1)
            .padding(.leading, inset)
    }
}

/// A thin horizontal progress indicator.
struct ProgressLine: View {
    let done: Int
    let total: Int
    var height: CGFloat = 4

    var body: some View {
        GeometryReader { geometry in
            let share = CGFloat(max(0, min(done, max(1, total)))) / CGFloat(max(1, total))
            ZStack(alignment: .leading) {
                Capsule().fill(Palette.surfaceHighest)
                Capsule().fill(Palette.accent).frame(width: geometry.size.width * share)
            }
        }
        .frame(height: height)
    }
}

/// The hero background of the practice options and Premium screens.
struct HeroWash: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 28, style: .continuous)
            .fill(LinearGradient(colors: [Color(hex: 0x4A3036), Color(hex: 0x2A2236), Palette.surface],
                                 startPoint: .topLeading, endPoint: .bottomTrailing))
            .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous)
                .strokeBorder(Palette.accent.opacity(0.2), lineWidth: 1))
    }
}

/// A text field with the app's filled look and an accent outline while editing.
struct MemoField: View {
    let hint: String
    @Binding var text: String
    var multiline = false
    var minLines = 1
    var serif = false
    var limit = Int.max
    var capitalization: TextInputAutocapitalization = .sentences
    var keyboard: UIKeyboardType = .default
    var error: String?
    @FocusState private var focused: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Group {
                if multiline {
                    TextField(hint, text: limited, axis: .vertical)
                        .lineLimit(minLines...)
                } else {
                    TextField(hint, text: limited)
                }
            }
            .font(serif ? .system(size: 16, design: .serif) : .system(size: 16))
            .foregroundColor(Palette.text)
            .tint(Palette.accent)
            .textInputAutocapitalization(capitalization)
            .keyboardType(keyboard)
            .focused($focused)
            .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
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
            text = (value as NSString).length > limit ? (value as NSString).substring(to: limit) : value
        })
    }
}

extension View {
    /// Fills the screen with the app background behind any content.
    func memoBackground() -> some View {
        background(Palette.background.ignoresSafeArea())
    }
}
