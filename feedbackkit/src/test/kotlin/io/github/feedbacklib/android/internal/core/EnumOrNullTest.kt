package io.github.feedbacklib.android.internal.core

import io.github.feedbacklib.android.ReportType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class EnumOrNullTest {

    @Test
    fun `a known name parses`() {
        assertEquals(ReportType.QUESTION, enumOrNull<ReportType>("QUESTION"))
    }

    @Test
    fun `a missing, unknown or differently cased name is null`() {
        assertNull(enumOrNull<ReportType>(null))
        assertNull(enumOrNull<ReportType>("RENAMED"))
        assertNull(enumOrNull<ReportType>("question"))
        assertNull(enumOrNull<ReportType>(""))
    }
}
