package io.github.feedbacklib.android.internal.core

/**
 * Process-wide SDK logger for code that has no runtime to inject it into — public senders and the
 * facade before build(). The runtime replaces it with its own configured logger.
 */
internal object SdkLog {
    @Volatile
    var logger: SdkLogger = SdkLogger()
}
