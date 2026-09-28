package io.github.feedbacklib.android

import android.content.Context
import io.github.feedbacklib.android.internal.core.SdkLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The default sender while there is no backend: saves each report as
 * `Android/data/<package>/files/feedbackkit-reports/<id>.zip` (reachable over USB, no root) and
 * logs the path.
 *
 * Zips are never deleted automatically and the directory is readable by apps with storage
 * permission on Android 8-9: use it for development and the sample, and set a real sender with
 * `Builder.setReportSender` in production.
 */
public class LocalReportSender internal constructor(
    private val outputDir: () -> File?,
) : ReportSender {

    public constructor(context: Context) : this(externalReportsDir(context.applicationContext))

    override suspend fun send(report: PreparedReport): SendResult = withContext(Dispatchers.IO) {
        val dir = outputDir()
            ?: return@withContext SendResult.RetryableFailure(IOException("External files directory is unavailable"))
        val tmp = File(dir, "${report.id}.zip.tmp")
        try {
            dir.mkdirs()
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("report.json"))
                zip.write(report.reportJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
                report.attachments.forEach { attachment ->
                    zip.putNextEntry(ZipEntry("attachments/${attachment.id}-${safeName(attachment.fileName)}"))
                    attachment.file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
            }
            val zipFile = File(dir, "${report.id}.zip")
            zipFile.delete()
            if (!tmp.renameTo(zipFile)) throw IOException("Cannot move ${tmp.name} into place")
            SdkLog.logger.i("Report ${report.id} saved to ${zipFile.absolutePath}")
            SendResult.Success
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tmp.delete()
            SendResult.RetryableFailure(e)
        }
    }

    private fun safeName(fileName: String): String = fileName.replace('/', '_').replace('\\', '_')

    internal companion object {
        const val DIRECTORY_NAME: String = "feedbackkit-reports"

        private fun externalReportsDir(app: Context): () -> File? = { app.getExternalFilesDir(DIRECTORY_NAME) }
    }
}
