package io.github.feedbacklib.android

/** Outcome of one delivery attempt by a [ReportSender]. */
public sealed interface SendResult {

    /** Delivered; the report is removed from the device. */
    public data object Success : SendResult

    /** Not delivered, worth retrying later (network, 5xx, 408, 429). */
    public data class RetryableFailure(public val cause: Throwable? = null) : SendResult

    /** Rejected for good (other 4xx); kept on the device as failed, never retried. */
    public data class PermanentFailure(public val cause: Throwable? = null) : SendResult
}
