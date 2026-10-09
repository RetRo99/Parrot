# sherpa-onnx's native generation code looks the cancel callback's method up by exact
# signature, invoke([F)Ljava/lang/Integer; (TTS-F22). No Kotlin call site uses that
# specialised overload directly — callers only see Function1 — so R8 would be free to
# rename it, which would bring the JNI NoSuchMethodError back in release builds only.
# The class itself may still be renamed and repackaged; only the method name matters.
-keepclassmembers class com.retro99.reader.ui.tts.NeuralGenerationCallbackKt$neuralGenerationCallback$1 {
    java.lang.Integer invoke(float[]);
}
