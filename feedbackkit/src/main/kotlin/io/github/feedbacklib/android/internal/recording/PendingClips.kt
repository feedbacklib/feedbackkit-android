package io.github.feedbacklib.android.internal.recording

import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlinx.coroutines.CompletableDeferred
import java.io.File
import java.util.UUID

/**
 * Auto Screen Recording's clips on their way to a report (spec §7). Finalising the current segment and
 * stitching 30 s can take many seconds on a slow device, so the report opens at once with a token
 * and its clip follows: the invocation [open]s the token and [deliver]s the clip (or null) when the
 * recorder answers; the report [await]s it. Synchronized: the invocation delivers on the main thread,
 * the report awaits from its own scope. Lives as long as the process — after process death a token
 * is unknown and [await] answers null at once.
 *
 * Every clip handed in ends up with its consumer or deleted, never lingering in the cache: one
 * delivered for an abandoned, answered or unknown token, and one abandoned before anyone took it.
 * A clip once taken is the consumer's; the registry never deletes it. [deleteFile] deletes off the
 * main thread.
 */
internal class PendingClips(private val deleteFile: (File) -> Unit, private val logger: SdkLogger) {

    private class Slot {
        /** Completed by the delivery or the abandon; [file] is read under the lock only. */
        val answered = CompletableDeferred<Unit>()
        var delivered = false
        var file: File? = null
    }

    // Guarded by this.
    private val slots = HashMap<String, Slot>()

    fun open(): String {
        val token = UUID.randomUUID().toString()
        synchronized(this) { slots[token] = Slot() }
        return token
    }

    /**
     * The clip of [token], or null when there is none. The first delivery answers the token and
     * returns true; any later one, or one for a token abandoned, taken or unknown, deletes its clip.
     */
    fun deliver(token: String, file: File?): Boolean {
        val slot = synchronized(this) {
            slots[token]?.takeIf { !it.delivered }?.also {
                it.delivered = true
                it.file = file
            }
        }
        if (slot == null) {
            file?.let(::discard)
            return false
        }
        // Outside the lock: a waiter can resume right here, and it takes the lock itself.
        slot.answered.complete(Unit)
        return true
    }

    /**
     * Waits for the answer of [token] and takes it: the clip, then null for everyone after. Null at
     * once for a token this process does not know. Cancelling the wait leaves the clip for a later one.
     */
    suspend fun await(token: String): File? {
        val slot = synchronized(this) { slots[token] } ?: return null
        slot.answered.await()
        return synchronized(this) {
            if (slots[token] === slot) {
                slots.remove(token)
                slot.file
            } else {
                null // abandoned meanwhile, or another waiter took it
            }
        }
    }

    /** The clip of [token] is not wanted any more: deleted now if it came, or when it comes. */
    fun abandon(token: String) {
        // Gone from the map: a delivery that comes later finds no slot and deletes its clip.
        val (slot, arrived) = synchronized(this) {
            val removed = slots.remove(token) ?: return
            val file = removed.file
            removed.file = null
            removed to file
        }
        arrived?.let(::discard)
        slot.answered.complete(Unit) // a waiter wakes up to nothing
    }

    internal companion object {
        /**
         * Where a report keeps the token it awaits, in its saved state; it travels in a report that
         * stepped aside, so whoever drops such a report gives the clip up too.
         */
        const val STATE_KEY: String = "feedbackkit.autoClip"
    }

    private fun discard(file: File) {
        try {
            deleteFile(file)
        } catch (e: Exception) {
            logger.w("Could not delete an automatic screen recording nobody will attach", e)
        }
    }
}
