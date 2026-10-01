package com.retro99.reader.ui.navigator

import com.retro99.analytics.api.Analytics
import com.retro99.reader.ui.tts.TtsSentence
import com.retro99.server.api.TextAnchor
import com.retro99.reader.ui.tts.TtsSentenceChunker
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

object ChapterSentenceExtractor : KoinComponent {

    private val analytics: Analytics by inject<Analytics>()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    private const val READABLE_CONTENT_CHECK_JS = """
        (function() {
            const body = document.body;
            if (!body) return false;
            const text = (body.innerText || body.textContent || '')
                .replace(/\s+/g, ' ')
                .trim();
            return text.length > 0;
        })()
    """

    private const val SENTENCE_EXTRACTION_JS = """
        (function() {
            const sentenceSelector = '.parrot-sentence, [id*=".xhtml-sentence"]';
            try {
                if (!hasSentenceElements()) {
                    buildSentenceSpans();
                }
                return JSON.stringify({ status: 'success', sentences: collect() });
            } catch (e) {
                return JSON.stringify({ status: 'error', message: String(e) });
            }

            function hasSentenceElements() {
                return document.querySelectorAll(sentenceSelector).length > 0;
            }

            function collect() {
                const nodes = document.querySelectorAll(sentenceSelector);
                const result = [];
                for (let i = 0; i < nodes.length; i++) {
                    const el = nodes[i];
                    const id = el.id;
                    if (!id) continue;
                    if (el.querySelector(sentenceSelector)) continue;
                    const text = (el.textContent || '').replace(/\s+/g, ' ').trim();
                    if (text.length === 0) continue;
                    result.push({ id: id, t: encodeURIComponent(text) });
                }
                return result;
            }

            function buildSentenceSpans() {
                const body = document.body;
                if (!body) return;

                const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        if (!node.nodeValue || node.nodeValue.trim().length === 0) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        const parent = node.parentElement;
                        if (!parent) return NodeFilter.FILTER_REJECT;
                        const tag = parent.tagName;
                        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') {
                            return NodeFilter.FILTER_REJECT;
                        }
                        if (parent.closest(sentenceSelector)) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });

                const groups = [];
                let currentGroup = null;
                let current;
                while ((current = walker.nextNode())) {
                    const block = findTextBlock(current.parentElement, body);
                    if (!currentGroup || currentGroup.block !== block) {
                        currentGroup = { block: block, nodes: [] };
                        groups.push(currentGroup);
                    }
                    currentGroup.nodes.push(current);
                }

                const plans = [];
                let counter = 0;
                for (let i = 0; i < groups.length; i++) {
                    const group = groups[i];
                    const text = group.nodes.map(function(node) {
                        return node.nodeValue || '';
                    }).join('');
                    const sentences = splitSentences(text);
                    const segments = [];
                    for (let j = 0; j < sentences.length; j++) {
                        const sentence = sentences[j];
                        if (text.slice(sentence.start, sentence.end).trim().length === 0) continue;
                        segments.push({
                            start: sentence.start,
                            end: sentence.end,
                            id: 'parrot-sentence-' + (counter++)
                        });
                    }
                    if (segments.length > 0) {
                        plans.push({ nodes: group.nodes, segments: segments });
                    }
                }

                for (let i = plans.length - 1; i >= 0; i--) {
                    wrapGroupSentences(plans[i]);
                }
            }

            function findTextBlock(element, body) {
                if (!element) return body;
                const blockSelector =
                    'p,h1,h2,h3,h4,h5,h6,li,blockquote,figcaption,' +
                    'td,th,dt,dd,div,section,article';
                return element.closest(blockSelector) || body;
            }

            function wrapGroupSentences(plan) {
                for (let i = plan.segments.length - 1; i >= 0; i--) {
                    const segment = plan.segments[i];
                    const start = locateBoundary(plan.nodes, segment.start, false);
                    const end = locateBoundary(plan.nodes, segment.end, true);
                    if (!start || !end) continue;

                    const range = document.createRange();
                    range.setStart(start.node, start.offset);
                    range.setEnd(end.node, end.offset);
                    if (range.collapsed) continue;

                    const contents = range.extractContents();
                    const span = document.createElement('span');
                    span.className = 'parrot-sentence';
                    span.id = segment.id;
                    span.appendChild(contents);
                    range.insertNode(span);
                }
            }

            function locateBoundary(nodes, targetOffset, isEnd) {
                let consumed = 0;
                for (let i = 0; i < nodes.length; i++) {
                    const node = nodes[i];
                    const length = node.nodeValue ? node.nodeValue.length : 0;
                    const nodeEnd = consumed + length;
                    if (
                        targetOffset < nodeEnd ||
                        (isEnd && targetOffset === nodeEnd) ||
                        i === nodes.length - 1
                    ) {
                        return {
                            node: node,
                            offset: Math.min(Math.max(targetOffset - consumed, 0), length)
                        };
                    }
                    consumed = nodeEnd;
                }
                return null;
            }

            function splitSentences(text) {
                const result = [];
                let start = 0;
                let i = 0;
                while (i < text.length) {
                    const ch = text[i];
                    if (ch === '.' || ch === '!' || ch === '?' || ch === '\u2026') {
                        if (ch === '.' && isDecimal(text, i)) { i++; continue; }
                        if (ch === '.' && isAbbreviation(text, i)) { i++; continue; }

                        let end = i + 1;
                        while (end < text.length && isClosingQuote(text[end])) end++;

                        const next = text[end];
                        if (next === undefined || next === ' ' || next === '\n' || next === '\t') {
                            if (text.slice(start, end).trim().length > 0) {
                                result.push({ start: start, end: end });
                            }
                            start = end;
                            while (
                                start < text.length &&
                                (
                                    text[start] === ' ' ||
                                    text[start] === '\n' ||
                                    text[start] === '\t'
                                )
                            ) {
                                start++;
                            }
                            i = start;
                            continue;
                        }
                    }
                    i++;
                }
                if (start < text.length && text.slice(start).trim().length > 0) {
                    result.push({ start: start, end: text.length });
                }
                return result;
            }

            function isDecimal(text, index) {
                if (index <= 0 || index + 1 >= text.length) return false;
                return isDigit(text[index - 1]) && isDigit(text[index + 1]);
            }

            function isAbbreviation(text, index) {
                let begin = index - 1;
                while (
                    begin >= 0 &&
                    !/\s/.test(text[begin]) &&
                    '([{,"\''.indexOf(text[begin]) < 0
                ) {
                    begin--;
                }
                const rawToken = text.slice(begin + 1, index);
                if (rawToken.length === 0) return false;
                const token = rawToken.toLowerCase();
                const abbreviations = [
                    'mr','mrs','ms','dr','prof','sr','jr','st','vs','etc','no','vol','ch','pp',
                    'fig','inc','ltd','co','corp','dept','univ','approx','est','al','e.g','i.e',
                    'a.m','p.m','u.s','u.k'
                ];
                if (abbreviations.indexOf(token) >= 0) return true;
                if (rawToken.length <= 2 && rawToken === rawToken.toUpperCase()) return true;
                return false;
            }

            function isDigit(c) {
                return c >= '0' && c <= '9';
            }

            function isClosingQuote(c) {
                return c === '"' || c === '\u201d' || c === '\'' || c === '\u2019';
            }
        })()
    """

