package io.github.feedbacklib.android.internal.core

import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import java.io.OutputStream

/**
 * Lets a save that ran out of memory half-way through encoding, or whose encoding went over
 * [maxBytes], start its output over: a file is truncated, an in-memory buffer reset. Anything else
 * can start over only if nothing was written. Nothing past [maxBytes] reaches [out]: the rest of an
 * encoding that went over is dropped and [overflowed] set.
 */
internal class RestartableOutput(private val out: OutputStream, private val maxBytes: Long) : OutputStream() {
    private var written = 0L

    /** The encoding went over [maxBytes]; what [out] holds is cut short and must not be kept. */
    var overflowed = false
        private set

    override fun write(b: Int) {
        if (fits(1)) {
            out.write(b)
            written++
        }
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (fits(len)) {
            out.write(b, off, len)
            written += len
        }
    }

    private fun fits(len: Int): Boolean {
        if (!overflowed && written + len > maxBytes) overflowed = true
        return !overflowed
    }

    override fun flush() = out.flush()

    /** Empties what was written so far; false when [out] cannot do that. */
    fun restart(): Boolean {
        when {
            written == 0L -> Unit
            out is FileOutputStream -> out.channel.truncate(0).position(0)
            out is ByteArrayOutputStream -> out.reset()
            else -> return false
        }
        written = 0L
        overflowed = false
        return true
    }
}
