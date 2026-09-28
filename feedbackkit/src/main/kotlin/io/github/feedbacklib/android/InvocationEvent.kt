package io.github.feedbacklib.android

/** How the user can open FeedbackKit (spec §4, §5). [NONE] leaves only `FeedbackKit.show()`. */
public enum class InvocationEvent {
    NONE,
    SHAKE,
    SCREENSHOT,
    FLOATING_BUTTON,
    TWO_FINGER_SWIPE_LEFT,
}
