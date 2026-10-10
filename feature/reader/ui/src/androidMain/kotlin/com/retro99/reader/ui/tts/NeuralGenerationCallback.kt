package com.retro99.reader.ui.tts

/**
 * The progress callback handed to sherpa-onnx generation calls. Returns 1 to abort the
 * native generation, 0 to let it continue.
 *
 * This must stay an explicit `object`, never a lambda. sherpa-onnx's native code looks the
 * callback's method up by exact signature, `invoke([F)Ljava/lang/Integer;`. Kotlin compiles
 * a lambda to a synthetic class carrying only the erased `invoke(Object)Object`, so the
 * native lookup fails and ART kills the process on every synthesis (TTS-F22). An explicit
 * object declares the specialised method. `NeuralGenerationCallbackTest` guards this.
 */
internal fun neuralGenerationCallback(shouldCancel: () -> Boolean): (FloatArray) -> Int =
    object : (FloatArray) -> Int {
        override fun invoke(samples: FloatArray): Int = if (shouldCancel()) 1 else 0
    }
