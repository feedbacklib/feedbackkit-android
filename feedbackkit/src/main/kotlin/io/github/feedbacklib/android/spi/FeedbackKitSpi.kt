package io.github.feedbacklib.android.spi

/**
 * Marks the contract between FeedbackKit's own artifacts, `feedbackkit` and `feedbackkit-recording`.
 * It is public only because the JVM has no visibility between two libraries.
 * Apps must not use it: it changes with any release, without deprecation, and only the
 * `feedbackkit-recording` of the same version implements it.
 */
@RequiresOptIn(
    message = "FeedbackKit's contract between its own artifacts; apps must not use it.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY, AnnotationTarget.TYPEALIAS)
public annotation class FeedbackKitSpi
