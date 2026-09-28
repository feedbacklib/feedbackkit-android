package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import io.github.feedbacklib.android.internal.capture.ScreenCapturer
import io.github.feedbacklib.android.internal.core.SdkLogger

/** SDK views drawn over the host that must never appear in its screenshots. Main thread. */
internal fun interface CaptureHider {
    /** Hides this component's views on [activity], then calls [onHidden] with `restore` once a frame without them was drawn. */
    fun hideForCapture(activity: Activity, onHidden: (restore: () -> Unit) -> Unit)
}

/** A frame at any refresh rate comes well within this; a window that has not drawn by then is not drawing. */
private const val FRAME_WAIT_MILLIS = 250L

/**
 * Hides [view] and calls [onHidden] once a frame without it has been drawn, handing it `restore`,
 * which makes the view visible again (idempotent, never throws). With no [view], [onHidden] runs
 * straight away with a no-op. [what] names the view in logs. Main thread.
 *
 * Waiting for the frame: an [ViewTreeObserver.OnDrawListener] fires when the window draws, and that
 * draw already reflects INVISIBLE. PixelCopy is requested from a message posted from that listener,
 * i.e. after the draw traversal has handed the frame to the RenderThread; the copy request is queued
 * on the same RenderThread behind that frame, so it reads the buffer without the view. A Choreographer
 * frame callback would only say a frame started, not that it was drawn, and would need a guessed extra
 * frame. A window that draws nothing (e.g. stopped) never fires the listener; [FRAME_WAIT_MILLIS] then
 * proceeds anyway, so the capture's own timeout still bounds only the PixelCopy round-trip.
 */
internal fun hideUntilDrawn(view: View?, logger: SdkLogger, what: String, onHidden: (restore: () -> Unit) -> Unit) {
    if (view == null) {
        onHidden {}
        return
    }
    var restored = false
    val restore = {
        if (!restored) {
            restored = true
            try {
                view.visibility = View.VISIBLE
            } catch (e: Exception) {
                logger.e("Could not show the $what", e)
            }
        }
    }
    val handler = Handler(Looper.getMainLooper())
    var observer: ViewTreeObserver? = null
    var drawListener: ViewTreeObserver.OnDrawListener? = null
    var proceeded = false
    val proceed = object : Runnable {
        override fun run() {
            if (proceeded) return
            proceeded = true
            handler.removeCallbacks(this)
            // Removed here, not inside onDraw(), where removal throws.
            try {
                val tree = observer
                val listener = drawListener
                if (tree != null && listener != null && tree.isAlive) tree.removeOnDrawListener(listener)
            } catch (e: Exception) {
                logger.e("Could not stop watching frames for the $what", e)
            }
            try {
                onHidden(restore)
            } catch (e: Exception) {
                logger.e("Screenshot capture failed", e)
                restore()
            }
        }
    }
    try {
        view.visibility = View.INVISIBLE
        val listener = ViewTreeObserver.OnDrawListener { handler.post(proceed) }
        observer = view.viewTreeObserver.also { it.addOnDrawListener(listener) }
        drawListener = listener
        handler.postDelayed(proceed, FRAME_WAIT_MILLIS)
    } catch (e: Exception) {
        logger.e("Could not hide the $what for the screenshot", e)
        restore()
        proceed.run()
    }
}

/**
 * [capture] with [hider]'s views hidden, so SDK controls never appear in their own screenshot. They
 * come back when the capture ends, whatever the result; [callback] runs exactly once, on the main
 * thread. Main thread.
 */
internal class HidingCapture(
    private val hider: CaptureHider,
    private val capture: ScreenCapture,
    private val logger: SdkLogger,
) : ScreenCapture {

    override fun capture(activity: Activity, callback: (ScreenCapturer.Result) -> Unit) {
        hider.hideForCapture(activity) { restore ->
            var finished = false
            val finish = { result: ScreenCapturer.Result ->
                if (!finished) {
                    finished = true
                    restore()
                    callback(result)
                }
            }
            try {
                capture.capture(activity, finish)
            } catch (e: Exception) {
                logger.e("Screenshot capture failed", e)
                finish(ScreenCapturer.Result.Failed)
            }
        }
    }
}
