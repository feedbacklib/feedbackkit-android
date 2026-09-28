package io.github.feedbacklib.android.internal.annotate

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

class RasterTest {

    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val gray = 0xFF808080.toInt()

    private fun surface(width: Int, height: Int, color: Int = white) = IntArraySurface(width, height).apply { fill(color) }

    private fun red(argb: Int) = (argb shr 16) and 0xFF

    @Test
    fun `blend with full coverage of an opaque colour is that colour, with none it is the original`() {
        assertEquals(red, Raster.blend(white, red, 1f))
        assertEquals(white, Raster.blend(white, red, 0f))
        // 128/255 of red over white: 255 · (1 − 128/255) = 127 left of green and blue.
        assertEquals(0xFFFF7F7F.toInt(), Raster.blend(white, 0x80FF0000.toInt(), 1f))
    }

    @Test
    fun `a stroke covers its width, fades over one pixel at the edge and leaves the rest`() {
        val s = surface(20, 20)
        Raster.drawStroke(s, floatArrayOf(2f, 10.5f, 17f, 10.5f), 4f, red)
        assertEquals(red, s[10, 9])
        assertEquals(red, s[10, 10])
        assertEquals(red, s[10, 11])
        assertTrue(s[10, 12] != white && s[10, 12] != red, "the edge pixel is half covered")
        assertEquals(white, s[10, 13])
        assertEquals(white, s[10, 7])
    }

    @Test
    fun `a single point draws a dot`() {
        val s = surface(12, 12)
        Raster.drawStroke(s, floatArrayOf(5.5f, 5.5f), 4f, red)
        assertEquals(red, s[5, 5])
        assertEquals(white, s[5, 9])
    }

    @Test
    fun `a translucent stroke is not painted twice where its segments meet`() {
        val s = surface(20, 20)
        val translucent = 0x80FF0000.toInt()
        Raster.drawStroke(s, floatArrayOf(2f, 10.5f, 10f, 10.5f, 17f, 10.5f), 4f, translucent)
        assertEquals(Raster.blend(white, translucent, 1f), s[10, 10])
        assertEquals(s[6, 10], s[10, 10])
    }

    @Test
    fun `a stroke taller than one band is drawn in every band`() {
        val s = surface(10, Raster.BAND_ROWS * 3)
        Raster.drawStroke(s, floatArrayOf(5.5f, 0f, 5.5f, Raster.BAND_ROWS * 3f), 3f, red)
        (0 until s.height).forEach { y -> assertEquals(red, s[5, y], "row $y") }
    }

    @Test
    fun `a stroke outside the surface changes nothing and does not throw`() {
        val s = surface(10, 10)
        Raster.drawStroke(s, floatArrayOf(-50f, -50f, -40f, -40f), 4f, red)
        Raster.drawStroke(s, floatArrayOf(), 4f, red)
        assertTrue(s.pixels.all { it == white })
    }

    @Test
    fun `pixelation turns each 16 px block into its average and nothing of the detail survives`() {
        val s = surface(32, 16)
        for (y in 0 until 16) for (x in 0 until 32) s[x, y] = if (x >= 16) blue else if (x % 2 == 0) black else white
        Raster.pixelate(s, PixelRect(0, 0, 32, 16), 16)
        for (y in 0 until 16) for (x in 0 until 16) assertEquals(gray, s[x, y], "($x, $y)")
        for (y in 0 until 16) for (x in 16 until 32) assertEquals(blue, s[x, y], "($x, $y)")
    }

    @Test
    fun `a partial block at the edge is averaged over what it covers`() {
        val s = surface(20, 16)
        for (y in 0 until 16) for (x in 0 until 20) s[x, y] = if (x < 16) white else if (x % 2 == 0) black else white
        Raster.pixelate(s, PixelRect(0, 0, 20, 16), 16)
        assertEquals(white, s[0, 0])
        for (x in 16 until 20) assertEquals(gray, s[x, 5])
    }

    @Test
    fun `blocks stay anchored at the rectangle's corner where it is clipped`() {
        val s = surface(16, 16)
        for (y in 0 until 16) for (x in 0 until 16) s[x, y] = if (x % 2 == 0) black else white
        // The rectangle starts 8 px outside: its first block covers only pixels 0..7.
        Raster.pixelate(s, PixelRect(-8, -8, 24, 24), 16)
        assertEquals(gray, s[0, 0])
        assertEquals(s[0, 0], s[7, 7])
        assertEquals(gray, s[8, 8])
    }

    @Test
    fun `the magnifier shows what is under its centre twice as large`() {
        val s = surface(40, 40)
        for (y in 21..22) for (x in 21..22) s[x, y] = red
        Raster.magnify(s, 20f, 20f, 10f, 2f, 1f, gray)
        // The 2×2 red square under the centre becomes 4×4, and the original is gone.
        val reds = (0 until 40).flatMap { y -> (0 until 40).map { x -> x to y } }.filter { (x, y) -> s[x, y] == red }
        assertEquals((22..25).flatMap { y -> (22..25).map { x -> x to y } }.toSet(), reds.toSet())
    }

    @Test
    fun `the magnifier draws a ring and leaves everything outside it`() {
        val s = surface(40, 40)
        Raster.magnify(s, 20f, 20f, 10f, 2f, 1f, 0xFF9E9E9E.toInt())
        assertTrue(red(s[20, 30]) < 250, "the ring just outside the radius")
        assertEquals(white, s[0, 0])
        assertEquals(white, s[20, 33])
    }

