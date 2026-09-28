package io.github.feedbacklib.android.capture

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout

/** Red screen with a blue 200×200 px square at (100, 300). */
class RedActivity : Activity() {
    lateinit var secret: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.RED) }
        secret = View(this).apply { setBackgroundColor(Color.BLUE) }
        root.addView(secret, FrameLayout.LayoutParams(200, 200).apply { leftMargin = 100; topMargin = 300 })
        setContentView(root)
    }
}

class SecureActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(View(this).apply { setBackgroundColor(Color.RED) })
    }
}
