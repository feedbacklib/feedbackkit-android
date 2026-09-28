package io.github.feedbacklib.android.internal.capture

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.WindowManager
import io.github.feedbacklib.android.internal.core.SdkLogger
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Screenshot of the host window at invocation time (spec §5): PixelCopy (correct for hardware
 * layers and Compose), private regions blacked out before anything reaches disk, nothing at all
 * for FLAG_SECURE windows. Saving the PNG is bounded by `saveTimeoutMillis`; a later file is deleted.
 * One save at a time: a capture while the previous bitmap is still being saved fails at once.
 */
internal class ScreenCapturer(
    private val outputDir: () -> File,
    private val logger: SdkLogger,
    private val ioExecutor: Executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "feedbackkit-capture-io").apply { isDaemon = true }
    },
    private val timeoutMillis: Long = 1_000,
    private val saveTimeoutMillis: Long = 3_000,
) {

    sealed interface Result {
        data class Saved(val file: File) : Result
        data object Secure : Result
        data object Failed : Result
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Set while a bitmap waits for or is in save(); even a timed-out save still holds its bitmap. */
    private val saveOutstanding = AtomicBoolean(false)

    /** Main thread only; [callback] runs on the main thread exactly once. */
    fun capture(activity: Activity, callback: (Result) -> Unit) {
        val delivered = AtomicBoolean(false)
        // Returns whether this call won the race to deliver: a late arrival (e.g. save()
        // finishing after the timeout already fired) must undo its own side effects instead.
        val deliver: (Result) -> Boolean = { result ->
            val won = delivered.compareAndSet(false, true)
            if (won) {
                mainHandler.post {
                    try {
                        callback(result)
                    } catch (e: Exception) {
                        logger.e("Capture callback failed", e)
                    }
                }
            }
            won
        }
        var bitmap: Bitmap? = null
        var timeout: Runnable? = null
        try {
            val window = activity.window
            if (window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                deliver(Result.Secure)
                return
            }
            val decor = window.decorView
            if (decor.width <= 0 || decor.height <= 0) {
                deliver(Result.Failed)
                return
            }
            val regions = PrivateViewRegistry.regionsFor(decor)
            val captured = try {
                Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
            } catch (e: OutOfMemoryError) {
                logger.w("Not enough memory to capture the screen: ${e.message}")
                deliver(Result.Failed)
                return
            }
            bitmap = captured

            // Bounds only the PixelCopy round-trip; cancelled the moment its listener runs, so a
            // slow save() on the io executor is never mistaken for a stuck capture.
            val copyTimeout = Runnable { deliver(Result.Failed) }
            timeout = copyTimeout
            mainHandler.postDelayed(copyTimeout, timeoutMillis)
            PixelCopy.request(window, captured, { copyResult ->
                mainHandler.removeCallbacks(copyTimeout)
                try {
                    if (copyResult == PixelCopy.SUCCESS && !saveOutstanding.compareAndSet(false, true)) {
                        // A disk still busy with the last full-screen bitmap: holding a second one
                        // until it answers only adds memory pressure. Open without a screenshot.
                        logger.w("The previous screenshot is still being saved; opening without one")
                        captured.recycle()
                        deliver(Result.Failed)
                    } else if (copyResult == PixelCopy.SUCCESS) {
                        // A disk that never answers must not keep the coordinator's capture in
                        // flight (and every later invocation blocked): give up and open without it.
                        val saveTimeout = Runnable {
                            if (deliver(Result.Failed)) logger.w("Saving the screenshot took longer than $saveTimeoutMillis ms; opening without it")
                        }
                        mainHandler.postDelayed(saveTimeout, saveTimeoutMillis)
                        try {
                            ioExecutor.execute {
                                val result = try {
                                    save(captured, regions)
                                } finally {
                                    saveOutstanding.set(false)
                                }
                                mainHandler.removeCallbacks(saveTimeout)
                                // Lost the race (a timeout already delivered Failed): the file this
                                // produced must not linger on disk.
                                if (!deliver(result) && result is Result.Saved) {
                                    logger.d("The screenshot was saved after the timeout; deleting it")
                                    result.file.delete()
                                }
                            }
                        } catch (e: Exception) {
                            saveOutstanding.set(false)
                            mainHandler.removeCallbacks(saveTimeout)
                            throw e
                        }
                    } else {
                        logger.w("PixelCopy failed with code $copyResult")
                        captured.recycle()
                        deliver(Result.Failed)
                    }
                } catch (e: Exception) {
                    logger.e("PixelCopy callback failed", e)
                    captured.recycle()
                    deliver(Result.Failed)
                }
            }, mainHandler)
        } catch (e: Exception) {
            logger.e("Screenshot capture failed", e)
            timeout?.let(mainHandler::removeCallbacks)
            bitmap?.recycle()
            deliver(Result.Failed)
        }
    }

    private fun save(bitmap: Bitmap, regions: List<Rect>): Result {
        // Declared outside the try so a failure mid-write never leaves a truncated PNG behind.
        var file: File? = null
        return try {
            mask(bitmap, regions)
            val dir = outputDir().apply { mkdirs() }
            val target = File(dir, "${UUID.randomUUID()}.png")
            file = target
            val written = target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (!written) throw IOException("PNG encoder refused the bitmap")
            Result.Saved(target)
        } catch (e: OutOfMemoryError) {
            logger.w("Not enough memory to save the screenshot: ${e.message}")
            file?.delete()
            Result.Failed
        } catch (e: Exception) {
            logger.e("Could not save the screenshot", e)
            file?.delete()
            Result.Failed
        } finally {
            bitmap.recycle()
        }
    }

    internal companion object {
        fun mask(bitmap: Bitmap, regions: List<Rect>) {
            if (regions.isEmpty()) return
            val canvas = Canvas(bitmap)
            val paint = Paint().apply { color = Color.BLACK }
            regions.forEach { canvas.drawRect(it, paint) }
        }
    }
}
