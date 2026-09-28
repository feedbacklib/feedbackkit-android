package io.github.feedbacklib.android.internal.report

import java.io.File
import java.io.InputStream

private const val BUFFER_SIZE = 64 * 1024

/**
 * Copies at most [maxBytes] from [input] into [target]; returns the byte count, or `null` when the
 * source is larger. Shared by [io.github.feedbacklib.android.internal.queue.ReportStore] and
 * [DraftStore.addImage].
 */
internal fun copyLimited(input: InputStream, target: File, maxBytes: Long): Long? {
    var total = 0L
    target.outputStream().use { out ->
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            out.write(buffer, 0, read)
        }
    }
    return total
}
