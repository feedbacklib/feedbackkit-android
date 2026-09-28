package io.github.feedbacklib.android

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.feedbacklib.android.internal.core.AttachmentTypes
import io.github.feedbacklib.android.internal.core.ExtendedHints
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.ReportUiConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ReportUiSettingsTest {

    private val app: Application = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        FeedbackKit.resetForTests()
        WorkManagerTestInitHelper.initializeTestWorkManager(app, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
    }

    @After
    fun tearDown() {
        FeedbackKit.resetForTests()
        // Leaves no open WorkManager database behind for CloseGuard to report in a later test.
        WorkManagerTestInitHelper.closeWorkDatabase()
    }

    private fun ui(): ReportUiConfig = FeedbackKitRuntime.current!!.config.value.ui

    @Test
    fun `defaults offer three report types, a required email and no extended step`() {
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(setOf(ReportType.BUG, ReportType.FEEDBACK, ReportType.QUESTION), ui().reportTypes)
        assertTrue(ui().options.isEmpty())
        assertTrue(ui().commentMinimums.isEmpty())
        assertEquals(ExtendedBugReport.State.DISABLED, ui().extendedState)
        assertEquals(ExtendedHints(), ui().extendedHints)
        assertEquals(ColorTheme.SYSTEM, ui().colorTheme)
        assertNull(ui().primaryColor)
        assertTrue(ui().customTexts.isEmpty())
        assertEquals(AttachmentTypes(initialScreenshot = true, extraScreenshot = true, gallery = true, screenRecording = true), ui().attachmentTypes)
    }

    @Test
    fun `attachment types set before build apply at build and later calls replace them`() {
        BugReporting.setAttachmentTypesEnabled(initialScreenshot = false, extraScreenshot = true, gallery = false, screenRecording = true)
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(AttachmentTypes(initialScreenshot = false, extraScreenshot = true, gallery = false, screenRecording = true), ui().attachmentTypes)

        BugReporting.setAttachmentTypesEnabled(initialScreenshot = true, extraScreenshot = false, gallery = true, screenRecording = false)
        assertEquals(AttachmentTypes(initialScreenshot = true, extraScreenshot = false, gallery = true, screenRecording = false), ui().attachmentTypes)
    }

    @Test
    fun `settings made before build are applied at build`() {
        val dismiss = OnDismissCallback { _, _ -> }
        BugReporting.setReportTypes(ReportType.BUG, ReportType.QUESTION)
        BugReporting.setOptions(Option.EMAIL_FIELD_OPTIONAL, Option.COMMENT_FIELD_REQUIRED)
        BugReporting.setCommentMinimumCharacterCount(20, ReportType.BUG)
        BugReporting.setExtendedBugReportState(ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS)
        BugReporting.setExtendedBugReportHints("Open settings", null, "  ")
        BugReporting.setOnDismissCallback(dismiss)
        FeedbackKit.setColorTheme(ColorTheme.DARK)
        FeedbackKit.setPrimaryColor(0x3366CC)
        FeedbackKit.setCustomTexts(mapOf(TextKey.PROMPT_TITLE to "Hi"))
        FeedbackKit.Builder(app, "cid").build()

        assertEquals(setOf(ReportType.BUG, ReportType.QUESTION), ui().reportTypes)
        assertEquals(setOf(Option.EMAIL_FIELD_OPTIONAL, Option.COMMENT_FIELD_REQUIRED), ui().options)
        assertEquals(mapOf(ReportType.BUG to 20), ui().commentMinimums)
        assertEquals(ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS, ui().extendedState)
        assertEquals(ExtendedHints(steps = "Open settings", actual = null, expected = null), ui().extendedHints)
        assertEquals(ColorTheme.DARK, ui().colorTheme)
        assertEquals(0xFF3366CC.toInt(), ui().primaryColor)
        assertEquals(mapOf(TextKey.PROMPT_TITLE to "Hi"), ui().customTexts)
        assertSame(dismiss, FeedbackKitRuntime.current!!.onDismissCallback)
    }

    @Test
    fun `builder colour theme wins over an earlier setColorTheme, which survives a silent builder`() {
        FeedbackKit.setColorTheme(ColorTheme.DARK)
        FeedbackKit.Builder(app, "cid").setColorTheme(ColorTheme.LIGHT).build()
        assertEquals(ColorTheme.LIGHT, ui().colorTheme)

        FeedbackKit.resetForTests()
        FeedbackKit.setColorTheme(ColorTheme.DARK)
        FeedbackKit.Builder(app, "cid").build()
        assertEquals(ColorTheme.DARK, ui().colorTheme)
    }

    @Test
    fun `the proactive-only type is dropped and an empty type list is ignored`() {
        FeedbackKit.Builder(app, "cid").build()
        BugReporting.setReportTypes(ReportType.FEEDBACK, ReportType.FRUSTRATING_EXPERIENCE)
        assertEquals(setOf(ReportType.FEEDBACK), ui().reportTypes)

        BugReporting.setReportTypes(ReportType.FRUSTRATING_EXPERIENCE)
        BugReporting.setReportTypes()
        assertEquals(setOf(ReportType.FEEDBACK), ui().reportTypes)
    }

    @Test
    fun `comment minimum applies per type, to every type when none is given, and zero removes it`() {
        FeedbackKit.Builder(app, "cid").build()
        BugReporting.setCommentMinimumCharacterCount(10)
        assertEquals(ReportType.entries.associateWith { 10 }, ui().commentMinimums)

        BugReporting.setCommentMinimumCharacterCount(0, ReportType.QUESTION)
        BugReporting.setCommentMinimumCharacterCount(-3, ReportType.FEEDBACK)
        assertEquals(mapOf(ReportType.BUG to 10, ReportType.FRUSTRATING_EXPERIENCE to 10), ui().commentMinimums)
    }

    @Test
    fun `blank custom texts are dropped and each call replaces the previous map`() {
        FeedbackKit.Builder(app, "cid").build()
        FeedbackKit.setCustomTexts(mapOf(TextKey.PROMPT_TITLE to "Hi", TextKey.BUTTON_SEND to " "))
        assertEquals(mapOf(TextKey.PROMPT_TITLE to "Hi"), ui().customTexts)

        FeedbackKit.setCustomTexts(mapOf(TextKey.SUCCESS_TITLE to "Merci"))
        assertEquals(mapOf(TextKey.SUCCESS_TITLE to "Merci"), ui().customTexts)
    }

    @Test
    fun `a host map that throws while read is logged, not thrown, before and after build`() {
        val broken = object : AbstractMap<TextKey, String>() {
            override val entries: Set<Map.Entry<TextKey, String>>
                get() = error("host bug")
        }
        FeedbackKit.setCustomTexts(broken)
        FeedbackKit.Builder(app, "cid").build()
        FeedbackKit.setCustomTexts(broken)
        assertTrue(ui().customTexts.isEmpty())
    }

    @Test
    fun `settings change a running sdk, alpha is ignored and null removes the dismiss callback`() {
        FeedbackKit.Builder(app, "cid").build()
        BugReporting.setOnDismissCallback { _, _ -> }
        BugReporting.setOnDismissCallback(null)
        FeedbackKit.setPrimaryColor(0x80FF0000.toInt())

        assertNull(FeedbackKitRuntime.current!!.onDismissCallback)
        assertEquals(0xFFFF0000.toInt(), ui().primaryColor)
    }
}