    /**
     * Returns the text before and after the start of the visible page, as
     * `{status, before, after}` with both texts URI-encoded. Used for the position's text anchor.
     */
    private const val TEXT_ANCHOR_JS = """
        (function() {
            try {
                const body = document.body;
                if (!body) return JSON.stringify({ status: 'error', message: 'no body' });
                const nodes = [];
                const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        if (!node.nodeValue || node.nodeValue.trim().length === 0) {
                            return NodeFilter.FILTER_REJECT;
                        }
                        const parent = node.parentElement;
                        if (!parent) return NodeFilter.FILTER_REJECT;
                        const tag = parent.tagName;
                        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') {
                            return NodeFilter.FILTER_REJECT;
                        }
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });
                let current;
                while ((current = walker.nextNode())) nodes.push(current);

                const width = window.innerWidth;
                const height = window.innerHeight;
                function isVisible(node, start, end) {
                    const range = document.createRange();
                    range.setStart(node, start);
                    range.setEnd(node, end);
                    const rects = range.getClientRects();
                    for (let i = 0; i < rects.length; i++) {
                        const rect = rects[i];
                        if (rect.width === 0 && rect.height === 0) continue;
                        if (rect.right > 0 && rect.left < width &&
                            rect.bottom > 0 && rect.top < height) {
                            return true;
                        }
                    }
                    return false;
                }
                function endsBefore(node, offset) {
                    const range = document.createRange();
                    range.setStart(node, offset);
                    range.setEnd(node, offset + 1);
                    const rect = range.getBoundingClientRect();
                    return rect.right <= 0 || rect.bottom <= 0;
                }

                let index = -1;
                let offset = 0;
                for (let i = 0; i < nodes.length; i++) {
                    const length = nodes[i].nodeValue.length;
                    if (!isVisible(nodes[i], 0, length)) continue;
                    index = i;
                    let low = 0;
                    let high = length - 1;
                    while (low < high) {
                        const middle = Math.floor((low + high) / 2);
                        if (endsBefore(nodes[i], middle)) low = middle + 1; else high = middle;
                    }
                    offset = low;
                    break;
                }
                if (index < 0) {
                    return JSON.stringify({ status: 'error', message: 'nothing visible' });
                }

                let before = nodes[index].nodeValue.slice(0, offset);
                for (let i = index - 1; i >= 0 && before.length < 400; i--) {
                    before = nodes[i].nodeValue + ' ' + before;
                }
                let after = nodes[index].nodeValue.slice(offset);
                for (let i = index + 1; i < nodes.length && after.length < 600; i++) {
                    after = after + ' ' + nodes[i].nodeValue;
                }
                return JSON.stringify({
                    status: 'success',
                    before: encodeURIComponent(before.slice(-400)),
                    after: encodeURIComponent(after.slice(0, 600))
                });
            } catch (e) {
                return JSON.stringify({ status: 'error', message: String(e) });
            }
        })()
    """

