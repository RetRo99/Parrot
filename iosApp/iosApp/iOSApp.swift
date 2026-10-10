import SwiftUI
import FirebaseCore
import ComposeApp
import BackgroundTasks

private let syncRecoveryTaskIdentifier = "com.retro99.parrot.sync-recovery"

private final class SyncRecoveryTaskCompletion {
    private let task: BGProcessingTask
    private let lock = NSLock()
    private var hasCompleted = false

    init(task: BGProcessingTask) {
        self.task = task
    }

    func complete(success: Bool) {
        lock.lock()
        guard !hasCompleted else {
            lock.unlock()
            return
        }
        hasCompleted = true
        lock.unlock()
        task.setTaskCompleted(success: success)
    }
}

class AppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        FirebaseApp.configure()

        registerSyncRecoveryTask()
        scheduleSyncRecovery()

        // Register the EPUB reader bridge for iOS Readium integration
        EpubReaderBridgeRegistry.shared.register(bridge: ReadiumEpubReaderBridge())
        EpubMetadataBridgeRegistry.shared.register(bridge: ReadiumEpubMetadataBridge())

        // Throwaway TTS spike: registers the bridge and runs once when a Kokoro model
        // directory exists in Documents/kokoro. Not wired into any product UI.
        TtsSpikeRunner.registerAndMaybeRun()

        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        scheduleSyncRecovery()
    }

    private func registerSyncRecoveryTask() {
        BGTaskScheduler.shared.register(
            forTaskWithIdentifier: syncRecoveryTaskIdentifier,
            using: nil,
        ) { task in
            guard let processingTask = task as? BGProcessingTask else {
                task.setTaskCompleted(success: false)
                return
            }
            self.handleSyncRecovery(processingTask)
        }
    }

    private func handleSyncRecovery(_ task: BGProcessingTask) {
        scheduleSyncRecovery()
        let completion = SyncRecoveryTaskCompletion(task: task)
        task.expirationHandler = {
            SyncTriggerBridge.shared.cancelRecovery()
            completion.complete(success: false)
        }
        SyncTriggerBridge.shared.onRecovery { success in
            completion.complete(success: success.boolValue)
        }
    }

    private func scheduleSyncRecovery() {
        let request = BGProcessingTaskRequest(identifier: syncRecoveryTaskIdentifier)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false
        try? BGTaskScheduler.shared.submit(request)
    }
}

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var delegate

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
