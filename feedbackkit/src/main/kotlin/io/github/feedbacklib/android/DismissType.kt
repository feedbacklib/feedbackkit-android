package io.github.feedbacklib.android

/** Why the FeedbackKit screen closed, see [OnDismissCallback]. */
public enum class DismissType {
    /** A report was queued and the thank-you screen closed. */
    SUBMIT,

    /** The user closed the screen without sending. */
    CANCEL,

    /**
     * The screen stepped aside so the user could capture another screen of the app ("Add
     * screenshot"). The report is kept: it opens again after Capture or Cancel and reports SUBMIT
     * or CANCEL when it finally closes — CANCEL also if FeedbackKit is disabled meanwhile.
     * FeedbackKit ignores show() and invocation events until then.
     */
    ADD_ATTACHMENT,
}
