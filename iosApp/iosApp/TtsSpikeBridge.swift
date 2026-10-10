import ComposeApp
import Foundation

/// Swift side of the throwaway TTS spike: implements the Kotlin interface declared in
/// IosTtsSpike.kt and forwards to the sherpa-onnx C API (ParrotTtsSpike).
final class TtsSpikeBridgeImpl: IosTtsSpikeBridge {

    private let engine = ParrotTtsSpike()

    func loadEngine(modelDir: String, onError: @escaping (String) -> Void) -> String? {
        do {
            return try engine.loadEngine(modelDir: modelDir)
        } catch {
            onError(error.localizedDescription)
            return nil
        }
    }

    func synthesize(
        text: String,
        speakerId: Int32,
        speed: Float,
        outputPath: String,
        onError: @escaping (String) -> Void
    ) -> String? {
        do {
            return try engine.synthesize(
                text: text,
                speakerId: speakerId,
                speed: speed,
                outputPath: outputPath
            )
        } catch {
            onError(error.localizedDescription)
            return nil
        }
    }

    func peakMemoryBytes() -> Int64 {
        engine.peakMemoryBytes()
    }

    func probeQueuePlayback(wavPaths: [String]) -> String {
        engine.probeQueuePlayback(wavPaths: wavPaths)
    }

    func pitchCapabilities() -> String {
        engine.pitchCapabilities()
    }
}

enum TtsSpikeRunner {
    private static var didRun = false

    /// Registers the spike bridge and, when the model directory exists, runs the spike
    /// once and writes the report to Documents/spike-report.txt.
    static func registerAndMaybeRun() {
        IosTtsSpikeBridgeRegistry.shared.register(bridge: TtsSpikeBridgeImpl())
        guard !didRun else { return }
        didRun = true

        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask).first!
        let modelDir = docs.appendingPathComponent("kokoro").path
        guard FileManager.default.fileExists(atPath: modelDir) else {
            NSLog("TtsSpike: no model dir at %@ — skipping spike run", modelDir)
            return
        }

        NSLog("TtsSpike: starting spike run, modelDir=%@", modelDir)
        DispatchQueue.global(qos: .userInitiated).async {
            IosTtsSpike.shared.run(modelDir: modelDir) { result, error in
                let report: String
                if let error {
                    report = "spike run failed: \(error.localizedDescription)"
                } else {
                    report = result ?? "no report"
                }
                NSLog("TtsSpike report:\n%@", report)
                try? report.write(
                    to: docs.appendingPathComponent("spike-report.txt"),
                    atomically: true,
                    encoding: .utf8
                )
            }
        }
    }
}
