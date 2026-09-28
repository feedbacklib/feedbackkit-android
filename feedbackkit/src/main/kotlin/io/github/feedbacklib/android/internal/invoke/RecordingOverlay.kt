package io.github.feedbacklib.android.internal.invoke

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.RecordingButtonPosition
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.ui.theme.contentColorOn
import java.util.Locale
import kotlin.math.roundToInt

/** Where the coordinator shows a recording's Stop control; a seam for tests. Main thread. */
internal interface RecordingOverlayHost {
    fun show(activity: Activity)
    fun hide(activity: Activity)
    fun hideAll()

    /** The position setting changed: move the controls already shown. */
    fun relayout()

    companion object {
        val NONE: RecordingOverlayHost = object : RecordingOverlayHost {
            override fun show(activity: Activity) = Unit
            override fun hide(activity: Activity) = Unit
            override fun hideAll() = Unit
            override fun relayout() = Unit
        }
    }
}

/** A corner for the Stop control as plain values, so the JVM tests check it without Android. */
internal data class ControlPlacement(
    val right: Boolean,
    val bottom: Boolean,
    val marginLeft: Int,
    val marginTop: Int,
    val marginRight: Int,
    val marginBottom: Int,
)

internal object RecordingControlPlacement {

    /** [position] is a physical corner, clear of the system bars on its two sides by [marginPx]. */
    fun of(position: RecordingButtonPosition, insets: BarInsets, marginPx: Int): ControlPlacement {
        val right = position == RecordingButtonPosition.TOP_RIGHT || position == RecordingButtonPosition.BOTTOM_RIGHT
        val bottom = position == RecordingButtonPosition.BOTTOM_LEFT || position == RecordingButtonPosition.BOTTOM_RIGHT
        return ControlPlacement(
            right = right,
            bottom = bottom,
            marginLeft = if (right) 0 else insets.left + marginPx,
            marginTop = if (bottom) 0 else insets.top + marginPx,
            marginRight = if (right) insets.right + marginPx else 0,
            marginBottom = if (bottom) insets.bottom + marginPx else 0,
        )
    }

    /** Recorded time as m:ss; negative counts as none. */
    fun elapsedText(millis: Long): String {
        val seconds = millis.coerceAtLeast(0) / 1_000
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }
}

/**
 * The recording's control (spec §7): a red dot, the time recorded and the Stop button on a dark pill.
 * Plain views with their own colours, like the capture controls, so the host's theme never restyles
 * them. The timer ticks on the second while attached.
 */
@SuppressLint("ViewConstructor")
internal class RecordingControlView(
    context: Context,
    accentColor: Int,
    private val startedAt: () -> Long,
    private val now: () -> Long,
    private val logger: SdkLogger,
    onStop: () -> Unit,
) : LinearLayout(context) {

    private val timer: TextView

    private val tick = object : Runnable {
        override fun run() {
            try {
                update()
                postDelayed(this, 1_000 - elapsed() % 1_000)
            } catch (e: Exception) {
                logger.e("Could not update the recording timer", e)
            }
        }
    }

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true // a tap on the pill never reaches the host screen under it
        elevation = dp(8).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(28).toFloat()
            setColor(PANEL_COLOR)
        }
        setPadding(dp(16), dp(4), dp(4), dp(4))
        addView(
            View(context).apply {
                importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(DOT_COLOR)
                }
            },
            LayoutParams(dp(10), dp(10)),
        )
        timer = TextView(context).apply {
            tag = TAG_TIMER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            fontFeatureSettings = "tnum" // digits of one width: the pill does not jitter every second
            setPadding(dp(8), 0, dp(12), 0)
        }
        addView(timer)
        addView(
            Button(context).apply {
                setText(R.string.feedbackkit_record_stop)
                contentDescription = context.getString(R.string.feedbackkit_record_stop_description)
                tag = TAG_STOP
                isAllCaps = false
                stateListAnimator = null
                setTextColor(contentColorOn(accentColor))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                minHeight = dp(48)
                minimumHeight = dp(48)
                minWidth = dp(88)
                minimumWidth = dp(88)
                setPadding(dp(20), 0, dp(20), 0)
                background = GradientDrawable().apply {
                    cornerRadius = dp(24).toFloat()
                    setColor(accentColor)
                }
                setOnClickListener { onStop() }
            },
        )
        update()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun elapsed(): Long = (now() - startedAt()).coerceAtLeast(0)

    private fun update() {
        val text = RecordingControlPlacement.elapsedText(elapsed())
        timer.text = text
        timer.contentDescription = context.getString(R.string.feedbackkit_record_timer_description, text)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    internal companion object {
        const val TAG_STOP: String = "feedbackkit.recordingStop"
        const val TAG_TIMER: String = "feedbackkit.recordingTimer"
        private const val PANEL_COLOR = 0xE6212121.toInt()
        private const val DOT_COLOR = 0xFFE53935.toInt()
    }
}

