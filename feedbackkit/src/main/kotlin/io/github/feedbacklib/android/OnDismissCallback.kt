package io.github.feedbacklib.android

/**
 * Called on the main thread once the FeedbackKit screen has closed: [DismissType.SUBMIT] after a
 * report was queued, [DismissType.CANCEL] when the user left without sending,
 * [DismissType.ADD_ATTACHMENT] when it only stepped aside for an extra screenshot; the same report
 * comes back afterwards. [reportType] is the type the user was writing, or the first offered type
 * if they closed the menu before choosing. After SUBMIT or CANCEL, invocations are accepted again
 * as soon as a host screen is back in front; a host that wants to reopen FeedbackKit right away
 * from here should post the call, for example with `Handler.post`. Held by a strong reference —
 * clear it with `null` when its owner goes away.
 */
public fun interface OnDismissCallback {
    public fun onDismiss(dismissType: DismissType, reportType: ReportType)
}
