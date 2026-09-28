package io.github.feedbacklib.android.internal.transport

import io.github.feedbacklib.android.PreparedReport
import java.io.OutputStream

/** Streams a report as `multipart/form-data`: part `report` (JSON), then one part per attachment. */
internal class MultipartWriter(
    private val out: OutputStream,
    private val boundary: String,
) {

    fun writeReport(report: PreparedReport) {
        writePart("Content-Disposition: form-data; name=\"report\"\r\nContent-Type: application/json; charset=utf-8") {
            it.write(report.reportJson.toByteArray(Charsets.UTF_8))
        }
        report.attachments.forEach { attachment ->
            val disposition = "Content-Disposition: form-data; name=\"${attachment.id}\"; " +
                "filename=\"${escape(attachment.fileName)}\""
            writePart("$disposition\r\nContent-Type: ${safeMimeType(attachment.mimeType)}") { sink ->
                attachment.file.inputStream().use { it.copyTo(sink) }
            }
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    private inline fun writePart(headers: String, body: (OutputStream) -> Unit) {
        out.write("--$boundary\r\n$headers\r\n\r\n".toByteArray(Charsets.UTF_8))
        body(out)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
    }

    companion object {
        /** Keeps a file name from terminating the header early or injecting new lines. */
        fun escape(fileName: String): String =
            fileName.replace("\r", "").replace("\n", "").replace("\"", "%22")

        private const val TOKEN = "[!#\$%&'*+.^_`|~0-9A-Za-z-]+"
        private val MIME_TYPE = Regex("$TOKEN/$TOKEN([ \\t]*;[ \\t]*$TOKEN=$TOKEN)*")

        /**
         * [mimeType] if it is a plain `type/subtype` with token parameters, else
         * `application/octet-stream`: a MIME type from a gallery provider must never end the header
         * early or add one. A line break anywhere refuses it outright (cutting it out would stitch
         * the parts around it into a type nobody sent), and only spaces and tabs may pad it.
         */
        fun safeMimeType(mimeType: String): String {
            val cleaned = mimeType.trim { it == ' ' || it == '\t' }
            return if (MIME_TYPE.matches(cleaned)) cleaned else "application/octet-stream"
        }
    }
}
