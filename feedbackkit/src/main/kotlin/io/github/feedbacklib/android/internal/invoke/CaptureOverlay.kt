package io.github.feedbacklib.android.internal.invoke

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.StringRes
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.ui.theme.contentColorOn
import kotlin.math.roundToInt

/** Where the coordinator shows the "Capture" / "Cancel" controls; a seam for tests. Main thread. */
internal interface CaptureOverlayHost {
    fun show(activity: Activity)
    fun hide(activity: Activity)
    fun hideAll()

    companion object {
        val NONE: CaptureOverlayHost = object : CaptureOverlayHost {
            override fun show(activity: Activity) = Unit
            override fun hide(activity: Activity) = Unit
            override fun hideAll() = Unit
        }
    }
}

/**
 * The capture mode panel (spec §6): a hint and the "Cancel" and "Capture" buttons on a dark rounded
 * panel. Plain views with their own colours, so the host's theme never restyles them.
 */
@SuppressLint("ViewConstructor")
internal class CaptureOverlayView(
    context: Context,
    accentColor: Int,
    onCapture: () -> Unit,
    onCancel: () -> Unit,
) : LinearLayout(context) {

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        isClickable = true // a tap on the panel never reaches the host screen under it
        elevation = dp(8).toFloat()
        background = GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(PANEL_COLOR)
        }
        setPadding(dp(16), dp(12), dp(16), dp(12))
        addView(
            TextView(context).apply {
                text = context.getString(R.string.feedbackkit_capture_hint)
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                gravity = Gravity.CENTER
            },
        )
        val buttons = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, 0)
        }
        buttons.addView(button(R.string.feedbackkit_capture_cancel, Color.TRANSPARENT, Color.WHITE, TAG_CANCEL, onCancel))
        buttons.addView(
            button(R.string.feedbackkit_capture_take, accentColor, contentColorOn(accentColor), TAG_CAPTURE, onCapture),
            LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dp(12) },
        )
        addView(buttons)
    }

    private fun button(@StringRes label: Int, fill: Int, textColor: Int, viewTag: String, onClick: () -> Unit): Button =
        Button(context).apply {
            setText(label)
            contentDescription = context.getString(label)
            tag = viewTag
            isAllCaps = false
            stateListAnimator = null
            setTextColor(textColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = dp(96)
            minimumWidth = dp(96)
            setPadding(dp(20), 0, dp(20), 0)
            background = GradientDrawable().apply {
                cornerRadius = dp(24).toFloat()
                setColor(fill)
                if (fill == Color.TRANSPARENT) setStroke(dp(1), Color.WHITE)
            }
            setOnClickListener { onClick() }
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    internal companion object {
        const val TAG_CAPTURE: String = "feedbackkit.capture"
        const val TAG_CANCEL: String = "feedbackkit.captureCancel"
        private const val PANEL_COLOR = 0xE6212121.toInt()
    }
}

/**
 * Shows the capture mode panel at the bottom of each resumed host screen, above the navigation bar,
 * without SYSTEM_ALERT_WINDOW — the floating button's technique (spec §5, §6). Taps and system
 * callbacks never throw into the host.
 */
internal class CaptureOverlay(
    private val accentColor: () -> Int,
    private val onCapture: (Activity) -> Unit,
    private val onCancel: (Activity) -> Unit,
    private val logger: SdkLogger,
) : CaptureOverlayHost, CaptureHider {

    // Plain map, as for the floating button: each panel holds its activity as context; hide() on
    // every pause and hideAll() when capture mode ends are the cleanup.
    private val panels = HashMap<Activity, CaptureOverlayView>()
    private val layoutWatches = HashMap<Activity, () -> Unit>()

    override fun show(activity: Activity) {
        try {
            if (panels.containsKey(activity)) return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val panel = CaptureOverlayView(
                activity,
                accentColor(),
                onCapture = { safely("handle Capture on") { onCapture(activity) } },
                onCancel = { safely("handle Cancel on") { onCancel(activity) } },
            )
            decor.addView(panel, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
            panels[activity] = panel
            decor.post { safely("position") { place(panel, decor) } }
            layoutWatches[activity] = onEveryLayout(decor) { safely("reposition") { place(panel, decor) } }
        } catch (e: Exception) {
            logger.e("Could not show the screenshot controls", e)
        }
    }

    override fun hide(activity: Activity) {
        layoutWatches.remove(activity)?.let { stop -> safely("stop following the layout for") { stop() } }
        val panel = panels.remove(activity) ?: return
        safely("remove") { (panel.parent as? ViewGroup)?.removeView(panel) }
    }

    override fun hideAll() {
        panels.keys.toList().forEach(::hide)
    }

    override fun hideForCapture(activity: Activity, onHidden: (restore: () -> Unit) -> Unit) =
        hideUntilDrawn(panels[activity]?.takeIf { it.visibility == View.VISIBLE && it.isAttachedToWindow }, logger, "screenshot controls", onHidden)

    /** Above the navigation bar and the gesture area, whatever the host does with insets. */
    private fun place(panel: View, decor: View) {
        val params = panel.layoutParams as? FrameLayout.LayoutParams ?: return
        val margin = systemBarInsets(decor).bottom + (MARGIN_DP * decor.resources.displayMetrics.density).roundToInt()
        if (params.bottomMargin == margin) return // unchanged: no new layout, no loop with onEveryLayout
        params.bottomMargin = margin
        panel.layoutParams = params
    }

    private inline fun safely(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.e("Could not $action the screenshot controls", e)
        }
    }

    private companion object {
        const val MARGIN_DP = 24
    }
}
