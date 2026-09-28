package io.github.feedbacklib.android.internal.invoke

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.internal.core.SdkLogger
import kotlin.math.abs
import kotlin.math.roundToInt

internal enum class ButtonEdge { LEFT, RIGHT }

/** Where the button sits; kept for the process lifetime and updated when the user drags it. */
internal class FloatingButtonState(edge: ButtonEdge = ButtonEdge.RIGHT, offsetDp: Int = 200) {
    @Volatile
    var edge: ButtonEdge = edge

    @Volatile
    var offsetDp: Int = offsetDp
}

/** System bar insets in px; a plain type so the geometry stays testable on the JVM without Android stubs. */
internal data class BarInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val NONE = BarInsets(0, 0, 0, 0)
    }
}

/** The system bars' insets on [view]'s window, in px; none before the view is attached. */
internal fun systemBarInsets(view: View): BarInsets {
    val insets = view.rootWindowInsets ?: return BarInsets.NONE
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val bars = insets.getInsets(WindowInsets.Type.systemBars())
        BarInsets(bars.left, bars.top, bars.right, bars.bottom)
    } else {
        @Suppress("DEPRECATION")
        BarInsets(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
    }
}

/**
 * Runs [onChange], posted, after every layout of [decor]: a rotation or resize the host handles
 * itself (`configChanges`), system bars appearing or going away. Returns what stops it. [onChange]
 * must not throw and must not change the layout when nothing moved, or it would run forever.
 */
internal fun onEveryLayout(decor: View, onChange: () -> Unit): () -> Unit {
    val runnable = Runnable { onChange() }
    val listener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        decor.removeCallbacks(runnable) // one update per burst of layouts
        decor.post(runnable)
    }
    decor.addOnLayoutChangeListener(listener)
    return {
        decor.removeOnLayoutChangeListener(listener)
        decor.removeCallbacks(runnable)
    }
}

internal object FloatingButtonGeometry {

    fun position(
        edge: ButtonEdge,
        offsetPx: Int,
        containerWidth: Int,
        containerHeight: Int,
        sizePx: Int,
        marginPx: Int,
        insets: BarInsets,
    ): Pair<Float, Float> {
        val x = when (edge) {
            ButtonEdge.LEFT -> insets.left + marginPx
            ButtonEdge.RIGHT -> containerWidth - insets.right - marginPx - sizePx
        }
        val minY = insets.top
        val maxY = (containerHeight - insets.bottom - marginPx - sizePx).coerceAtLeast(minY)
        val y = (insets.top + offsetPx).coerceIn(minY, maxY)
        return x.toFloat() to y.toFloat()
    }

    fun nearestEdge(centerX: Float, containerWidth: Int): ButtonEdge =
        if (centerX < containerWidth / 2f) ButtonEdge.LEFT else ButtonEdge.RIGHT
}

@SuppressLint("ViewConstructor")
internal class FloatingButtonView(
    context: Context,
    private val onTap: () -> Unit,
    private val onDropped: (centerX: Float, y: Float) -> Unit,
) : View(context) {

    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = BUTTON_COLOR }
    private val icon: Drawable? = context.getDrawable(R.drawable.feedbackkit_ic_report)
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    /** Whether the user is dragging the button; a layout pass must not snap it back meanwhile. */
    val isDragging: Boolean
        get() = dragging

    init {
        contentDescription = context.getString(R.string.feedbackkit_floating_button_description)
        isClickable = true
        elevation = 6 * resources.displayMetrics.density
        setOnClickListener { onTap() }
    }

    override fun onDraw(canvas: Canvas) {
        val radius = width / 2f
        canvas.drawCircle(radius, radius, radius, circle)
        icon?.let {
            val inset = (width * ICON_INSET_RATIO).roundToInt()
            it.setBounds(inset, inset, width - inset, height - inset)
            it.draw(canvas)
        }
    }

    @SuppressLint("ClickableViewAccessibility") // performClick() is called for taps.
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                startX = x
                startY = y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downRawX
                val dy = event.rawY - downRawY
                if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) dragging = true
                if (dragging) {
                    x = startX + dx
                    y = startY + dy
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) onDropped(x + width / 2f, y) else performClick()
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                // A cancelled drag (parent intercept, system gesture) goes back to where it started,
                // which was a clamped position, instead of staying wherever the last move left it.
                if (dragging) {
                    x = startX
                    y = startY
                }
                dragging = false
            }
        }
        return true
    }

    private companion object {
        const val BUTTON_COLOR = 0xFF1565C0.toInt()

        /** Share of the diameter left empty on each side of the icon (24 dp icon in a 56 dp circle ≈ 0.28). */
        const val ICON_INSET_RATIO = 0.28f
    }
}

