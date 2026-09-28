package io.github.feedbacklib.android.internal.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.internal.report.DraftFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

// Needs real Bitmap pixels (EXIF rotation, the byte-bounded cache): JUnit 4 + Robolectric, not
// plain JUnit 5 (whose android.jar stub throws on any Android call).
@RunWith(RobolectricTestRunner::class)
class ThumbnailsTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Before
    fun setUp() {
        ThumbnailCache.clear()
    }

    @Test
    fun `sample size halves until the height fits`() {
        assertEquals(4, sampleSizeFor(1080, 2400, 480))
        assertEquals(1, sampleSizeFor(100, 100, 480))
    }

    @Test
    fun `a landscape image is sampled against its height, not its longer width`() {
        // The thumbnail tile fixes the height and lets the width follow (ContentScale.Fit into a
        // fixed-height box), so a 4000x3000 landscape screenshot must sample against 3000 (giving 4),
        // not against the longer 4000 width (which would give 8 and blur it once fit to the tile).
        assertEquals(4, sampleSizeFor(4000, 3000, 480))
    }

    @Test
    fun `a panorama is sampled until its width is at most four tile heights`() {
        // 20000x2000 by its height alone would sample 4 and decode 5000x500: 10 MB for one tile.
        assertEquals(16, sampleSizeFor(20_000, 2_000, 480))
        assertEquals("exactly four tile heights wide is small enough", 2, sampleSizeFor(3_840, 1_000, 480))
    }

    @Test
    fun `unknown sizes decode at full size`() {
        assertEquals(1, sampleSizeFor(0, 2400, 480))
        assertEquals(1, sampleSizeFor(1080, 2400, 0))
    }

    @Test
    fun `a file the editor rewrote gets a new thumbnail key`() {
        val before = DraftFile(AttachmentKind.SCREENSHOT, File("d/screenshot.png"), "image/png", lastModified = 1_000, sizeBytes = 10)
        val after = before.copy(lastModified = 2_000, sizeBytes = 12)
        assertEquals(ThumbnailKey.of(before, 480), ThumbnailKey.of(before.copy(), 480))
        assertNotEquals(ThumbnailKey.of(before, 480), ThumbnailKey.of(after, 480))
        assertNotEquals(ThumbnailKey.of(before, 480), ThumbnailKey.of(before, 240))
    }

    @Test
    fun `a photo turned by EXIF swaps its sides`() {
        assertEquals(3000 to 4000, ExifOrientation.orientedSize(4000, 3000, ExifOrientation.ROTATE_90))
        assertEquals(3000 to 4000, ExifOrientation.orientedSize(4000, 3000, ExifOrientation.TRANSVERSE))
        assertEquals(4000 to 3000, ExifOrientation.orientedSize(4000, 3000, ExifOrientation.ROTATE_180))
        assertEquals(4000 to 3000, ExifOrientation.orientedSize(4000, 3000, ExifOrientation.NORMAL))
    }

    @Test
    fun `read skips files whose extension EXIF never touches`() {
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read(File("screenshot.png")))
    }

    @Test
    fun `read falls back to NORMAL instead of throwing when the file is not a real image`() {
        val garbage = File(temp.root, "broken.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertEquals(ExifOrientation.NORMAL, ExifOrientation.read(garbage))
    }

    @Test
    fun `apply returns the very same bitmap for NORMAL, without recycling it`() {
        val source = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
        val result = ExifOrientation.apply(source, ExifOrientation.NORMAL)
        assertSame(source, result)
        assertFalse(source.isRecycled)
    }

    // Robolectric's default (legacy) Bitmap shadow computes the size Bitmap.createBitmap(source, x,
    // y, w, h, matrix, filter) turns a photo to, but — unlike a real device — does not actually
    // render through the matrix, so a decoded pixel-by-pixel check of a turned photo cannot run
    // here; the geometry (which orientations swap width/height) is covered by
    // `a photo turned by EXIF swaps its sides` above through the shared `orientedSize` helper.
    // What this asserts instead, for every orientation that is not a no-op: apply() asks for a
    // correctly (turned) sized replacement bitmap and recycles the one it replaces, exactly once.
    @Test
    fun `apply produces a correctly sized replacement and recycles the original for every turned orientation`() {
        val turned = listOf(
            ExifOrientation.FLIP_HORIZONTAL, ExifOrientation.ROTATE_180, ExifOrientation.FLIP_VERTICAL,
            ExifOrientation.TRANSPOSE, ExifOrientation.ROTATE_90, ExifOrientation.TRANSVERSE, ExifOrientation.ROTATE_270,
        )
        for (orientation in turned) {
            val source = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888)
            val result = ExifOrientation.apply(source, orientation)
            val (width, height) = ExifOrientation.orientedSize(8, 6, orientation)
            assertEquals("width for orientation $orientation", width, result.width)
            assertEquals("height for orientation $orientation", height, result.height)
            assertTrue("the source must be recycled once replaced, for orientation $orientation", source.isRecycled)
        }
    }

    @Test
    fun `the cache remembers a decoded thumbnail until it is evicted or cleared`() {
        val key = ThumbnailKey("a", 0, 0, 480)
        val bitmap = Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).asImageBitmap()
        assertNull(ThumbnailCache.get(key))

        ThumbnailCache.put(key, bitmap)
        assertSame(bitmap, ThumbnailCache.get(key))

        ThumbnailCache.clear()
        assertNull("clear() drops everything, e.g. when the report screen really closes", ThumbnailCache.get(key))
    }

    @Test
    fun `the cache is bounded by decoded bytes, not by how many thumbnails are held`() {
        // ~1.87 MB each (700 * 700 * 4 bytes/px): two fit the ~4 MB budget, a third does not.
        fun bitmap() = Bitmap.createBitmap(700, 700, Bitmap.Config.ARGB_8888).asImageBitmap()
        val a = ThumbnailKey("a", 0, 0, 480)
        val b = ThumbnailKey("b", 0, 0, 480)
        val c = ThumbnailKey("c", 0, 0, 480)

        ThumbnailCache.put(a, bitmap())
        ThumbnailCache.put(b, bitmap())
        assertNotNull(ThumbnailCache.get(a)) // touch a: b is now the least recently used
        ThumbnailCache.put(c, bitmap())

        assertNull("the oldest thumbnail is evicted once the byte budget is exceeded", ThumbnailCache.get(b))
        assertNotNull(ThumbnailCache.get(a))
        assertNotNull(ThumbnailCache.get(c))
    }
}
