package com.retro99.reader.ui.navigator

import com.retro99.analytics.api.Analytics
import com.retro99.reader.domain.recap.RecapCapturePolicy
import com.retro99.reader.domain.recap.RecapTextPiece
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The text on screen, from the first visible word to the last, with no
 * fallback: a page showing no text gives null. Unlike
 * [VisibleSentenceDetector] it never answers with the chapter's last
 * sentence, and it reads the DOM without changing it.
 */
data class VisibleTextRange(
    /** In reading order; offsets are into the chapter's text. */
    val pieces: List<RecapTextPiece>,
) {
    /** Offset of the first visible character in the chapter. */
    val startOffset: Int get() = pieces.first().start

    /** Offset just past the last visible character in the chapter. */
    val endOffset: Int get() = pieces.last().end

    override fun toString(): String =
        "VisibleTextRange(start=$startOffset, end=$endOffset, pieces=${pieces.size})"
}

object VisibleTextRangeDetector : KoinComponent {

    private val analytics: Analytics by inject<Analytics>()

    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * Walks the chapter's text nodes in order. Offsets count every text node
     * outside script and style, so sentence spans added for read-aloud don't
     * shift them. Nodes cut by the page edge are trimmed to whole visible
     * words. The visible run ends at the first laid-out node past it.
     */
    private const val VISIBLE_TEXT_RANGE_JS = """
        (function() {
            try {
                const body = document.body;
                if (!body) return JSON.stringify({ status: 'none' });
                const width = window.innerWidth;
                const height = window.innerHeight;
                const maxChars = %MAX_CHARS%;
                const blockSelector =
                    'p,h1,h2,h3,h4,h5,h6,li,blockquote,figcaption,pre,' +
                    'td,th,dt,dd,div,section,article';
                const walker = document.createTreeWalker(body, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(node) {
                        const parent = node.parentElement;
                        if (!parent) return NodeFilter.FILTER_REJECT;
                        const tag = parent.tagName;
                        if (tag === 'SCRIPT' || tag === 'STYLE' || tag === 'NOSCRIPT') {
                            return NodeFilter.FILTER_REJECT;
                        }
                        return NodeFilter.FILTER_ACCEPT;
                    }
                });

                function isRectVisible(rect) {
                    if (rect.width === 0 && rect.height === 0) return false;
                    return rect.right > 1 && rect.left < width - 1 &&
                        rect.bottom > 1 && rect.top < height - 1;
                }

                // 0: not laid out, 1: off screen, 2: partly, 3: fully visible.
                function classify(node, start, end) {
                    const range = document.createRange();
                    range.setStart(node, start);
                    range.setEnd(node, end);
                    const rects = range.getClientRects();
                    let shown = 0;
                    let laidOut = 0;
                    for (let i = 0; i < rects.length; i++) {
                        const rect = rects[i];
                        if (rect.width === 0 && rect.height === 0) continue;
                        laidOut++;
                        if (isRectVisible(rect)) shown++;
                    }
                    if (laidOut === 0) return 0;
                    if (shown === 0) return 1;
                    return shown === laidOut ? 3 : 2;
                }

                function visibleWords(node, value) {
                    const words = /\S+/g;
                    let first = -1;
                    let last = -1;
                    let match;
                    while ((match = words.exec(value)) !== null) {
                        const end = match.index + match[0].length;
                        const state = classify(node, match.index, end);
                        if (state >= 2) {
                            if (first < 0) first = match.index;
                            last = end;
                        } else if (state === 1 && first >= 0) {
                            break;
                        }
                    }
                    return first < 0 ? null : [first, last];
                }

                const pieces = [];
                let offset = 0;
                let seen = false;
                let total = 0;
                let lastBlock = null;
                let node;
                while ((node = walker.nextNode())) {
                    const value = node.nodeValue || '';
                    const nodeStart = offset;
                    offset += value.length;
                    if (value.trim().length === 0) continue;
                    const state = classify(node, 0, value.length);
                    if (state === 0) continue;
                    if (state === 1) {
                        if (seen) break;
                        continue;
                    }
                    let start = 0;
                    let end = value.length;
                    if (state === 2) {
                        const span = visibleWords(node, value);
                        if (!span) {
                            if (seen) break;
                            continue;
                        }
                        start = span[0];
                        end = span[1];
                    }
                    seen = true;
                    const parent = node.parentElement;
                    const block = (parent && parent.closest(blockSelector)) || body;
                    pieces.push({
                        s: nodeStart + start,
                        e: nodeStart + end,
                        b: lastBlock !== null && block !== lastBlock ? 1 : 0,
                        t: encodeURIComponent(value.slice(start, end))
                    });
                    lastBlock = block;
                    total += end - start;
                    if (total >= maxChars) break;
                }
                if (pieces.length === 0) return JSON.stringify({ status: 'none' });
                return JSON.stringify({ status: 'found', pieces: pieces });
            } catch (e) {
                return JSON.stringify({ status: 'error' });
            }
        })()
    """

    fun getScript(): String = VISIBLE_TEXT_RANGE_JS.trimIndent()
        .replace("%MAX_CHARS%", RecapCapturePolicy.MAX_PAGE_CHARS.toString())

    /** Null when nothing is visible or the result can't be read. */
    fun parseResult(json: String): VisibleTextRange? {
        return try {
            val data = jsonParser.decodeFromString<VisibleTextRangeResult>(json)
            if (data.status != "found") return null
            val pieces = data.pieces
                .filter { it.end > it.start && it.start >= 0 }
                .map { piece ->
                    RecapTextPiece(
                        start = piece.start,
                        end = piece.end,
                        text = ChapterSentenceExtractor.percentDecode(piece.text),
                        startsBlock = piece.startsBlock == 1,
                    )
                }
            pieces.takeIf { it.isNotEmpty() }?.let(::VisibleTextRange)
        } catch (e: Exception) {
            // Never pass the payload on: it holds book text.
            analytics.logException(e, "Failed to parse the visible text range")
            null
        }
    }
}

@Serializable
internal data class VisibleTextRangeResult(
    @SerialName("status")
    val status: String,
    @SerialName("pieces")
    val pieces: List<VisibleTextPieceJson> = emptyList(),
)

@Serializable
internal data class VisibleTextPieceJson(
    @SerialName("s")
    val start: Int,
    @SerialName("e")
    val end: Int,
    @SerialName("b")
    val startsBlock: Int = 0,
    @SerialName("t")
    val text: String = "",
)
