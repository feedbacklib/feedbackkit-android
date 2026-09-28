package io.github.feedbacklib.android.internal.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.feedbacklib.android.R
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.annotate.AnnotationGestures
import io.github.feedbacklib.android.internal.annotate.AnnotationTool
import io.github.feedbacklib.android.internal.annotate.EditOp
import io.github.feedbacklib.android.internal.annotate.FitTransform
import io.github.feedbacklib.android.internal.annotate.MagnifierGeometry
import io.github.feedbacklib.android.internal.annotate.MagnifierHit
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.annotate.PenWidth
import io.github.feedbacklib.android.internal.ui.AnnotationActions
import io.github.feedbacklib.android.internal.ui.AnnotationUiState
import io.github.feedbacklib.android.internal.ui.LocalTexts
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** The annotation editor (spec §6): top bar with close, undo and Done; the image; the tools. */
@Composable
internal fun AnnotationScreen(state: AnnotationUiState, actions: AnnotationActions) {
    Scaffold(
        topBar = { AnnotationTopBar(state, actions) },
        bottomBar = { AnnotationToolbar(state, actions) },
        containerColor = Color.Black,
    ) { padding ->
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .consumeWindowInsets(padding),
        ) {
            when {
                state.loadFailed -> Text(
                    stringResource(R.string.feedbackkit_annotation_load_failed),
                    color = Color.White,
                    modifier = Modifier.padding(24.dp),
                )
                state.preview == null -> CircularProgressIndicator()
                else -> AnnotationCanvas(state, actions)
            }
        }
    }
    if (state.confirmingDiscard) DiscardEditsDialog(actions)
}

@Composable
private fun AnnotationTopBar(state: AnnotationUiState, actions: AnnotationActions) {
    Surface(tonalElevation = 3.dp) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Top))
                .heightIn(min = 64.dp),
        ) {
            IconButton(onClick = actions::onAnnotationCancel, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.feedbackkit_ic_close), contentDescription = stringResource(R.string.feedbackkit_action_close))
            }
            Text(
                stringResource(R.string.feedbackkit_annotation_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 4.dp),
            )
            IconButton(onClick = actions::onUndo, enabled = state.canUndo && state.canEdit, modifier = Modifier.size(48.dp)) {
                Icon(painterResource(R.drawable.feedbackkit_ic_undo), contentDescription = stringResource(R.string.feedbackkit_annotation_undo))
            }
            if (state.saving) CircularProgressIndicator(Modifier.size(24.dp))
            TextButton(
                onClick = actions::onAnnotationDone,
                enabled = state.canEdit && !state.loadFailed,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag(ReportTestTags.ANNOTATION_DONE),
            ) {
                Text(stringResource(R.string.feedbackkit_annotation_done))
            }
        }
    }
}

@Composable
private fun AnnotationToolbar(state: AnnotationUiState, actions: AnnotationActions) {
    Surface(tonalElevation = 3.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            if (state.saveFailed) {
                Text(
                    stringResource(R.string.feedbackkit_annotation_save_failed),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .padding(8.dp)
                        .semantics { liveRegion = LiveRegionMode.Polite }
                        .testTag(ReportTestTags.ANNOTATION_ERROR),
                )
            }
            if (state.tool == AnnotationTool.PEN) {
                Row(horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.selectableGroup()) {
                        PenColor.entries.forEach { color -> PenColorOption(color, color == state.penColor) { actions.onPenColorSelected(color) } }
                    }
                    Spacer(Modifier.width(16.dp))
                    Row(Modifier.selectableGroup()) {
                        PenWidth.entries.forEach { width -> PenWidthOption(width, width == state.penWidth, state.penColor) { actions.onPenWidthSelected(width) } }
                    }
                }
            }
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
            ) {
                ToolOption(AnnotationTool.PEN, R.drawable.feedbackkit_ic_pen, R.string.feedbackkit_tool_pen, state, actions)
                ToolOption(AnnotationTool.MAGNIFIER, R.drawable.feedbackkit_ic_magnifier, R.string.feedbackkit_tool_magnifier, state, actions)
                ToolOption(AnnotationTool.BLUR, R.drawable.feedbackkit_ic_blur, R.string.feedbackkit_tool_blur, state, actions)
            }
        }
    }
}

/** One of the three tools: a radio-style choice, so TalkBack reads which one is on. */
@Composable
private fun ToolOption(tool: AnnotationTool, @DrawableRes icon: Int, @StringRes label: Int, state: AnnotationUiState, actions: AnnotationActions) {
    val selected = state.tool == tool
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp) // §10: a 48 dp touch target
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton) { actions.onToolSelected(tool) }
            .testTag(ReportTestTags.tool(tool)),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(label))
    }
}

