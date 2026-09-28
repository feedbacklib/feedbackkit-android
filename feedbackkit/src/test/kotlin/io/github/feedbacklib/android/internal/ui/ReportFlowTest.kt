package io.github.feedbacklib.android.internal.ui

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.ExtendedBugReport
import io.github.feedbacklib.android.Option
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.AttachmentTypes
import io.github.feedbacklib.android.internal.core.ReportUiConfig
import io.github.feedbacklib.android.internal.report.AddImageResult
import io.github.feedbacklib.android.internal.report.DraftAttachment
import io.github.feedbacklib.android.internal.report.DraftFile
import io.github.feedbacklib.android.internal.report.ExtendedFields
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class ReportFlowTest {

    private val emails = EmailValidator { it.contains('@') && !it.endsWith('@') }
    private val plain = FormRules(EmailMode.REQUIRED, commentRequired = false, commentMinLength = 0, extendedState = ExtendedBugReport.State.DISABLED)
    private val grin = "😀"

    private fun galleryFiles(n: Int) = (1..n).map { DraftFile(AttachmentKind.GALLERY_IMAGE, File("gallery-$it.png"), "image/png") }

    private val form = DraftState(
        step = ReportStep.FORM,
        type = ReportType.BUG,
        menuShown = false,
        fields = DraftFields(email = "me@example.com"),
        emailPrefill = "",
        screenshotUnavailable = false,
    )

    @Test
    fun `the gallery tile shows while there is room for a fourth attachment and the type is on`() {
        val ui = ReportUiConfig()
        assertTrue(form.copy(attachments = galleryFiles(3)).toUiState(ui, emails).canAddGalleryImage)

        val full = form.copy(attachments = galleryFiles(4)).toUiState(ui, emails)
        assertFalse(full.canAddGalleryImage)
        assertTrue(full.attachmentLimitReached)

        val off = ui.copy(attachmentTypes = AttachmentTypes(extraScreenshot = false, gallery = false))
        assertFalse(form.toUiState(off, emails).canAddGalleryImage)
        assertFalse(form.copy(attachments = galleryFiles(4)).toUiState(off, emails).attachmentLimitReached, "no caption about a limit nothing can reach")
    }

    @Test
    fun `the screenshot tile follows its own switch and the same limit`() {
        val ui = ReportUiConfig()
        assertTrue(form.copy(attachments = galleryFiles(3)).toUiState(ui, emails).canAddExtraScreenshot)
        assertFalse(form.copy(attachments = galleryFiles(4)).toUiState(ui, emails).canAddExtraScreenshot)
        val noScreenshots = ui.copy(attachmentTypes = AttachmentTypes(extraScreenshot = false))
        assertFalse(form.toUiState(noScreenshots, emails).canAddExtraScreenshot)
        assertTrue(form.toUiState(noScreenshots, emails).canAddGalleryImage)
    }

    @Test
    fun `an automatic recording in preparation takes one of the four places`() {
        val ui = ReportUiConfig()
        val pending = form.copy(attachments = galleryFiles(3), autoClipPending = true).toUiState(ui, emails, canRecord = true)
        assertTrue(pending.autoClipPending)
        assertFalse(pending.canAddGalleryImage)
        assertFalse(pending.canAddExtraScreenshot)
        assertFalse(pending.canRecordScreen)
        assertTrue(pending.attachmentLimitReached)
        assertTrue(form.copy(attachments = galleryFiles(2), autoClipPending = true).toUiState(ui, emails).canAddGalleryImage)
    }

    @Test
    fun `the record tile needs a recorder, the type switched on and room for another attachment`() {
        val ui = ReportUiConfig()
        assertFalse(form.toUiState(ui, emails).canRecordScreen, "no recorder")
        assertTrue(form.toUiState(ui, emails, canRecord = true).canRecordScreen)
        assertFalse(form.copy(attachments = galleryFiles(4)).toUiState(ui, emails, canRecord = true).canRecordScreen)
        val off = ui.copy(attachmentTypes = AttachmentTypes(screenRecording = false))
        assertFalse(form.toUiState(off, emails, canRecord = true).canRecordScreen)
    }

    @Test
    fun `the limit caption counts the record tile only once a recorder exists`() {
        val onlyRecording = ReportUiConfig(attachmentTypes = AttachmentTypes(extraScreenshot = false, gallery = false, screenRecording = true))
        val full = form.copy(attachments = galleryFiles(4))
        assertFalse(full.toUiState(onlyRecording, emails).attachmentLimitReached)
        assertTrue(full.toUiState(onlyRecording, emails, canRecord = true).attachmentLimitReached)
    }

    @Test
    fun `a consent in progress reaches the ui state`() {
        assertTrue(form.copy(recordingStarting = true).toUiState(ReportUiConfig(), emails, canRecord = true).recordingStarting)
        assertFalse(form.toUiState(ReportUiConfig(), emails, canRecord = true).recordingStarting)
    }

    @Test
    fun `every refused image has its own notice and an added one none`() {
        val added = DraftFile(AttachmentKind.GALLERY_IMAGE, File("gallery-1.png"), "image/png")
        assertNull(AddImageResult.Added(added).toNotice())
        assertEquals(AttachNotice.LIMIT_REACHED, AddImageResult.LimitReached.toNotice())
        assertEquals(AttachNotice.IMAGE_TOO_LARGE, AddImageResult.TooLarge.toNotice())
        assertEquals(AttachNotice.IMAGE_UNSUPPORTED, AddImageResult.Unsupported.toNotice())
        assertEquals(AttachNotice.IMAGE_FAILED, AddImageResult.Failed.toNotice())
    }

    @Test
    fun `hidden email wins over optional and a comment minimum makes the comment required`() {
        val ui = ReportUiConfig(options = setOf(Option.EMAIL_FIELD_OPTIONAL, Option.EMAIL_FIELD_HIDDEN), commentMinimums = mapOf(ReportType.FEEDBACK to 15))
        val rules = FormRules.from(ui, ReportType.FEEDBACK)
        assertEquals(EmailMode.HIDDEN, rules.emailMode)
        assertTrue(rules.commentRequired)
        assertEquals(15, rules.commentMinLength)
        assertFalse(FormRules.from(ui, ReportType.QUESTION).commentRequired)
        assertEquals(EmailMode.OPTIONAL, FormRules.from(ReportUiConfig(options = setOf(Option.EMAIL_FIELD_OPTIONAL)), ReportType.BUG).emailMode)
        assertEquals(EmailMode.REQUIRED, FormRules.from(ReportUiConfig(), ReportType.BUG).emailMode)
        assertTrue(FormRules.from(ReportUiConfig(options = setOf(Option.COMMENT_FIELD_REQUIRED)), ReportType.BUG).commentRequired)
    }

    @Test
    fun `only a bug gets the extended step`() {
        val ui = ReportUiConfig(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS)
        assertTrue(FormRules.from(ui, ReportType.BUG).hasExtendedStep)
        assertFalse(FormRules.from(ui, ReportType.FEEDBACK).hasExtendedStep)
        assertFalse(FormRules.from(ReportUiConfig(), ReportType.BUG).hasExtendedStep)
    }

    @Test
    fun `a required email must be well formed, an optional one only when filled in, a hidden one never`() {
        fun check(mode: EmailMode, email: String) = FormValidation.check(DraftFields(email = email), plain.copy(emailMode = mode), emails)
        assertFalse(check(EmailMode.REQUIRED, "").emailValid)
        assertTrue(check(EmailMode.REQUIRED, "").emailMissing)
        assertFalse(check(EmailMode.REQUIRED, "").showEmailError)
        assertFalse(check(EmailMode.REQUIRED, "me@").emailValid)
        assertTrue(check(EmailMode.REQUIRED, "me@").showEmailError)
        assertTrue(check(EmailMode.REQUIRED, "  me@example.com ").emailValid)
        assertTrue(check(EmailMode.OPTIONAL, "").emailValid)
        assertFalse(check(EmailMode.OPTIONAL, "").emailMissing)
        assertFalse(check(EmailMode.OPTIONAL, "me@").emailValid)
        assertTrue(check(EmailMode.HIDDEN, "me@").emailValid)
        assertFalse(check(EmailMode.HIDDEN, "me@").showEmailError)
    }

    @Test
    fun `whitespace does not count towards the comment minimum and an emoji counts once`() {
        val rules = plain.copy(commentRequired = true, commentMinLength = 3)
        fun check(comment: String) = FormValidation.check(DraftFields(email = "me@example.com", comment = comment), rules, emails)
        assertFalse(check("      ").commentValid)
        assertTrue(check("      ").commentMissing)
        assertEquals(2, check("  ab  ").commentLength)
        assertFalse(check("  ab  ").commentValid)
        assertEquals(3, check(grin + grin + grin).commentLength)
        assertTrue(check(grin + grin + grin).commentValid)
    }

    @Test
    fun `a required comment without a minimum needs one character and an optional one needs none`() {
        assertFalse(FormValidation.check(DraftFields(email = "me@example.com"), plain.copy(commentRequired = true), emails).canContinue)
        assertTrue(FormValidation.check(DraftFields(email = "me@example.com", comment = "x"), plain.copy(commentRequired = true), emails).canContinue)
        assertTrue(FormValidation.check(DraftFields(email = "me@example.com"), plain, emails).canContinue)
    }

    @Test
    fun `required extended fields all need text and optional ones never block`() {
        val required = plain.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS)
        assertFalse(FormValidation.extendedValid(DraftFields(steps = "a", actual = "b", expected = " "), required))
        assertTrue(FormValidation.extendedValid(DraftFields(steps = "a", actual = "b", expected = "c"), required))
        assertTrue(FormValidation.extendedValid(DraftFields(), required.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS)))
    }

    @Test
    fun `the menu shows offered types in a fixed order and never the proactive one`() {
        val ui = ReportUiConfig(reportTypes = linkedSetOf(ReportType.QUESTION, ReportType.FRUSTRATING_EXPERIENCE, ReportType.BUG))
        assertEquals(listOf(ReportType.BUG, ReportType.QUESTION), ReportNavigation.menuTypes(ui))
    }

    @Test
    fun `start picks the menu only for several types without a preset`() {
        val all = listOf(ReportType.BUG, ReportType.FEEDBACK, ReportType.QUESTION)
        assertEquals(StartPoint(ReportStep.MENU, null, true), ReportNavigation.start(null, all))
        assertEquals(StartPoint(ReportStep.FORM, ReportType.QUESTION, false), ReportNavigation.start(ReportType.QUESTION, all))
        assertEquals(StartPoint(ReportStep.FORM, ReportType.FEEDBACK, false), ReportNavigation.start(null, listOf(ReportType.FEEDBACK)))
    }

    @Test
    fun `back walks one step at a time and asks to cancel from the first screen`() {
        assertEquals(BackAction.RequestCancel, ReportNavigation.back(ReportStep.MENU, menuShown = true))
        assertEquals(BackAction.GoTo(ReportStep.MENU), ReportNavigation.back(ReportStep.FORM, menuShown = true))
        assertEquals(BackAction.RequestCancel, ReportNavigation.back(ReportStep.FORM, menuShown = false))
        assertEquals(BackAction.GoTo(ReportStep.FORM), ReportNavigation.back(ReportStep.EXTENDED, menuShown = false))
        assertEquals(BackAction.Ignore, ReportNavigation.back(ReportStep.SENDING, menuShown = true))
        assertEquals(BackAction.Ignore, ReportNavigation.back(ReportStep.SUCCESS, menuShown = true))
    }

    @Test
    fun `a failed or interrupted send returns to the step it was sent from`() {
        assertEquals(ReportStep.FORM, ReportNavigation.stepBeforeSend(plain))
        assertEquals(ReportStep.EXTENDED, ReportNavigation.stepBeforeSend(plain.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS)))
    }

    @Test
    fun `only text the user typed asks for confirmation`() {
        assertFalse(ReportNavigation.needsCancelConfirmation(DraftFields(email = "me@example.com", comment = "   "), "me@example.com"))
        assertTrue(ReportNavigation.needsCancelConfirmation(DraftFields(email = "me@example.com", expected = "x"), "me@example.com"))
        assertTrue(ReportNavigation.needsCancelConfirmation(DraftFields(email = "you@example.com"), "me@example.com"))
    }

    @Test
    fun `closing before choosing a type reports the first offered one`() {
        assertEquals(ReportType.FEEDBACK, ReportNavigation.dismissReportType(null, listOf(ReportType.FEEDBACK, ReportType.QUESTION)))
        assertEquals(ReportType.QUESTION, ReportNavigation.dismissReportType(ReportType.QUESTION, listOf(ReportType.FEEDBACK)))
        assertEquals(ReportType.BUG, ReportNavigation.dismissReportType(null, emptyList()))
    }

    @Test
    fun `the primary action is Next only on a bug form with an extended step`() {
        val ui = ReportUiConfig(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS)
        fun draft(step: ReportStep, type: ReportType?) = DraftState(step, type, menuShown = true, fields = DraftFields(), emailPrefill = "", screenshotUnavailable = false)
        assertEquals(PrimaryAction.NEXT, draft(ReportStep.FORM, ReportType.BUG).toUiState(ui, emails).primaryAction)
        assertEquals(PrimaryAction.SEND, draft(ReportStep.EXTENDED, ReportType.BUG).toUiState(ui, emails).primaryAction)
        assertEquals(PrimaryAction.SEND, draft(ReportStep.FORM, ReportType.FEEDBACK).toUiState(ui, emails).primaryAction)

        val menu = draft(ReportStep.MENU, null).toUiState(ui, emails)
        assertNull(menu.rules)
        assertTrue(menu.check.canContinue)
        assertFalse(menu.canGoBack)
        assertTrue(draft(ReportStep.FORM, ReportType.BUG).toUiState(ui, emails).canGoBack)
    }

    @Test
    fun `the queued draft trims text, sends the identified email for a hidden field and extended fields only with the step`() {
        val shot = DraftFile(AttachmentKind.SCREENSHOT, File("drafts/d/screenshot.png"), "image/png")
        val state = DraftState(
            step = ReportStep.EXTENDED,
            type = ReportType.BUG,
            menuShown = false,
            fields = DraftFields(email = " typed@example.com ", comment = "  Crash  ", steps = " a ", actual = "b", expected = "c"),
            emailPrefill = "",
            screenshotUnavailable = false,
        )
        val withStep = FormRules(EmailMode.REQUIRED, false, 0, ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS)

        val draft = state.toReportDraft(ReportType.BUG, withStep, identifiedEmail = "id@example.com", files = listOf(shot), currentScreen = "Host")
        assertEquals("typed@example.com", draft.email)
        assertEquals("Crash", draft.comment)
        assertEquals(ExtendedFields("a", "b", "c"), draft.extended)
        assertEquals(listOf(DraftAttachment(AttachmentKind.SCREENSHOT, shot.file, "screenshot.png", "image/png")), draft.attachments)
        assertEquals("Host", draft.currentScreen)

        val hidden = state.toReportDraft(ReportType.BUG, withStep.copy(emailMode = EmailMode.HIDDEN, extendedState = ExtendedBugReport.State.DISABLED), "id@example.com", emptyList(), null)
        assertEquals("id@example.com", hidden.email)
        assertNull(hidden.extended)

        val blank = state.copy(fields = state.fields.copy(email = "  ")).toReportDraft(ReportType.BUG, withStep.copy(emailMode = EmailMode.OPTIONAL), null, emptyList(), null)
        assertNull(blank.email)
    }

    @Test
    fun `a proactive start is its prompt with its own type, and back from the prompt asks to cancel`() {
        assertEquals(
            StartPoint(ReportStep.PROACTIVE_PROMPT, ReportType.FRUSTRATING_EXPERIENCE, menuShown = false),
            ReportNavigation.start(null, listOf(ReportType.BUG, ReportType.QUESTION), proactive = true),
        )
        assertEquals(BackAction.RequestCancel, ReportNavigation.back(ReportStep.PROACTIVE_PROMPT, menuShown = false))
        assertFalse(form.copy(step = ReportStep.PROACTIVE_PROMPT, type = ReportType.FRUSTRATING_EXPERIENCE).toUiState(ReportUiConfig(), emails).primaryEnabled)
    }

    @Test
    fun `the proactive form offers gallery images, never a screenshot or a recording`() {
        val frustrating = form.copy(type = ReportType.FRUSTRATING_EXPERIENCE)
        val ui = frustrating.toUiState(ReportUiConfig(), emails, canRecord = true)
        assertTrue(ui.canAddGalleryImage)
        assertFalse(ui.canAddExtraScreenshot)
        assertFalse(ui.canRecordScreen)
        assertTrue(frustrating.copy(attachments = galleryFiles(4)).toUiState(ReportUiConfig(), emails, canRecord = true).attachmentLimitReached)
        val noGallery = ReportUiConfig(attachmentTypes = AttachmentTypes(gallery = false))
        assertFalse(frustrating.copy(attachments = galleryFiles(4)).toUiState(noGallery, emails, canRecord = true).attachmentLimitReached, "no tile the limit could hide")
        assertTrue(form.toUiState(ReportUiConfig(), emails, canRecord = true).canAddExtraScreenshot, "a bug keeps them")
    }

    @Test
    fun `the queued draft carries the proactive block it is given`() {
        val info = ProactiveInfo(ProactiveTrigger.FORCE_RESTART, "2026-09-28T10:00:00Z", null, null)
        val frustrating = form.copy(type = ReportType.FRUSTRATING_EXPERIENCE, fields = DraftFields(email = "me@example.com", comment = "It froze"))
        assertEquals(info, frustrating.toReportDraft(ReportType.FRUSTRATING_EXPERIENCE, plain, null, emptyList(), "com.example.Main", proactive = info).proactive)
        assertNull(form.toReportDraft(ReportType.BUG, plain, null, emptyList(), null).proactive)
    }
}
