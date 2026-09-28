package io.github.feedbacklib.android.internal.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalResources
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.TextKey

@get:StringRes
internal val TextKey.resId: Int
    get() = when (this) {
        TextKey.PROMPT_TITLE -> R.string.feedbackkit_prompt_title
        TextKey.REPORT_BUG -> R.string.feedbackkit_report_bug
        TextKey.REPORT_BUG_DESCRIPTION -> R.string.feedbackkit_report_bug_description
        TextKey.REPORT_FEEDBACK -> R.string.feedbackkit_report_feedback
        TextKey.REPORT_FEEDBACK_DESCRIPTION -> R.string.feedbackkit_report_feedback_description
        TextKey.REPORT_QUESTION -> R.string.feedbackkit_report_question
        TextKey.REPORT_QUESTION_DESCRIPTION -> R.string.feedbackkit_report_question_description
        TextKey.EMAIL_LABEL -> R.string.feedbackkit_email_label
        TextKey.EMAIL_LABEL_OPTIONAL -> R.string.feedbackkit_email_label_optional
        TextKey.EMAIL_INVALID -> R.string.feedbackkit_email_invalid
        TextKey.REQUIRED_FIELD -> R.string.feedbackkit_required_field
        TextKey.COMMENT_HINT_BUG -> R.string.feedbackkit_comment_hint_bug
        TextKey.COMMENT_HINT_FEEDBACK -> R.string.feedbackkit_comment_hint_feedback
        TextKey.COMMENT_HINT_QUESTION -> R.string.feedbackkit_comment_hint_question
        TextKey.SCREENSHOT_UNAVAILABLE -> R.string.feedbackkit_screenshot_unavailable
        TextKey.EXTENDED_TITLE -> R.string.feedbackkit_extended_title
        TextKey.EXTENDED_STEPS -> R.string.feedbackkit_extended_steps
        TextKey.EXTENDED_ACTUAL -> R.string.feedbackkit_extended_actual
        TextKey.EXTENDED_EXPECTED -> R.string.feedbackkit_extended_expected
        TextKey.BUTTON_NEXT -> R.string.feedbackkit_button_next
        TextKey.BUTTON_SEND -> R.string.feedbackkit_button_send
        TextKey.SENDING -> R.string.feedbackkit_sending
        TextKey.SUBMIT_FAILED -> R.string.feedbackkit_submit_failed
        TextKey.SUCCESS_TITLE -> R.string.feedbackkit_success_title
        TextKey.SUCCESS_MESSAGE -> R.string.feedbackkit_success_message
        TextKey.CANCEL_CONFIRM_TITLE -> R.string.feedbackkit_cancel_confirm_title
        TextKey.CANCEL_CONFIRM_MESSAGE -> R.string.feedbackkit_cancel_confirm_message
        TextKey.CANCEL_CONFIRM_DISCARD -> R.string.feedbackkit_cancel_confirm_discard
        TextKey.CANCEL_CONFIRM_KEEP -> R.string.feedbackkit_cancel_confirm_keep
        TextKey.PROACTIVE_PROMPT_MESSAGE -> R.string.feedbackkit_proactive_prompt_message
        TextKey.PROACTIVE_TELL_US -> R.string.feedbackkit_proactive_tell_us
        TextKey.PROACTIVE_NOT_NOW -> R.string.feedbackkit_proactive_not_now
        TextKey.REPORT_FRUSTRATING_EXPERIENCE -> R.string.feedbackkit_report_frustrating_experience
        TextKey.COMMENT_HINT_FRUSTRATING_EXPERIENCE -> R.string.feedbackkit_comment_hint_frustrating_experience
    }

/**
 * Visible texts of the report screen: the host's overrides first, then the en/ru resources. A blank
 * override never blanks a label: setCustomTexts drops them, and this is the last line.
 */
internal class Texts(
    private val overrides: Map<TextKey, String>,
    private val resource: (Int) -> String,
) {
    operator fun get(key: TextKey): String = overrides[key]?.takeIf { it.isNotBlank() } ?: resource(key.resId)
}

internal val LocalTexts = staticCompositionLocalOf<Texts> { error("FeedbackKit texts are not provided") }

@Composable
internal fun rememberTexts(overrides: Map<TextKey, String>): Texts {
    val resources = LocalResources.current
    return remember(overrides, resources) { Texts(overrides) { id -> resources.getString(id) } }
}
