package com.retro99.reader.ui.reader

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.unit.IntSize
import com.retro99.base.nowMillis
import com.retro99.reader.domain.model.ReaderSettingsDomainModel.Companion.DEFAULT_DOUBLE_TAP_TIMEOUT_MS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Modifier that handles common reader gestures:
 * - Pinch-to-zoom with relative scale factor
 * - Tap in left third of screen (e.g., previous page)
 * - Tap in right third of screen (e.g., next page)
 * - Tap in middle third of screen (e.g., toggle controls)
 * - Double-tap detection (when detectDoubleTaps is true)
 *
 * @param containerSize The size of the container for calculating tap regions
 * @param detectDoubleTaps If true, waits doubleTapTimeoutMs before firing single taps
 *                         to detect double-taps. If false, taps fire immediately.
 * @param doubleTapTimeoutMs Timeout in milliseconds to wait for a second tap before treating as single tap.
 *                           Only used when detectDoubleTaps is true.
 * @param tapNavigationEnabled If false, left/right taps are disabled (middle tap still works)
 * @param onZoomChange Callback during zoom gesture with relative scale (1.0 = no change)
 * @param onZoomEnd Callback when zoom gesture ends with final relative scale
 * @param onLeftTap Callback when user taps left third of screen (only called if tapNavigationEnabled)
 * @param onRightTap Callback when user taps right third of screen (only called if tapNavigationEnabled)
 * @param onMiddleTap Callback when user taps middle third of screen
 * @param onDoubleTap Callback when user double-taps (only called when detectDoubleTaps is true)
 * @param onContentTap Resolves a native content tap before zone or double-tap actions.
 *                     Returns true when the content handled it, even with navigation disabled.
 */
@Composable
internal fun Modifier.readerGestures(
    containerSize: IntSize,
    detectDoubleTaps: Boolean = false,
    doubleTapTimeoutMs: Int = DEFAULT_DOUBLE_TAP_TIMEOUT_MS,
    tapNavigationEnabled: Boolean = true,
    onZoomChange: (scale: Double) -> Unit,
    onZoomEnd: (finalScale: Double) -> Unit,
    onLeftTap: () -> Unit,
    onRightTap: () -> Unit,
    onMiddleTap: () -> Unit,
    onDoubleTap: () -> Unit = {},
    onContentTap: suspend () -> Boolean = { false },
): Modifier {
    val currentOnZoomChange = rememberUpdatedState(onZoomChange)
    val currentOnZoomEnd = rememberUpdatedState(onZoomEnd)
    val currentOnLeftTap = rememberUpdatedState(onLeftTap)
    val currentOnRightTap = rememberUpdatedState(onRightTap)
    val currentOnMiddleTap = rememberUpdatedState(onMiddleTap)
    val currentOnDoubleTap = rememberUpdatedState(onDoubleTap)
    val currentOnContentTap = rememberUpdatedState(onContentTap)

    return this.pointerInput(containerSize, detectDoubleTaps, doubleTapTimeoutMs, tapNavigationEnabled) {
        val touchSlop = viewConfiguration.touchSlop
        var lastTapTimeMs = 0L
        var pendingTapJob: Job? = null
        val scope = CoroutineScope(coroutineContext)

        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val downPosition = down.position
            var zoomAccumulator = 1f
            var gestureActive = false
            var isSingleTap = true

            do {
                val event = awaitPointerEvent(PointerEventPass.Main)
                if (event.changes.size >= 2) {
                    isSingleTap = false
                    val zoomChange = event.calculateZoom()

                    if (zoomChange != 1f) {
                        zoomAccumulator *= zoomChange
                    }

                    val shouldStartZoom = zoomAccumulator < 0.80f || zoomAccumulator > 1.20f
                    if (gestureActive || shouldStartZoom) {
                        gestureActive = true
                        currentOnZoomChange.value(zoomAccumulator.toDouble())
                        event.changes.forEach {
                            if (it.positionChanged()) it.consume()
                        }
                    }
                } else if (isSingleTap) {
                    // Check if any pointer moved beyond touch slop threshold
                    val hasDragged = event.changes.any { change ->
                        val distance = (change.position - downPosition).getDistance()
                        distance > touchSlop
                    }
                    if (hasDragged) {
                        isSingleTap = false
                    }
                }
            } while (event.changes.any { it.pressed })

            // Gesture ended
            if (gestureActive) {
                currentOnZoomEnd.value(zoomAccumulator.toDouble())
            }

            // Handle taps for page navigation and controls toggle
            if (isSingleTap && containerSize.width > 0) {
                val tapX = down.position.x
                val leftThird = containerSize.width / 3f
                val rightThird = containerSize.width * 2f / 3f
                val currentTimeMs = nowMillis()

                // Determine tap action based on region and whether tap navigation is enabled
                val tapAction: (() -> Unit)? = when {
                    tapX < leftThird -> if (tapNavigationEnabled) currentOnLeftTap.value else null
                    tapX > rightThird -> if (tapNavigationEnabled) currentOnRightTap.value else null
                    else -> currentOnMiddleTap.value // Middle tap always works (toggles controls)
                }

                // Native WebViews may consume even empty-page taps. Ask the content
                // explicitly rather than treating interop consumption as a saved hit.
                scope.launch {
                    if (currentOnContentTap.value()) {
                        pendingTapJob?.cancel()
                        pendingTapJob = null
                        lastTapTimeMs = 0L
                        return@launch
                    }
                    if (tapAction == null) return@launch
                    if (!detectDoubleTaps) {
                        tapAction()
                    } else {
                        val timeSinceLastTap = currentTimeMs - lastTapTimeMs
                        val isDoubleTap = timeSinceLastTap < doubleTapTimeoutMs

                        if (isDoubleTap) {
                            pendingTapJob?.cancel()
                            pendingTapJob = null
                            lastTapTimeMs = 0L
                            currentOnDoubleTap.value()
                        } else {
                            lastTapTimeMs = currentTimeMs
                            pendingTapJob?.cancel()
                            pendingTapJob = scope.launch {
                                delay(doubleTapTimeoutMs.toLong())
                                tapAction()
                            }
                        }
                    }
                }
            }
        }
    }
}
