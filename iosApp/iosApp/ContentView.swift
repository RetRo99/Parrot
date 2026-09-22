import UIKit
import SwiftUI
import ComposeApp
import Network

private final class SyncConnectivityObserver {
    private let monitor = NWPathMonitor()
    private let queue = DispatchQueue(label: "com.retro99.parrot.connectivity")
    private var previousStatus: NWPath.Status?
    private var isStarted = false

    init() {
        monitor.pathUpdateHandler = { [weak self] path in
            let wasDisconnected = self?.previousStatus.map { $0 != .satisfied } ?? false
            self?.previousStatus = path.status
            if wasDisconnected && path.status == .satisfied {
                DispatchQueue.main.async {
                    SyncTriggerBridge.shared.onConnectivityRestored()
                }
            }
        }
    }

    func start() {
        guard !isStarted else { return }
        isStarted = true
        monitor.start(queue: queue)
    }

    deinit {
        monitor.cancel()
    }
}

struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @State private var connectivityObserver = SyncConnectivityObserver()
    @State private var didRequestStartupSync = false

    var body: some View {
        ComposeView()
            .ignoresSafeArea()
            .onAppear {
                connectivityObserver.start()
                if !didRequestStartupSync {
                    didRequestStartupSync = true
                    SyncTriggerBridge.shared.onStartup()
                }
            }
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
                if newPhase == .active && oldPhase != .active {
                    SyncTriggerBridge.shared.onForeground()
                }
                if oldPhase == .background && newPhase == .active {
                    _ = CloudOAuthCallbackBridge.shared.cancelPending(
                        message: "Google sign-in was cancelled"
                    )
                }
            }
    }
}
