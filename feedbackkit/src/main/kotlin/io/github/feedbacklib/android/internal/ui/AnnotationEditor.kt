package io.github.feedbacklib.android.internal.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import io.github.feedbacklib.android.internal.annotate.AnnotationGestures
import io.github.feedbacklib.android.internal.annotate.AnnotationTool
import io.github.feedbacklib.android.internal.annotate.EditHistory
import io.github.feedbacklib.android.internal.annotate.EditOp
import io.github.feedbacklib.android.internal.annotate.EditsJson
import io.github.feedbacklib.android.internal.annotate.LoadedImage
import io.github.feedbacklib.android.internal.annotate.MagnifierGeometry
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.annotate.PenWidth
import io.github.feedbacklib.android.internal.report.DraftFile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the editor screen draws (spec §6). */
internal data class AnnotationUiState(
    val fileName: String,
    val imageWidth: Int = 0,
    val imageHeight: Int = 0,
    /** The image with the ops drawn in, downsampled to the screen; null while loading. */
    val preview: ImageBitmap? = null,
    val ops: List<EditOp> = emptyList(),
    val tool: AnnotationTool = AnnotationTool.PEN,
    val penColor: PenColor = PenColor.RED,
    val penWidth: PenWidth = PenWidth.THIN,
    val canUndo: Boolean = false,
    val loadFailed: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val confirmingDiscard: Boolean = false,
) {
    val loaded: Boolean
        get() = imageWidth > 0 && imageHeight > 0

    /** Whether edits, undo and Done are taken now. */
    val canEdit: Boolean
        get() = loaded && !saving
}

/** Everything the editor screen can ask for; implemented by ReportDraftViewModel. Image pixels throughout. Main thread. */
internal interface AnnotationActions {
    fun onToolSelected(tool: AnnotationTool)
    fun onPenColorSelected(color: PenColor)
    fun onPenWidthSelected(width: PenWidth)
    fun onStrokeDrawn(points: List<Float>, width: Float)
    fun onBlurDrawn(left: Float, top: Float, right: Float, bottom: Float)
    fun onMagnifierPlaced(centerX: Float, centerY: Float)
    fun onMagnifierChanged(index: Int, centerX: Float, centerY: Float, radius: Float)
    fun onUndo()
    fun onAnnotationDone()
    fun onAnnotationCancel()
    fun onAnnotationDiscardConfirmed()
    fun onAnnotationDiscardDismissed()
}

/**
 * One editing session of one attachment (spec §6), owned by ReportDraftViewModel. Loads a preview
 * fitted to the screen, keeps the steps — saved to the draft after each one, never a bitmap —
 * redraws the preview on [cpu], and on Done writes the full-size PNG on the draft's queue. Main thread.
 */