@Composable
private fun PenColorOption(color: PenColor, selected: Boolean, onClick: () -> Unit) {
    val label = stringResource(
        when (color) {
            PenColor.RED -> R.string.feedbackkit_pen_red
            PenColor.YELLOW -> R.string.feedbackkit_pen_yellow
            PenColor.BLUE -> R.string.feedbackkit_pen_blue
        },
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(ReportTestTags.penColor(color)),
    ) {
        val ring = if (selected) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier
        Box(
            Modifier
                .size(28.dp)
                .then(ring)
                .padding(4.dp)
                .background(Color(color.argb), CircleShape),
        )
    }
}

@Composable
private fun PenWidthOption(width: PenWidth, selected: Boolean, color: PenColor, onClick: () -> Unit) {
    val label = stringResource(if (width == PenWidth.THIN) R.string.feedbackkit_pen_thin else R.string.feedbackkit_pen_thick)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label }
            .testTag(ReportTestTags.penWidth(width)),
    ) {
        Box(
            Modifier
                .width(24.dp)
                .height((width.widthDp / 2).dp)
                .background(Color(color.argb), RoundedCornerShape(50)),
        )
    }
}

/**
 * The image fitted into the canvas with the ops already drawn in (the preview), plus what the
 * finger is doing right now: the line being drawn, the blur rectangle, the magnifiers' outlines and
 * resize handles. Touches become ops in image pixels.
 */
