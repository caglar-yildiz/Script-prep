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
        }
    }
}

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
