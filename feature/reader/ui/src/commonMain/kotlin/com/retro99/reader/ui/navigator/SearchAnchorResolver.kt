package com.retro99.reader.ui.navigator

import com.retro99.reader.ui.reader.ReaderSearchResult

/** Resolves the original text context against the live DOM without changing EPUB markup. */
object SearchAnchorResolver {
    const val CLEAR = """(function() {
        if (window.CSS && CSS.highlights) CSS.highlights.delete('ember-search-current');
        document.getElementById('ember-search-current-style')?.remove();
        document.querySelectorAll('[data-ember-search-current]').forEach(function(mark) {
            const parent = mark.parentNode;
            mark.replaceWith(...mark.childNodes);
            parent?.normalize();
        });
        return true;
    })()"""

    fun script(result: ReaderSearchResult, background: Int? = null, foreground: Int? = null): String {
        fun css(color: Int): String = "#" + (color.toLong() and 0xffffff).toString(16).padStart(6, '0')
        val styling = if (background != null && foreground != null) """
            if (window.CSS && CSS.highlights && window.Highlight) {
                let style = document.getElementById('ember-search-current-style');
                if (!style) { style = document.createElement('style'); style.id = 'ember-search-current-style'; document.head.appendChild(style); }
                style.textContent = '::highlight(ember-search-current) { background-color: ${css(background)}; color: ${css(foreground)}; }';
                CSS.highlights.set('ember-search-current', new Highlight(range));
            } else {
                // iOS 15/16 do not have CSS Custom Highlights. Wrap only text-node slices,
                // never whole paragraphs or sentence elements; text and element IDs survive.
                const slices = [];
                let started = false;
                for (const n of nodes) {
                    if (n === found.start.node) started = true;
                    if (!started) continue;
                    slices.push({node:n, start:n === found.start.node ? found.start.offset : 0,
                        end:n === found.end.node ? found.end.offset + 1 : n.nodeValue.length});
                    if (n === found.end.node) break;
                }
                for (const slice of slices.reverse()) {
                    if (slice.end <= slice.start) continue;
                    const part = document.createRange();
                    part.setStart(slice.node, slice.start); part.setEnd(slice.node, slice.end);
                    const span = document.createElement('span');
                    span.setAttribute('data-ember-search-current', '1');
                    span.style.setProperty('background-color', '${css(background)}', 'important');
                    span.style.setProperty('color', '${css(foreground)}', 'important');
                    part.surroundContents(span);
                }
            }
        """ else ""
        return """
            (function() {
                const locator = ${result.locatorJson};
                if (!document.body || !locator.text?.highlight) return null;
                try {
                    const expected = decodeURIComponent(locator.href.split('#')[0]).replace(/^\.\//, '');
                    if (!decodeURIComponent(window.location.pathname).endsWith(expected)) return null;
                } catch (_) { return null; }
                if (${background != null}) document.querySelectorAll('[data-ember-search-current]').forEach(function(mark) {
                    const parent = mark.parentNode;
                    mark.replaceWith(...mark.childNodes);
                    parent?.normalize();
                });
                const nodes = [];
                const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, {
                    acceptNode: function(n) {
                        return n.parentElement?.closest('script,style,noscript,[aria-hidden="true"]') ? NodeFilter.FILTER_REJECT : NodeFilter.FILTER_ACCEPT;
                    }
                });
                let node;
                while ((node = walker.nextNode())) nodes.push(node);
                const normalize = s => s.replace(/\s+/g, ' ');
                function resolve(collapse) {
                    let text = '', map = [], previousBlock = null;
                    const blockSelector = 'p,div,section,article,h1,h2,h3,h4,h5,h6,li,blockquote,td';
                    for (const n of nodes) {
                        const block = n.parentElement?.closest(blockSelector);
                        if (collapse && previousBlock && block !== previousBlock && text && !text.endsWith(' ')) {
                            text += ' '; map.push(null);
                        }
                        for (let i = 0; i < n.nodeValue.length; i++) {
                            const c = n.nodeValue[i];
                            if (collapse && /\s/.test(c)) {
                                if (!text.endsWith(' ')) { text += ' '; map.push({node:n, offset:i}); }
                            } else { text += c; map.push({node:n, offset:i}); }
                        }
                        previousBlock = block;
                    }
                    const match = collapse ? normalize(locator.text.highlight) : locator.text.highlight;
                    const before = collapse ? normalize(locator.text.before || '') : (locator.text.before || '');
                    const after = collapse ? normalize(locator.text.after || '') : (locator.text.after || '');
                    let best = null, index = text.indexOf(match);
                    while (index >= 0) {
                        let score = 0;
                        for (let i = 1; i <= Math.min(before.length, index); i++) {
                            if (before[before.length-i] !== text[index-i]) break; score++;
                        }
                        for (let i = 0; i < Math.min(after.length, text.length-index-match.length); i++) {
                            if (after[i] !== text[index+match.length+i]) break; score++;
                        }
                        // Context is authoritative; estimated progression only breaks repeated-text ties.
                        const distance = Math.abs(index / Math.max(1, text.length) - (locator.locations?.progression || 0));
                        const rank = score * 2 - distance;
                        if ((!best || rank > best.rank) && map[index] && map[index+match.length-1]) {
                            best = {rank:rank, start:map[index], end:map[index+match.length-1]};
                        }
                        index = text.indexOf(match, index + 1);
                    }
                    return best;
                }
                const found = resolve(false) || resolve(true);
                if (!found) return null;
                const range = document.createRange();
                range.setStart(found.start.node, found.start.offset);
                range.setEnd(found.end.node, found.end.offset + 1);
                const sentence = found.start.node.parentElement?.closest('.parrot-sentence,[id*="-sentence"]');
                const sentenceId = sentence?.id || null;
                $styling
                return sentenceId;
            })()
        """.trimIndent()
    }
}