@Composable
internal fun AnnotationCanvas(state: AnnotationUiState, actions: AnnotationActions) {
    val preview = state.preview ?: return
    val latest by rememberUpdatedState(state)
    var size by remember { mutableStateOf(IntSize.Zero) }
    val fit = remember(state.imageWidth, state.imageHeight, size) {
        FitTransform.fit(state.imageWidth, state.imageHeight, size.width.toFloat(), size.height.toFloat())
    }
    val stroke = remember { mutableStateListOf<Float>() }
    var blurStart by remember { mutableStateOf<Offset?>(null) }
    var blurEnd by remember { mutableStateOf<Offset?>(null) }
    var dragged by remember { mutableStateOf<Pair<Int, EditOp.Magnifier>?>(null) }
    val description = stringResource(R.string.feedbackkit_annotation_canvas)

    Canvas(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .semantics { contentDescription = description }
            .testTag(ReportTestTags.ANNOTATION_CANVAS)
            .pointerInput(state.tool, fit) {
                val minStep = 2.dp.toPx() / fit.scale
                val handleReach = 24.dp.toPx() / fit.scale
                fun Offset.toImage() = Offset(fit.toImageX(x), fit.toImageY(y))
                // The overload that reports the touch-down as well: the plain one starts a drag only
                // where the touch slop was crossed, which would cut the first few dp off every line.
                when (state.tool) {
                    AnnotationTool.PEN -> detectDragGestures(
                        orientationLock = null,
                        onDragStart = { down, slopCrossed, _ ->
                            stroke.clear()
                            for (position in listOf(down.position, slopCrossed.position)) {
                                val p = position.toImage()
                                if (AnnotationGestures.shouldAdd(stroke, p.x, p.y, minStep)) {
                                    stroke.add(p.x)
                                    stroke.add(p.y)
                                }
                            }
                        },
                        onDrag = { change, _ ->
                            val p = change.position.toImage()
                            if (AnnotationGestures.shouldAdd(stroke, p.x, p.y, minStep)) {
                                stroke.add(p.x)
                                stroke.add(p.y)
                            }
                        },
                        onDragEnd = { _ ->
                            actions.onStrokeDrawn(stroke.toList(), latest.penWidth.widthDp.dp.toPx() / fit.scale)
                            stroke.clear()
                        },
                        onDragCancel = { stroke.clear() },
                    )
                    AnnotationTool.BLUR -> detectDragGestures(
                        orientationLock = null,
                        onDragStart = { down, slopCrossed, _ ->
                            blurStart = down.position.toImage()
                            blurEnd = slopCrossed.position.toImage()
                        },
                        onDrag = { change, _ -> blurEnd = change.position.toImage() },
                        onDragEnd = { _ ->
                            val a = blurStart
                            val b = blurEnd
                            if (a != null && b != null) {
                                AnnotationGestures.blurRect(a.x, a.y, b.x, b.y, latest.imageWidth, latest.imageHeight, 8.dp.toPx() / fit.scale)
                                    ?.let { actions.onBlurDrawn(it.left, it.top, it.right, it.bottom) }
                            }
                            blurStart = null
                            blurEnd = null
                        },
                        onDragCancel = {
                            blurStart = null
                            blurEnd = null
                        },
                    )
                    AnnotationTool.MAGNIFIER -> {
                        var hit: MagnifierHit? = null
                        var origin = Offset.Zero
                        detectDragGestures(
                            orientationLock = null,
                            onDragStart = { down, _, _ ->
                                origin = down.position.toImage()
                                hit = MagnifierGeometry.hit(latest.ops, origin.x, origin.y, handleReach)
                                dragged = hit?.let { h -> (latest.ops[h.index] as? EditOp.Magnifier)?.let { h.index to it } }
                            },
                            onDrag = { change, _ ->
                                val h = hit
                                val op = h?.let { latest.ops.getOrNull(it.index) as? EditOp.Magnifier }
                                if (h != null && op != null) {
                                    val p = change.position.toImage()
                                    dragged = h.index to if (h.onHandle) {
                                        op.copy(radius = MagnifierGeometry.clampRadius(hypot(p.x - op.centerX, p.y - op.centerY), latest.imageWidth, latest.imageHeight))
                                    } else {
                                        op.copy(
                                            centerX = (op.centerX + p.x - origin.x).coerceIn(0f, latest.imageWidth.toFloat()),
                                            centerY = (op.centerY + p.y - origin.y).coerceIn(0f, latest.imageHeight.toFloat()),
                                        )
                                    }
                                }
                            },
                            onDragEnd = { _ ->
                                dragged?.let { (index, op) -> actions.onMagnifierChanged(index, op.centerX, op.centerY, op.radius) }
                                dragged = null
                                hit = null
                            },
                            onDragCancel = {
                                dragged = null
                                hit = null
                            },
                        )
                    }
                }
            }
            .pointerInput(state.tool, fit) {
                detectTapGestures { tap ->
                    val x = fit.toImageX(tap.x)
                    val y = fit.toImageY(tap.y)
                    if (x !in 0f..latest.imageWidth.toFloat() || y !in 0f..latest.imageHeight.toFloat()) return@detectTapGestures
                    when (latest.tool) {
                        AnnotationTool.PEN -> actions.onStrokeDrawn(listOf(x, y), latest.penWidth.widthDp.dp.toPx() / fit.scale)
                        AnnotationTool.MAGNIFIER ->
                            if (MagnifierGeometry.hit(latest.ops, x, y, 24.dp.toPx() / fit.scale) == null) actions.onMagnifierPlaced(x, y)
                        AnnotationTool.BLUR -> Unit
                    }
                }
            },
    ) {
        drawImage(
            preview,
            dstOffset = IntOffset(fit.offsetX.roundToInt(), fit.offsetY.roundToInt()),
            dstSize = IntSize((state.imageWidth * fit.scale).roundToInt(), (state.imageHeight * fit.scale).roundToInt()),
            filterQuality = FilterQuality.Medium,
        )
        if (stroke.size >= 2) {
            val path = Path().apply {
                moveTo(fit.toViewX(stroke[0]), fit.toViewY(stroke[1]))
                for (i in 2 until stroke.size - 1 step 2) lineTo(fit.toViewX(stroke[i]), fit.toViewY(stroke[i + 1]))
            }
            drawPath(
                path,
                Color(state.penColor.argb),
                style = Stroke(width = state.penWidth.widthDp.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
        }
        val a = blurStart
        val b = blurEnd
        if (a != null && b != null) {
            val topLeft = Offset(fit.toViewX(min(a.x, b.x)), fit.toViewY(min(a.y, b.y)))
            val box = Size(fit.toViewX(max(a.x, b.x)) - topLeft.x, fit.toViewY(max(a.y, b.y)) - topLeft.y)
            drawRect(Color.White.copy(alpha = 0.25f), topLeft, box)
            drawRect(Color.White, topLeft, box, style = Stroke(2.dp.toPx()))
        }
        if (state.tool == AnnotationTool.MAGNIFIER) {
            state.ops.forEachIndexed { index, op ->
                if (op !is EditOp.Magnifier) return@forEachIndexed
                val shown = dragged?.takeIf { it.first == index }?.second ?: op
                drawCircle(Color.White, radius = shown.radius * fit.scale, center = Offset(fit.toViewX(shown.centerX), fit.toViewY(shown.centerY)), style = Stroke(2.dp.toPx()))
                val (hx, hy) = MagnifierGeometry.handle(shown)
                drawCircle(Color.White, radius = 8.dp.toPx(), center = Offset(fit.toViewX(hx), fit.toViewY(hy)))
            }
        }
    }
}

@Composable
private fun DiscardEditsDialog(actions: AnnotationActions) {
    val texts = LocalTexts.current
    AlertDialog(
        onDismissRequest = actions::onAnnotationDiscardDismissed,
        title = { Text(stringResource(R.string.feedbackkit_annotation_discard_title)) },
        confirmButton = { TextButton(onClick = actions::onAnnotationDiscardConfirmed) { Text(texts[TextKey.CANCEL_CONFIRM_DISCARD]) } },
        dismissButton = { TextButton(onClick = actions::onAnnotationDiscardDismissed) { Text(texts[TextKey.CANCEL_CONFIRM_KEEP]) } },
    )
}
