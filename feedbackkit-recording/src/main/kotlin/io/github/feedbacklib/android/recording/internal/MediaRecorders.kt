package io.github.feedbacklib.android.recording.internal

import android.content.Context
import android.hardware.display.DisplayManager
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.os.Build
import android.util.DisplayMetrics
import android.view.Display
import io.github.feedbacklib.android.spi.RecorderLog
import java.io.File

/** Created on the session thread, so its info and error events arrive there too (its looper). */
internal fun newMediaRecorder(context: Context): MediaRecorder =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()

/**
 * Video only (spec §7): no audio source is ever set, so the file has no audio track. [maxDurationMillis]
 * is the recorder's own limit, only a second guard behind the session's [RecordingLimit] and so set
 * later than it ([recorderMaxDurationMillis]); it counts media time, not the clock, and fires late
 * when frames are sparse. 0 sets none.
 */
internal fun MediaRecorder.configure(video: VideoSpec, output: File, maxDurationMillis: Long) {
    setVideoSource(MediaRecorder.VideoSource.SURFACE)
    setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
    setVideoEncoder(MediaRecorder.VideoEncoder.H264)
    setVideoSize(video.width, video.height)
    setVideoFrameRate(video.frameRate)
    setVideoEncodingBitRate(video.bitRate)
    if (maxDurationMillis > 0) setMaxDuration(maxDurationMillis.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    setOutputFile(output)
}

/** The whole default display (system bars included), whatever the app's window is. */
internal fun displayVideoSpec(context: Context): VideoSpec {
    val display = checkNotNull(context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)) { "No default display" }
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION") // the one call that gives the full display size on every API 26-37
    display.getRealMetrics(metrics)
    return VideoSpec.fit(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
}

/**
 * This size, or the largest smaller one of the same aspect that some H.264 encoder of the device takes
 * ([VideoSpec.fitEncoder]); MediaRecorder tries the matching encoders in turn. Unchanged, with a
 * warning, when the encoders cannot be listed or none takes any size: prepare() then reports the real
 * failure. Session thread: the codec list is built on first use.
 */
internal fun VideoSpec.forAvcEncoder(log: RecorderLog): VideoSpec {
    val encoders = avcEncoders(log)
    if (encoders.isEmpty()) {
        log.w("Found no H.264 encoder to check ${width}x$height against")
        return this
    }
    val fitted = fitEncoder { w, h -> encoders.any { it.isSizeSupported(w, h) } }
    if (fitted == null) {
        log.w("No H.264 encoder takes ${width}x$height or a smaller size; trying it anyway")
        return this
    }
    if (fitted != this) log.i("No H.264 encoder takes ${width}x$height; recording at ${fitted.width}x${fitted.height}")
    return fitted
}

private fun avcEncoders(log: RecorderLog): List<MediaCodecInfo.VideoCapabilities> {
    val codecs = try {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    } catch (error: Exception) {
        log.w("Could not list the video encoders", error)
        return emptyList()
    }
    return codecs
        .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, ignoreCase = true) } }
        .mapNotNull { info ->
            try {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            } catch (error: Exception) {
                log.d("Could not read the capabilities of ${info.name}", error)
                null
            }
        }
}
