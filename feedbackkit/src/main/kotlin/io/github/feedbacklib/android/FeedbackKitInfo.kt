package io.github.feedbacklib.android

import io.github.feedbacklib.android.internal.core.SDK_VERSION

/** Build information about the FeedbackKit SDK itself. */
public object FeedbackKitInfo {

    /** Version of the SDK artifact, e.g. `0.1.0`. */
    public val VERSION: String = SDK_VERSION
}
