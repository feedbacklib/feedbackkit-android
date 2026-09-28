package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `session.json` (spec §8): the run going on, as the next start will read it. */
@Serializable
internal data class SessionState(
    val sessionId: String,
    /** Wall clock of this run's build(). */
    val startedAt: Long,
    /** Wall clock of the last heartbeat in the foreground, or of leaving it; 0 while never in the foreground. */
    val lastForegroundAt: Long,
    /** Not in the foreground now: written at once on leaving it, and true before the first foreground. */
    val wentBackground: Boolean,
    /** Wall clock of the last time the app left the foreground; 0 if it never did, and in a file of the previous version. */
    val lastBackgroundAt: Long = 0,
    /** Wall clock of the last heartbeat, in the foreground or in the 10 s after leaving it; 0 if none, and in a file of the previous version. */
    val lastAliveAt: Long = 0,
)

/** `crash.marker` (spec §8): the uncaught exception that ended a run. */
@Serializable
internal data class CrashMarker(
    val time: Long,
    /** The exception's class name. */
    val exception: String,
    /** At most 50 lines, the first with the message. */
    val stacktrace: String,
)

/**
 * `proactive.json` (spec §8). Every field may be missing: the previous version wrote only [lastModalAt],
 * and detection can write [pending] before any prompt has shown.
 */
@Serializable
internal data class ProactiveState(
    /** Wall clock of the last prompt shown; null if none yet. */
    val lastModalAt: Long? = null,
    /** Timestamp of the newest ApplicationExitInfo record already looked at; null before the first look. */
    val lastProcessedExitAt: Long? = null,
    /** The event still waiting for its prompt. */
    val pending: PendingEvent? = null,
)

/** A crash found by the start whose build() was at [detectedAt], waiting for its prompt (spec §8); a force restart is never written here. */
@Serializable
internal data class PendingEvent(
    val detectedAt: Long,
    val trigger: ProactiveTrigger,
    /** As in the report's `proactive` block: the exception class, or "ANR" / "Native crash" / "Crash". */
    val exception: String? = null,
    val stacktrace: String? = null,
)

/** The JSON of the session files. Decoding never throws: a damaged, foreign or incomplete file is no state. */
internal object SessionJson {

    private val json = Json {
        ignoreUnknownKeys = true // a newer version may add fields
        encodeDefaults = true
    }

    fun encodeSession(state: SessionState): String = json.encodeToString(SessionState.serializer(), state)

    fun decodeSession(text: String): SessionState? = decode(SessionState.serializer(), text)

    fun encodeCrash(marker: CrashMarker): String = json.encodeToString(CrashMarker.serializer(), marker)

    fun decodeCrash(text: String): CrashMarker? = decode(CrashMarker.serializer(), text)

    fun encodeProactive(state: ProactiveState): String = json.encodeToString(ProactiveState.serializer(), state)

    fun decodeProactive(text: String): ProactiveState? = decode(ProactiveState.serializer(), text)

    private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        try {
            json.decodeFromString(serializer, text)
        } catch (e: IllegalArgumentException) { // SerializationException, and a root that is not an object
            null
        }
}
