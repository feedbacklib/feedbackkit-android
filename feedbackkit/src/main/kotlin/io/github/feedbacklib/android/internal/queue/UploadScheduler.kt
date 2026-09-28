package io.github.feedbacklib.android.internal.queue

/** Asks for the queue to be delivered in the background. */
internal fun interface UploadScheduler {
    fun schedule()
}
