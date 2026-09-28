package io.github.feedbacklib.android.internal.annotate

import android.graphics.Bitmap
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream

/**
 * The save and preview pipelines when memory runs out (controller ruling D5): an OutOfMemoryError at
 * decode, render or compress retries the whole pipeline at the next smaller sample, down to 1/4,
 * and every bitmap is recycled whatever happens. The allocating steps are injected; Robolectric
 * supplies the Bitmaps.
 */
@RunWith(RobolectricTestRunner::class)
class BitmapAnnotationImagesTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val ops = listOf<EditOp>(EditOp.Blur(0f, 0f, 40f, 40f))

    /**
     * A 400×200 image whose steps throw OutOfMemoryError at [stage] for the samples in [failing];
     * [nullAt] makes decode or orient return null instead, as native allocations fail on API 26+.
     */
    private class Steps(private val stage: String = "", private val failing: Set<Int> = emptySet()) : BitmapSteps() {
        val bitmaps = mutableListOf<Bitmap>()
        val samplesTried = mutableListOf<Int>()
        val renderScales = mutableListOf<Float>()
        var unreadable = false
        var nullAt: Pair<String, Set<Int>> = "" to emptySet()
        var renderFailure: Exception? = null

        /** When set, compress writes this many bytes at each sample instead of the 4-byte marker. */
        var encodedSize: ((Int) -> Int)? = null

        private fun sampleOf(bitmap: Bitmap) = WIDTH / bitmap.width

        private fun failIf(name: String, sample: Int) {
            if (stage == name && sample in failing) throw OutOfMemoryError("$name at 1/$sample")
        }

        private fun nullIf(name: String, sample: Int) = nullAt.first == name && sample in nullAt.second

        override fun bounds(file: File): Pair<Int, Int>? = if (unreadable) null else WIDTH to HEIGHT

        override fun decode(file: File, sample: Int): Bitmap? {
            samplesTried += sample
            failIf("decode", sample) // a decoder that runs out of memory returns nothing
            if (nullIf("decode", sample)) return null
            return Bitmap.createBitmap(WIDTH / sample, HEIGHT / sample, Bitmap.Config.ARGB_8888).also { bitmaps += it }
        }

        override fun orient(bitmap: Bitmap, orientation: Int): Bitmap? {
            failIf("orient", sampleOf(bitmap)) // before the turned copy exists: the decoded bitmap is still the caller's
            return if (nullIf("orient", sampleOf(bitmap))) null else bitmap
        }

        override fun render(bitmap: Bitmap, ops: List<EditOp>, scale: Float) {
            renderScales += scale
            failIf("render", sampleOf(bitmap))
            renderFailure?.let { throw it }
        }

        override fun compress(bitmap: Bitmap, out: OutputStream): Boolean {
            val sample = sampleOf(bitmap)
            encodedSize?.let { size ->
                repeat(size(sample)) { out.write(sample) }
                return true
            }
            out.write(byteArrayOf(0x50, sample.toByte())) // part of a file, then the failure
            failIf("compress", sample)
            out.write(byteArrayOf(0x4E, 0x47))
            return true
        }

        companion object {
            const val WIDTH = 400
            const val HEIGHT = 200
        }
    }

    private fun source(): File = temp.newFile("screenshot.png")

    private fun saved(sample: Int) = byteArrayOf(0x50, sample.toByte(), 0x4E, 0x47)

    private fun Steps.assertAllRecycled() = bitmaps.forEachIndexed { i, bitmap -> assertTrue("bitmap $i is recycled", bitmap.isRecycled) }

    @Test
    fun aFullSizeSaveNeedsNoFallback() {
        val steps = Steps()
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps).writeEdited(source(), ops, out))
        assertArrayEquals(saved(1), out.toByteArray())
        assertEquals(listOf(1), steps.samplesTried)
        assertEquals(listOf(1f), steps.renderScales)
        steps.assertAllRecycled()
    }

    @Test
    fun runningOutOfMemoryWhileDecodingSavesAtHalfSize() {
        val steps = Steps("decode", setOf(1))
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps).writeEdited(source(), ops, out))
        assertArrayEquals(saved(2), out.toByteArray())
        assertEquals(listOf(1, 2), steps.samplesTried)
        assertEquals("the ops are drawn at the size decoded", listOf(0.5f), steps.renderScales)
        steps.assertAllRecycled()
    }

    @Test
    fun runningOutOfMemoryWhileTurningRecyclesTheDecodedBitmap() {
        val steps = Steps("orient", setOf(1))
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps).writeEdited(source(), ops, out))
        assertArrayEquals(saved(2), out.toByteArray())
        assertEquals(2, steps.bitmaps.size)
        steps.assertAllRecycled()
    }

    @Test
    fun runningOutOfMemoryWhileRenderingSavesAtAQuarter() {
        val steps = Steps("render", setOf(1, 2))
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps).writeEdited(source(), ops, out))
        assertArrayEquals(saved(4), out.toByteArray())
        assertEquals(listOf(1f, 0.5f, 0.25f), steps.renderScales)
        steps.assertAllRecycled()
    }

    @Test
    fun runningOutOfMemoryWhileCompressingStartsTheOutputOver() {
        val steps = Steps("compress", setOf(1))
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps).writeEdited(source(), ops, out))
        assertArrayEquals("the half-written first attempt is gone", saved(2), out.toByteArray())
        steps.assertAllRecycled()
    }

    @Test
    fun aHalfWrittenFileIsTruncatedBeforeTheRetry() {
        val steps = Steps("compress", setOf(1, 2))
        val target = temp.newFile("edit.tmp")
        val written = target.outputStream().use { BitmapAnnotationImages(steps).writeEdited(source(), ops, it) }
        assertTrue(written)
        assertArrayEquals(saved(4), target.readBytes())
        steps.assertAllRecycled()
    }

    @Test
    fun aStreamThatCannotStartOverFailsInsteadOfWritingACorruptImage() {
        val steps = Steps("compress", setOf(1))
        val sink = ByteArrayOutputStream()
        val opaque = object : OutputStream() {
            override fun write(b: Int) = sink.write(b)
        }
        assertFalse(BitmapAnnotationImages(steps).writeEdited(source(), ops, opaque))
        assertEquals(listOf(1), steps.samplesTried)
        steps.assertAllRecycled()
    }

    @Test
    fun whenAQuarterDoesNotFitEitherTheSaveFails() {
        val source = source()
        for (stage in listOf("decode", "orient", "render", "compress")) {
            val steps = Steps(stage, setOf(1, 2, 4))
            assertFalse(stage, BitmapAnnotationImages(steps).writeEdited(source, ops, ByteArrayOutputStream()))
            assertEquals(stage, listOf(1, 2, 4), steps.samplesTried)
            steps.assertAllRecycled()
        }
    }

    @Test
    fun aDecoderThatReturnsNothingAtFullSizeSavesAtHalfSize() {
        val steps = Steps().apply { nullAt = "decode" to setOf(1) }
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps).writeEdited(source(), ops, out))
        assertArrayEquals(saved(2), out.toByteArray())
        assertEquals(listOf(1, 2), steps.samplesTried)
        steps.assertAllRecycled()
    }

    @Test
    fun aTurnThatCannotAllocateRecyclesTheDecodedBitmapAndFallsBack() {
        val steps = Steps().apply { nullAt = "orient" to setOf(1, 2) }
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps).writeEdited(source(), ops, out))
        assertArrayEquals(saved(4), out.toByteArray())
        assertEquals(3, steps.bitmaps.size)
        steps.assertAllRecycled()
    }

    @Test
    fun aDecoderThatNeverReturnsAnythingFailsAfterAQuarter() {
        val steps = Steps().apply { nullAt = "decode" to setOf(1, 2, 4) }
        assertFalse(BitmapAnnotationImages(steps).writeEdited(source(), ops, ByteArrayOutputStream()))
        assertEquals(listOf(1, 2, 4), steps.samplesTried)
    }

    @Test
    fun anUnreadableImageFailsWithoutRetrying() {
        val steps = Steps().apply { unreadable = true }
        val source = source()
        assertFalse(BitmapAnnotationImages(steps).writeEdited(source, ops, ByteArrayOutputStream()))
        assertNull(BitmapAnnotationImages(steps).load(source, 100, 100))
        assertEquals("nothing is decoded once bounds() says no", emptyList<Int>(), steps.samplesTried)
    }

    @Test
    fun aFailureOtherThanMemoryWhileRenderingFailsTheSaveAndRecyclesTheBitmap() {
        val steps = Steps().apply { renderFailure = IllegalStateException("broken op") }
        assertFalse(BitmapAnnotationImages(steps).writeEdited(source(), ops, ByteArrayOutputStream()))
        assertEquals("no retry: this is not memory", listOf(1), steps.samplesTried)
        assertEquals(1, steps.bitmaps.size)
        steps.assertAllRecycled()
    }

    @Test
    fun aPreviewWhoseDecoderReturnsNothingFallsBackToASmallerSample() {
        val steps = Steps().apply { nullAt = "decode" to setOf(4) }
        val loaded = BitmapAnnotationImages(steps).load(source(), 100, 100)!!
        assertEquals(listOf(4, 8), steps.samplesTried)
        assertEquals(50, loaded.base!!.width)
    }

    @Test
    fun thePreviewFallsBackToASmallerSampleToo() {
        val steps = Steps("decode", setOf(4))
        val loaded = BitmapAnnotationImages(steps).load(source(), 100, 100)!!
        assertEquals(400, loaded.width)
        assertEquals(200, loaded.height)
        assertEquals(listOf(4, 8), steps.samplesTried)
        assertEquals(50, loaded.base!!.width)
        assertFalse(loaded.base.isRecycled)
    }

    @Test
    fun aPreviewThatNeverFitsIsNull() {
        val steps = Steps("decode", setOf(4, 8, 16))
        assertNull(BitmapAnnotationImages(steps).load(source(), 100, 100))
        steps.assertAllRecycled()
    }

    @Test
    fun anEditedImageOverTheByteLimitIsSavedAtHalfSize() {
        val steps = Steps().apply { encodedSize = { sample -> 4000 / (sample * sample) } }
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps, maxBytes = 1000).writeEdited(source(), ops, out))
        assertEquals(listOf(1, 2), steps.samplesTried)
        assertEquals("only the half-size attempt is kept", 1000, out.size())
        assertTrue(out.toByteArray().all { it == 2.toByte() })
        steps.assertAllRecycled()
    }

    @Test
    fun anOversizedFirstAttemptIsTruncatedFromTheFile() {
        val steps = Steps().apply { encodedSize = { sample -> 4000 / (sample * sample) } }
        val target = temp.newFile("edit.tmp")
        val written = target.outputStream().use { BitmapAnnotationImages(steps, maxBytes = 300).writeEdited(source(), ops, it) }
        assertTrue(written)
        assertEquals(listOf(1, 2, 4), steps.samplesTried)
        assertEquals(250L, target.length())
    }

    @Test
    fun anEditedImageOverTheByteLimitEvenAtAQuarterFails() {
        val steps = Steps().apply { encodedSize = { sample -> 4000 / (sample * sample) } }
        assertFalse(BitmapAnnotationImages(steps, maxBytes = 100).writeEdited(source(), ops, ByteArrayOutputStream()))
        assertEquals(listOf(1, 2, 4), steps.samplesTried)
        steps.assertAllRecycled()
    }

    @Test
    fun anEditedImageExactlyAtTheByteLimitIsKeptAtFullSize() {
        val steps = Steps().apply { encodedSize = { sample -> 4000 / (sample * sample) } }
        val out = ByteArrayOutputStream()
        assertTrue(BitmapAnnotationImages(steps, maxBytes = 4000).writeEdited(source(), ops, out))
        assertEquals(listOf(1), steps.samplesTried)
        assertEquals(4000, out.size())
    }
}
