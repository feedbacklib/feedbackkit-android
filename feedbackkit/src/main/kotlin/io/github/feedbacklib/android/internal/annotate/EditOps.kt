package io.github.feedbacklib.android.internal.annotate

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Tools of the annotation editor (spec §6). Saved by name: never rename an entry. */
internal enum class AnnotationTool { PEN, MAGNIFIER, BLUR }

/** The pen's three colours. Saved by name; a stroke stores its ARGB, so a new palette never repaints old edits. */
internal enum class PenColor(val argb: Int) {
    RED(0xFFE53935.toInt()),
    YELLOW(0xFFFDD835.toInt()),
    BLUE(0xFF1E88E5.toInt()),
}

/** The pen's two widths on screen; a stroke stores its width in image pixels. Saved by name. */
internal enum class PenWidth(val widthDp: Float) {
    THIN(4f),
    THICK(12f),
}

/**
 * One annotation, in pixels of the full-size image as it is shown (after EXIF rotation): x runs
 * 0..width, y 0..height, and a pixel's centre sits at +0.5.
 */
@Serializable
internal sealed interface EditOp {

    /** A freehand line through [points] (x0, y0, x1, y1, …), [width] px thick. */
    @Serializable
    @SerialName("stroke")
    data class Stroke(val color: Int, val width: Float, val points: List<Float>) : EditOp

    /** A circle showing what lies under its centre magnified ×2. */
    @Serializable
    @SerialName("magnifier")
    data class Magnifier(val centerX: Float, val centerY: Float, val radius: Float) : EditOp

    /** A rectangle turned into 16 px blocks of their average colour, for good. */
    @Serializable
    @SerialName("blur")
    data class Blur(val left: Float, val top: Float, val right: Float, val bottom: Float) : EditOp
}

/** One thing the user did; undo drops the last one. */
@Serializable
internal sealed interface EditStep {

    @Serializable
    @SerialName("add")
    data class Add(val op: EditOp) : EditStep

    /** A magnifier moved or resized. */
    @Serializable
    @SerialName("replace")
    data class Replace(val index: Int, val op: EditOp) : EditStep
}

/**
 * An edit as the steps the user took; the ops are their fold, so moving a magnifier and undoing
 * puts it back where it was. Small enough to save after every step (spec §6: survives process
 * death without keeping a bitmap).
 */
internal data class EditHistory(val steps: List<EditStep> = emptyList()) {

    val canUndo: Boolean
        get() = steps.isNotEmpty()

    fun ops(): List<EditOp> {
        val ops = ArrayList<EditOp>(steps.size)
        steps.forEach { step ->
            when (step) {
                is EditStep.Add -> ops += step.op
                is EditStep.Replace -> if (step.index in ops.indices) ops[step.index] = step.op
            }
        }
        return ops
    }

    fun add(op: EditOp): EditHistory = if (steps.size >= MAX_STEPS) this else copy(steps = steps + EditStep.Add(op))

    fun replace(index: Int, op: EditOp): EditHistory =
        if (steps.size >= MAX_STEPS || index !in ops().indices) this else copy(steps = steps + EditStep.Replace(index, op))

    fun undo(): EditHistory = if (steps.isEmpty()) this else copy(steps = steps.dropLast(1))

    companion object {
        /** Far beyond a real annotation; keeps the saved file and the undo list bounded. */
        const val MAX_STEPS: Int = 200
    }
}

/** The editor's steps as saved in the draft (`edits/<file>.json`). */
internal object EditsJson {

    @Serializable
    private data class EditsFile(val version: Int, val steps: List<EditStep>)

    private const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }

    fun encode(history: EditHistory): String = json.encodeToString(EditsFile.serializer(), EditsFile(VERSION, history.steps))

    /**
     * Never throws: a missing, damaged or newer file is an empty edit. A file is untrusted input
     * (spec §6: could be corrupted, or larger than anything the editor itself would ever write), so
     * what comes back is also clamped to what the editor's own limits would have produced: at most
     * [EditHistory.MAX_STEPS] steps, and at most [AnnotationGestures.MAX_STROKE_POINTS] points on
     * any one stroke.
     */
    fun decode(text: String?): EditHistory {
        if (text.isNullOrBlank()) return EditHistory()
        return try {
            val file = json.decodeFromString(EditsFile.serializer(), text)
            if (file.version != VERSION) return EditHistory()
            EditHistory(file.steps.take(EditHistory.MAX_STEPS).map(::clampStep))
        } catch (e: IllegalArgumentException) { // SerializationException is one
            EditHistory()
        }
    }

    private fun clampStep(step: EditStep): EditStep = when (step) {
        is EditStep.Add -> EditStep.Add(clampOp(step.op))
        is EditStep.Replace -> EditStep.Replace(step.index, clampOp(step.op))
    }

    private fun clampOp(op: EditOp): EditOp {
        val maxPoints = AnnotationGestures.MAX_STROKE_POINTS * 2
        return if (op is EditOp.Stroke && op.points.size > maxPoints) op.copy(points = op.points.take(maxPoints)) else op
    }
}
