package com.retro99.reader.ui.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.retro99.reader.ui.di.ReaderScope
import org.koin.core.annotation.Scope
import org.koin.core.annotation.Scoped

/**
 * Manages audio focus for the media player.
 *
 * This class handles:
 * - Requesting audio focus before playback
 * - Responding to audio focus changes (ducking, pausing)
 * - Releasing audio focus when playback stops
 * - Handling interruptions from phone calls, other apps, etc.
 * - Distinguishing between user-initiated and system-initiated pauses
 *
 * ## Thread Safety
 *
 * The [OnAudioFocusChangeListener] callback may be invoked on a different thread
 * (typically the audio thread). All boolean flags that are read/written from both
 * the main thread and the focus listener are marked as @Volatile to ensure visibility.
 *
 * ExoPlayer methods (play, pause, volume) must be called on the main thread, so
 * the focus listener dispatches these calls via a Handler.
 *
 * Public methods should be called from the main thread.
 */
@OptIn(UnstableApi::class)
@Scope(ReaderScope::class)
@Scoped
class AudioFocusManager(
    private val context: Context,
    private val mediaPlaybackController: MediaPlaybackController,
) {
    /** Gets the ExoPlayer from the service. Returns null if service not started. */
    private val player: ExoPlayer?
        get() = mediaPlaybackController.player
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null

    @Volatile
    private var hasAudioFocus = false

    // Whether we're waiting for delayed focus grant
    @Volatile
    private var pendingDelayedFocus = false

    // Volume level before ducking
    @Volatile
    private var volumeBeforeDuck: Float = 1.0f

    // Whether we were playing before losing focus due to SYSTEM interruption
    // This is only set when the system takes focus away, not when user pauses
    @Volatile
    private var wasPlayingBeforeSystemFocusLoss = false

    // Callback for when delayed focus is granted
    @Volatile
    private var onDelayedFocusGranted: (() -> Unit)? = null

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        // Dispatch to main thread since ExoPlayer methods must be called on main thread
        mainHandler.post {
            handleFocusChange(focusChange)
        }
    }

    /**
     * Handles audio focus changes on the main thread.
     * This is called from the focus listener via mainHandler.post().
     */
    @MainThread
    private fun handleFocusChange(focusChange: Int) {
        val currentPlayer = player ?: return // No player available, nothing to do

        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                // Regained focus - restore volume and optionally resume
                hasAudioFocus = true
                pendingDelayedFocus = false
                currentPlayer.volume = volumeBeforeDuck

                // Only auto-resume if the SYSTEM paused us, not if user manually paused
                if (wasPlayingBeforeSystemFocusLoss) {
                    currentPlayer.play()
                    wasPlayingBeforeSystemFocusLoss = false
                }

                // Handle delayed focus grant
                onDelayedFocusGranted?.invoke()
                onDelayedFocusGranted = null
            }

            AudioManager.AUDIOFOCUS_LOSS -> {
                // Permanent loss - pause playback but don't auto-resume later
                hasAudioFocus = false
                pendingDelayedFocus = false
                // Don't set wasPlayingBeforeSystemFocusLoss - permanent loss shouldn't auto-resume
                currentPlayer.pause()
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                // Temporary loss (e.g., phone call) - pause and remember for auto-resume
                hasAudioFocus = false
                // Only remember if we were actually playing when system interrupted
                if (currentPlayer.isPlaying) {
                    wasPlayingBeforeSystemFocusLoss = true
                    currentPlayer.pause()
                }
            }

            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                // Can duck - lower volume instead of pausing
                hasAudioFocus = true
                volumeBeforeDuck = currentPlayer.volume
                currentPlayer.volume = DUCK_VOLUME
            }
        }
    }

    /**
     * Notifies the manager that the user manually paused playback.
     * This prevents auto-resume when focus is regained.
     */
    @MainThread
    fun onUserPaused() {
        wasPlayingBeforeSystemFocusLoss = false
    }

    /**
     * Requests audio focus for playback.
     *
     * @param onDelayedGrant Optional callback invoked if focus is granted after a delay.
     *                       The caller should start playback when this is called.
     * @return true if focus was granted immediately or delayed, false if denied
     */
    @MainThread
    fun requestFocus(onDelayedGrant: (() -> Unit)? = null): Boolean {
        if (hasAudioFocus) return true

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(true)
            .setOnAudioFocusChangeListener(focusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(focusRequest!!)

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                hasAudioFocus = true
                pendingDelayedFocus = false
                true
            }

            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                // Focus will be granted later via the listener
                hasAudioFocus = false
                pendingDelayedFocus = true
                onDelayedFocusGranted = onDelayedGrant
                // Return true because focus will be granted, just not immediately
                true
            }

            else -> {
                hasAudioFocus = false
                pendingDelayedFocus = false
                false
            }
        }
    }

    /**
     * Checks if we have audio focus or are waiting for delayed focus.
     */
    fun hasFocusOrPending(): Boolean = hasAudioFocus || pendingDelayedFocus

    /**
     * Abandons audio focus.
     * Should be called when playback is stopped.
     */
    @MainThread
    fun abandonFocus() {
        if (!hasAudioFocus && !pendingDelayedFocus) return

        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }

        hasAudioFocus = false
        pendingDelayedFocus = false
        onDelayedFocusGranted = null
        focusRequest = null
    }

    /**
     * Configures the ExoPlayer with appropriate audio attributes.
     * Should be called when creating the player.
     *
     * Note: We set handleAudioFocus=false because we manage audio focus manually
     * Now that ExoPlayer handles audio focus automatically (handleAudioFocus=true in service),
     * this method just ensures audio attributes are set if not already configured.
     */
    @MainThread
    fun configurePlayerAudioAttributes() {
        val currentPlayer = player ?: return // Player not available yet, will be configured later

        val audioAttributes = androidx.media3.common.AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .build()

        // handleAudioFocus=true lets ExoPlayer handle audio focus, which properly
        // activates the audio session and routes audio to the correct output
        currentPlayer.setAudioAttributes(audioAttributes, true)
    }

    companion object {
        private const val DUCK_VOLUME = 0.2f
    }
}

