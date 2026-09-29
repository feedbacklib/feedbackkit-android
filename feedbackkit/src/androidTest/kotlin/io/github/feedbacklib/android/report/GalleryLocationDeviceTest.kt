package io.github.feedbacklib.android.report

import android.graphics.Bitmap
import android.graphics.Color
import android.media.ExifInterface
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.report.AddImageResult
import io.github.feedbacklib.android.internal.report.DraftStore
import io.github.feedbacklib.android.internal.report.ExifLocationStripper
import io.github.feedbacklib.android.internal.report.ImageLocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A gallery photo with a location goes through the real import path and reaches the draft without it (spec §6). */
@RunWith(AndroidJUnit4::class)
class GalleryLocationDeviceTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "gallery-location-test").apply {
        deleteRecursively()
        mkdirs()
    }
    private val logger = SdkLogger(LogLevel.NONE)
    private val store = DraftStore({ File(dir, "drafts") }, logger, ExifLocationStripper(logger))

    private fun photo(name: String, format: Bitmap.CompressFormat): File = File(dir, name).apply {
        val bitmap = Bitmap.createBitmap(64, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        outputStream().use { bitmap.compress(format, 90, it) }
        ExifInterface(path).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_MAKE, "Maker")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE, "55/1,45/1,0/1")
            setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, "N")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE, "37/1,37/1,12/1")
            setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, "E")
            setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "150/1")
            setAttribute(ExifInterface.TAG_GPS_TIMESTAMP, "12:00:00")
            setAttribute(ExifInterface.TAG_GPS_DATESTAMP, "2026:09:29")
            saveAttributes()
        }
        assertTrue("the fixture has a location", ExifInterface(path).getLatLong(FloatArray(2)))
    }

    private fun import(source: File, mimeType: String): File {
        val result = runBlocking {
            store.onQueue("d1") { addImage("d1", { mimeType }, { source.inputStream() }, 4, 10_000_000) }
        }
        return (result as AddImageResult.Added).file.file
    }

    private fun assertNoLocationButOrientation(file: File) {
        val exif = ExifInterface(file.path)
        for (tag in ImageLocation.GPS_TAGS) assertNull(tag, exif.getAttribute(tag))
        assertFalse(exif.getLatLong(FloatArray(2)))
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals("Maker", exif.getAttribute(ExifInterface.TAG_MAKE))
    }

    @Test
    fun aJpegLosesItsLocationAndKeepsItsOrientation() {
        val imported = import(photo("photo.jpg", Bitmap.CompressFormat.JPEG), "image/jpeg")
        assertEquals("jpg", imported.extension)
        assertNoLocationButOrientation(imported)
    }

    @Test
    fun aPngLosesItsLocationWhereThePlatformRewritesIt() {
        assumeTrue(Build.VERSION.SDK_INT >= 30)
        val imported = import(photo("photo.png", Bitmap.CompressFormat.PNG), "image/png")
        assertNoLocationButOrientation(imported)
    }
}
