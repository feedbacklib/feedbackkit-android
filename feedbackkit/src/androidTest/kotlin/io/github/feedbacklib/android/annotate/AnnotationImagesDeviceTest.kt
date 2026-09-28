package io.github.feedbacklib.android.annotate

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.ExifInterface
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.feedbacklib.android.internal.annotate.AndroidAnnotationImages
import io.github.feedbacklib.android.internal.annotate.EditOp
import io.github.feedbacklib.android.internal.annotate.PenColor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/** The Android side of the editor on real bitmaps: decode, EXIF, draw, encode. */
@RunWith(AndroidJUnit4::class)
class AnnotationImagesDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "annotation-test").apply {
        deleteRecursively()
        mkdirs()
    }

    private fun checkerboard(width: Int = 64, height: Int = 32, name: String = "checker.png"): File = File(dir, name).apply {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) for (x in 0 until width) bitmap.setPixel(x, y, if ((x + y) % 2 == 0) Color.BLACK else Color.WHITE)
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun plain(width: Int, height: Int, name: String): File = File(dir, name).apply {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GRAY) }
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun editedBytes(source: File, ops: List<EditOp>): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(AndroidAnnotationImages.writeEdited(source, ops, out))
        return out.toByteArray()
    }

    private fun edited(source: File, ops: List<EditOp>): Bitmap = editedBytes(source, ops).let { BitmapFactory.decodeByteArray(it, 0, it.size) }

    /** A 200×100 JPEG stored sideways: red on its left half, blue on its right, EXIF "rotate 90° clockwise". */
    private fun sidewaysJpeg(): File = File(dir, "turned.jpg").apply {
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        Canvas(bitmap).drawRect(0f, 0f, 100f, 100f, Paint().apply { color = Color.RED })
        outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
    }

    /** Upright, the red half is on top and the blue half below (JPEG: approximately). */
    private fun assertUpright(bitmap: Bitmap, scale: Int = 1) {
        assertEquals(100 / scale, bitmap.width)
        assertEquals(200 / scale, bitmap.height)
        for ((x, y) in listOf(50 to 20, 20 to 80, 80 to 60)) assertRed(bitmap.getPixel(x / scale, y / scale), "($x, $y)")
        for ((x, y) in listOf(50 to 180, 20 to 120, 80 to 140)) assertBlue(bitmap.getPixel(x / scale, y / scale), "($x, $y)")
    }

    private fun assertRed(pixel: Int, where: String) =
        assertTrue("$where = ${Integer.toHexString(pixel)}", Color.red(pixel) > 200 && Color.blue(pixel) < 60)

    private fun assertBlue(pixel: Int, where: String) =
        assertTrue("$where = ${Integer.toHexString(pixel)}", Color.blue(pixel) > 200 && Color.red(pixel) < 60)

    @Test
    fun aBlurLeavesOnlyBlockAverages() {
        val saved = edited(checkerboard(), listOf(EditOp.Blur(0f, 0f, 64f, 32f)))
        assertEquals(64, saved.width)
        for (y in 0 until 32) for (x in 0 until 64) {
            val red = Color.red(saved.getPixel(x, y))
            assertTrue("($x, $y) = $red", red in 120..136)
        }
    }

    @Test
    fun aStrokeIsDrawnAtFullSize() {
        val saved = edited(checkerboard(), listOf(EditOp.Stroke(PenColor.RED.argb, 6f, listOf(0f, 16f, 64f, 16f))))
        assertEquals(PenColor.RED.argb, saved.getPixel(32, 16))
    }

    @Test
    fun aLargeImageLoadsAFittedPreviewButReportsItsFullSize() {
        val loaded = AndroidAnnotationImages.load(plain(4000, 1000, "wide.png"), 1000, 1000)!!
        assertEquals(4000, loaded.width)
        assertEquals(1000, loaded.height)
        assertEquals(1000, loaded.base!!.width) // 1/4: still as large as it is drawn
    }

    @Test
    fun aTurnedJpegIsShownAndSavedUpright() {
        val jpeg = sidewaysJpeg()

        val loaded = AndroidAnnotationImages.load(jpeg, 1000, 1000)!!
        assertEquals(100, loaded.width)
        assertEquals(200, loaded.height)
        assertUpright(loaded.base!!)
        assertUpright(AndroidAnnotationImages.render(loaded, emptyList())!!.asAndroidBitmap())

        val bytes = editedBytes(jpeg, emptyList())
        assertArrayEquals("saved as PNG", byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()), bytes.copyOf(4))
        assertUpright(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
    }

    @Test
    fun aTurnedJpegPreviewIsUprightWhenSampledToo() {
        val loaded = AndroidAnnotationImages.load(sidewaysJpeg(), 50, 100)!!
        assertEquals(100, loaded.width)
        assertEquals(200, loaded.height)
        assertUpright(loaded.base!!, scale = 2)
    }

    @Test
    fun renderDrawsOnACopyAndLeavesTheBaseAlone() {
        val loaded = AndroidAnnotationImages.load(checkerboard(), 1000, 1000)!!
        val preview = AndroidAnnotationImages.render(loaded, listOf(EditOp.Blur(0f, 0f, 64f, 32f)))!!.asAndroidBitmap()
        assertTrue(Color.red(preview.getPixel(0, 0)) in 120..136)
        assertEquals(Color.BLACK, loaded.base!!.getPixel(0, 0))
    }
}