internal class AnnotationEditor(
    private val draftId: String,
    val target: DraftFile,
    private val env: ReportEnvironment,
    private val scope: CoroutineScope,
    private val detached: CoroutineScope,
    private val cpu: CoroutineDispatcher,
    initial: AnnotationUiState,
    private val onSettingsChanged: (AnnotationUiState) -> Unit,
    private val onFinished: (saved: DraftFile?) -> Unit,
) {
    private var history = EditHistory()
    private var image: LoadedImage? = null
    private var finished = false

    /** Steps restored from the draft that are not loaded yet: closing now must not delete them. */
    private var restoring = false

    /** The newest preview asked for; a render that is no longer it is skipped (read on [renderQueue]). */
    @Volatile
    private var renderGeneration = 0

    /** One preview at a time: a burst of steps redraws only the newest, never all of them at once. */
    private val renderQueue = cpu.limitedParallelism(1)

    var state: AnnotationUiState by mutableStateOf(initial.copy(fileName = target.fileName))
        private set

    /** [restore]: bring back the steps saved in the draft (after process death); otherwise start clean. */
    fun load(restore: Boolean) {
        val (maxWidth, maxHeight) = env.displaySize()
        restoring = restore
        scope.launch {
            val (loaded, saved) = env.drafts.onQueue(draftId) {
                val edits = if (restore) {
                    readEdits(draftId, target.fileName)
                } else {
                    deleteEdits(draftId, target.fileName) // leftovers of an abandoned session
                    null
                }
                guarded("Could not open the image for editing") { env.images.load(target.file, maxWidth, maxHeight) } to EditsJson.decode(edits)
            }
            restoring = false
            if (loaded == null) {
                state = state.copy(loadFailed = true)
                return@launch
            }
            image = loaded
            history = saved
            state = state.copy(imageWidth = loaded.width, imageHeight = loaded.height, ops = saved.ops(), canUndo = saved.canUndo)
            render()
        }
    }

    fun onToolSelected(tool: AnnotationTool) = settings(state.copy(tool = tool))

    fun onPenColorSelected(color: PenColor) = settings(state.copy(penColor = color))

    fun onPenWidthSelected(width: PenWidth) = settings(state.copy(penWidth = width))

    fun onStrokeDrawn(points: List<Float>, width: Float) {
        if (points.size < 2 || points.size % 2 != 0 || width <= 0f) return
        change(history.add(EditOp.Stroke(state.penColor.argb, width, points.take(AnnotationGestures.MAX_STROKE_POINTS * 2))))
    }

    fun onBlurDrawn(left: Float, top: Float, right: Float, bottom: Float) {
        val blur = AnnotationGestures.blurRect(left, top, right, bottom, state.imageWidth, state.imageHeight, minSize = 1f) ?: return
        change(history.add(blur))
    }

    fun onMagnifierPlaced(centerX: Float, centerY: Float) {
        if (!state.loaded) return
        val radius = MagnifierGeometry.defaultRadius(state.imageWidth, state.imageHeight)
        change(history.add(EditOp.Magnifier(centerX.coerceIn(0f, state.imageWidth.toFloat()), centerY.coerceIn(0f, state.imageHeight.toFloat()), radius)))
    }

    fun onMagnifierChanged(index: Int, centerX: Float, centerY: Float, radius: Float) {
        if (history.ops().getOrNull(index) !is EditOp.Magnifier) return
        val moved = EditOp.Magnifier(
            centerX.coerceIn(0f, state.imageWidth.toFloat()),
            centerY.coerceIn(0f, state.imageHeight.toFloat()),
            MagnifierGeometry.clampRadius(radius, state.imageWidth, state.imageHeight),
        )
        change(history.replace(index, moved))
    }

    fun onUndo() = change(history.undo())

    fun onDone() {
        if (!state.canEdit || finished) return
        val ops = history.ops()
        if (ops.isEmpty()) {
            finish(null)
            return
        }
        // Set before anything suspends: a second tap finds canEdit false and saves nothing.
        state = state.copy(saving = true, saveFailed = false)
        scope.launch {
            // Full resolution, on the draft's queue (off the main thread), replacing the original.
            val saved = env.drafts.onQueue(draftId) {
                replaceEdited(draftId, target.fileName) { out -> env.images.writeEdited(target.file, ops, out) }
            }
            if (saved != null) finish(saved) else state = state.copy(saving = false, saveFailed = true)
        }
    }

    fun onCancel() {
        if (state.saving || finished) return
        if (history.ops().isEmpty() || !state.loaded) finish(null) else settings(state.copy(confirmingDiscard = true))
    }

    fun onDiscardConfirmed() {
        if (!state.saving) finish(null)
    }

    fun onDiscardDismissed() = settings(state.copy(confirmingDiscard = false))

    private fun settings(next: AnnotationUiState) {
        state = next
        onSettingsChanged(next)
    }

    private fun change(next: EditHistory) {
        if (!state.canEdit || finished || next == history) return
        history = next
        state = state.copy(ops = next.ops(), canUndo = next.canUndo, saveFailed = false)
        // Encoded on the queue too: nothing but the in-memory step happens on the main thread.
        detached.launch(env.drafts.queue(draftId)) { env.drafts.writeEdits(draftId, target.fileName, EditsJson.encode(next)) }
        render()
    }

    private fun render() {
        val loaded = image ?: return
        val ops = history.ops()
        val generation = ++renderGeneration
        scope.launch {
            val bitmap = withContext(renderQueue) {
                // Superseded while it waited for the one before: skip the work, not just its result.
                if (generation != renderGeneration) null else guarded("Could not draw the annotation preview") { env.images.render(loaded, ops) }
            }
            if (generation != renderGeneration) return@launch
            when {
                bitmap != null -> state = state.copy(preview = bitmap)
                state.preview == null -> state = state.copy(loadFailed = true) // nothing to show at all
            }
        }
    }

    /** AnnotationImages never throws by contract; should one break it, the SDK still must not crash the host. */
    private inline fun <T> guarded(message: String, block: () -> T?): T? =
        try {
            block()
        } catch (e: Exception) {
            env.logger.w(message, e)
            null
        }

    private fun finish(saved: DraftFile?) {
        if (finished) return
        finished = true
        // Nothing saved: the steps go too — unless they were restored and never loaded, so the user
        // never saw them to decide (the next plain open deletes such leftovers). A saved edit
        // already dropped them in replaceEdited.
        if (saved == null && !restoring) detached.launch(env.drafts.queue(draftId)) { env.drafts.deleteEdits(draftId, target.fileName) }
        onFinished(saved)
    }
}
