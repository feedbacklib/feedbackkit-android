package io.github.feedbacklib.android.recording.internal

import java.io.File

/**
 * The last [capacity] segments of Auto Screen Recording (spec §7: the last four), the one being
 * written included. Pure: it only decides which files go; the caller deletes what it returns.
 */
internal class SegmentRing(private val capacity: Int) {

    init {
        require(capacity >= 1) { "A ring needs room for the segment being written" }
    }

    private val finished = ArrayDeque<File>()

    /** The segment being written, if the recorder runs. */
    var current: File? = null
        private set

    /**
     * [file] is now being written; the previous current one is finished. If the recorder's `stop()`
     * for that previous segment failed, the executor calls [finishCurrent] with `usable = false`
     * before this, so a segment already open is never silently dropped here. Segment names are never
     * reused: [file] must be neither the current segment nor any already-finished one. Returns what
     * fell out.
     */
    fun begin(file: File): List<File> {
        require(file != current && file !in finished) { "Segment names are never reused: $file" }
        current?.let(finished::addLast)
        current = file
        return trim()
    }

    /** The recorder stopped (pause, clip, restart): the current segment is finished, or dropped when not [usable]. */
    fun finishCurrent(usable: Boolean): List<File> {
        val file = current ?: return emptyList()
        current = null
        if (!usable) return listOf(file)
        finished.addLast(file)
        return trim()
    }

    /** Finished segments, oldest first. */
    fun segments(): List<File> = finished.toList()

    fun clear(): List<File> {
        val all = finished.toList() + listOfNotNull(current)
        finished.clear()
        current = null
        return all
    }

    private fun trim(): List<File> {
        val dropped = mutableListOf<File>()
        while (finished.size + (if (current != null) 1 else 0) > capacity) dropped += finished.removeFirst()
        return dropped
    }
}
