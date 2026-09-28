package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.ReportType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.File

class MutableReportImplTest {

    private val report = MutableReportImpl(ReportType.BUG, "user@example.com", "Broken")

    @Test
    fun `exposes the draft fields it was created with`() {
        assertEquals(ReportType.BUG, report.type)
        assertEquals("user@example.com", report.email)
        assertEquals("Broken", report.comment)
    }

    @Test
    fun `tags are de-duplicated and blank tags are ignored`() {
        report.addTag("beta")
        report.addTag("beta")
        report.addTag("  ")
        assertEquals(listOf("beta"), report.tags)
    }

    @Test
    fun `user attribute set twice keeps the last value`() {
        report.setUserAttribute("plan", "free")
        report.setUserAttribute("plan", "pro")
        assertEquals(mapOf("plan" to "pro"), report.userAttributes)
    }

    @Test
    fun `user data can be set and cleared`() {
        report.setUserData("payload")
        assertEquals("payload", report.userData)
        report.setUserData(null)
        assertNull(report.userData)
    }

    @Test
    fun `addFile without a name uses the file's own name`() {
        report.addFile(File("/tmp/app.log"))
        report.addFile(File("/tmp/x.bin"), "dump.bin")
        assertEquals(listOf("app.log", "dump.bin"), report.additions().files.map { it.fileName })
    }

    @Test
    fun `additions is a snapshot unaffected by later changes`() {
        report.addTag("a")
        report.appendConsoleLog("line 1")
        val snapshot = report.additions()
        report.addTag("b")
        report.appendConsoleLog("line 2")
        assertEquals(listOf("a"), snapshot.tags)
        assertEquals(listOf("line 1"), snapshot.consoleLog)
    }

    @Test
    fun `empty additions carry nothing`() {
        assertEquals(ReportAdditions(emptyList(), emptyList(), emptyList(), emptyMap(), null), ReportAdditions.EMPTY)
    }
}
