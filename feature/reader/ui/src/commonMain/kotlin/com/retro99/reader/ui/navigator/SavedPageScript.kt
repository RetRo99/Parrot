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

    /** Saved target of the last touch, captured in WebView coordinates before native tap handling. */
    fun takeSavedTap(): String = call("takeSavedTap")

    fun parseSavedTap(raw: String?): String? {
        val value = raw?.let {
            runCatching { json.decodeFromString(String.serializer(), it) }.getOrNull()
        } ?: return null
        val id = if (value.startsWith("\"")) {
            runCatching { json.decodeFromString(String.serializer(), value) }.getOrNull()
        } else value
        return id?.takeUnless { it.isEmpty() || it == "null" }
    }

    /** The first sentence that starts on the current page. */
    fun firstSentence(): String = call("firstSentence")

    /** The sentence at a fraction of the chapter (bookmarks from before sentences were kept). */
    fun sentenceAt(progression: Double): String = call("sentenceAt", progression.toString())

    /** The sentence containing [anchor]'s text, e.g. the one being spoken. */
    fun sentenceFor(anchor: PageAnchor): String = call("sentenceFor", json.encodeToString(anchor))

    /** The sentence of a read-aloud element, by its id. */
    fun elementSentence(elementId: String): String =
        call("elementSentence", json.encodeToString(String.serializer(), elementId))

    /**
     * Draws the marks for [marks] and returns the ids of the ones whose text starts on the
     * current page. See [PageMark] for what is drawn.
     */
    fun page(marks: List<PageMark>, options: PageMarkOptions): String = call(
        "page",
        json.encodeToString(ListSerializer(PageMark.serializer()), marks),
        json.encodeToString(PageMarkOptions.serializer(), options),
    )

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
        if (!style) {
            style = document.createElement('style');
            style.id = id;
            (document.head || document.documentElement).appendChild(style);
        }
        style.textContent = eink
            ? '::selection { background: #000 !important; color: #fff !important; }'
            : '::selection { background: rgba(181, 88, 29, 0.32) !important; }';
        return true;
    };

    // ---- Marks: the rules under a highlight and the bars at the edge of the page ----
    // Everything here is a fixed number of dp (CSS px are dp in both WebViews), so nothing
    // grows with the book font. The marks live in their own layer over the page and are
    // never part of the book's text: the page cannot reflow around them. Only the marks
    // themselves take taps; blank margin still belongs to page navigation.

    var savedRanges = [];
    var savedTap = null;
    function contains(rect, x, y) {
        return x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
    }
    function savedTarget(x, y) {
        var host = document.getElementById('parrot-marks');
        if (host) {
            for (var i = host.children.length - 1; i >= 0; i--) {
                var mark = host.children[i];
                if (mark.savedId && contains(mark.getBoundingClientRect(), x, y)) return mark.savedId;
            }
        }
        for (var r = 0; r < savedRanges.length; r++) {
            var rects = savedRanges[r].range.getClientRects();
            for (var j = 0; j < rects.length; j++) {
                if (contains(rects[j], x, y)) return savedRanges[r].id;
            }
        }
        return null;
    }
    // Use DOM coordinates, not Compose coordinates: Readium can inset, scroll or scale
    // its WebView. Capture on down so asynchronous decoration activation cannot race us.
    document.addEventListener('touchstart', function(event) {
        var touch = event.touches.length === 1 ? event.touches[0] : null;
        savedTap = touch ? savedTarget(touch.clientX, touch.clientY) : null;
    }, { capture: true, passive: true });
    document.addEventListener('mousedown', function(event) {
        savedTap = savedTarget(event.clientX, event.clientY);
    }, true);
    P.takeSavedTap = function() {
        var id = savedTap;
        savedTap = null;
        return id;
    };

    var metricCache = {};

    // An ARGB int as CSS, keeping its own alpha.
    function css(argb) {
        var v = argb | 0;
        var a = Math.round(((v >>> 24) & 255) * 1000 / 255) / 1000;
        return 'rgba(' + ((v >>> 16) & 255) + ',' + ((v >>> 8) & 255) + ',' + (v & 255) + ',' + a + ')';
    }

    function markHost() {
        var host = document.getElementById('parrot-marks');
        if (!host) {
            host = document.createElement('div');
            host.id = 'parrot-marks';
            host.setAttribute('aria-hidden', 'true');
            (document.body || document.documentElement).appendChild(host);
        }
        host.style.cssText = 'position:absolute;left:0;top:0;width:0;height:0;overflow:visible;' +
            'margin:0;padding:0;border:0;pointer-events:none;z-index:1;';
        while (host.firstChild) host.removeChild(host.firstChild);
        return host;
    }

    function markBox(host, x, y, w, h, radius, color, id, edgeTarget) {
        x = Number(x); y = Number(y); w = Number(w); h = Number(h); radius = Number(radius);
        if (!isFinite(x) || !isFinite(y) || !isFinite(w) || !isFinite(h)) return;
        if (!(w > 0) || !(h > 0)) return;
        // A 4dp bar is inside some phones' edge-rejection area. Extend its target
        // inward without changing the visible mark or intercepting the whole margin.
        var pad = id && edgeTarget ? 24 : 0;
        var el = document.createElement('div');
        // ReadiumCSS paints every element transparent once a page colour is set, and its
        // !important beats a plain inline declaration - so this one has to be !important too.
        // Set per property: one bad value in a cssText makes the rest of it get dropped.
        var st = el.style;
        st.setProperty('position', 'absolute');
        st.setProperty('box-sizing', 'border-box');
        st.setProperty('margin', '0');
        st.setProperty('padding', '0');
        st.setProperty('border', '0');
        st.setProperty('pointer-events', id ? 'auto' : 'none');
        el.savedId = id;
        // The common reader gesture opens the item using takeSavedTap. Do not let
        // this synthetic click become a sentence/read-aloud or Readium page tap.
        el.addEventListener('click', function(event) {
            event.preventDefault();
            event.stopPropagation();
        });
        st.setProperty('left', (x - pad) + 'px');
        st.setProperty('top', y + 'px');
        st.setProperty('width', (w + pad * 2) + 'px');
        st.setProperty('height', h + 'px');
        // ReadiumCSS gives every div `max-width: 100%`, and the marks' host is zero-width so
        // that it cannot affect the page's layout - which would clamp every mark to no width.
        st.setProperty('max-width', 'none', 'important');
        st.setProperty('max-height', 'none', 'important');
        st.setProperty('border-radius', radius + 'px');
        if (pad) {
            var paint = document.createElement('div');
            paint.style.cssText = 'position:absolute;left:' + pad + 'px;top:0;width:' + w +
                'px;height:100%;max-width:none!important;pointer-events:none;border-radius:' + radius + 'px;';
            paint.style.setProperty('background-color', color, 'important');
            el.appendChild(paint);
        } else st.setProperty('background-color', color, 'important');
        host.appendChild(el);
    }

    // How many pages sit side by side, and how wide each one is. Readium fills one "column"
    // per page, so this is also the distance between the outer edges the bars sit on.
    function pages(o) {
        var screen = window.innerWidth || 1;
        if (o && o.scroll) return { pitch: screen, count: 1 };
        var cs = getComputedStyle(document.documentElement);
        var count = parseInt(cs.getPropertyValue('column-count'), 10) || 0;
        var gap = parseFloat(cs.getPropertyValue('column-gap')) || 0;
        var raw = cs.getPropertyValue('column-width') || '';
        var width = /px\s*$/.test(raw) ? parseFloat(raw) : 0;
        if (width >= screen) return { pitch: screen, count: 1 };
        var n = count > 0 ? count : (width > 0 ? Math.max(1, Math.floor((screen + gap) / (width + gap))) : 1);
        if (!(n > 0)) n = 1;
        return { pitch: (screen - (n - 1) * gap) / n + gap, count: n };
    }

    // Font ascent and descent in px, which is where a text fragment's box sits on its baseline.
    function metrics(node) {
        var cs = getComputedStyle(node);
        var key = (cs.fontStyle || '') + '|' + (cs.fontWeight || '') + '|' + (cs.fontSize || '') +
            '|' + (cs.fontFamily || '');
        var hit = metricCache[key];
        if (hit) return hit;
        var size = parseFloat(cs.fontSize) || 16;
        var out = { up: size * 0.8, down: size * 0.2 };
        try {
            var ctx = document.createElement('canvas').getContext('2d');
            ctx.font = (cs.fontStyle || 'normal') + ' ' + (cs.fontWeight || 'normal') + ' ' +
                (cs.fontSize || '16px') + ' ' + (cs.fontFamily || 'serif');
            var m = ctx.measureText('Hxpg');
            if (m.fontBoundingBoxAscent > 0) {
                out.up = m.fontBoundingBoxAscent;
                out.down = m.fontBoundingBoxDescent || 0;
            }
        } catch (e) {}
        metricCache[key] = out;
        return out;
    }

    // Overlapping segments on one edge become one; the rest keep their own bar.
    function mergeSpans(spans) {
        var sorted = spans.slice().sort(function(a, b) { return a.top - b.top; });
        var out = [];
        for (var i = 0; i < sorted.length; i++) {
            var last = out.length ? out[out.length - 1] : null;
            if (last && sorted[i].top <= last.bottom + 0.5) {
                if (sorted[i].bottom > last.bottom) last.bottom = sorted[i].bottom;
            } else {
                out.push({ top: sorted[i].top, bottom: sorted[i].bottom });
            }
        }
        return out;
    }
    P.mergeSpans = mergeSpans;

    // Which edge of the page a bar belongs to: 0 is the left edge, 1 the right one. The bar
    // takes the page's outer edge, so it never lands in the gutter of a two-page spread.
    function edgeOf(band, count, rtl) {
        if (count <= 1) return rtl ? 0 : 1;
        var within = ((band % count) + count) % count;
        if (within === 0) return rtl ? 1 : 0;
        return rtl ? 0 : 1;
    }
    P.edgeOf = edgeOf;

    // Draws the marks for a chapter and returns the ids whose text starts on this page.
    function drawMarks(specs, options) {
        var o = options || {};
        var host = markHost();
        savedRanges = [];
        var ids = [];
        if (!specs || !specs.length) return ids;
        var index = buildIndex();
        if (!index.text.length) return ids;
        var scroller = document.scrollingElement || document.documentElement;
        var sl = scroller.scrollLeft || 0;
        var st = scroller.scrollTop || 0;
        var layout = pages(o);
        var rtl = (getComputedStyle(document.body || document.documentElement).direction === 'rtl');
        var edgeGap = o.edgeGap == null ? 4 : o.edgeGap;
        var edgeWidth = o.edgeWidth == null ? 4 : o.edgeWidth;
        var edgeRadius = o.edgeRadius == null ? 2 : o.edgeRadius;
        var minMargin = o.minMargin == null ? 8 : o.minMargin;
        var ruleBelow = o.ruleBelow == null ? 3 : o.ruleBelow;
        var ruleWeight = o.ruleWeight == null ? 2 : o.ruleWeight;
        var pairWeight = o.pairWeight == null ? 1.5 : o.pairWeight;
        var pairGap = o.pairGap == null ? 2 : o.pairGap;
        var pairBelow = o.pairBelow == null ? 7 : o.pairBelow;
        var pairFallback = o.pairFallback == null ? 3 : o.pairFallback;
        var bars = {};

        for (var i = 0; i < specs.length; i++) {
            var spec = specs[i];
            var found = resolve(index, spec);
            if (!found) continue;
            if (offsetVisible(index, found.start)) ids.push(spec.id);
            if (!spec.tappable && !spec.ruleCount && !spec.barColor) continue;
            var range = rangeFor(index, found.start, found.end);
            if (!range) continue;
            if (spec.tappable) savedRanges.push({ id: spec.id, range: range });
            var rects = range.getClientRects();
            if (!rects || !rects.length) continue;
            var point = pointAt(index, found.start);
            var host2 = point && point.node && point.node.parentElement;
            var font = metrics(host2 || document.body);
            var lineHeight = host2 ? (parseFloat(getComputedStyle(host2).lineHeight) || 0) : 0;

            for (var r = 0; r < rects.length; r++) {
                var rect = rects[r];
                if (!(rect.width > 0) && !(rect.height > 0)) continue;
                var top = rect.top + st;
                var left = rect.left + sl;
                var bottom = rect.bottom + st;

                // Rules: one per line fragment, a fixed distance under the text's baseline.
                if (spec.ruleCount > 0) {
                    var total = font.up + font.down || rect.height || 1;
                    var ascent = rect.height * (font.up / total);
                    var baseline = top + ascent;
                    // Room under the baseline before the next line's text begins.
                    // Large fonts have descenders deeper than ruleBelow. Keep the rule
                    // outside the entire glyph box, not across the tails of g/p/y.
                    var y = Math.max(baseline + ruleBelow, bottom + 1);
                    var below = Math.max(0, top + (lineHeight || total) - y);
                    var weights = [ruleWeight];
                    if (spec.ruleCount > 1) {
                        weights = below >= pairBelow ? [pairWeight, pairWeight] : [pairFallback];
                    }
                    for (var w = 0; w < weights.length; w++) {
                        if (w > 0) y += weights[w - 1] + pairGap;
                        markBox(host, left, y, rect.width, weights[w], 0, css(spec.ruleColor), spec.tappable ? spec.id : null);
                    }
                }

                // One bar segment per page the highlight has lines on: the lines on a page
                // form one segment, and segments from different highlights that overlap join.
                if (spec.barColor) {
                    var band = Math.floor((left + rect.width / 2) / layout.pitch);
                    var edge = edgeOf(band, layout.count, rtl);
                    var margin = (edge === 0 ? o.marginLeft : o.marginRight) || 0;
                    if (margin < minMargin) continue;
                    var key = spec.id + '@' + band;
                    var span = bars[key];
                    if (!span) {
                        span = bars[key] = {
                            id: spec.tappable ? spec.id : null,
                            edge: edge, band: band, color: css(spec.barColor), top: top, bottom: bottom
                        };
                    } else {
                        if (top < span.top) span.top = top;
                        if (bottom > span.bottom) span.bottom = bottom;
                    }
                }
            }
        }

        // Keep each item's identity even when bars overlap. The last painted bar wins
        // in the overlap, just as it does for decorations; the rest retains its own target.
        for (var key2 in bars) {
            if (!Object.prototype.hasOwnProperty.call(bars, key2)) continue;
            var s = bars[key2];
            var from = s.band * layout.pitch;
            var x = s.edge === 1 ? from + layout.pitch - edgeGap - edgeWidth : from + edgeGap;
            markBox(host, x, s.top, edgeWidth, s.bottom - s.top, edgeRadius, s.color, s.id, true);
        }
        return ids;
    }

    // The marks are laid out against the text, so anything that reflows the page (a rotation,
    // a new font size, a margin change) moves them. Redraw from the last request when that
    // happens instead of leaving stale - or missing - marks behind.
    var markPending = false;
    function markSchedule() {
        if (markPending) return;
        markPending = true;
        requestAnimationFrame(function() {
            markPending = false;
            if (P.lastMarks) drawMarks(P.lastMarks.specs, P.lastMarks.options);
        });
    }

    function markHooks() {
        if (P.marksHooked) return;
        P.marksHooked = true;
        window.addEventListener('resize', markSchedule);
        if (document.readyState !== 'complete') window.addEventListener('load', markSchedule);
        if (document.fonts && document.fonts.ready) document.fonts.ready.then(markSchedule);
        try {
            new ResizeObserver(markSchedule).observe(document.body);
        } catch (e) {}
    }

    P.page = function(specs, options) {
        P.lastMarks = { specs: specs, options: options };
        markHooks();
        return drawMarks(specs, options);
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

/**
 * One saved range and the marks the page draws for it.
 *
 * The navigator draws the fill and owns the taps on [tappable] ranges; [SavedPageScript.page]
 * draws the rules and the edge bars in its own layer over the page. Colours are ARGB.
 */
@Serializable
data class PageMark(
    val id: String = "",
    /** The resource the range lives in. */
    val href: String = "",
    val mediaType: String? = null,
    val quote: String,
    val before: String? = null,
    val after: String? = null,
    val progression: Double? = null,
    /** Fill behind the text, or 0 when the theme draws none (e-ink). */
    val fill: Int = 0,
    /** Whether the navigator draws a tap target for this range. */
    val tappable: Boolean = false,
    /** Colour of the rule under the range, or 0 for none. */
    val ruleColor: Int = 0,
    /** How many rules to draw: 0, 1 (or 2 on e-ink for a highlight with a note). */
    val ruleCount: Int = 0,
    /** Colour of the bar at the edge of the page, or 0 for none. */
    val barColor: Int = 0,
)

/**
 * Sizes for [SavedPageScript.page], all in dp. They are constants rather than parameters
 * because the marks must not change size with the book's font.
 */
@Serializable
data class PageMarkOptions(
    /** Space between the bar and the edge of the page. */
    val edgeGap: Int = 4,
    val edgeWidth: Int = 4,
    val edgeRadius: Int = 2,
    /** No bar where the page margin is thinner than this: the rule carries the signal. */
    val minMargin: Int = 8,
    val marginLeft: Int = 0,
    val marginRight: Int = 0,
    /** How far under the text's baseline the rules start. */
    val ruleBelow: Int = 3,
    /** One rule (also the plain e-ink highlight). */
    val ruleWeight: Int = 2,
    /** Two rules (a highlight with a note on e-ink), and the space between them. */
    val pairWeight: Double = 1.5,
    val pairGap: Double = 2.0,
    /** Line spacing under the baseline that still fits the pair; less falls back to one rule. */
    val pairBelow: Double = 7.0,
    val pairFallback: Double = 3.0,
    /** No columns while scrolling. */
    val scroll: Boolean = false,
)
