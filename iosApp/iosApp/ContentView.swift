import UIKit
import SwiftUI
import ComposeApp

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .onOpenURL { url in
                let uri = url.absoluteString
                let handledByStorytellerOAuth = StorytellerOAuthCallbackRegistry.shared.handleRedirect(
                    uri: uri
                )
                if !handledByStorytellerOAuth {
                    _ = CloudOAuthCallbackBridge.shared.handleRedirect(uri: uri)
                }
            }
            .onChange(of: scenePhase) { oldPhase, newPhase in
                if oldPhase == .background && newPhase == .active {
                    _ = CloudOAuthCallbackBridge.shared.cancelPending(
                        message: "Google sign-in was cancelled"
                    )
                }
            }
    }
}