    @Test
    fun `a magnifier larger than one band is complete`() {
        val size = Raster.BAND_ROWS * 3
        val s = surface(size, size)
        for (x in 0 until size) s[x, size / 2] = red // a horizontal line through the centre
        Raster.magnify(s, size / 2f, size / 2f, size * 0.45f, 2f, 1f, gray)
        assertEquals(red, s[size / 2, size / 2])
        assertEquals(red, s[size / 2 + Raster.BAND_ROWS, size / 2])
        assertNotEquals(red, s[size / 2, size / 2 + 2])
    }

    /** Records the tallest region any [read] was asked for, then delegates to a real surface. */
    private class RecordingSurface(private val delegate: IntArraySurface) : PixelSurface by delegate {
        var maxReadHeight = 0
            private set

        override fun read(rect: PixelRect): PixelRegion {
            maxReadHeight = max(maxReadHeight, rect.height)
            return delegate.read(rect)
        }

        override fun write(region: PixelRegion) = delegate.write(region)
    }

    @Test
    fun `a huge radius never makes the magnifier read a source square scaling with it, only bands`() {
        // Stands in for a large photo: a magnifier with a radius many times BAND_ROWS must still
        // only ever ask for band-sized regions, never a source square of about the radius's size
        // (controller ruling D5).
        val size = Raster.BAND_ROWS * 10
        val recording = RecordingSurface(surface(size, size))
        val radius = size * 0.4f
        assertTrue(radius > Raster.BAND_ROWS * 3, "the radius must dwarf a band for this to prove anything")

        Raster.magnify(recording, size / 2f, size / 2f, radius, 2f, 1f, gray)

        assertTrue(
            recording.maxReadHeight <= Raster.BAND_ROWS,
            "largest region read was ${recording.maxReadHeight} rows tall, radius was $radius",
        )
    }

    /**
     * The brief's original design, unchanged: one immutable read of the whole source square before
     * any destination band is written — no banding of the source, no two-sweep ordering. Kept here,
     * separate from [Raster.magnify], purely as a slow-but-obviously-correct reference to check the
     * banded, sqrt-avoiding version against.
     */
    private fun referenceMagnify(surface: PixelSurface, centerX: Float, centerY: Float, radius: Float, zoom: Float, ringWidth: Float, ringColor: Int) {
        if (radius <= 0f || zoom < 1f) return
        val outer = radius + max(0f, ringWidth)
        val box = PixelRect.covering(centerX - outer - 1f, centerY - outer - 1f, centerX + outer + 1f, centerY + outer + 1f)
            .intersect(surface.bounds)
        if (box.isEmpty) return
        val reach = radius / zoom
        val sourceRect = PixelRect.covering(centerX - reach - 1f, centerY - reach - 1f, centerX + reach + 1f, centerY + reach + 1f)
            .intersect(surface.bounds)
        if (sourceRect.isEmpty) return
        val source = surface.read(sourceRect)
        var bandTop = box.top
        while (bandTop < box.bottom) {
            val band = PixelRect(box.left, bandTop, box.right, min(bandTop + Raster.BAND_ROWS, box.bottom))
            val region = surface.read(band)
            for (y in band.top until band.bottom) {
                for (x in band.left until band.right) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    val distance = hypot(px - centerX, py - centerY)
                    if (distance <= radius) {
                        val sx = floor(centerX + (px - centerX) / zoom).toInt().coerceIn(sourceRect.left, sourceRect.right - 1)
                        val sy = floor(centerY + (py - centerY) / zoom).toInt().coerceIn(sourceRect.top, sourceRect.bottom - 1)
                        region[x, y] = source[sx, sy]
                    } else {
                        val coverage = outer + 0.5f - distance
                        if (coverage > 0f) region[x, y] = Raster.blend(region[x, y], ringColor, min(coverage, 1f))
                    }
                }
            }
            surface.write(region)
            bandTop = band.bottom
        }
    }

    /** Deterministic, position-dependent content: two surfaces filled this way start out identical. */
    private fun IntArraySurface.noisify() {
        for (y in 0 until height) {
            for (x in 0 until width) {
                val v = x * 928_371 + y * 12_841 + 17
                this[x, y] = (0xFF shl 24) or ((v and 0xFF) shl 16) or (((v ushr 8) and 0xFF) shl 8) or ((v ushr 16) and 0xFF)
            }
        }
    }

    @Test
    fun `matches the reference, whole-square-read magnifier pixel for pixel, over many centres and radii`() {
        val size = Raster.BAND_ROWS * 6
        val mid = size / 2f
        val zoom = 2f
        val ringWidth = 3f
        val radii = listOf(20f, Raster.BAND_ROWS * 0.5f, Raster.BAND_ROWS * 1.5f, Raster.BAND_ROWS * 3f)
        val centres = mapOf(
            "inside" to (mid to mid),
            "on x.5" to ((mid + 0.5f) to (mid + 0.5f)),
            "partly outside, top-left corner" to (10f to 10f),
            "partly outside, bottom-right corner" to ((size - 10f) to (size - 10f)),
            "fully outside, above" to (mid to -80f),
            "fully outside, below" to (mid to (size + 80f)),
        )

        for ((label, centre) in centres) {
            val (cx, cy) = centre
            for (radius in radii) {
                val actual = IntArraySurface(size, size).apply { noisify() }
                val expected = IntArraySurface(size, size).apply { noisify() }

                Raster.magnify(actual, cx, cy, radius, zoom, ringWidth, gray)
                referenceMagnify(expected, cx, cy, radius, zoom, ringWidth, gray)

                assertArrayEquals(expected.pixels, actual.pixels, "centre=$label ($cx, $cy), radius=$radius")
            }
        }
    }
}