    fun getScript(): String = SENTENCE_EXTRACTION_JS.trimIndent()

    fun getTextAnchorScript(): String = TEXT_ANCHOR_JS.trimIndent()

    /** The anchor from [getTextAnchorScript]'s result, trimmed to 20 words before, 30 after. */
    fun parseTextAnchor(json: String): TextAnchor? {
        return try {
            val data = jsonParser.decodeFromString<TextAnchorResult>(json)
            if (data.status != "success") return null
            TextAnchor.of(
                before = percentDecode(data.before),
                after = percentDecode(data.after),
            )
        } catch (e: Exception) {
            analytics.logException(e, "Failed to parse the text anchor, json: $json")
            null
        }
    }

    fun getReadableContentCheckScript(): String = READABLE_CONTENT_CHECK_JS.trimIndent()

    fun parseResult(json: String): List<TtsSentence> {
        return try {
            val data = jsonParser.decodeFromString<ChapterSentencesResult>(json)
            if (data.status != "success") return emptyList()
            data.sentences
                .flatMap { item ->
                    TtsSentenceChunker.chunk(percentDecode(item.text)).map { text ->
                        ParsedSentence(
                            elementId = item.id,
                            text = text,
                        )
                    }
                }
                .mapIndexed { index, sentence ->
                    TtsSentence(
                        index = index,
                        elementId = sentence.elementId,
                        text = sentence.text,
                    )
                }
        } catch (e: Exception) {
            analytics.logException(e, "Failed to parse chapter sentences, json: $json")
            emptyList()
        }
    }

    private fun percentDecode(input: String): String {
        val bytes = ArrayList<Byte>(input.length)
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (char == '%' && index + 2 < input.length) {
                val value = input.substring(index + 1, index + 3).toIntOrNull(16)
                if (value != null) {
                    bytes.add(value.toByte())
                    index += 3
                    continue
                }
            }
            char.toString().encodeToByteArray().forEach { byte -> bytes.add(byte) }
            index++
        }
        return bytes.toByteArray().decodeToString()
    }
}

@Serializable
internal data class ChapterSentencesResult(
    @SerialName("status")
    val status: String,
    @SerialName("sentences")
    val sentences: List<ChapterSentenceJson> = emptyList(),
)

@Serializable
internal data class ChapterSentenceJson(
    @SerialName("id")
    val id: String? = null,
    @SerialName("t")
    val text: String = "",
)

@Serializable
internal data class TextAnchorResult(
    @SerialName("status")
    val status: String,
    @SerialName("before")
    val before: String = "",
    @SerialName("after")
    val after: String = "",
)

private data class ParsedSentence(
    val elementId: String?,
    val text: String,
)
