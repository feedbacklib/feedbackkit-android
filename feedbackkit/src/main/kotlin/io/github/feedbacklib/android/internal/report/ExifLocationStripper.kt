package io.github.feedbacklib.android.internal.report

import android.media.ExifInterface
import android.os.Build
import io.github.feedbacklib.android.internal.core.SdkLogger
import java.io.File

/**
 * [LocationStripper] over the platform [ExifInterface] (the SDK takes no androidx.exifinterface
 * dependency), per [ImageLocation.handling]. Never throws: any failure keeps the image out.
 */
internal class ExifLocationStripper(
    private val logger: SdkLogger,
    private val sdkInt: Int = Build.VERSION.SDK_INT,
) : LocationStripper {

    override fun strip(file: File, extension: String): Boolean {
        val clean = try {
            when (ImageLocation.handling(extension, sdkInt)) {
                LocationHandling.KEEP -> true
                LocationHandling.STRIP -> !ImageLocation.hasExifBlock(file, extension) || rewriteWithoutGps(file)
                LocationHandling.REJECT_IF_GPS -> ExifInterface(file.path).let { it.parsed() && !it.hasGps() }
                LocationHandling.REJECT_IF_EXIF -> !ImageLocation.hasExifBlock(file, extension)
                LocationHandling.REJECT -> false
            }
        } catch (e: Exception) {
            logger.w("Could not remove the location from a gallery image", e)
            false
        } catch (e: OutOfMemoryError) {
            logger.w("Could not remove the location from a gallery image", e)
            false
        }
        if (!clean) logger.w("A gallery image that may carry a location was not added")
        return clean
    }

    /**
     * Saves the EXIF again without its GPS tags. The platform refuses to save a file it could not
     * parse (it would otherwise just read no tags at all), so a successful save proves the tags
     * were seen; the file is then read back to be sure.
     */
    private fun rewriteWithoutGps(file: File): Boolean {
        val exif = ExifInterface(file.path)
        ImageLocation.GPS_TAGS.forEach { exif.setAttribute(it, null) }
        exif.saveAttributes()
        return !ExifInterface(file.path).hasGps()
    }

    private fun ExifInterface.hasGps(): Boolean = ImageLocation.GPS_TAGS.any { getAttribute(it) != null }

    /**
     * A HEIF the platform read: its width comes from the container. Reading failures are silent,
     * and the platform then fills in a width of 0 for compatibility.
     */
    private fun ExifInterface.parsed(): Boolean = getAttributeInt(ExifInterface.TAG_IMAGE_WIDTH, 0) > 0
}
