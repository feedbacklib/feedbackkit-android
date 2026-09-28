package io.github.feedbacklib.android

/**
 * Called on the main thread right before the FeedbackKit screen opens (the screenshot is already
 * taken). Held by a strong reference — clear it with `null` when its owner goes away.
 */
public fun interface OnInvokeCallback {
    public fun onInvoke()
}
