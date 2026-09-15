package com.retro99.reader.ui.navigator

/**
 * Utility for detecting taps on sentence elements in the EPUB WebView.
 *
 * This injects JavaScript that:
 * 1. Listens for click events on the document
 * 2. Prefers a generated TTS sentence, then finds the closest element with an ID
 * 3. Calls a native callback with the fragment ID
 *
 * Double-tap recognition is handled by [SentenceDoubleTapRecognizer] in common Kotlin code.
 * This script only reports each tap with the element ID.
 *
 * The callback is registered via a JavaScript interface on Android or message handler on iOS.
 */
object DoubleTapDetector {

    /**
     * JavaScript code to inject for tap detection on sentence elements.
     *
     * This script:
     * - Listens for 'click' events (single taps)
     * - Prefers the generated TTS sentence ancestor over nested IDs
     * - Falls back to the nearest element with an ID for media-overlay sentences
     * - Calls a native callback with the fragment ID
     *
     * Native code is responsible for double-tap detection timing.
     *
     * The callback name is configurable to support different platforms:
     * - Android: Uses JavaScriptInterface with a specific method name
     * - iOS: Uses WKScriptMessageHandler
     *
     * @param callbackName The name of the native callback function to invoke
     */
    fun getTapDetectionScript(callbackName: String = "SentenceTap"): String {
        return """
            (function() {
                // Prevent multiple injections
                if (window.__tapDetectorInstalled) return;
                window.__tapDetectorInstalled = true;

                function reportTap(fragmentId) {
                    try {
                        if (typeof $callbackName !== 'undefined' && $callbackName.onTap) {
                            $callbackName.onTap(fragmentId);
                        } else if (
                            window.webkit &&
                            window.webkit.messageHandlers &&
                            window.webkit.messageHandlers.$callbackName
                        ) {
                            window.webkit.messageHandlers.$callbackName.postMessage(fragmentId);
                        }
                    } catch (e) {
                        console.error('Tap callback error:', e);
                    }
                }

                document.addEventListener('click', function(event) {
                    var element = event.target;
                    var ttsSentence = element && element.closest
                        ? element.closest('.parrot-sentence')
                        : null;
                    if (ttsSentence && ttsSentence.id) {
                        reportTap(ttsSentence.id);
                        return;
                    }

                    while (element && element !== document.body) {
                        if (element.id) {
                            reportTap(element.id);
                            return;
                        }
                        element = element.parentElement;
                    }

                    reportTap('');
                }, { passive: true });
            })();
        """.trimIndent()
    }

    /**
     * JavaScript code to remove the tap detector.
     * Call this when cleaning up the WebView.
     */
    fun getRemoveTapDetectorScript(): String {
        return """
            (function() {
                window.__tapDetectorInstalled = false;
            })();
        """.trimIndent()
    }
}
