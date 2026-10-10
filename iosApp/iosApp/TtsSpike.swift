import AVFoundation
import Foundation
import SherpaOnnxC

/// Throwaway spike: proves sherpa-onnx Kokoro synthesis and AVQueuePlayer behaviour.
///
/// Everything here is driven from Kotlin (IosTtsSpike.kt in the ComposeApp framework);
/// nothing is wired into product UI.
@objc(ParrotTtsSpike)
final class ParrotTtsSpike: NSObject {

    private var tts: OpaquePointer?
    private var sampleRate: Int = 0

    // MARK: - Synthesis

    /// Loads the Kokoro engine from an unpacked pack directory and synthesises a
    /// warm-up string so later timings do not pay graph initialisation.
    /// Returns "sampleRate=<r> speakers=<n>" or throws.
    @objc func loadEngine(modelDir: String) throws -> String {
        let model = (modelDir as NSString).appendingPathComponent("model.int8.onnx")
        let voices = (modelDir as NSString).appendingPathComponent("voices.bin")
        let tokens = (modelDir as NSString).appendingPathComponent("tokens.txt")
        let dataDir = (modelDir as NSString).appendingPathComponent("espeak-ng-data")

        var config = SherpaOnnxOfflineTtsConfig()
        memset(&config, 0, MemoryLayout<SherpaOnnxOfflineTtsConfig>.size)
        config.model.kokoro.model = UnsafePointer(strdup(model))
        config.model.kokoro.voices = UnsafePointer(strdup(voices))
        config.model.kokoro.tokens = UnsafePointer(strdup(tokens))
        config.model.kokoro.data_dir = UnsafePointer(strdup(dataDir))
        config.model.num_threads = 4
        config.model.debug = 0
        config.model.provider = UnsafePointer(strdup("cpu"))
        config.max_num_sentences = 2

        guard let engine = SherpaOnnxCreateOfflineTts(&config) else {
            throw NSError(domain: "TtsSpike", code: 1, userInfo: [
                NSLocalizedDescriptionKey: "SherpaOnnxCreateOfflineTts returned nil",
            ])
        }
        tts = engine
        sampleRate = Int(SherpaOnnxOfflineTtsSampleRate(engine))
        let speakers = Int(SherpaOnnxOfflineTtsNumSpeakers(engine))

        // Warm up the runtime (mirrors SherpaOnnxSynthesizer.kt WARMUP_TEXT).
        if let audio = SherpaOnnxOfflineTtsGenerate(engine, "a", 0, 1.0) {
            SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio)
        }
        return "sampleRate=\(sampleRate) speakers=\(speakers)"
    }

    /// Synthesises `text` and writes a canonical WAV to `outputPath`.
    /// Returns a stats string; throws on failure.
    @objc func synthesize(
        text: String,
        speakerId: Int32,
        speed: Float,
        outputPath: String
    ) throws -> String {
        guard let engine = tts else {
            throw NSError(domain: "TtsSpike", code: 2, userInfo: [
                NSLocalizedDescriptionKey: "engine not loaded",
            ])
        }
        guard let audio = SherpaOnnxOfflineTtsGenerate(engine, text, speakerId, speed) else {
            throw NSError(domain: "TtsSpike", code: 3, userInfo: [
                NSLocalizedDescriptionKey: "generate returned nil",
            ])
        }
        defer { SherpaOnnxDestroyOfflineTtsGeneratedAudio(audio) }

        let n = Int(audio.pointee.n)
        let rate = Int(audio.pointee.sample_rate)
        var samples = [Float](repeating: 0, count: n)
        samples.withUnsafeMutableBufferPointer { buf in
            if let base = buf.baseAddress {
                base.assign(from: audio.pointee.samples, count: n)
            }
        }

        // Silence check: peak amplitude and RMS over the whole buffer.
        var peak: Float = 0
        var sumSquares: Double = 0
        for s in samples {
            let a = abs(s)
            if a > peak { peak = a }
            sumSquares += Double(s) * Double(s)
        }
        let rms = n > 0 ? sqrt(sumSquares / Double(n)) : 0

        let ok = SherpaOnnxWriteWave(samples, Int32(n), Int32(rate), outputPath)
        guard ok == 1 else {
            throw NSError(domain: "TtsSpike", code: 4, userInfo: [
                NSLocalizedDescriptionKey: "SherpaOnnxWriteWave failed",
            ])
        }
        let durationSec = rate > 0 ? Double(n) / Double(rate) : 0
        return "n=\(n) sampleRate=\(rate) durationSec=\(String(format: "%.3f", durationSec)) "
            + "peak=\(String(format: "%.4f", peak)) rms=\(String(format: "%.4f", rms))"
    }

    /// Peak resident memory of this process, in bytes (mach task_info).
    @objc func peakMemoryBytes() -> Int64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(
            MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<integer_t>.size
        )
        let kerr: kern_return_t = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) {
                task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count)
            }
        }
        guard kerr == KERN_SUCCESS else { return -1 }
        return Int64(info.phys_footprint)
    }

    // MARK: - AVQueuePlayer probes

    /// Plays a list of WAVs through AVQueuePlayer and reports timing / transition facts.
    /// Returns a report string once every item has finished (or failed).
    @objc func probeQueuePlayback(wavPaths: [String]) -> String {
        var report: [String] = []
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .default, options: [])
            try session.setActive(true)
            report.append("session: category=playback active=yes")
        } catch {
            report.append("session: FAILED \(error.localizedDescription)")
        }

        let items = wavPaths.map { AVPlayerItem(url: URL(fileURLWithPath: $0)) }
        let player = AVQueuePlayer(items: items)

        // Expected per-item durations from the asset itself.
        for (index, item) in items.enumerated() {
            let d = item.asset.duration
            report.append("item\(index) duration=\(String(format: "%.3f", CMTimeGetSeconds(d)))s")
        }
        report.append("route: \(session.currentRoute.outputs.map { $0.portType.rawValue }.joined(separator: ","))")

        var transitionKinds: [String] = []
        var transitionTimes: [TimeInterval] = []
        var observers: [NSKeyValueObservation] = []
        let start = CACurrentMediaTime()
        let lock = NSLock()

        func record(_ label: String) {
            lock.lock()
            transitionKinds.append(label)
            transitionTimes.append(CACurrentMediaTime() - start)
            lock.unlock()
        }

        for (index, item) in items.enumerated() {
            observers.append(item.observe(\.status, options: [.new]) { observed, _ in
                if observed.status == .failed {
                    record("item\(index):failed(\(observed.error?.localizedDescription ?? "?"))")
                }
            })
        }
        observers.append(player.observe(\.currentItem, options: [.initial, .new]) { observed, _ in
            if let item = observed.currentItem, let idx = items.firstIndex(of: item) {
                record("currentItem->item\(idx)")
            }
        })

        let endExpectation = ProbeExpectation()
        var endObserver: NSObjectProtocol?
        endObserver = NotificationCenter.default.addObserver(
            forName: .AVPlayerItemDidPlayToEndTime,
            object: items.last,
            queue: nil
        ) { _ in
            record("lastItemEnded")
            endExpectation.fulfill()
        }

        // Periodic time observer: ground truth on whether playhead actually advances.
        var playheadSamples: [String] = []
        let timeObserver = player.addPeriodicTimeObserver(
            forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
            queue: nil
        ) { t in
            let cur = CMTimeGetSeconds(t)
            let idx = player.currentItem.flatMap { items.firstIndex(of: $0) } ?? -1
            playheadSamples.append("i\(idx)@\(String(format: "%.2f", cur))")
        }

        player.play()
        report.append("rate after play(): \(player.rate)")

        // Diagnostic: does a plain single-file AVPlayer advance on this target?
        let single = AVPlayer(url: URL(fileURLWithPath: wavPaths[0]))
        single.play()
        var singlePos: [String] = []
        for _ in 0..<8 {
            Thread.sleep(forTimeInterval: 0.5)
            singlePos.append(String(format: "%.2f", CMTimeGetSeconds(single.currentTime())))
        }
        let singleAdvanced = CMTimeGetSeconds(single.currentTime()) > 0.5
        single.pause()
        report.append("single AVPlayer positions over 4s: \(singlePos.joined(separator: " ")) advanced=\(singleAdvanced)")

        let finished = endExpectation.wait(timeout: 30)
        let elapsed = CACurrentMediaTime() - start
        player.pause()
        player.removeTimeObserver(timeObserver)
        if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
        observers.forEach { $0.invalidate() }

        report.append("transitions: \(transitionKinds.joined(separator: " -> "))")
        report.append("playhead samples (first 12): \(playheadSamples.prefix(12).joined(separator: " "))")
        report.append("playhead samples (last 6): \(playheadSamples.suffix(6).joined(separator: " "))")
        report.append("elapsed: \(String(format: "%.2f", elapsed))s finished: \(finished)")
        return report.joined(separator: "\n")
    }

    /// Does pitch-without-rate exist for file playback?
    /// AVPlayer has no pitch control; AVAudioUnitTimePitch is the answer — report what iOS offers.
    @objc func pitchCapabilities() -> String {
        var lines: [String] = []
        // AVPlayer.rate changes pitch with it unless audioTimePitchAlgorithm is set.
        lines.append("AVPlayerItem.audioTimePitchAlgorithm values: low, medium, high (set per item); rate alone pitch-shifts unless set")
        lines.append("AVAudioUnitTimePitch available: pitch (cents) + rate are independent parameters")
        return lines.joined(separator: "\n")
    }
}

/// Minimal expectation so the spike does not need XCTest.
private final class ProbeExpectation {
    private let semaphore = DispatchSemaphore(value: 0)
    private var fulfilled = false
    private let lock = NSLock()

    func fulfill() {
        lock.lock()
        defer { lock.unlock() }
        if !fulfilled {
            fulfilled = true
            semaphore.signal()
        }
    }

    func wait(timeout: TimeInterval) -> Bool {
        semaphore.wait(timeout: .now() + timeout) == .success
    }
}
