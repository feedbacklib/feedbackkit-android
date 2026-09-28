package io.github.feedbacklib.android.internal.recording

/** Numbers and places of screen recording (spec §7). */
internal object RecordingLimits {
    /** A manual recording stops by itself after this. */
    const val MAX_MANUAL_RECORDING_MILLIS: Long = 60_000

    /** What an invocation attaches of Auto Screen Recording. */
    const val AUTO_CLIP_WINDOW_MILLIS: Long = 30_000

    /** Under cacheDir: recordings before they join a draft; purged after 24 h like captures. */
    const val CACHE_DIR: String = "feedbackkit/recording"

    /** Under [CACHE_DIR]: Auto Screen Recording's segments, owned by feedbackkit-recording. */
    const val SEGMENTS_SUBDIR: String = "segments"
}
