package io.github.feedbacklib.android.internal.annotate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorGeometryTest {

    @Test
    fun `the image is fitted and centred, and view points map back to image pixels`() {
        val fit = FitTransform.fit(1000, 2000, 500f, 500f)
        assertEquals(0.25f, fit.scale)
        assertEquals(125f, fit.offsetX)
        assertEquals(0f, fit.offsetY)
        assertEquals(0f, fit.toImageX(125f))
        assertEquals(1000f, fit.toImageX(375f))
        assertEquals(2000f, fit.toImageY(500f))
        assertEquals(375f, fit.toViewX(1000f))
    }

    @Test
    fun `an unknown size fits as identity`() {
        assertEquals(FitTransform(1f, 0f, 0f), FitTransform.fit(0, 100, 500f, 500f))
        assertEquals(FitTransform(1f, 0f, 0f), FitTransform.fit(100, 100, 0f, 500f))
    }

    @Test
    fun `the preview is decoded no smaller than it is drawn`() {
        assertEquals(1, previewSampleSize(1080, 2400, 1080, 2400))
        assertEquals(2, previewSampleSize(4000, 3000, 1080, 2400))
        assertEquals(4, previewSampleSize(8000, 6000, 1080, 2400))
        assertEquals(1, previewSampleSize(100, 100, 1080, 2400))
        assertEquals(1, previewSampleSize(0, 100, 1080, 2400))
    }

    @Test
    fun `a magnifier starts at 15 percent of the short side and stays between 5 and 45`() {
        assertEquals(150f, MagnifierGeometry.defaultRadius(1000, 2000))
        assertEquals(50f, MagnifierGeometry.clampRadius(1f, 1000, 2000))
        assertEquals(450f, MagnifierGeometry.clampRadius(5_000f, 1000, 2000))
        assertEquals(200f, MagnifierGeometry.clampRadius(200f, 1000, 2000))
    }

    @Test
    fun `the handle wins over the inside, the topmost magnifier wins and other ops are ignored`() {
        val low = EditOp.Magnifier(100f, 100f, 50f)
        val high = EditOp.Magnifier(120f, 100f, 50f)
        val ops = listOf(low, EditOp.Blur(0f, 0f, 500f, 500f), high)
        assertEquals(MagnifierHit(2, onHandle = false), MagnifierGeometry.hit(ops, 110f, 100f, 10f))
        val (hx, hy) = MagnifierGeometry.handle(high)
        assertEquals(MagnifierHit(2, onHandle = true), MagnifierGeometry.hit(ops, hx + 3f, hy, 10f))
        assertEquals(MagnifierHit(0, onHandle = false), MagnifierGeometry.hit(ops, 55f, 100f, 10f))
        assertNull(MagnifierGeometry.hit(ops, 400f, 400f, 10f))
    }

    @Test
    fun `a stroke takes a point only when the finger moved far enough, up to a cap`() {
        assertTrue(AnnotationGestures.shouldAdd(emptyList(), 1f, 1f, 5f))
        assertFalse(AnnotationGestures.shouldAdd(listOf(0f, 0f), 3f, 0f, 5f))
        assertTrue(AnnotationGestures.shouldAdd(listOf(0f, 0f), 6f, 0f, 5f))
        val full = List(AnnotationGestures.MAX_STROKE_POINTS * 2) { 0f }
        assertFalse(AnnotationGestures.shouldAdd(full, 100f, 100f, 5f))
    }

    @Test
    fun `a blur drag is normalised, clipped to the image and ignored when too small`() {
        assertEquals(EditOp.Blur(10f, 20f, 100f, 200f), AnnotationGestures.blurRect(100f, 200f, 10f, 20f, 1000, 1000, 8f))
        assertEquals(EditOp.Blur(0f, 0f, 1000f, 50f), AnnotationGestures.blurRect(-30f, -5f, 1200f, 50f, 1000, 1000, 8f))
        assertNull(AnnotationGestures.blurRect(10f, 10f, 14f, 100f, 1000, 1000, 8f))
    }
}
