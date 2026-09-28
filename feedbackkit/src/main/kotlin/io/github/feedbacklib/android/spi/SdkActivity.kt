package io.github.feedbacklib.android.spi

import androidx.annotation.RestrictTo

/**
 * Marks FeedbackKit's own activities (the report screen, the recording consent): they keep the app
 * in foreground but are never "the host screen" — no floating button or recording controls over
 * them, and no invocation from them. Not for apps.
 */
@FeedbackKitSpi
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface SdkActivity
