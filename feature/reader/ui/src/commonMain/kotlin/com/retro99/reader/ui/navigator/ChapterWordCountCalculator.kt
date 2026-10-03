package com.retro99.reader.ui.navigator

import com.retro99.analytics.api.Analytics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * Shared utility for calculating the word count of the current chapter.
 *
 * Uses JavaScript to count words in the chapter's text content.
 * This is used to estimate reading time for the current chapter.
 */
object ChapterWordCountCalculator : KoinComponent {

    private val analytics: Analytics by inject<Analytics>()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * JavaScript to count words in the current chapter.
     *
     * Readium renders EPUB content inside an iframe. The JavaScript is executed
     * inside that iframe context, so we're already in the content document.
     *
     * This script:
     * 1. Gets all text content from the document body
     * 2. Counts words by splitting on whitespace
     * 3. Returns the total word count
     */
    private const val WORD_COUNT_JS = """
        (function() {
            try {
                const body = document.body;
                if (!body) {
                    return JSON.stringify({ status: 'error', message: 'No body element' });
                }
                
                // innerText is the rendered text and already excludes script, style and
                // hidden elements. The old textContent fallback counted them and inflated
                // the chapter's word count; only use it when innerText is unavailable.
                let textContent = body.innerText;
                if (textContent == null) {
                    const clone = body.cloneNode(true);
                    clone.querySelectorAll('script,style,noscript,template,svg').forEach(function(node) {
                        node.remove();
                    });
                    textContent = clone.textContent || '';
                }
                
                // Split by whitespace and filter out empty strings
                const words = textContent.trim().split(/\s+/).filter(function(word) {
                    return word.length > 0;
                });
                
                const wordCount = words.length;
                
                return JSON.stringify({
                    status: 'success',
                    wordCount: wordCount
                });
            } catch (e) {
                return JSON.stringify({ status: 'error', message: e.message });
            }
        })()
    """

    /**
     * Gets the JavaScript for calculating the chapter word count.
     *
     * @return The JavaScript code ready to execute
     */
    fun getWordCountScript(): String = WORD_COUNT_JS

    /**
     * Parses the JSON result from the word count JavaScript.
     *
     * @param json The raw JSON string from JavaScript evaluation
     * @return The word count, or null on error
     */
    fun parseWordCountResult(json: String): Int? {
        return try {
            parseWordCountResultInternal(json)
        } catch (e: Exception) {
            analytics.logException(e, "Failed to parse word count result, json: $json")
            null
        }
    }

    private fun parseWordCountResultInternal(json: String): Int? {
        val data = jsonParser.decodeFromString<WordCountResult>(json)

        if (data.status != "success") {
            return null
        }

        return data.wordCount
    }
}

/**
 * Data class for parsing the word count JavaScript result.
 */
@Serializable
internal data class WordCountResult(
    @SerialName("status")
    val status: String,
    @SerialName("wordCount")
    val wordCount: Int? = null,
    @SerialName("message")
    val message: String? = null,
)

