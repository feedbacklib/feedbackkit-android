package io.github.feedbacklib.android.recording

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/** What a recorded file holds, read the way a player would. */
internal data class VideoInfo(val videoTracks: Int, val audioTracks: Int, val videoMime: String?, val durationUs: Long, val width: Int, val height: Int, val videoSamples: Int)

internal object Mp4Probe {
    fun read(file: File): VideoInfo {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val formats = (0 until extractor.trackCount).map(extractor::getTrackFormat)
            val video = formats.filter { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            val first = video.firstOrNull()
            val samples = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                .takeIf { it >= 0 }?.let { countSamples(extractor, it) } ?: 0
            return VideoInfo(
                videoTracks = video.size,
                audioTracks = formats.count { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true },
                videoMime = first?.getString(MediaFormat.KEY_MIME),
                durationUs = first?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION) ?: 0L,
                width = first?.getInteger(MediaFormat.KEY_WIDTH) ?: 0,
                height = first?.getInteger(MediaFormat.KEY_HEIGHT) ?: 0,
                videoSamples = samples,
            )
        } finally {
            extractor.release()
        }
    }

    /** Frames actually in the track: what a player shows, whatever the duration says. */
    private fun countSamples(extractor: MediaExtractor, track: Int): Int {
        extractor.selectTrack(track)
        var count = 0
        while (extractor.sampleTrackIndex >= 0) {
            count++
            extractor.advance()
        }
        return count
    }
}
