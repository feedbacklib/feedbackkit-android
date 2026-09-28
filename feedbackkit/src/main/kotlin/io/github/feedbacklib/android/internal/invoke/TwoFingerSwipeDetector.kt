package io.github.feedbacklib.android.internal.invoke

import android.app.Activity
import android.view.KeyboardShortcutGroup
import android.view.Menu
import android.view.MotionEvent
import android.view.Window
import io.github.feedbacklib.android.internal.core.SdkLogger
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Sees every touch first, then always hands it to the original callback unchanged. Java default
 * methods of [Window.Callback] are delegated explicitly so the host's overrides still run.
 *
 * The wrapper cannot be taken out of the window again (the host may have wrapped it in turn), so
 * it forwards touches to whichever detector is bound now; with nothing bound, events pass through
 * untouched. Main thread.
 *
 * A host that later replaces the window callback without delegating to the previous one drops this
 * wrapper: the two-finger swipe then stops working for that window. Best effort.
 */
internal class TouchInterceptingCallback(
    private val delegate: Window.Callback,
) : Window.Callback by delegate {

    private var owner: Any? = null
    private var onTouch: ((MotionEvent) -> Unit)? = null

    fun bind(owner: Any, onTouch: (MotionEvent) -> Unit) {
        this.owner = owner
        this.onTouch = onTouch
    }

    /** Clears the binding only if [owner] still holds it, so a late detach cannot undo a newer bind. */
    fun unbind(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        onTouch = null
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        onTouch?.invoke(event)
        return delegate.dispatchTouchEvent(event)
    }

    override fun onProvideKeyboardShortcuts(data: MutableList<KeyboardShortcutGroup>?, menu: Menu?, deviceId: Int) =
        delegate.onProvideKeyboardShortcuts(data, menu, deviceId)

    override fun onPointerCaptureChanged(hasCapture: Boolean) = delegate.onPointerCaptureChanged(hasCapture)
}

/**
 * Wraps each host activity's window once, for the window's lifetime, and binds the wrapper to this
 * detector while attached (spec §5). A later detector (a new build() after a reset) rebinds the same
 * wrapper instead of stacking another one.
 */
internal class TwoFingerSwipeDetector(
    private val isEnabled: () -> Boolean,
    private val onSwipe: () -> Unit,
    private val logger: SdkLogger,
) : ActivityDetector {

    override fun attach(activity: Activity) {
        val window = activity.window ?: return
        val wrapper = wrappers[window]?.get() ?: wrap(window) ?: return
        val recognizer = TwoFingerSwipeRecognizer()
        wrapper.bind(this) { event -> onTouch(recognizer, event, window) }
    }

    override fun detach(activity: Activity) {
        val window = activity.window ?: return
        wrappers[window]?.get()?.unbind(this)
    }

    private fun wrap(window: Window): TouchInterceptingCallback? {
        val current = window.callback ?: return null
        val wrapper = current as? TouchInterceptingCallback ?: TouchInterceptingCallback(current).also { window.callback = it }
        wrappers[window] = WeakReference(wrapper)
        return wrapper
    }

    private fun onTouch(recognizer: TwoFingerSwipeRecognizer, event: MotionEvent, window: Window) {
        try {
            if (!isEnabled()) return
            val pointers = List(event.pointerCount) { i -> Pointer(event.getPointerId(i), event.getX(i), event.getY(i)) }
            if (recognizer.onEvent(event.actionMasked, pointers, event.eventTime, window.decorView.width)) onSwipe()
        } catch (e: Exception) {
            logger.e("Two-finger swipe detection failed", e)
        }
    }

    private companion object {
        // Process-wide, so a detector of a later build() finds the wrapper an earlier one installed.
        // Main thread only. Both sides weak: the window chain keeps the wrapper alive while it is in
        // use, and the wrapper references the activity, which references the window.
        val wrappers = WeakHashMap<Window, WeakReference<TouchInterceptingCallback>>()
    }
}