/**
 * Shows the Stop control in the corner [position] asks for on each resumed host screen, above the
 * system bars, without SYSTEM_ALERT_WINDOW — the capture controls' technique. Taps and system
 * callbacks never throw into the host. The control is part of the app's window, so the recording
 * of the whole display shows it too (spec §7).
 */
internal class RecordingOverlay(
    private val position: () -> RecordingButtonPosition,
    private val accentColor: () -> Int,
    private val startedAt: () -> Long,
    private val onStop: (Activity) -> Unit,
    private val logger: SdkLogger,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : RecordingOverlayHost {

    // Plain maps, as for the capture controls: hide() on every pause and hideAll() when the mode ends are the cleanup.
    private val controls = HashMap<Activity, RecordingControlView>()
    private val layoutWatches = HashMap<Activity, () -> Unit>()

    override fun show(activity: Activity) {
        try {
            if (controls.containsKey(activity)) return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val view = RecordingControlView(activity, accentColor(), startedAt, now, logger) { safely("handle Stop on") { onStop(activity) } }
            decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            controls[activity] = view
            place(view, decor)
            decor.post { safely("position") { place(view, decor) } }
            layoutWatches[activity] = onEveryLayout(decor) { safely("reposition") { place(view, decor) } }
        } catch (e: Exception) {
            logger.e("Could not show the recording controls", e)
        }
    }

    override fun hide(activity: Activity) {
        layoutWatches.remove(activity)?.let { stop -> safely("stop following the layout for") { stop() } }
        val view = controls.remove(activity) ?: return
        safely("remove") { (view.parent as? ViewGroup)?.removeView(view) }
    }

    override fun hideAll() {
        controls.keys.toList().forEach(::hide)
    }

    override fun relayout() {
        controls.forEach { (activity, view) ->
            val decor = activity.window?.decorView ?: return@forEach
            safely("reposition") { place(view, decor) }
        }
    }

    private fun place(view: View, decor: View) {
        val params = view.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = (MARGIN_DP * decor.resources.displayMetrics.density).roundToInt()
        val placement = RecordingControlPlacement.of(position(), systemBarInsets(decor), margin)
        val gravity = (if (placement.bottom) Gravity.BOTTOM else Gravity.TOP) or (if (placement.right) Gravity.RIGHT else Gravity.LEFT)
        val unchanged = params.gravity == gravity && params.leftMargin == placement.marginLeft && params.topMargin == placement.marginTop &&
            params.rightMargin == placement.marginRight && params.bottomMargin == placement.marginBottom
        if (unchanged) return // no new layout, no loop with onEveryLayout
        params.gravity = gravity
        params.setMargins(placement.marginLeft, placement.marginTop, placement.marginRight, placement.marginBottom)
        view.layoutParams = params
    }

    private inline fun safely(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.e("Could not $action the recording controls", e)
        }
    }

    private companion object {
        const val MARGIN_DP = 16
    }
}
