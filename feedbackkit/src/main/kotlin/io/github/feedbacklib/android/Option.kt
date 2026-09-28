package io.github.feedbacklib.android

/** Report form options, see [BugReporting.setOptions]. */
public enum class Option {
    /** The email may be left empty; an email that is filled in must still be valid. */
    EMAIL_FIELD_OPTIONAL,

    /**
     * No email field; the report carries the email from [FeedbackKit.identifyUser], if any.
     * Wins over [EMAIL_FIELD_OPTIONAL].
     */
    EMAIL_FIELD_HIDDEN,

    /** The comment must not be empty. */
    COMMENT_FIELD_REQUIRED,
}
