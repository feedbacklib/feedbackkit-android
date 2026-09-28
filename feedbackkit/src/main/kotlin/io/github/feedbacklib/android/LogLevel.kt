package io.github.feedbacklib.android

/**
 * Verbosity of the SDK's own logcat output. Each level includes every level above it:
 * [WARNING] writes errors and warnings. [NONE] silences the SDK entirely.
 */
public enum class LogLevel {
    NONE,
    ERROR,
    WARNING,
    INFO,
    DEBUG,
    VERBOSE,
}
