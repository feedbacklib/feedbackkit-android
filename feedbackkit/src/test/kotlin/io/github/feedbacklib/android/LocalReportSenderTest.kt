package io.github.feedbacklib.android

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.zip.ZipFile

class LocalReportSenderTest {

    @TempDir
    lateinit var dir: File

    private val outputDir by lazy { File(dir, "out") }

    private fun zipEntries(zip: File): Map<String, String> = ZipFile(zip).use { file ->
        file.entries().toList().associate { it.name to file.getInputStream(it).readBytes().decodeToString() }
    }

    @Test
    fun `writes the report and its attachments into one zip`() = runBlocking {
        val result = LocalReportSender { outputDir }
            .send(preparedReport(dir, "log.txt" to "hello".toByteArray()))

        assertEquals(SendResult.Success, result)
        val entries = zipEntries(File(outputDir, "report-1.zip"))
        assertEquals("""{"id":"report-1"}""", entries["report.json"])
        assertEquals("hello", entries["attachments/att-0-log.txt"])
        assertFalse(File(outputDir, "report-1.zip.tmp").exists())
    }

    @Test
    fun `path separators in file names do not escape the attachments folder`() = runBlocking {
        LocalReportSender { outputDir }.send(preparedReport(dir, "../../etc/x.txt" to byteArrayOf(1)))
        assertTrue(zipEntries(File(outputDir, "report-1.zip")).keys.contains("attachments/att-0-.._.._etc_x.txt"))
    }

    @Test
    fun `sending the same report again replaces the zip`() = runBlocking {
        val sender = LocalReportSender { outputDir }
        sender.send(preparedReport(dir, "a.txt" to "one".toByteArray()))
        sender.send(preparedReport(dir, "a.txt" to "two".toByteArray()))
        assertEquals("two", zipEntries(File(outputDir, "report-1.zip"))["attachments/att-0-a.txt"])
    }

    @Test
    fun `missing output directory is retryable`() = runBlocking {
        assertTrue(LocalReportSender { null }.send(preparedReport(dir)) is SendResult.RetryableFailure)
    }

    @Test
    fun `failed write leaves neither a temporary nor a final zip`() = runBlocking {
        val report = preparedReport(dir, "log.txt" to "hello".toByteArray())
        report.attachments.single().file.delete()

        val result = LocalReportSender { outputDir }.send(report)

        assertTrue(result is SendResult.RetryableFailure)
        assertFalse(File(outputDir, "report-1.zip.tmp").exists())
        assertFalse(File(outputDir, "report-1.zip").exists())
    }
}
