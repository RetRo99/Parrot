package com.retro99.reader.ui.navigator

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Page-side helpers for bookmarks and highlights, shared by Android and iOS.
 *
 * Every saved item is anchored by its text (a quote with a little context on each
 * side), never by layout, so it lands on the same sentence after font or margin
 * changes. The script works on the chapter's text nodes without changing the DOM.
 * Tested in headless Chromium with a paginated column layout like Readium's.
 */
object SavedPageScript {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    /** Text of the selection on the page, with its bounds in the reader's coordinates (dp). */
    fun selection(): String = call("selection")

    fun clearSelection(): String = call("clearSelection")

    /** The first sentence that starts on the current page. */
    fun firstSentence(): String = call("firstSentence")

    /** The sentence at a fraction of the chapter (bookmarks from before sentences were kept). */
    fun sentenceAt(progression: Double): String = call("sentenceAt", progression.toString())

    /** The sentence containing [anchor]'s text, e.g. the one being spoken. */
    fun sentenceFor(anchor: PageAnchor): String = call("sentenceFor", json.encodeToString(anchor))

    /** The sentence of a read-aloud element, by its id. */
    fun elementSentence(elementId: String): String =
        call("elementSentence", json.encodeToString(String.serializer(), elementId))

    /** Which of [anchors] start on the current page. */
    fun onPage(anchors: List<PageAnchor>): String =
        call("onPage", json.encodeToString(ListSerializer(PageAnchor.serializer()), anchors))

    /** Highlights [selection] overlaps or touches, with the text of the combined range. */
    fun merge(selection: PageAnchor, highlights: List<PageAnchor>): String = call(
        "merge",
        json.encodeToString(selection),
        json.encodeToString(ListSerializer(PageAnchor.serializer()), highlights),
    )

    fun setSelectionStyle(eink: Boolean): String = call("setSelectionStyle", eink.toString())

    fun parseAnchor(raw: String?): PageText? = decode(raw)?.let { body ->
        runCatching { json.decodeFromString(PageText.serializer(), body) }.getOrNull()
    }

    fun parseIds(raw: String?): List<String> = decode(raw)?.let { body ->
        runCatching { json.decodeFromString(ListSerializer(String.serializer()), body) }.getOrNull()
    }.orEmpty()

    /**
     * WebViews hand back JSON.stringify's result either as the string itself (iOS) or
     * as a JSON string literal (Android). Null and errors come back as null.
     */
    internal fun decode(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        val body = if (trimmed.startsWith("\"")) {
            runCatching { json.decodeFromString(String.serializer(), trimmed) }.getOrNull() ?: return null
        } else {
            trimmed
        }
        return body.takeUnless { it.isBlank() || it == "null" || it == "undefined" }
    }

    private fun call(function: String, vararg args: String): String =
        "(function() { try { " + INSTALL + "\n return JSON.stringify(window.parrotSaved." + function +
            "(" + args.joinToString(", ") + ")); } catch (e) { return null; } })()"

