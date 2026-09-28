package io.github.feedbacklib.android.internal.transport

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.PreparedAttachment
import io.github.feedbacklib.android.PreparedReport
import io.github.feedbacklib.android.ReportType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File

class MultipartWriterTest {

    @TempDir
    lateinit var dir: File

    private fun report(mimeType: String): PreparedReport {
        val file = File(dir, "photo.bin").apply { writeBytes(byteArrayOf(1)) }
        return PreparedReport(
            id = "r-1",
            cid = "cid",
            type = ReportType.BUG,
            reportJson = "{}",
            attachments = listOf(PreparedAttachment("att-1", AttachmentKind.GALLERY_IMAGE, "photo.jpg", mimeType, 1, file)),
        )
    }

    private fun write(report: PreparedReport): String =
        ByteArrayOutputStream().also { MultipartWriter(it, "b").writeReport(report) }.toString("ISO-8859-1")

    @Test
    fun `a well-formed mime type is written as is`() {
        assertTrue(write(report("image/jpeg")).contains("Content-Type: image/jpeg\r\n\r\n"))
    }

    @Test
    fun `a mime type with line breaks cannot add a header`() {
        val body = write(report("image/png\r\nX-Injected: 1"))
        assertFalse(body.contains("X-Injected"))
        assertTrue(body.contains("Content-Type: application/octet-stream\r\n\r\n"))
    }

    @Test
    fun `only type, subtype and token parameters survive`() {
        assertEquals("image/webp", MultipartWriter.safeMimeType(" image/webp "))
        assertEquals("text/plain; charset=utf-8", MultipartWriter.safeMimeType("text/plain; charset=utf-8"))
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType(""))
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType("image"))
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType("image/png\"; x=\"y"))
    }

    @Test
    fun `a mime type with a line break is refused rather than stitched together`() {
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType("image/png\r\n"))
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType("image/\npng"))
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType("text/plain;\r\n charset=utf-8"))
    }

    @Test
    fun `only spaces and tabs may surround a parameter`() {
        assertEquals("text/plain;\tcharset=utf-8", MultipartWriter.safeMimeType("text/plain;\tcharset=utf-8"))
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType("text/plain;\u000Bcharset=utf-8"))
        assertEquals("application/octet-stream", MultipartWriter.safeMimeType("text/plain\u000C; charset=utf-8"))
    }
}
