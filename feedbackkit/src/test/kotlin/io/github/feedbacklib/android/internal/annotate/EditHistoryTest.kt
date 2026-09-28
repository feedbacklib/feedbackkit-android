package io.github.feedbacklib.android.internal.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditHistoryTest {

    private val stroke = EditOp.Stroke(PenColor.RED.argb, 4f, listOf(1f, 2f, 3f, 4f))
    private val lens = EditOp.Magnifier(50f, 60f, 20f)
    private val blur = EditOp.Blur(0f, 0f, 16f, 16f)

    @Test
    fun `ops are the steps folded in order`() {
        val history = EditHistory().add(stroke).add(lens).add(blur)
        assertEquals(listOf(stroke, lens, blur), history.ops())
        assertTrue(history.canUndo)
        assertFalse(EditHistory().canUndo)
    }

    @Test
    fun `moving a magnifier replaces it and one undo puts it back`() {
        val moved = lens.copy(centerX = 80f)
        val history = EditHistory().add(stroke).add(lens).replace(1, moved)
        assertEquals(listOf(stroke, moved), history.ops())
        assertEquals(listOf(stroke, lens), history.undo().ops())
        assertEquals(listOf(stroke), history.undo().undo().ops())
    }

    @Test
    fun `undo on an empty history and a replace of a missing op change nothing`() {
        val empty = EditHistory()
        assertSame(empty, empty.undo())
        val one = empty.add(stroke)
        assertSame(one, one.replace(5, lens))
    }

    @Test
    fun `the history stops growing at its cap`() {
        var history = EditHistory()
        repeat(EditHistory.MAX_STEPS + 10) { history = history.add(stroke) }
        assertEquals(EditHistory.MAX_STEPS, history.steps.size)
    }

    @Test
    fun `the saved json brings back the same steps`() {
        val history = EditHistory().add(stroke).add(lens).replace(1, lens.copy(radius = 33.5f)).add(blur)
        assertEquals(history, EditsJson.decode(EditsJson.encode(history)))
    }

    @Test
    fun `a missing, damaged or newer file is an empty edit`() {
        assertEquals(EditHistory(), EditsJson.decode(null))
        assertEquals(EditHistory(), EditsJson.decode(""))
        assertEquals(EditHistory(), EditsJson.decode("{not json"))
        assertEquals(EditHistory(), EditsJson.decode("""{"version":2,"steps":[]}"""))
        assertEquals(EditHistory(), EditsJson.decode("""{"version":1,"steps":[{"type":"teleport"}]}"""))
    }

    @Test
    fun `truncated json never throws and comes back empty`() {
        val full = EditsJson.encode(EditHistory().add(stroke).add(lens))
        // Cut at every length, including in the middle of a string or a number: none of it throws.
        for (cut in 1 until full.length) {
            assertEquals(EditHistory(), EditsJson.decode(full.substring(0, cut)), "cut at $cut: \"${full.substring(0, cut)}\"")
        }
    }

    @Test
    fun `unknown top-level and per-step fields are ignored, not fatal`() {
        val history = EditHistory().add(stroke).add(lens)
        val withUnknownFields = """
            {"version":1,"unknownTopLevel":{"nested":true},"steps":[
                {"type":"add","op":{"type":"stroke","color":${stroke.color},"width":${stroke.width},"points":${stroke.points},"future":"x"},"extra":1},
                {"type":"add","op":{"type":"magnifier","centerX":${lens.centerX},"centerY":${lens.centerY},"radius":${lens.radius},"future":42},"another":[1,2,3]}
            ]}
        """.trimIndent()
        assertEquals(history, EditsJson.decode(withUnknownFields))
    }

    @Test
    fun `an op of an unknown type discards the whole file, not just that step`() {
        val decoded = EditsJson.decode(
            """{"version":1,"steps":[
                {"type":"add","op":{"type":"stroke","color":${stroke.color},"width":${stroke.width},"points":${stroke.points}}},
                {"type":"add","op":{"type":"sparkle","intensity":9000}},
                {"type":"add","op":{"type":"blur","left":${blur.left},"top":${blur.top},"right":${blur.right},"bottom":${blur.bottom}}}
            ]}""",
        )
        // The whole file is discarded rather than risking a partially-decoded, misaligned history:
        // an unrecognised op type makes the polymorphic step decode fail, caught as an empty edit.
        assertEquals(EditHistory(), decoded)
    }

    @Test
    fun `structurally wrong json (an array or a bare value at the root) is an empty edit, not a crash`() {
        assertEquals(EditHistory(), EditsJson.decode("[]"))
        assertEquals(EditHistory(), EditsJson.decode("null"))
        assertEquals(EditHistory(), EditsJson.decode("42"))
        assertEquals(EditHistory(), EditsJson.decode("\"just a string\""))
        assertEquals(EditHistory(), EditsJson.decode("""{"version":"one","steps":[]}"""))
    }

    @Test
    fun `decode clamps more steps than the editor would ever save to MAX_STEPS`() {
        val oneStep = """{"type":"add","op":{"type":"blur","left":0.0,"top":0.0,"right":1.0,"bottom":1.0}}"""
        val tooMany = List(EditHistory.MAX_STEPS + 50) { oneStep }.joinToString(",")
        val decoded = EditsJson.decode("""{"version":1,"steps":[$tooMany]}""")
        assertEquals(EditHistory.MAX_STEPS, decoded.steps.size)
    }

    @Test
    fun `decode clamps a stroke with more points than the editor would ever save to MAX_STROKE_POINTS`() {
        val tooManyPoints = List(AnnotationGestures.MAX_STROKE_POINTS * 2 + 40) { it.toFloat() }
        val saved = EditHistory().add(EditOp.Stroke(PenColor.RED.argb, 4f, tooManyPoints))
        val decoded = EditsJson.decode(EditsJson.encode(saved))
        val decodedStroke = decoded.ops().single() as EditOp.Stroke
        assertEquals(tooManyPoints.take(AnnotationGestures.MAX_STROKE_POINTS * 2), decodedStroke.points)
    }
}