/** Adds the button to each resumed host activity's window, without SYSTEM_ALERT_WINDOW (spec §5). */
internal class FloatingButtonDetector(
    private val state: FloatingButtonState,
    private val onTap: () -> Unit,
    private val logger: SdkLogger,
) : ActivityDetector, CaptureHider {

    // Plain map on purpose: each button holds its activity as context, so weak keys would never be
    // cleared. InvocationManager detaches on every pause and in stopAll(), which is the cleanup.
    private val buttons = HashMap<Activity, FloatingButtonView>()
    private val layoutWatches = HashMap<Activity, () -> Unit>()

    override fun attach(activity: Activity) {
        try {
            if (buttons.containsKey(activity)) return
            val decor = activity.window?.decorView as? ViewGroup ?: return
            val density = activity.resources.displayMetrics.density
            val size = (SIZE_DP * density).roundToInt()
            lateinit var button: FloatingButtonView
            button = FloatingButtonView(activity, { safely("handle a tap on") { onTap() } }) { centerX, y ->
                safely("move") {
                    state.edge = FloatingButtonGeometry.nearestEdge(centerX, decor.width)
                    state.offsetDp = ((y - systemBarInsets(decor).top) / density).roundToInt().coerceAtLeast(0)
                    place(button, decor)
                }
            }
            decor.addView(button, FrameLayout.LayoutParams(size, size))
            buttons[activity] = button
            decor.post { safely("position") { place(button, decor) } }
            layoutWatches[activity] = onEveryLayout(decor) { safely("reposition") { if (!button.isDragging) place(button, decor) } }
        } catch (e: Exception) {
            logger.e("Could not show the floating button", e)
        }
    }

    override fun detach(activity: Activity) {
        layoutWatches.remove(activity)?.let { stop -> safely("stop following the layout of") { stop() } }
        val button = buttons.remove(activity) ?: return
        try {
            (button.parent as? ViewGroup)?.removeView(button)
        } catch (e: Exception) {
            logger.e("Could not remove the floating button", e)
        }
    }

    /** Re-applies [state] to the buttons on screen, e.g. after the host changed edge or offset. */
    fun relayout() {
        buttons.values.forEach { button ->
            safely("reposition") { (button.parent as? ViewGroup)?.let { place(button, it) } }
        }
    }

    /** Hides the button on [activity] for a screenshot; see [hideUntilDrawn]. */
    override fun hideForCapture(activity: Activity, onHidden: (restore: () -> Unit) -> Unit) =
        hideUntilDrawn(buttons[activity]?.takeIf { it.visibility == View.VISIBLE && it.isAttachedToWindow }, logger, "floating button", onHidden)

    /** Touch callbacks, posted layout and host-driven relayout run outside attach()'s guard. */
    private inline fun safely(action: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            logger.e("Could not $action the floating button", e)
        }
    }

    private fun place(button: View, decor: ViewGroup) {
        if (decor.width == 0 || decor.height == 0) return
        val density = decor.resources.displayMetrics.density
        val (x, y) = FloatingButtonGeometry.position(
            edge = state.edge,
            offsetPx = (state.offsetDp * density).roundToInt(),
            containerWidth = decor.width,
            containerHeight = decor.height,
            sizePx = button.layoutParams.width,
            marginPx = (MARGIN_DP * density).roundToInt(),
            insets = systemBarInsets(decor),
        )
        button.x = x
        button.y = y
    }

    private companion object {
        const val SIZE_DP = 56
        const val MARGIN_DP = 16
    }
}
