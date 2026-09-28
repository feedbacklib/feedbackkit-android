package io.github.feedbacklib.android.internal.transport

import io.github.feedbacklib.android.SendResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HttpStatusMappingTest {

    @Test
    fun `2xx is success`() {
        listOf(200, 201, 202, 204).forEach { assertEquals(SendResult.Success, HttpStatusMapping.toResult(it), "HTTP $it") }
    }

    @Test
    fun `timeouts, throttling, server errors and no status are retryable`() {
        listOf(408, 429, 500, 502, 503, 599, -1).forEach {
            assertTrue(HttpStatusMapping.toResult(it) is SendResult.RetryableFailure, "HTTP $it")
        }
    }

    @Test
    fun `other client errors are permanent`() {
        listOf(400, 401, 403, 404, 413, 422).forEach {
            assertTrue(HttpStatusMapping.toResult(it) is SendResult.PermanentFailure, "HTTP $it")
        }
    }

    @Test
    fun `file names cannot break the multipart header`() {
        assertEquals("a%22b.txt", MultipartWriter.escape("a\"b\r\n.txt"))
    }
}
