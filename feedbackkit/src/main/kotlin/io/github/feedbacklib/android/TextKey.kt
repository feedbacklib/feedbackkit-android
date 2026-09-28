package io.github.feedbacklib.android

/**
 * Texts of the FeedbackKit screen a host can replace with [FeedbackKit.setCustomTexts]. The SDK
 * ships English and Russian; a replaced text is shown whatever the device language. Default
 * English texts are given for each key.
 */
public enum class TextKey {
    /** "How can we help?" — title of the report type menu. */
    PROMPT_TITLE,

    /** "Report a bug" — menu item and form title. */
    REPORT_BUG,

    /** "Something in the app is broken" */
    REPORT_BUG_DESCRIPTION,

    /** "Suggest an improvement" — menu item and form title. */
    REPORT_FEEDBACK,

    /** "Tell us what could be better" */
    REPORT_FEEDBACK_DESCRIPTION,

    /** "Ask a question" — menu item and form title. */
    REPORT_QUESTION,

    /** "Ask anything about the app" */
    REPORT_QUESTION_DESCRIPTION,

    /** "Email" — label of a required email field. */
    EMAIL_LABEL,

    /** "Email (optional)" */
    EMAIL_LABEL_OPTIONAL,

    /** "Enter a valid email address" */
    EMAIL_INVALID,

    /** "Required" — under a required field that is still empty. */
    REQUIRED_FIELD,

    /** "What went wrong?" — comment label of a bug report. */
    COMMENT_HINT_BUG,

    /** "What would you like to improve?" */
    COMMENT_HINT_FEEDBACK,

    /** "What is your question?" */
    COMMENT_HINT_QUESTION,

    /** "Screenshot unavailable for this screen" — the screen was secure or could not be captured. */
    SCREENSHOT_UNAVAILABLE,

    /** "Tell us more" — title of the extended bug report step. */
    EXTENDED_TITLE,

    /** "Steps to reproduce" */
    EXTENDED_STEPS,

    /** "Actual result" */
    EXTENDED_ACTUAL,

    /** "Expected result" */
    EXTENDED_EXPECTED,

    /** "Next" */
    BUTTON_NEXT,

    /** "Send" */
    BUTTON_SEND,

    /** "Sending…" */
    SENDING,

    /** "Could not save the report. Please try again." */
    SUBMIT_FAILED,

    /** "Thank you!" */
    SUCCESS_TITLE,

    /** "Your report is on its way." */
    SUCCESS_MESSAGE,

    /** "Discard the report?" */
    CANCEL_CONFIRM_TITLE,

    /** "What you have entered will be lost." */
    CANCEL_CONFIRM_MESSAGE,

    /** "Discard" */
    CANCEL_CONFIRM_DISCARD,

    /** "Keep editing" */
    CANCEL_CONFIRM_KEEP,

    /** "Looks like something went wrong. Would you tell us what happened?" — proactive reporting's prompt. */
    PROACTIVE_PROMPT_MESSAGE,

    /** "Tell us" — opens the report from the prompt. */
    PROACTIVE_TELL_US,

    /** "Not now" — closes the prompt. */
    PROACTIVE_NOT_NOW,

    /** "What happened?" — title of the report the prompt opens. */
    REPORT_FRUSTRATING_EXPERIENCE,

    /** "Describe what happened" — its comment label. */
    COMMENT_HINT_FRUSTRATING_EXPERIENCE,
}
