package io.github.feedbacklib.android.internal.ui

import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.OnDismissCallback
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CloseReporterTest {

    private val logger = SdkLogger(LogLevel.NONE)

    @Test
    fun `reports the close with its type and the dismissal exactly once, coordinator first`() {
        val events = mutableListOf<String>()
        val reporter = CloseReporter(
            onUiClosed = { type -> events += "closed/$type" },
            dismissCallback = { OnDismissCallback { type, reportType -> events += "$type/$reportType" } },
            logger = logger,
        )
        reporter.report(DismissType.SUBMIT, ReportType.BUG)
        reporter.report(DismissType.CANCEL, ReportType.BUG)
        assertEquals(listOf("closed/SUBMIT", "SUBMIT/BUG"), events)
        assertTrue(reporter.reported)
    }

    @Test
    fun `a throwing host callback never escapes`() {
        var closes = 0
        CloseReporter({ closes++ }, { OnDismissCallback { _, _ -> error("host bug") } }, logger).report(DismissType.CANCEL, ReportType.FEEDBACK)
        assertEquals(1, closes)
    }

    @Test
    fun `a failing close hook still lets the host hear about the dismissal`() {
        var dismissed = false
        CloseReporter({ error("hook bug") }, { OnDismissCallback { _, _ -> dismissed = true } }, logger).report(DismissType.CANCEL, ReportType.QUESTION)
        assertTrue(dismissed)
    }

    @Test
    fun `no dismiss callback is fine`() {
        var closes = 0
        CloseReporter({ closes++ }, { null }, logger).report(DismissType.CANCEL, ReportType.BUG)
        assertEquals(1, closes)
    }
}
