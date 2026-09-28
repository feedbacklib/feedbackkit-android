package io.github.feedbacklib.android.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.ExtendedBugReport
import io.github.feedbacklib.android.Option
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.annotate.AnnotationTool
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.annotate.PenWidth
import io.github.feedbacklib.android.internal.core.AttachmentTypes
import io.github.feedbacklib.android.internal.core.ExtendedHints
import io.github.feedbacklib.android.internal.core.ReportUiConfig
import io.github.feedbacklib.android.internal.report.DraftFile
import io.github.feedbacklib.android.internal.ui.AnnotationUiState
import io.github.feedbacklib.android.internal.ui.AttachNotice
import io.github.feedbacklib.android.internal.ui.DraftFields
import io.github.feedbacklib.android.internal.ui.DraftState
import io.github.feedbacklib.android.internal.ui.EmailValidator
import io.github.feedbacklib.android.internal.ui.ReportActions
import io.github.feedbacklib.android.internal.ui.ReportStep
import io.github.feedbacklib.android.internal.ui.ReportUiState
import io.github.feedbacklib.android.internal.ui.screens.ReportRoot
import io.github.feedbacklib.android.internal.ui.screens.ReportTestTags
import io.github.feedbacklib.android.internal.ui.toUiState
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The report screens drawn from fixed states (English device locale). */
@RunWith(AndroidJUnit4::class)
class ReportScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val actions = RecordingActions()
    private val emails = EmailValidator { it.contains('@') && !it.endsWith('@') }
    private val app: Application = ApplicationProvider.getApplicationContext()

    private fun state(
        step: ReportStep = ReportStep.FORM,
        type: ReportType? = ReportType.BUG,
        fields: DraftFields = DraftFields(email = "me@example.com"),
        ui: ReportUiConfig = ReportUiConfig(),
        menuShown: Boolean = false,
        screenshotUnavailable: Boolean = false,
        confirmingCancel: Boolean = false,
        submitFailed: Boolean = false,
        attachments: List<DraftFile> = emptyList(),
        notice: AttachNotice? = null,
    ): ReportUiState = DraftState(
        step = step,
        type = type,
        menuShown = menuShown,
        fields = fields,
        emailPrefill = "me@example.com",
        screenshotUnavailable = screenshotUnavailable,
        confirmingCancel = confirmingCancel,
        submitFailed = submitFailed,
        attachments = attachments,
        notice = notice,
    ).toUiState(ui, emails)

    private fun show(state: ReportUiState) = compose.setContent { ReportRoot(state, actions) }

    @Test
    fun menuListsTheOfferedTypesAndReportsTheChoice() {
        show(state(step = ReportStep.MENU, type = null, menuShown = true, ui = ReportUiConfig(reportTypes = setOf(ReportType.BUG, ReportType.QUESTION))))
        compose.onNodeWithText("How can we help?").assertIsDisplayed()
        compose.onNodeWithText("Report a bug").assertIsDisplayed()
        compose.onNodeWithText("Suggest an improvement").assertDoesNotExist()
        compose.onNodeWithText("Ask a question").performClick()
        assertEquals(listOf("type:QUESTION"), actions.calls)
    }

    @Test
    fun sendIsDisabledWhileTheRequiredEmailIsMissing() {
        show(state(fields = DraftFields(email = "")))
        compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).assertIsNotEnabled()
        compose.onNodeWithText("Required").assertIsDisplayed()
    }

    @Test
    fun aValidFormSends() {
        show(state())
        compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).assertIsEnabled().assertTextEquals("Send").performClick()
        assertEquals(listOf("primary"), actions.calls)
    }

    @Test
    fun anInvalidEmailSaysSo() {
        show(state(fields = DraftFields(email = "me@")))
        compose.onNodeWithText("Enter a valid email address").assertIsDisplayed()
    }

    @Test
    fun aHiddenEmailHasNoField() {
        show(state(ui = ReportUiConfig(options = setOf(Option.EMAIL_FIELD_HIDDEN))))
        compose.onNodeWithTag(ReportTestTags.EMAIL_FIELD).assertDoesNotExist()
    }

    @Test
    fun aCommentMinimumShowsTheCounter() {
        show(state(fields = DraftFields(email = "me@example.com", comment = "abc"), ui = ReportUiConfig(commentMinimums = mapOf(ReportType.BUG to 10))))
        // The counter sits in the text field's supporting slot, which the field may merge into itself.
        compose.onNodeWithTag(ReportTestTags.COMMENT_COUNTER, useUnmergedTree = true).assertTextEquals("3/10")
        compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).assertIsNotEnabled()
    }

    @Test
    fun aBugWithAnExtendedStepOffersNext() {
        show(state(ui = ReportUiConfig(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS)))
        compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).assertTextEquals("Next")
    }

    @Test
    fun theExtendedStepUsesHostHintsAndBuiltInDefaults() {
        show(
            state(
                step = ReportStep.EXTENDED,
                ui = ReportUiConfig(
                    extendedState = ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS,
                    extendedHints = ExtendedHints(steps = "Open the router page"),
                ),
            ),
        )
        compose.onNodeWithText("Tell us more").assertIsDisplayed()
        compose.onNodeWithText("Steps to reproduce").assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).assertIsNotEnabled()
        // Material 3 shows a placeholder under a label only while its empty field is focused.
        compose.onNodeWithTag(ReportTestTags.STEPS_FIELD).performClick()
        compose.onNodeWithText("Open the router page").assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.ACTUAL_FIELD).performClick()
        compose.onNodeWithText("What happened?").assertIsDisplayed()
    }

    @Test
    fun aMissingScreenshotShowsThePlaceholder() {
        show(state(screenshotUnavailable = true))
        compose.onNodeWithText("Screenshot unavailable for this screen").assertIsDisplayed()
    }

    @Test
    fun theCancelDialogKeepsOrDiscards() {
        show(state(fields = DraftFields(email = "me@example.com", comment = "draft"), confirmingCancel = true))
        compose.onNodeWithText("Discard the report?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Discard").performClick()
        assertEquals(listOf("cancelDismissed", "cancelConfirmed"), actions.calls)
    }

    @Test
    fun customTextsReplaceBuiltInOnes() {
        show(state(type = ReportType.FEEDBACK, ui = ReportUiConfig(customTexts = mapOf(TextKey.BUTTON_SEND to "Ship it"))))
        compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).assertTextEquals("Ship it")
    }

    @Test
    fun iconButtonsAreLabelledAndLargeEnough() {
        show(state(menuShown = true))
        compose.onNodeWithContentDescription("Back").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithContentDescription("Close").assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("cancelRequested"), actions.calls)
    }

    @Test
    fun theMenuTitleIsASeparateHeadingFromTheScrim() {
        show(state(step = ReportStep.MENU, type = null, menuShown = true))
        // The scrim's `clickable` merges any non-actionable descendant into its own "Close" node; the
        // heading must be its own node, with no click action, to prove it is not that descendant.
        compose.onNode(isHeading()).assertIsDisplayed().assertHasNoClickAction()
    }

    @Test
    fun theLastMenuItemCanBeScrolledIntoViewAtDoubleFontScale() {
        val menuState = state(step = ReportStep.MENU, type = null, menuShown = true)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                // A small "screen" so the menu (title + 3 items, all doubled) overflows it regardless
                // of the test device's real screen size, forcing the sheet to scroll.
                Box(Modifier.size(320.dp, 400.dp)) {
                    ReportRoot(menuState, actions)
                }
            }
        }
        compose.onNodeWithText("Ask a question").performScrollTo().performClick()
        assertEquals(listOf("type:QUESTION"), actions.calls)
    }

    @Test
    fun theTopBarTitleKeepsTheStandardInsetWithoutABackButton() {
        show(state(type = ReportType.QUESTION)) // opened with show(type): nothing to go back to
        val bar = compose.onNodeWithTag(ReportTestTags.TOP_BAR).getBoundsInRoot()
        val title = compose.onNode(hasText("Ask a question") and hasAnyAncestor(hasTestTag(ReportTestTags.TOP_BAR))).getBoundsInRoot()
        val inset = title.left - bar.left
        assertTrue("title inset $inset", inset >= 15.5.dp && inset <= 16.5.dp)
    }

    @Test
    fun theMenuSheetIgnoresATapOnItsEmptySpace() {
        show(state(step = ReportStep.MENU, type = null, menuShown = true))
        // The sheet's top padding, above the title row: inside the sheet, on nothing actionable.
        compose.onNodeWithTag(ReportTestTags.MENU).performTouchInput { click(Offset(centerX, 4.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(emptyList<String>(), actions.calls)
    }

    @Test
    fun theTopBarTitleIsFullyDisplayedAtDoubleFontScale() {
        val longTitle = "Ask a question about something that needs several words to fully describe what happened"
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                ReportRoot(
                    state(
                        type = ReportType.QUESTION,
                        ui = ReportUiConfig(customTexts = mapOf(TextKey.REPORT_QUESTION to longTitle)),
                    ),
                    actions,
                )
            }
        }
        // The old M3 TopAppBar had a fixed height and truncated a wrapped title with an ellipsis; the
        // exact untruncated string only matches if the bar grew to fit it instead of clipping it.
        compose.onNodeWithText(longTitle).assertIsDisplayed()
    }

    @Test
    fun anAttachmentTileShowsAndRemoves() {
        val file = DraftFile(AttachmentKind.SCREENSHOT, File("missing-screenshot.png"), "image/png")
        show(state(attachments = listOf(file)))
        compose.onNodeWithTag(ReportTestTags.attachment(file.fileName)).assertIsDisplayed()
        compose.onNodeWithContentDescription("Remove screenshot")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        assertEquals(listOf("remove:${file.fileName}"), actions.calls)
    }

    @Test
    fun theRemoveBadgeLeavesTheThumbnailMostlyUncovered() {
        val file = DraftFile(AttachmentKind.SCREENSHOT, File("missing-screenshot.png"), "image/png")
        show(state(attachments = listOf(file)))
        val tile = compose.onNodeWithTag(ReportTestTags.attachment(file.fileName)).getBoundsInRoot()
        val badge = compose.onNodeWithTag(ReportTestTags.ATTACHMENT_REMOVE_BADGE, useUnmergedTree = true).getBoundsInRoot()
        val badgeWidth = badge.right - badge.left
        val badgeHeight = badge.bottom - badge.top
        assertTrue("badge $badgeWidth x $badgeHeight", badgeWidth <= 28.dp && badgeHeight <= 28.dp)
        val tileArea = (tile.right - tile.left).value * (tile.bottom - tile.top).value
        val badgeArea = badgeWidth.value * badgeHeight.value
        assertTrue("the badge covers ${badgeArea / tileArea} of the tile", badgeArea / tileArea < 0.1f)
        // In the top-end corner of the tile.
        assertTrue(tile.right - badge.right <= 16.dp && badge.top - tile.top <= 16.dp)
    }

    @Test
    fun theAddImageTileAsksForTheGallery() {
        show(state())
        compose.onNodeWithTag(ReportTestTags.ADD_IMAGE)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("Add image").assertExists()
        assertEquals(listOf("addImage"), actions.calls)
    }

    @Test
    fun theAddScreenshotTileStepsAside() {
        show(state())
        compose.onNodeWithTag(ReportTestTags.ADD_SCREENSHOT)
            .assertIsDisplayed()
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        compose.onNodeWithText("Add screenshot").assertExists()
        assertEquals(listOf("addScreenshot"), actions.calls)
    }

    @Test
    fun aSwitchedOffScreenshotHasNoTile() {
        show(state(ui = ReportUiConfig(attachmentTypes = AttachmentTypes(extraScreenshot = false))))
        compose.onNodeWithTag(ReportTestTags.ADD_SCREENSHOT).assertDoesNotExist()
        compose.onNodeWithTag(ReportTestTags.ADD_IMAGE).assertIsDisplayed()
    }

    @Test
    fun aFailedExtraScreenshotSaysSo() {
        show(state(notice = AttachNotice.SCREENSHOT_UNAVAILABLE))
        compose.onNodeWithTag(ReportTestTags.ATTACH_NOTICE).assertTextEquals("Screenshot unavailable for this screen")
    }

    @Test
    fun atTheLimitTheAddTileGivesWayToACaption() {
        val four = (1..4).map { DraftFile(AttachmentKind.GALLERY_IMAGE, File("gallery-$it.png"), "image/png") }
        show(state(attachments = four))
        compose.onNodeWithTag(ReportTestTags.ADD_IMAGE).assertDoesNotExist()
        compose.onNodeWithTag(ReportTestTags.ADD_SCREENSHOT).assertDoesNotExist()
        compose.onNodeWithTag(ReportTestTags.ATTACHMENT_LIMIT).assertTextEquals("Up to 4 attachments")
    }

    @Test
    fun aSwitchedOffGalleryHasNoTile() {
        show(state(ui = ReportUiConfig(attachmentTypes = AttachmentTypes(gallery = false))))
        compose.onNodeWithTag(ReportTestTags.ADD_IMAGE).assertDoesNotExist()
    }

    @Test
    fun aRefusedImageSaysWhy() {
        show(state(notice = AttachNotice.IMAGE_TOO_LARGE))
        compose.onNodeWithTag(ReportTestTags.ATTACH_NOTICE).assertTextEquals("This image is larger than 10 MB")
    }

    @Test
    fun theLimitCaptionDoesNotDuplicateAJustRefusedLimitNotice() {
        val four = (1..4).map { DraftFile(AttachmentKind.GALLERY_IMAGE, File("gallery-$it.png"), "image/png") }
        show(state(attachments = four, notice = AttachNotice.LIMIT_REACHED))
        compose.onNodeWithTag(ReportTestTags.ATTACHMENT_LIMIT).assertDoesNotExist()
        compose.onNodeWithTag(ReportTestTags.ATTACH_NOTICE).assertTextEquals("Up to 4 attachments")
    }

    @Test
    fun galleryImagesAreDescribedAsImages() {
        val file = DraftFile(AttachmentKind.GALLERY_IMAGE, File("gallery-1.png"), "image/png")
        show(state(attachments = listOf(file)))
        // The file is missing, so no thumbnail decodes: the remove button names the attachment.
        compose.onNodeWithContentDescription("Remove image").assertExists()
    }

    @Test
    fun theAddTileIsReachableBehindThreeThumbnails() {
        val three = (1..3).map { DraftFile(AttachmentKind.GALLERY_IMAGE, File("gallery-$it.png"), "image/png") }
        show(state(attachments = three))
        compose.onNodeWithTag(ReportTestTags.ATTACHMENTS).performScrollToNode(hasTestTag(ReportTestTags.ADD_IMAGE))
        compose.onNodeWithTag(ReportTestTags.ADD_IMAGE).assertIsDisplayed()
    }

    @Test
    fun aSubmitFailureIsShown() {
        show(state(submitFailed = true))
        compose.onNodeWithTag(ReportTestTags.SUBMIT_ERROR).assertTextEquals("Could not save the report. Please try again.")
    }

    @Test
    fun theThankYouScreen() {
        show(state(step = ReportStep.SUCCESS))
        compose.onNodeWithText("Thank you!").assertIsDisplayed()
        compose.onNodeWithText("Your report is on its way.").assertIsDisplayed()
    }

    @Test
    fun doubleFontScaleKeepsEverythingReachable() {
        val big = state(screenshotUnavailable = true, ui = ReportUiConfig(commentMinimums = mapOf(ReportType.BUG to 10)))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                ReportRoot(big, actions)
            }
        }
        compose.onNodeWithTag(ReportTestTags.PRIMARY_BUTTON).assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.SCREENSHOT_UNAVAILABLE).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun tappingAThumbnailOpensTheEditor() {
        val file = DraftFile(AttachmentKind.SCREENSHOT, File("missing-screenshot.png"), "image/png")
        show(state(attachments = listOf(file)))
        compose.onNodeWithTag(ReportTestTags.attachment(file.fileName)).performClick()
        assertEquals(listOf("edit:${file.fileName}"), actions.calls)
    }

    @Test
    fun recordTileShowsWithARecorderAndAsksOnTap() {
        show(state().copy(canRecordScreen = true))
        compose.onNodeWithTag(ReportTestTags.RECORD_SCREEN).performScrollTo().assertIsEnabled()
        compose.onNodeWithText("Record screen").assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.RECORD_SCREEN).performClick()
        assertEquals(listOf("recordScreen"), actions.calls)
    }

    @Test
    fun recordTileIsOffWhileTheConsentIsOpen() {
        show(state().copy(canRecordScreen = true, recordingStarting = true))
        compose.onNodeWithTag(ReportTestTags.RECORD_SCREEN).performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun anAutomaticRecordingInPreparationShowsAPlaceholderAfterTheAttachments() {
        val shot = DraftFile(AttachmentKind.SCREENSHOT, File("/nonexistent/screenshot.png"), "image/png")
        show(state(attachments = listOf(shot)).copy(autoClipPending = true))
        compose.onNodeWithTag(ReportTestTags.AUTO_CLIP_PENDING).performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Preparing the screen recording").assertExists()
        compose.onNodeWithTag(ReportTestTags.AUTO_CLIP_PENDING).assertHasNoClickAction()
        val placeholder = compose.onNodeWithTag(ReportTestTags.AUTO_CLIP_PENDING).getBoundsInRoot()
        val screenshot = compose.onNodeWithTag(ReportTestTags.attachment("screenshot.png")).getBoundsInRoot()
        assertTrue("the placeholder stands where the clip will: after the attachments", placeholder.left >= screenshot.right)
    }

    @Test
    fun attachmentsArrivingInFrontOfTheShownOnesScrollIntoView() {
        // Back from a manual recording: the row first shows the automatic clip, then the draft brings the
        // screenshot and the new recording, which sort in front of it.
        val auto = DraftFile(AttachmentKind.AUTO_SCREEN_RECORDING, File("/nonexistent/auto-recording.mp4"), "video/mp4")
        val shot = DraftFile(AttachmentKind.SCREENSHOT, File("/nonexistent/screenshot.png"), "image/png")
        val video = DraftFile(AttachmentKind.SCREEN_RECORDING, File("/nonexistent/recording-1.mp4"), "video/mp4")
        val current = mutableStateOf(state(attachments = listOf(auto)).copy(canAddExtraScreenshot = true, canRecordScreen = true, canAddGalleryImage = true))
        compose.setContent { ReportRoot(current.value, actions) }
        compose.onNodeWithTag(ReportTestTags.attachment("auto-recording.mp4")).performScrollTo()
        current.value = state(attachments = listOf(shot, video, auto)).copy(canAddExtraScreenshot = true, canRecordScreen = true)
        compose.waitForIdle()
        compose.onNodeWithTag(ReportTestTags.attachment("screenshot.png")).assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.attachment("recording-1.mp4")).assertIsDisplayed()
    }

    @Test
    fun noPlaceholderWithoutAnAutomaticRecordingInPreparation() {
        show(state())
        compose.onNodeWithTag(ReportTestTags.AUTO_CLIP_PENDING).assertDoesNotExist()
    }

    @Test
    fun aRecordingThumbnailHasAVideoBadgeAndNoEditor() {
        val video = DraftFile(AttachmentKind.SCREEN_RECORDING, File("/nonexistent/recording-1.mp4"), "video/mp4")
        show(state(attachments = listOf(video)))
        compose.onNodeWithTag(ReportTestTags.ATTACHMENT_VIDEO_BADGE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.attachment("recording-1.mp4")).performTouchInput { click(center) }
        assertTrue("a recording has no editor: ${actions.calls}", actions.calls.none { it.startsWith("edit:") })
        compose.onNodeWithContentDescription("Remove recording").performClick()
        assertEquals(listOf("remove:recording-1.mp4"), actions.calls)
    }

    @Test
    fun aCorruptRecordingFileShowsAPlaceholderInsteadOfCrashing() {
        // Real bytes MediaMetadataRetriever cannot parse: the actual failure path, not a fake one.
        val file = File(app.cacheDir, "corrupt-recording.mp4").apply { writeBytes(ByteArray(256) { it.toByte() }) }
        val video = DraftFile(AttachmentKind.SCREEN_RECORDING, file, "video/mp4")
        show(state(attachments = listOf(video)))
        compose.onNodeWithTag(ReportTestTags.attachment(file.name)).assertIsDisplayed()
        // Give the background decode of the real, unreadable file time to fail without crashing.
        Thread.sleep(500)
        compose.waitForIdle()
        compose.onNodeWithTag(ReportTestTags.attachment(file.name)).assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.ATTACHMENT_VIDEO_BADGE, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Screen recording").assertDoesNotExist()
        file.delete()
    }

    @Test
    fun theAnnotateStepShowsTheEditor() {
        val editor = AnnotationUiState(fileName = "screenshot.png", imageWidth = 100, imageHeight = 200, preview = ImageBitmap(100, 200))
        show(state(step = ReportStep.ANNOTATE).copy(annotation = editor))
        compose.onNodeWithTag(ReportTestTags.ANNOTATION_CANVAS).assertIsDisplayed()
        compose.onNodeWithText("Annotate").assertIsDisplayed()
    }

    @Test
    fun theProactivePromptAsksAndReportsBothAnswers() {
        show(state(step = ReportStep.PROACTIVE_PROMPT, type = ReportType.FRUSTRATING_EXPERIENCE))
        compose.onNodeWithText("Looks like something went wrong. Would you tell us what happened?").assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.PROACTIVE_ACCEPT).assertHeightIsAtLeast(48.dp).assertTextEquals("Tell us").performClick()
        compose.onNodeWithTag(ReportTestTags.PROACTIVE_DECLINE).assertHeightIsAtLeast(48.dp).assertTextEquals("Not now").performClick()
        assertEquals(listOf("proactiveAccepted", "proactiveDeclined"), actions.calls)
    }

    @Test
    fun theProactiveFormHasItsOwnTextsAndNoScreenshotTile() {
        show(state(type = ReportType.FRUSTRATING_EXPERIENCE))
        compose.onNodeWithText("What happened?").assertIsDisplayed()
        compose.onNodeWithText("Describe what happened").assertIsDisplayed()
        compose.onNodeWithTag(ReportTestTags.ADD_IMAGE).assertExists()
        compose.onNodeWithTag(ReportTestTags.ADD_SCREENSHOT).assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    private class RecordingActions : ReportActions {
        val calls = mutableListOf<String>()
        override fun onTypeSelected(type: ReportType) { calls += "type:$type" }
        override fun onEmailChanged(value: String) { calls += "email" }
        override fun onCommentChanged(value: String) { calls += "comment" }
        override fun onStepsChanged(value: String) { calls += "steps" }
        override fun onActualChanged(value: String) { calls += "actual" }
        override fun onExpectedChanged(value: String) { calls += "expected" }
        override fun onRemoveAttachment(file: DraftFile) { calls += "remove:${file.fileName}" }
        override fun onAddGalleryImage() { calls += "addImage" }
        override fun onAddExtraScreenshot() { calls += "addScreenshot" }
        override fun onRecordScreen() { calls += "recordScreen" }
        override fun onProactiveAccepted() { calls += "proactiveAccepted" }
        override fun onProactiveDeclined() { calls += "proactiveDeclined" }
        override fun onPrimaryAction() { calls += "primary" }
        override fun onBack() { calls += "back" }
        override fun onCancelRequested() { calls += "cancelRequested" }
        override fun onCancelConfirmed() { calls += "cancelConfirmed" }
        override fun onCancelDismissed() { calls += "cancelDismissed" }
        override fun onEditAttachment(file: DraftFile) { calls += "edit:${file.fileName}" }
        override fun onToolSelected(tool: AnnotationTool) { calls += "tool:$tool" }
        override fun onPenColorSelected(color: PenColor) { calls += "color:$color" }
        override fun onPenWidthSelected(width: PenWidth) { calls += "width:$width" }
        override fun onStrokeDrawn(points: List<Float>, width: Float) { calls += "stroke" }
        override fun onBlurDrawn(left: Float, top: Float, right: Float, bottom: Float) { calls += "blur" }
        override fun onMagnifierPlaced(centerX: Float, centerY: Float) { calls += "magnifier" }
        override fun onMagnifierChanged(index: Int, centerX: Float, centerY: Float, radius: Float) { calls += "moved:$index" }
        override fun onUndo() { calls += "undo" }
        override fun onAnnotationDone() { calls += "done" }
        override fun onAnnotationCancel() { calls += "cancel" }
        override fun onAnnotationDiscardConfirmed() { calls += "discardConfirmed" }
        override fun onAnnotationDiscardDismissed() { calls += "discardDismissed" }
    }
}
