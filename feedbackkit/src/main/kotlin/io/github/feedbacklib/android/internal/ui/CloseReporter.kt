package io.github.feedbacklib.android.internal.ui

import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.OnDismissCallback
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.SdkLogger

/**
 * Tells the invocation coordinator, then the host, that the SDK screen closed — exactly once, however
 * many lifecycle callbacks report it. The coordinator goes first so a host calling show() from
 * onDismiss is not ignored. Never throws (spec §10). Main thread.
 */
internal class CloseReporter(
    private val onUiClosed: (DismissType) -> Unit,
    private val dismissCallback: () -> OnDismissCallback?,
    private val logger: SdkLogger,
) {
    var reported: Boolean = false
        private set

    fun report(dismissType: DismissType, reportType: ReportType) {
        if (reported) return
        reported = true
        try {
            onUiClosed(dismissType)
        } catch (e: Exception) {
            logger.e("Could not report the closed FeedbackKit screen", e)
        }
        try {
            dismissCallback()?.onDismiss(dismissType, reportType)
        } catch (e: Exception) {
            logger.e("onDismissCallback threw; ignored", e)
        }
    }
}
