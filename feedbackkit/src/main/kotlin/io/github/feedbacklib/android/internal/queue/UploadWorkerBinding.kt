package io.github.feedbacklib.android.internal.queue

/**
 * How the WorkManager-instantiated worker reaches the SDK runtime. Set in build(); a null provider
 * (SDK not initialised in this process) or a null runner (SDK disabled) means "nothing to do".
 */
internal object UploadWorkerBinding {
    @Volatile
    var runnerProvider: (() -> UploadRunner?)? = null
}
