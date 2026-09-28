package io.github.feedbacklib.android

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class HttpReportSenderTest {

    @TempDir
    lateinit var dir: File

    private val server = MockWebServer()

    @BeforeEach
    fun start() = server.start()

    @AfterEach
    fun stop() = server.shutdown()

    private fun sender() = HttpReportSender(server.url("/api/").toString())

    @Test
    fun `posts a multipart report with headers and returns success on 201`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(201))

        val result = sender().send(preparedReport(dir, "log.txt" to "hello".toByteArray()))

        assertEquals(SendResult.Success, result)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/reports", request.path)
        assertEquals("cid-1", request.getHeader("X-FeedbackKit-CID"))
        assertEquals("report-1", request.getHeader("X-FeedbackKit-Report-Id"))
        assertEquals("FeedbackKit/${FeedbackKitInfo.VERSION}", request.getHeader("User-Agent"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("multipart/form-data; boundary="))
        val body = request.body.readUtf8()
        assertTrue(body.contains("Content-Disposition: form-data; name=\"report\""), body)
        assertTrue(body.contains("{\"id\":\"report-1\"}"), body)
        assertTrue(body.contains("name=\"att-0\"; filename=\"log.txt\""), body)
        assertTrue(body.contains("hello"), body)
    }

    @Test
    fun `503 and 429 are retryable`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setResponseCode(429))
        assertTrue(sender().send(preparedReport(dir)) is SendResult.RetryableFailure)
        assertTrue(sender().send(preparedReport(dir)) is SendResult.RetryableFailure)
    }

    @Test
    fun `400 is permanent`() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(400))
        assertTrue(sender().send(preparedReport(dir)) is SendResult.PermanentFailure)
    }

    @Test
    fun `unreachable server is retryable, not an exception`() = runBlocking {
        val endpoint = server.url("/").toString()
        server.shutdown()
        assertTrue(HttpReportSender(endpoint).send(preparedReport(dir)) is SendResult.RetryableFailure)
    }

    @Test
    fun `non-http endpoint is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { HttpReportSender("ftp://example.com") }
    }

    @Test
    fun `malformed endpoint is rejected at construction`() {
        assertThrows(IllegalArgumentException::class.java) { HttpReportSender("http://host:abc") }
    }
}
