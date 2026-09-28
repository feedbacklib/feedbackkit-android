package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.ReportType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

class ReportJsonTest {

    private val full = samplePayload(
        type = ReportType.FEEDBACK,
        attachments = listOf(AttachmentMeta("a-1", AttachmentKind.APP_FILE, "log.txt", "text/plain", 42)),
    ).copy(
        extended = ExtendedFields("1. open", "crash", "no crash"),
        proactive = ProactiveInfo(ProactiveTrigger.CRASH, "2026-09-26T10:00:00Z", "IllegalStateException", "at Foo"),
        tags = listOf("beta"),
        userAttributes = mapOf("plan" to "pro"),
        userData = "data",
        consoleLog = listOf("line 1"),
    )

    @Test
    fun `encode then decode returns the same payload`() {
        assertEquals(full, ReportJson.decode(ReportJson.encode(full)))
    }

    @Test
    fun `top-level keys are exactly the spec's report json fields`() {
        val keys = Json.parseToJsonElement(ReportJson.encode(full)).jsonObject.keys
        assertEquals(
            setOf(
                "schemaVersion", "id", "cid", "type", "createdAt", "email", "comment", "extended",
                "proactive", "tags", "userAttributes", "userData", "consoleLog", "attachments",
                "device", "app", "currentScreen",
            ),
            keys,
        )
    }

    @Test
    fun `enums and schema version are written as the spec shows them`() {
        val root = Json.parseToJsonElement(ReportJson.encode(full)).jsonObject
        assertEquals("1", root.getValue("schemaVersion").jsonPrimitive.content)
        assertEquals("FEEDBACK", root.getValue("type").jsonPrimitive.content)
        assertEquals("CRASH", root.getValue("proactive").jsonObject.getValue("trigger").jsonPrimitive.content)
    }

    @Test
    fun `absent optional blocks are written as explicit nulls`() {
        val root = Json.parseToJsonElement(ReportJson.encode(samplePayload())).jsonObject
        assertSame(JsonNull, root.getValue("extended"))
        assertSame(JsonNull, root.getValue("proactive"))
        assertSame(JsonNull, root.getValue("userData"))
    }

    @Test
    fun `decode ignores unknown keys and defaults a missing schema version`() {
        val json = ReportJson.encode(samplePayload())
            .replaceFirst("\"schemaVersion\":1,", "")
            .replaceFirst("{", "{\"futureField\":true,")
        val decoded = ReportJson.decode(json)
        assertEquals(1, decoded.schemaVersion)
        assertEquals(samplePayload(), decoded)
    }
}
