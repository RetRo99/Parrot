package com.retro99.reader.ui.tts

import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * sherpa-onnx's native generation code looks the callback's method up by exact signature,
 * `invoke([F)Ljava/lang/Integer;`. A callback class that only carries the erased
 * `invoke(Object)Object` makes the native lookup fail and ART aborts the process (TTS-F22).
 */
class NeuralGenerationCallbackTest {

    @Test
    fun `callback class declares the specialised invoke the native code looks up`() {
        // Given
        val callback = neuralGenerationCallback { false }

        // When
        val invoke = callback.javaClass.declaredMethods.singleOrNull { method ->
            method.name == "invoke" &&
                    method.parameterTypes.contentEquals(arrayOf(FloatArray::class.java))
        }

        // Then
        assertTrue(
            invoke != null,
            "no invoke(float[]) declared on ${callback.javaClass.name}; " +
                    "declared methods: " +
                    callback.javaClass.declaredMethods.joinToString { method ->
                        "${method.name}(${method.parameterTypes.joinToString { it.simpleName }})" +
                                ": ${method.returnType.name}"
                    },
        )
        assertEquals(Int::class.javaObjectType, invoke.returnType, "invoke(float[]) return type")
        assertTrue(Modifier.isPublic(invoke.modifiers), "invoke(float[]) must be public")
    }

    @Test
    fun `callback returns 1 to abort when cancelled`() {
        // Given
        val callback = neuralGenerationCallback { true }

        // When
        val result = callback(FloatArray(4))

        // Then
        assertEquals(1, result)
    }

    @Test
    fun `callback returns 0 to continue when not cancelled`() {
        // Given
        val callback = neuralGenerationCallback { false }

        // When
        val result = callback(FloatArray(4))

        // Then
        assertEquals(0, result)
    }
}
