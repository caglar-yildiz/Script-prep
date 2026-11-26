import SwiftUI
import UIKit

@main
struct MemoEverythingApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @StateObject private var model = AppModelHolder.shared

    var body: some Scene {
        WindowGroup {
            RootView()
                .environmentObject(model)
                .environment(\.locale, Strings.locale)
                .onAppear(perform: openDemoScreen)
        }
    }
}

#if DEBUG
/// Screenshots on CI: `-demo setup|rehearsal|settings|premium|editor` opens that screen with the sample.
private func openDemoScreen() {
    let arguments = ProcessInfo.processInfo.arguments
    guard let index = arguments.firstIndex(of: "-demo"), index + 1 < arguments.count else {
        return
    }
    let model = AppModelHolder.shared
    switch arguments[index + 1] {
    case "setup":
        model.openSample()
    case "rehearsal":
        model.openSample()
        model.startRehearsal()
    case "settings":
        model.show(.settings)
    case "premium":
        model.show(.settings)
        model.openPremium()
    case "editor":
        model.category = .roleRehearsal
        model.openEditor(id: nil, title: L("sample_title"), text: L("sample_script"))
    case "categories":
        model.show(.categories)
    default:
        break
    }
}
#else
private func openDemoScreen() {}
#endif

final class AppDelegate: NSObject, UIApplicationDelegate {
    func application(_ application: UIApplication,
                     didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil) -> Bool {
        // Ensemble rehearsal needs the Firebase project file; without it the feature stays hidden.
        Ensemble.configure()
        AudioSessions.usePlayback()
        return true
    }

    /// The AI voice download finished while the app was closed.
    func application(_ application: UIApplication, handleEventsForBackgroundURLSession identifier: String,
                     completionHandler: @escaping () -> Void) {
        if identifier == NeuralVoice.sessionIdentifier {
            NeuralVoice.shared.backgroundCompletion = completionHandler
        } else {
            completionHandler()
        }
    }
}
