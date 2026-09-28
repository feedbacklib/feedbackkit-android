package io.github.feedbacklib.android.internal.ui.screens

import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.annotate.AnnotationTool
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.annotate.PenWidth

/** Semantics test tags of the report screens (device tests only). */
internal object ReportTestTags {
    const val TOP_BAR = "feedbackkit.topBar"
    const val MENU = "feedbackkit.menu"
    const val PROACTIVE_PROMPT = "feedbackkit.proactivePrompt"
    const val PROACTIVE_ACCEPT = "feedbackkit.proactiveAccept"
    const val PROACTIVE_DECLINE = "feedbackkit.proactiveDecline"
    const val EMAIL_FIELD = "feedbackkit.email"
    const val COMMENT_FIELD = "feedbackkit.comment"
    const val COMMENT_COUNTER = "feedbackkit.commentCounter"
    const val STEPS_FIELD = "feedbackkit.steps"
    const val ACTUAL_FIELD = "feedbackkit.actual"
    const val EXPECTED_FIELD = "feedbackkit.expected"
    const val ATTACHMENTS = "feedbackkit.attachments"
    const val SCREENSHOT_UNAVAILABLE = "feedbackkit.screenshotUnavailable"
    const val ADD_IMAGE = "feedbackkit.addImage"
    const val ADD_SCREENSHOT = "feedbackkit.addScreenshot"
    const val RECORD_SCREEN = "feedbackkit.recordScreen"

    /** The placeholder of the automatic recording still being made. */
    const val AUTO_CLIP_PENDING = "feedbackkit.autoClipPending"
    const val ATTACHMENT_LIMIT = "feedbackkit.attachmentLimit"
    const val ATTACH_NOTICE = "feedbackkit.attachNotice"

    /** The visible part of an attachment's remove button; its touch target is larger. */
    const val ATTACHMENT_REMOVE_BADGE = "feedbackkit.attachmentRemoveBadge"

    /** The camera badge on a recording's thumbnail. */
    const val ATTACHMENT_VIDEO_BADGE = "feedbackkit.attachmentVideoBadge"
    const val PRIMARY_BUTTON = "feedbackkit.primary"
    const val SUBMIT_ERROR = "feedbackkit.submitError"
    const val SUCCESS = "feedbackkit.success"
    const val ANNOTATION_CANVAS = "feedbackkit.annotationCanvas"
    const val ANNOTATION_DONE = "feedbackkit.annotationDone"
    const val ANNOTATION_ERROR = "feedbackkit.annotationError"

    fun menuItem(type: ReportType): String = "feedbackkit.menu.${type.name}"

    fun attachment(fileName: String): String = "feedbackkit.attachment.$fileName"

    fun tool(tool: AnnotationTool): String = "feedbackkit.tool.${tool.name}"

    fun penColor(color: PenColor): String = "feedbackkit.penColor.${color.name}"

    fun penWidth(width: PenWidth): String = "feedbackkit.penWidth.${width.name}"
}
