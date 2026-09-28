package io.github.feedbacklib.android

/** The optional second step of a bug report: steps to reproduce, actual and expected result. */
public object ExtendedBugReport {

    /** See [BugReporting.setExtendedBugReportState]. */
    public enum class State {
        DISABLED,
        ENABLED_WITH_REQUIRED_FIELDS,
        ENABLED_WITH_OPTIONAL_FIELDS,
    }
}
