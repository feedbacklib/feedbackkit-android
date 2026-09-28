package io.github.feedbacklib.android.recording

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View

/**
 * A host screen that repaints every 100 ms. A handler, not an animator: test devices often run with
 * animations off, which would freeze an animator on its last frame and starve the recorder.
 */
class AnimatedActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var step = 0
    private lateinit var view: View
    private val repaint = object : Runnable {
        override fun run() {
            step = (step + 1) % 20
            view.setBackgroundColor(Color.rgb(12 * step, 40, 255 - 12 * step))
            handler.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        view = View(this)
        setContentView(view)
        handler.post(repaint)
    }

    override fun onDestroy() {
        handler.removeCallbacks(repaint)
        super.onDestroy()
    }
}
