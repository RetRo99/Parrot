package com.retro99.reader.ui.tts

/**
 * The progress callback handed to sherpa-onnx generation calls. Returns 1 to abort the
 * native generation, 0 to let it continue.
 */
internal fun neuralGenerationCallback(shouldCancel: () -> Boolean): (FloatArray) -> Int = {
    if (shouldCancel()) 1 else 0
}
