package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind

/** A screen recording, manual or automatic (spec §7): an MP4 with a video thumbnail and no editor. */
internal val AttachmentKind.isVideo: Boolean
    get() = this == AttachmentKind.SCREEN_RECORDING || this == AttachmentKind.AUTO_SCREEN_RECORDING
