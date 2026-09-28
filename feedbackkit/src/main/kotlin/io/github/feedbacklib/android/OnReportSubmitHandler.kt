package io.github.feedbacklib.android

/**
 * Called on a background thread right before a report is queued. It has 5 seconds; if it takes
 * longer or throws, the report is queued without its changes. Held by a strong reference —
 * clear it with `null` when its owner goes away.
 */
public fun interface OnReportSubmitHandler {
    public fun onReportSubmit(report: MutableReport)
}
