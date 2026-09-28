package io.github.feedbacklib.android

/**
 * Delivers one stored report. Called from a background worker, one report at a time, oldest first.
 * An exception thrown from [send] is treated as [SendResult.RetryableFailure].
 */
public interface ReportSender {
    public suspend fun send(report: PreparedReport): SendResult
}
