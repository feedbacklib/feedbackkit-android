package io.github.feedbacklib.android.internal.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.feedbacklib.android.internal.report.DraftFile
import java.io.File

/**
 * The largest power-of-two sample size that keeps [height] at least [minHeight]. The thumbnail tile
 * fixes the height and fits the image into it (`ContentScale.Fit`), so the height is what determines
 * the rendered resolution, whichever side is longer: sampling against the longest side would
 * oversample a landscape screenshot (its height is the *shorter* side) and blur it once scaled to
 * the tile.
 *
 * The width is bounded as well, to [MAX_THUMBNAIL_ASPECT] tile heights: the tile narrows a
 * panorama to its own width anyway, and sampled by its height alone a panorama decodes to megabytes.
 */
internal fun sampleSizeFor(width: Int, height: Int, minHeight: Int): Int {
    if (width <= 0 || height <= 0 || minHeight <= 0) return 1
    var sample = 1
    while (height / (sample * 2) >= minHeight) sample *= 2
    val maxWidth = minHeight.toLong() * MAX_THUMBNAIL_ASPECT
    while (width / sample > maxWidth) sample *= 2
    return sample
}

/** The widest thumbnail decoded, in tile heights. */
private const val MAX_THUMBNAIL_ASPECT = 4

/** A downsampled, EXIF-turned preview of an image file, or null when it cannot be read. Disk I/O: background only. */
internal fun decodeThumbnail(file: File, targetHeightPx: Int): ImageBitmap? =
    try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        val orientation = ExifOrientation.read(file)
        val (width, height) = ExifOrientation.orientedSize(bounds.outWidth, bounds.outHeight, orientation)
        // Sampled against the height the tile shows, which for a turned photo is its decoded width.
        val options = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(width, height, targetHeightPx) }
        BitmapFactory.decodeFile(file.path, options)?.let { ExifOrientation.apply(it, orientation) }?.asImageBitmap()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    }

/** The size a video thumbnail frame is decoded at: [targetHeight] tall at most, never up, the width by aspect. Pure. */
internal fun videoFrameSize(width: Int, height: Int, targetHeight: Int): Pair<Int, Int> {
    if (width <= 0 || height <= 0 || targetHeight <= 0) return width to height
    val h = minOf(height, targetHeight)
    return maxOf(1, (width.toLong() * h / height).toInt()) to h
}

/**
 * A frame a second into a recording (or its midpoint, whichever is sooner), scaled to the tile
 * height; null when it cannot be read (no frame, a broken file, or no memory: on API 26+ decoders
 * return null or throw OutOfMemoryError). Never the very first frame: for a manual recording that
 * is the report form itself, caught by the projection before the user did anything worth showing.
 * A portrait clip's rotation is read and its width and height swapped accordingly, so the tile is
 * sized the way the frame is actually drawn. Disk I/O and decoding: background only.
 */
internal fun decodeVideoFrame(file: File, targetHeightPx: Int): ImageBitmap? {
    var retriever: MediaMetadataRetriever? = null
    return try {
        val source = MediaMetadataRetriever().also { retriever = it }
        source.setDataSource(file.path)
        val rotation = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val rawWidth = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
        val rawHeight = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
        val (width, height) = if (rotation == 90 || rotation == 270) rawHeight to rawWidth else rawWidth to rawHeight
        val (w, h) = videoFrameSize(width, height, targetHeightPx)
        val durationMs = source.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val frameTimeUs = minOf(1_000L, durationMs / 2) * 1_000L
        val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1 && w > 0 && h > 0) {
            source.getScaledFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, w, h)
        } else {
            source.getFrameAtTime(frameTimeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)?.let { full ->
                if (w <= 0 || h <= 0 || full.height <= h) {
                    full
                } else {
                    Bitmap.createScaledBitmap(full, w, h, true).also { if (it !== full) full.recycle() }
                }
            }
        }
        frame?.asImageBitmap()
    } catch (e: Exception) {
        null
    } catch (e: OutOfMemoryError) {
        null
    } finally {
        try {
            retriever?.release()
        } catch (e: Exception) {
            // Nothing more to free.
        }
    }
}

/** Which decoded thumbnail a draft file has; a file the editor rewrote gets a new key. */
internal data class ThumbnailKey(val path: String, val lastModified: Long, val sizeBytes: Long, val heightPx: Int) {
    companion object {
        fun of(file: DraftFile, heightPx: Int): ThumbnailKey = ThumbnailKey(file.file.path, file.lastModified, file.sizeBytes, heightPx)
    }
}

/**
 * Thumbnails decoded in this process, bounded by decoded bytes rather than by entry count: a
 * handful of large photos must not pin tens of MB in the host for as long as the process lives.
 * [android.util.LruCache] is internally synchronized, so no extra locking is needed here. Cleared
 * when the report screen really closes and on any memory-trim signal (see `FeedbackActivity`) — the
 * cache is only a decode-avoiding speed-up, never a source of truth, so dropping it early costs
 * nothing but a redecode.
 */
internal object ThumbnailCache {
    private const val BUDGET_BYTES = 4 * 1024 * 1024

    private val cache = object : LruCache<ThumbnailKey, ImageBitmap>(BUDGET_BYTES) {
        override fun sizeOf(key: ThumbnailKey, value: ImageBitmap): Int = value.asAndroidBitmap().allocationByteCount
    }

    fun get(key: ThumbnailKey): ImageBitmap? = cache.get(key)

    fun put(key: ThumbnailKey, bitmap: ImageBitmap) {
        cache.put(key, bitmap)
    }

    /** Drops every held thumbnail. */
    fun clear() = cache.evictAll()
}
