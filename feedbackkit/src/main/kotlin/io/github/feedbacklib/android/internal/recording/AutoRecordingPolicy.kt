package io.github.feedbacklib.android.internal.recording

internal enum class AutoPhase { OFF, IDLE, ASKING, RECORDING, PAUSED }

internal enum class AutoCommand { ASK, PAUSE, RESUME, STOP }

/**
 * When Auto Screen Recording asks, records, pauses and stops (spec §7) — pure, so every sequence is
 * tested on the JVM. Each event returns the commands to carry out, in order. What it knows of the app
 * (foreground, a host screen resumed, FeedbackKit's screen open) is kept in every phase, OFF included.
 *
 * - Consent is asked over a resumed host screen, never over FeedbackKit's own: at once when switched
 *   on while a screen shows, otherwise on the first screen of the next foreground.
 * - Declined: not asked again until the process ends, whatever the host toggles.
 * - Failed to start, or the projection ended (the shade, a lock, another projection, a manual
 *   recording): asked again on the next transition to the foreground, not at once.
 * - Background or FeedbackKit's screen: paused; back to a visible app: resumed; a projection gone by
 *   then is asked for again.
 */
internal class AutoRecordingPolicy {

    var phase: AutoPhase = AutoPhase.OFF
        private set

    var refused: Boolean = false
        private set

    private var askPending = false
    private var foreground = false
    private var hostResumed = false
    private var uiOpen = false

    fun setEnabled(enabled: Boolean): List<AutoCommand> {
        if (enabled) {
            if (phase != AutoPhase.OFF) return NONE
            phase = AutoPhase.IDLE
            askPending = true
            return tryAsk()
        }
        val hadSession = phase == AutoPhase.ASKING || phase == AutoPhase.RECORDING || phase == AutoPhase.PAUSED
        phase = AutoPhase.OFF
        askPending = false
        return if (hadSession) listOf(AutoCommand.STOP) else NONE
    }

    fun onForeground(value: Boolean): List<AutoCommand> {
        if (foreground == value) return NONE
        foreground = value
        if (!value) {
            hostResumed = false
            if (phase != AutoPhase.RECORDING) return NONE
            phase = AutoPhase.PAUSED
            return listOf(AutoCommand.PAUSE)
        }
        return when (phase) {
            AutoPhase.PAUSED -> resumeIfVisible()
            AutoPhase.IDLE -> {
                askPending = true
                tryAsk()
            }
            else -> NONE
        }
    }

    fun onHost(resumed: Boolean): List<AutoCommand> {
        hostResumed = resumed
        return if (resumed) tryAsk() else NONE
    }

    fun onUiOpen(open: Boolean): List<AutoCommand> {
        if (uiOpen == open) return NONE
        uiOpen = open
        return when {
            open && phase == AutoPhase.RECORDING -> {
                phase = AutoPhase.PAUSED
                listOf(AutoCommand.PAUSE)
            }
            !open && phase == AutoPhase.PAUSED -> resumeIfVisible()
            !open -> tryAsk()
            else -> NONE
        }
    }

    fun onStarted(): List<AutoCommand> {
        if (phase != AutoPhase.ASKING) return NONE
        if (foreground && !uiOpen) {
            phase = AutoPhase.RECORDING
            return NONE
        }
        phase = AutoPhase.PAUSED
        return listOf(AutoCommand.PAUSE)
    }

    fun onNotStarted(refused: Boolean): List<AutoCommand> {
        if (phase != AutoPhase.ASKING) return NONE
        phase = AutoPhase.IDLE
        askPending = false
        if (refused) this.refused = true
        return NONE
    }

    /**
     * The projection ended, or a manual recording took the device's one projection: the next consent
     * waits for the next foreground. A question still waiting for its first screen waits too — not
     * asked right after the report that recorded closes.
     */
    fun onEnded(): List<AutoCommand> {
        if (phase == AutoPhase.OFF) return NONE
        phase = AutoPhase.IDLE
        askPending = false
        return NONE
    }

    /** RESUME found the projection gone: a new consent (spec §7). */
    fun onResumeFailed(): List<AutoCommand> {
        if (phase != AutoPhase.RECORDING) return NONE
        phase = AutoPhase.IDLE
        askPending = true
        return tryAsk()
    }

    /** ASK found no host screen after all: the next resumed one asks. */
    fun onAskFailed(): List<AutoCommand> {
        if (phase != AutoPhase.ASKING) return NONE
        phase = AutoPhase.IDLE
        askPending = true
        return NONE
    }

    private fun resumeIfVisible(): List<AutoCommand> {
        if (!foreground || uiOpen) return NONE
        phase = AutoPhase.RECORDING
        return listOf(AutoCommand.RESUME)
    }

    private fun tryAsk(): List<AutoCommand> {
        if (phase != AutoPhase.IDLE || !askPending || refused || !foreground || !hostResumed || uiOpen) return NONE
        phase = AutoPhase.ASKING
        askPending = false
        return listOf(AutoCommand.ASK)
    }

    private companion object {
        val NONE: List<AutoCommand> = emptyList()
    }
}
