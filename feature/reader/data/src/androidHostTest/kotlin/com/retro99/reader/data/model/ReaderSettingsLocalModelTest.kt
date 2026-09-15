package com.retro99.reader.data.model

import com.retro99.reader.domain.model.ReaderSettingsDomainModel
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.test.Test
import kotlin.test.assertEquals

@RunWith(Parameterized::class)
class ReaderSettingsLocalModelTest(
    private val domainModel: ReaderSettingsDomainModel,
) {

    @Test
    fun `TTS settings survive local model round trip`() {
        // Given
        val expected = domainModel

        // When
        val result = domainModel.toLocal().toDomain()

        // Then
        assertEquals(expected.ttsVoiceId, result.ttsVoiceId)
        assertEquals(expected.ttsRate, result.ttsRate)
        assertEquals(expected.ttsPitch, result.ttsPitch)
        assertEquals(expected.ttsEnabled, result.ttsEnabled)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{index}: {0}")
        fun params(): List<Array<ReaderSettingsDomainModel>> = listOf(
            arrayOf(ReaderSettingsDomainModel()),
            arrayOf(
                ReaderSettingsDomainModel(
                    ttsVoiceId = "system-voice",
                    ttsRate = 0.75f,
                    ttsPitch = 1.25f,
                    ttsEnabled = true,
                ),
            ),
            arrayOf(
                ReaderSettingsDomainModel(
                    ttsVoiceId = "kokoro:3",
                    ttsRate = 1.5f,
                    ttsPitch = 0.8f,
                    ttsEnabled = true,
                ),
            ),
        )
    }
}
