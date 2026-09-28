package io.github.feedbacklib.android.internal.invoke

import io.github.feedbacklib.android.ReportType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CaptureRequestJsonTest {

    private val request = CaptureRequest(
        draftId = "draft-1",
        reportType = ReportType.QUESTION,
        state = mapOf("feedbackkit.comment" to "Two screens", "feedbackkit.menuShown" to true, "feedbackkit.type" to null),
        currentScreen = "com.example.Main",
    )

    @Test
    fun `a request survives the file`() {
        assertEquals(request, CaptureRequestJson.decode(CaptureRequestJson.encode(request)))
        assertEquals(request.copy(currentScreen = null), CaptureRequestJson.decode(CaptureRequestJson.encode(request.copy(currentScreen = null))))
    }

    @Test
    fun `a damaged, foreign or newer file is no request`() {
        assertNull(CaptureRequestJson.decode(""))
        assertNull(CaptureRequestJson.decode("{"))
        assertNull(CaptureRequestJson.decode("[]"))
        assertNull(CaptureRequestJson.decode("""{"version":2,"draftId":"d","reportType":"BUG","state":{}}"""))
        assertNull(CaptureRequestJson.decode("""{"version":1,"reportType":"BUG","state":{}}"""))
        assertNull(CaptureRequestJson.decode("""{"version":1,"draftId":"d","reportType":"TELEPATHY","state":{}}"""))
    }

    @Test
    fun `values other than text, flags and null are not written`() {
        val odd = request.copy(state = mapOf("feedbackkit.count" to 5, "feedbackkit.comment" to "kept"))
        val decoded = CaptureRequestJson.decode(CaptureRequestJson.encode(odd))!!
        assertFalse(decoded.state.containsKey("feedbackkit.count"))
        assertEquals("kept", decoded.state["feedbackkit.comment"])
    }

    @Test
    fun `a state value that is neither text nor a flag is dropped, as encode would have`() {
        val decoded = CaptureRequestJson.decode(
            """{"version":1,"draftId":"d","reportType":"BUG","state":{"feedbackkit.count":5,"feedbackkit.odd":{},"feedbackkit.list":[],"feedbackkit.type":null,"feedbackkit.comment":"kept","feedbackkit.menuShown":false}}""",
        )!!
        assertEquals(mapOf("feedbackkit.type" to null, "feedbackkit.comment" to "kept", "feedbackkit.menuShown" to false), decoded.state)
    }
}