    private const val INSTALL = """
(function() {
    if (window.parrotSaved) return;
    var CONTEXT = 32;
    var MAX_QUOTE = 1500;
    var SKIP = 'script,style,noscript,[aria-hidden="true"]';
    var BLOCKS = 'p,div,section,article,h1,h2,h3,h4,h5,h6,li,blockquote,td,th,dt,dd,figcaption,pre';

    function textNodes() {
        var nodes = [];
        if (!document.body) return nodes;
        var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
            acceptNode: function(n) {
                var parent = n.parentElement;
                if (!parent || parent.closest(SKIP)) return NodeFilter.FILTER_REJECT;
                return NodeFilter.FILTER_ACCEPT;
            }
        });
        var node;
        while ((node = walker.nextNode())) nodes.push(node);
        return nodes;
    }

    // The chapter's text: every text node in order. starts[i] is where nodes[i] begins.
    function buildIndex() {
        var nodes = textNodes();
        var starts = [];
        var text = '';
        for (var i = 0; i < nodes.length; i++) {
            starts.push(text.length);
            text += nodes[i].nodeValue;
        }
        return { nodes: nodes, starts: starts, text: text };
    }

    function pointAt(index, offset) {
        var lo = 0, hi = index.nodes.length - 1;
        if (hi < 0) return null;
        while (lo < hi) {
            var mid = (lo + hi + 1) >> 1;
            if (index.starts[mid] <= offset) lo = mid; else hi = mid - 1;
        }
        var node = index.nodes[lo];
        return { node: node, offset: Math.min(offset - index.starts[lo], node.nodeValue.length) };
    }

    // Offset in the chapter text of a DOM boundary point.
    function offsetOf(index, container, offset) {
        if (container.nodeType === Node.TEXT_NODE) {
            var i = index.nodes.indexOf(container);
            if (i >= 0) return index.starts[i] + offset;
        }
        var probe = document.createRange();
        probe.setStart(container, offset);
        for (var j = 0; j < index.nodes.length; j++) {
            var n = index.nodes[j];
            if (probe.comparePoint(n, 0) >= 0) return index.starts[j];
        }
        return index.text.length;
    }

    function rangeFor(index, start, end) {
        var a = pointAt(index, start);
        var b = pointAt(index, Math.max(start, end));
        if (!a || !b) return null;
        var range = document.createRange();
        range.setStart(a.node, a.offset);
        range.setEnd(b.node, b.offset);
        return range;
    }

    function collapse(s) { return (s || '').replace(/\s+/g, ' '); }

    // Collapsed text with a map back to raw offsets, for matching across whitespace changes.
    function collapsedIndex(text) {
        var out = '', map = [];
        for (var i = 0; i < text.length; i++) {
            var c = text[i];
            if (/\s/.test(c)) {
                if (out.length && out[out.length - 1] === ' ') continue;
                out += ' ';
            } else {
                out += c;
            }
            map.push(i);
        }
        return { text: out, map: map };
    }

    function bestMatch(haystack, quote, before, after, progression) {
        if (!quote) return -1;
        var best = -1, bestRank = -Infinity;
        var at = haystack.indexOf(quote);
        while (at >= 0) {
            var score = 0, k;
            for (k = 1; k <= Math.min(before.length, at); k++) {
                if (before[before.length - k] !== haystack[at - k]) break;
                score++;
            }
            for (k = 0; k < Math.min(after.length, haystack.length - at - quote.length); k++) {
                if (after[k] !== haystack[at + quote.length + k]) break;
                score++;
            }
            var distance = Math.abs(at / Math.max(1, haystack.length) - (progression || 0));
            var rank = score * 2 - distance;
            if (rank > bestRank) { bestRank = rank; best = at; }
            at = haystack.indexOf(quote, at + 1);
        }
        return best;
    }

    // Finds an anchor's text in the chapter. Returns raw offsets or null.
    function resolve(index, anchor) {
        var quote = anchor.quote || '';
        var at = bestMatch(index.text, quote, anchor.before || '', anchor.after || '', anchor.progression);
        if (at >= 0) return { start: at, end: at + quote.length };
        var c = collapsedIndex(index.text);
        var cq = collapse(quote).trim();
        if (!cq) return null;
        var cat = bestMatch(c.text, cq, collapse(anchor.before), collapse(anchor.after), anchor.progression);
        if (cat < 0) return null;
        return { start: c.map[cat], end: c.map[cat + cq.length - 1] + 1 };
    }

    function anchorOf(index, start, end) {
        var text = index.text;
        while (start < end && /\s/.test(text[start])) start++;
        while (end > start && /\s/.test(text[end - 1])) end--;
        return {
            before: text.slice(Math.max(0, start - CONTEXT), start),
            quote: text.slice(start, end),
            after: text.slice(end, Math.min(text.length, end + CONTEXT)),
            progression: text.length ? start / text.length : 0,
            start: start,
            end: end
        };
    }

    function visible(rect) {
        if (!rect || (rect.width === 0 && rect.height === 0)) return false;
        return rect.right > 1 && rect.left < window.innerWidth - 1 &&
            rect.bottom > 1 && rect.top < window.innerHeight - 1;
    }

    // Is the first character at this offset shown on the current page?
    function offsetVisible(index, offset) {
        var text = index.text;
        while (offset < text.length && /\s/.test(text[offset])) offset++;
        if (offset >= text.length) return false;
        var range = rangeFor(index, offset, offset + 1);
        if (!range) return false;
        var rects = range.getClientRects();
        for (var i = 0; i < rects.length; i++) if (visible(rects[i])) return true;
        return false;
    }

    function firstVisibleOffset(index) {
        for (var i = 0; i < index.nodes.length; i++) {
            var node = index.nodes[i];
            var value = node.nodeValue;
            if (!value.trim()) continue;
            var whole = document.createRange();
            whole.selectNodeContents(node);
            var rects = whole.getClientRects(), any = false;
            for (var r = 0; r < rects.length; r++) if (visible(rects[r])) { any = true; break; }
            if (!any) continue;
            var words = /\S+/g, m;
            while ((m = words.exec(value)) !== null) {
                var probe = document.createRange();
                probe.setStart(node, m.index);
                probe.setEnd(node, m.index + 1);
                var pr = probe.getClientRects();
                for (var p = 0; p < pr.length; p++) if (visible(pr[p])) return index.starts[i] + m.index;
            }
        }
        return -1;
    }

    // Sentence boundaries around [from, to): [{start, end}], using Intl.Segmenter when present.
    function sentences(index, from, to) {
        var text = index.text;
        var lo = Math.max(0, from - 4000), hi = Math.min(text.length, to + 4000);
        // Don't let a sentence run across block elements (headings, paragraphs).
        var breaks = blockBreaks(index, lo, hi);
        var out = [];
        var segmentStart = lo;
        breaks.push(hi);
        for (var b = 0; b < breaks.length; b++) {
            var segEnd = breaks[b];
            if (segEnd <= segmentStart) continue;
            splitSentences(text.slice(segmentStart, segEnd), segmentStart, out);
            segmentStart = segEnd;
        }
        return out;
    }

    function blockBreaks(index, lo, hi) {
        var breaks = [], previous = null;
        for (var i = 0; i < index.nodes.length; i++) {
            var start = index.starts[i];
            if (start + index.nodes[i].nodeValue.length < lo) continue;
            if (start > hi) break;
            var block = index.nodes[i].parentElement && index.nodes[i].parentElement.closest(BLOCKS);
            if (previous !== null && block !== previous && start > lo) breaks.push(start);
            previous = block;
        }
        return breaks;
    }

    function splitSentences(slice, base, out) {
        if (window.Intl && Intl.Segmenter) {
            var segmenter = new Intl.Segmenter(document.documentElement.lang || undefined, { granularity: 'sentence' });
            var it = segmenter.segment(slice)[Symbol.iterator](), step;
            while (!(step = it.next()).done) {
                var seg = step.value;
                pushTrimmed(out, slice, base, seg.index, seg.index + seg.segment.length);
            }
            return;
        }
        var re = /[^.!?…。]+(?:[.!?…。]+["'”’)\]]*|$)\s*/g, m;
        while ((m = re.exec(slice)) !== null) {
            if (!m[0].length) { re.lastIndex++; continue; }
            pushTrimmed(out, slice, base, m.index, m.index + m[0].length);
        }
    }

    function pushTrimmed(out, slice, base, s, e) {
        while (s < e && /\s/.test(slice[s])) s++;
        while (e > s && /\s/.test(slice[e - 1])) e--;
        if (e > s) out.push({ start: base + s, end: base + e });
    }

    function capped(anchor) {
        if (anchor.quote.length > MAX_QUOTE) {
            anchor.quote = anchor.quote.slice(0, MAX_QUOTE);
            anchor.end = anchor.start + MAX_QUOTE;
            anchor.after = '';
        }
        return anchor;
    }

    var P = {};

    // The current text selection, or null.
    P.selection = function() {
        var sel = window.getSelection();
        if (!sel || sel.rangeCount === 0 || sel.isCollapsed) return null;
        var range = sel.getRangeAt(0);
        var index = buildIndex();
        var start = offsetOf(index, range.startContainer, range.startOffset);
        var end = offsetOf(index, range.endContainer, range.endOffset);
        if (end <= start) return null;
        var anchor = anchorOf(index, start, end);
        if (!anchor.quote) return null;
        var rect = range.getBoundingClientRect();
        anchor.rect = { left: rect.left, top: rect.top, right: rect.right, bottom: rect.bottom };
        anchor.viewport = { width: window.innerWidth, height: window.innerHeight };
        anchor.tooLong = anchor.quote.length > MAX_QUOTE;
        return anchor;
    };

    P.clearSelection = function() {
        var sel = window.getSelection();
        if (sel) sel.removeAllRanges();
        return true;
    };

    // The first sentence that starts on this page (or, failing that, the one the page opens in).
    P.firstSentence = function() {
        var index = buildIndex();
        var first = firstVisibleOffset(index);
        if (first < 0) return null;
        var list = sentences(index, first, first + 1);
        var containing = null;
        for (var i = 0; i < list.length; i++) {
            var s = list[i];
            if (s.end <= first) continue;
            if (s.start >= first) {
                if (offsetVisible(index, s.start)) return capped(anchorOf(index, s.start, s.end));
                break;
            }
            if (!containing) containing = s;
        }
        return containing ? capped(anchorOf(index, containing.start, containing.end)) : null;
    };

    // The sentence at a fraction of the chapter, for bookmarks saved before sentences were kept.
    P.sentenceAt = function(progression) {
        var index = buildIndex();
        if (!index.text.length) return null;
        var target = Math.floor(Math.max(0, Math.min(1, progression || 0)) * index.text.length);
        var list = sentences(index, target, target + 1);
        for (var i = 0; i < list.length; i++) {
            if (list[i].end > target) return capped(anchorOf(index, list[i].start, list[i].end));
        }
        return null;
    };

    // The sentence that contains the given text (a spoken sentence), near a progression.
    P.sentenceFor = function(anchor) {
        var index = buildIndex();
        var found = resolve(index, anchor);
        if (!found) return null;
        return capped(anchorOf(index, found.start, found.end));
    };

    // The text of a sentence element (read-aloud books mark each sentence with an id).
    P.elementSentence = function(id) {
        var element = document.getElementById(id);
        if (!element) return null;
        var index = buildIndex();
        var range = document.createRange();
        range.selectNodeContents(element);
        var start = offsetOf(index, range.startContainer, range.startOffset);
        var end = offsetOf(index, range.endContainer, range.endOffset);
        if (end <= start) return null;
        return capped(anchorOf(index, start, end));
    };

    // Ids of anchors whose first character is on the current page.
    P.onPage = function(anchors) {
        var index = buildIndex();
        var ids = [];
        for (var i = 0; i < anchors.length; i++) {
            var found = resolve(index, anchors[i]);
            if (found && offsetVisible(index, found.start)) ids.push(anchors[i].id);
        }
        return ids;
    };

    // Overlapping, or separated only by whitespace (two sentences side by side).
    function touches(text, r, start, end) {
        if (r.start <= end && r.end >= start) return true;
        var gap = r.end < start ? text.slice(r.end, start) : text.slice(end, r.start);
        return gap.length <= 3 && !/\S/.test(gap);
    }

    // Highlights the selection overlaps or touches, and the text of the combined range.
    P.merge = function(selection, highlights) {
        var index = buildIndex();
        var sel = resolve(index, selection);
        if (!sel) return null;
        var start = sel.start, end = sel.end, ids = [];
        var ranges = [];
        for (var i = 0; i < highlights.length; i++) {
            var found = resolve(index, highlights[i]);
            if (found) ranges.push({ id: highlights[i].id, start: found.start, end: found.end });
        }
        // Grow until stable, so a chain of touching highlights joins into one.
        var grown = true;
        while (grown) {
            grown = false;
            for (var j = 0; j < ranges.length; j++) {
                var r = ranges[j];
                if (ids.indexOf(r.id) >= 0) continue;
                if (touches(index.text, r, start, end)) {
                    ids.push(r.id);
                    if (r.start < start) start = r.start;
                    if (r.end > end) end = r.end;
                    grown = true;
                }
            }
        }
        var anchor = anchorOf(index, start, end);
        anchor.ids = ids;
        anchor.tooLong = anchor.quote.length > MAX_QUOTE;
        return anchor;
    };

    P.setSelectionStyle = function(eink) {
        var id = 'parrot-selection-style';
        var style = document.getElementById(id);
        if (!eink) { if (style) style.remove(); return true; }
        if (!style) {
            style = document.createElement('style');
            style.id = id;
            (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = '::selection { background: #000 !important; color: #fff !important; }';
        return true;
    };

    window.parrotSaved = P;
})();
"""
}

/** An anchor sent to the page: [id] names the saved item it belongs to. */
@Serializable
data class PageAnchor(
    val id: String = "",
    val quote: String,
    val before: String? = null,
    val after: String? = null,
    val progression: Double? = null,
)

/** Text found on the page. [rect] and [viewport] are only set for a selection. */
@Serializable
data class PageText(
    val quote: String,
    val before: String = "",
    val after: String = "",
    /** Fraction of the chapter's text before the quote. */
    val progression: Double = 0.0,
    val rect: PageRect? = null,
    val viewport: PageSize? = null,
    /** Over Parrot Cloud's limit for a highlight. */
    val tooLong: Boolean = false,
    /** For a merge: the highlights the range absorbed. */
    val ids: List<String> = emptyList(),
)

@Serializable
data class PageRect(val left: Double, val top: Double, val right: Double, val bottom: Double)

@Serializable
data class PageSize(val width: Double, val height: Double)
