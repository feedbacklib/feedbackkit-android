package io.github.feedbacklib.android.internal.ui

import android.app.Activity
import android.graphics.Bitmap
import android.net.Uri
import android.os.Looper
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.ColorTheme
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.ExtendedBugReport
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.Option
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.annotate.AnnotationImages
import io.github.feedbacklib.android.internal.annotate.AnnotationTool
import io.github.feedbacklib.android.internal.annotate.EditHistory
import io.github.feedbacklib.android.internal.annotate.EditOp
import io.github.feedbacklib.android.internal.annotate.EditsJson
import io.github.feedbacklib.android.internal.annotate.LoadedImage
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.core.AttachmentTypes
import io.github.feedbacklib.android.internal.core.Config
import io.github.feedbacklib.android.internal.core.ReportUiConfig
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.CaptureRequest
import io.github.feedbacklib.android.internal.invoke.CaptureRequestJson
import io.github.feedbacklib.android.internal.invoke.InvocationSource
import io.github.feedbacklib.android.internal.recording.PendingClips
import io.github.feedbacklib.android.internal.report.DraftFile
import io.github.feedbacklib.android.internal.report.DraftStore
import io.github.feedbacklib.android.internal.report.ExtendedFields
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import io.github.feedbacklib.android.internal.report.ReportDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.time.Duration
import kotlin.coroutines.CoroutineContext

@RunWith(RobolectricTestRunner::class)
class ReportDraftViewModelTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var env: FakeEnvironment

    @Before
    fun setUp() {
        env = FakeEnvironment(temp.newFolder("drafts"))
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun launchArgs(screenshot: File? = null, reportType: ReportType? = null) =
        FeedbackLaunchArgs(screenshot, false, "com.example.HostActivity", InvocationSource.MANUAL, reportType)

    private fun viewModel(
        args: FeedbackLaunchArgs = launchArgs(),
        handle: SavedStateHandle = SavedStateHandle(),
        store: ViewModelStore = ViewModelStore(),
    ): ReportDraftViewModel {
        val factory = viewModelFactory {
            initializer {
                ReportDraftViewModel(handle, args, env, EmailValidator { it.contains('@') && !it.endsWith('@') }, successDisplayMillis = 1_500, cpu = DirectDispatcher)
            }
        }
        return ViewModelProvider(store, factory)[ReportDraftViewModel::class.java].also { idle() }
    }

    private fun screenshot(): File = temp.newFile().apply { writeBytes(byteArrayOf(1, 2, 3)) }

    /** What the system keeps across process death. */
    private fun SavedStateHandle.afterProcessDeath() = SavedStateHandle(keys().associateWith { get<Any?>(it) })

    /** The screen FeedbackActivity.intent reopens: the saved state arrives as SavedStateHandle defaults. */
    private fun reopened(request: CaptureRequest, extra: File?, failed: Boolean = false, handle: SavedStateHandle = SavedStateHandle(request.state)): ReportDraftViewModel =
        viewModel(
            FeedbackLaunchArgs(null, false, request.currentScreen, InvocationSource.MANUAL, request.reportType, screenshotRequested = false, resume = ResumeArgs(extra, failed)),
            handle,
        )

    private fun captureFile(vm: ReportDraftViewModel) = File(env.drafts.dir(vm.draftId), DraftStore.CAPTURE_STATE_FILE_NAME)

    private var pickedImages = 0

    private fun image(mime: String? = "image/png", bytes: ByteArray? = byteArrayOf(1, 2, 3)): Uri =
        Uri.parse("content://media/picker/${++pickedImages}").also { env.content.entries[it] = mime to bytes }

    private fun ReportDraftViewModel.pickRequested(): Boolean =
        runBlocking { withTimeoutOrNull(100) { pickImageRequests.first() } } != null

    private fun ReportDraftViewModel.recordRequested(): Boolean =
        runBlocking { withTimeoutOrNull(100) { recordScreenRequests.first() } } != null

    @Test
    fun `several offered types start on the menu and a chosen type opens its form`() {
        val vm = viewModel()
        assertEquals(ReportStep.MENU, vm.uiState.step)
        assertEquals(listOf(ReportType.BUG, ReportType.FEEDBACK, ReportType.QUESTION), vm.uiState.menuTypes)

        vm.onTypeSelected(ReportType.FEEDBACK)
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertEquals(ReportType.FEEDBACK, vm.uiState.type)
        assertTrue(vm.uiState.canGoBack)
    }

    @Test
    fun `one offered type or a type from show opens the form straight away`() {
        env.ui { it.copy(reportTypes = setOf(ReportType.QUESTION)) }
        assertEquals(ReportType.QUESTION, viewModel().uiState.type)

        env.ui { it.copy(reportTypes = ReportUiConfig.DEFAULT_REPORT_TYPES) }
        val preset = viewModel(launchArgs(reportType = ReportType.BUG))
        assertEquals(ReportStep.FORM, preset.uiState.step)
        assertFalse(preset.uiState.canGoBack)
    }

    @Test
    fun `the email is prefilled and sending waits for a valid form`() {
        env.ui { it.copy(options = setOf(Option.COMMENT_FIELD_REQUIRED)) }
        val vm = viewModel(launchArgs(reportType = ReportType.FEEDBACK))
        assertEquals("me@example.com", vm.uiState.fields.email)
        assertFalse(vm.uiState.check.canContinue)
        vm.onPrimaryAction()
        assertTrue(env.submitted.isEmpty())

        vm.onCommentChanged("Dark mode please")
        assertTrue(vm.uiState.check.canContinue)
        vm.onEmailChanged("me@")
        assertTrue(vm.uiState.check.showEmailError)
        assertFalse(vm.uiState.check.canContinue)
    }

    @Test
    fun `the primary button is enabled by the form check and, on the extended step, by the extended fields too`() {
        env.ui { it.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS, options = setOf(Option.COMMENT_FIELD_REQUIRED)) }
        val vm = viewModel()
        assertFalse("nothing to send from the menu", vm.uiState.primaryEnabled)

        vm.onTypeSelected(ReportType.BUG)
        assertFalse(vm.uiState.primaryEnabled)
        vm.onCommentChanged("Save crashes")
        assertTrue(vm.uiState.primaryEnabled)
        vm.onPrimaryAction()
        assertEquals(ReportStep.EXTENDED, vm.uiState.step)
        assertFalse("required extended fields are empty", vm.uiState.primaryEnabled)

        vm.onStepsChanged("Tap save")
        vm.onActualChanged("Crash")
        vm.onExpectedChanged("Saved")
        assertTrue(vm.uiState.primaryEnabled)
        vm.onEmailChanged("me@")
        assertFalse("the form check still counts on the extended step", vm.uiState.primaryEnabled)
        vm.onEmailChanged("me@example.com")

        vm.onPrimaryAction()
        assertEquals(ReportStep.SENDING, vm.uiState.step)
        assertFalse(vm.uiState.primaryEnabled)
    }

    @Test
    fun `a bug goes through the extended step and the queued draft carries everything`() {
        env.ui { it.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS) }
        val shot = screenshot()
        val vm = viewModel(launchArgs(screenshot = shot, reportType = ReportType.BUG))
        assertEquals(listOf(AttachmentKind.SCREENSHOT), vm.uiState.attachments.map { it.kind })
        assertFalse("the screenshot must be moved, not copied", shot.exists())

        vm.onCommentChanged("  Save crashes  ")
        assertEquals(PrimaryAction.NEXT, vm.uiState.primaryAction)
        vm.onPrimaryAction()
        assertEquals(ReportStep.EXTENDED, vm.uiState.step)
        vm.onPrimaryAction() // required extended fields are empty
        assertTrue(env.submitted.isEmpty())

        vm.onStepsChanged("Tap save")
        vm.onActualChanged("Crash")
        vm.onExpectedChanged("Saved")
        vm.onPrimaryAction()
        idle()

        assertEquals(ReportStep.SENDING, vm.uiState.step)
        val draft = env.submitted.single()
        assertEquals(ReportType.BUG, draft.type)
        assertEquals("me@example.com", draft.email)
        assertEquals("Save crashes", draft.comment)
        assertEquals(ExtendedFields("Tap save", "Crash", "Saved"), draft.extended)
        assertEquals(listOf(AttachmentKind.SCREENSHOT), draft.attachments.map { it.kind })
        assertEquals("com.example.HostActivity", draft.currentScreen)
    }

    @Test
    fun `a queued report shows thanks for 1,5 s then closes with SUBMIT, and a double tap sends once`() {
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        vm.onPrimaryAction()
        idle()
        assertEquals(1, env.submitted.size)

        env.result.complete("report-1")
        idle()
        assertEquals(ReportStep.SUCCESS, vm.uiState.step)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_499))
        assertNull(vm.closed.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertEquals(DismissType.SUBMIT, vm.closed.value)
        assertEquals(DismissType.SUBMIT to ReportType.QUESTION, vm.dismissInfo())
    }

    @Test
    fun `a double tap while the files are still being read sends once`() {
        val io = QueueDispatcher()
        env = FakeEnvironment(temp.newFolder("queued"), io)
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.QUESTION))
        io.runAll()
        idle()
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        vm.onPrimaryAction() // the first send is still waiting for the disk
        io.runAll()
        idle()
        vm.onPrimaryAction()
        io.runAll()
        idle()
        assertEquals(1, env.submitted.size)
    }

    @Test
    fun `a tap after cancel, before the screen finishes, queues nothing`() {
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        assertTrue("an untouched question is sendable", vm.uiState.primaryEnabled)
        vm.onCancelRequested()
        assertEquals(DismissType.CANCEL, vm.closed.value)

        vm.onPrimaryAction()
        idle()
        assertTrue(env.submitted.isEmpty())
        assertEquals(DismissType.CANCEL to ReportType.QUESTION, vm.dismissInfo())
    }

    @Test
    fun `edits and removals are ignored while sending, on the thank-you screen and on the menu`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.QUESTION))
        val attachment = vm.uiState.attachments.single()
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        idle()
        assertEquals(ReportStep.SENDING, vm.uiState.step)

        vm.onCommentChanged("Changed while sending")
        vm.onEmailChanged("other@example.com")
        vm.onRemoveAttachment(attachment)
        idle()
        assertEquals("How?", vm.uiState.fields.comment)
        assertEquals("me@example.com", vm.uiState.fields.email)
        assertEquals(listOf(attachment), vm.uiState.attachments)
        assertTrue("the submitter may still be copying it", attachment.file.exists())

        env.result.complete("report-1")
        idle()
        assertEquals(ReportStep.SUCCESS, vm.uiState.step)
        vm.onStepsChanged("Too late")
        vm.onRemoveAttachment(attachment)
        idle()
        assertEquals("", vm.uiState.fields.steps)
        assertTrue(attachment.file.exists())

        val menu = viewModel()
        assertEquals(ReportStep.MENU, menu.uiState.step)
        menu.onCommentChanged("No type yet")
        assertEquals("", menu.uiState.fields.comment)
    }

    @Test
    fun `a report that could not be queued returns to the form with an error and keeps the text`() {
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        env.result.complete(null)
        idle()

        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertTrue(vm.uiState.submitFailed)
        assertEquals("How?", vm.uiState.fields.comment)
        vm.onCommentChanged("How come?")
        assertFalse(vm.uiState.submitFailed)
    }

    @Test
    fun `a submitter that throws is a failed send, not a crash`() {
        env.submitFailure = IllegalStateException("broken store")
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        idle()

        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertTrue(vm.uiState.submitFailed)
    }

    @Test
    fun `cancel closes an untouched draft at once and asks before dropping typed text`() {
        val untouched = viewModel(launchArgs(reportType = ReportType.BUG))
        untouched.onCancelRequested()
        assertEquals(DismissType.CANCEL, untouched.closed.value)

        val typed = viewModel(launchArgs(reportType = ReportType.BUG))
        typed.onCommentChanged("Half a thought")
        typed.onBack()
        assertTrue(typed.uiState.confirmingCancel)
        typed.onCancelDismissed()
        assertFalse(typed.uiState.confirmingCancel)
        assertNull(typed.closed.value)

        typed.onCancelRequested()
        typed.onCancelConfirmed()
        assertEquals(DismissType.CANCEL to ReportType.BUG, typed.dismissInfo())
    }

    @Test
    fun `closing the menu reports the first offered type`() {
        env.ui { it.copy(reportTypes = setOf(ReportType.QUESTION, ReportType.FEEDBACK)) }
        val vm = viewModel()
        vm.onBack()
        assertEquals(DismissType.CANCEL to ReportType.FEEDBACK, vm.dismissInfo())
    }

    @Test
    fun `process death restores text, step and the open dialog, and a restore mid-send returns to the form`() {
        val firstHandle = SavedStateHandle()
        val first = viewModel(launchArgs(reportType = ReportType.FEEDBACK), firstHandle)
        first.onCommentChanged("Please add export")
        first.onEmailChanged("other@example.com")
        first.onCancelRequested()

        val secondHandle = firstHandle.afterProcessDeath()
        val restored = viewModel(launchArgs(reportType = ReportType.FEEDBACK), secondHandle)
        assertEquals(first.draftId, restored.draftId)
        assertEquals("Please add export", restored.uiState.fields.comment)
        assertEquals("other@example.com", restored.uiState.fields.email)
        assertTrue(restored.uiState.confirmingCancel)

        restored.onCancelDismissed()
        restored.onPrimaryAction()
        assertEquals(ReportStep.SENDING, restored.uiState.step)
        assertEquals(1, env.submitted.size)

        val afterDeathWhileSending = viewModel(launchArgs(reportType = ReportType.FEEDBACK), secondHandle.afterProcessDeath())
        idle()
        assertEquals(ReportStep.FORM, afterDeathWhileSending.uiState.step)
        assertEquals("Please add export", afterDeathWhileSending.uiState.fields.comment)
        assertEquals("the restore never resends on its own", 1, env.submitted.size)
    }

    @Test
    fun `process death restores the extended step, the chosen type, the menu history and the screenshot`() {
        env.ui { it.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS) }
        val handle = SavedStateHandle()
        val args = launchArgs(screenshot = screenshot())
        val first = viewModel(args, handle)
        first.onTypeSelected(ReportType.BUG)
        first.onCommentChanged("Crash")
        first.onPrimaryAction()
        first.onStepsChanged("Tap save")
        assertEquals(ReportStep.EXTENDED, first.uiState.step)

        // The process dies: the first ViewModel is never cleared, its files stay; the intent is replayed.
        val restored = viewModel(args, handle.afterProcessDeath())
        assertEquals(ReportStep.EXTENDED, restored.uiState.step)
        assertEquals(ReportType.BUG, restored.uiState.type)
        assertEquals("Tap save", restored.uiState.fields.steps)
        assertEquals(listOf(AttachmentKind.SCREENSHOT), restored.uiState.attachments.map { it.kind })
        restored.onBack()
        restored.onBack()
        assertEquals("the menu was shown before the death", ReportStep.MENU, restored.uiState.step)
    }

    @Test
    fun `a restore on the extended step returns to the form when the host has switched that step off`() {
        env.ui { it.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS) }
        val handle = SavedStateHandle()
        val first = viewModel(launchArgs(reportType = ReportType.BUG), handle)
        first.onCommentChanged("Crash")
        first.onPrimaryAction()
        assertEquals(ReportStep.EXTENDED, first.uiState.step)

        env.ui { it.copy(extendedState = ExtendedBugReport.State.DISABLED) } // the new process configures it off
        val restored = viewModel(launchArgs(reportType = ReportType.BUG), handle.afterProcessDeath())
        assertEquals(ReportStep.FORM, restored.uiState.step)
        assertEquals("Crash", restored.uiState.fields.comment)
    }

    @Test
    fun `process death on the thank-you screen closes at once with SUBMIT`() {
        val handle = SavedStateHandle()
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION), handle)
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        env.result.complete("report-1")
        idle()
        assertEquals(ReportStep.SUCCESS, vm.uiState.step)

        val restored = viewModel(launchArgs(reportType = ReportType.QUESTION), handle.afterProcessDeath())
        assertEquals(DismissType.SUBMIT to ReportType.QUESTION, restored.dismissInfo())
    }

    @Test
    fun `a saved state with unknown names starts over on the menu instead of crashing`() {
        val handle = SavedStateHandle()
        viewModel(handle = handle).onTypeSelected(ReportType.FEEDBACK)
        val corrupted = handle.afterProcessDeath().apply {
            set("feedbackkit.step", "RENAMED_STEP")
            set("feedbackkit.type", "RENAMED_TYPE")
        }
        assertEquals(ReportStep.MENU, viewModel(handle = corrupted).uiState.step)

        val unknownType = handle.afterProcessDeath().apply { set("feedbackkit.type", "RENAMED_TYPE") }
        val vm = viewModel(handle = unknownType)
        assertEquals(ReportStep.MENU, vm.uiState.step)
        assertNull(vm.uiState.type)
    }

    @Test
    fun `rotation keeps the same draft, with the open dialog and the send in flight`() {
        val store = ViewModelStore()
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION), store = store)
        vm.onCommentChanged("How?")
        vm.onCancelRequested()
        assertSame(vm, viewModel(launchArgs(reportType = ReportType.QUESTION), store = store))
        assertTrue(vm.uiState.confirmingCancel)

        vm.onCancelDismissed()
        vm.onPrimaryAction()
        val afterRotation = viewModel(launchArgs(reportType = ReportType.QUESTION), store = store)
        assertSame(vm, afterRotation)
        env.result.complete("report-1")
        idle()
        assertEquals(ReportStep.SUCCESS, afterRotation.uiState.step)
        assertEquals(1, env.submitted.size)
    }

    @Test
    fun `removing the screenshot deletes its file and a launch without one shows the placeholder`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        val attachment = vm.uiState.attachments.single()
        assertFalse(vm.uiState.screenshotUnavailable)

        vm.onRemoveAttachment(attachment)
        idle()
        assertTrue(vm.uiState.attachments.isEmpty())
        assertFalse(attachment.file.exists())

        assertTrue(viewModel(launchArgs(screenshot = null, reportType = ReportType.BUG)).uiState.screenshotUnavailable)
    }

    @Test
    fun `a screenshot that could not be moved into the draft shows the placeholder`() {
        val gone = File(temp.root, "never-written.png") // the capture file vanished before the adopt
        val vm = viewModel(launchArgs(screenshot = gone, reportType = ReportType.BUG))
        assertTrue(vm.uiState.attachments.isEmpty())
        assertTrue(vm.uiState.screenshotUnavailable)
    }

    @Test
    fun `a launch that took no screenshot on purpose shows no placeholder`() {
        val args = FeedbackLaunchArgs(null, false, "com.example.HostActivity", InvocationSource.MANUAL, ReportType.BUG, screenshotRequested = false)
        val vm = viewModel(args)
        assertFalse(vm.uiState.screenshotUnavailable)
        assertTrue(vm.uiState.attachments.isEmpty())
    }

    @Test
    fun `an invocation screenshot is dropped when the host switched it off before the screen opened`() {
        env.ui { it.copy(attachmentTypes = AttachmentTypes(initialScreenshot = false)) }
        val shot = screenshot()
        val vm = viewModel(launchArgs(screenshot = shot, reportType = ReportType.BUG))
        assertTrue(vm.uiState.attachments.isEmpty())
        assertFalse("the capture must not linger in the cache", shot.exists())
        assertFalse(vm.uiState.screenshotUnavailable)
    }

    @Test
    fun `a restore keeps the saved placeholder state instead of re-deriving it from the draft`() {
        val handle = SavedStateHandle()
        val args = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG)
        val vm = viewModel(args, handle)
        vm.onRemoveAttachment(vm.uiState.attachments.single())
        idle()

        // The intent still names the (long adopted) capture file; the user removed the screenshot.
        val restored = viewModel(args, handle.afterProcessDeath())
        assertTrue(restored.uiState.attachments.isEmpty())
        assertFalse(restored.uiState.screenshotUnavailable)
    }

    @Test
    fun `a screenshot removed right before sending is never sent`() {
        // Runs queued work newest first: only a single serial queue keeps the removal before the read.
        val io = QueueDispatcher(newestFirst = true)
        env = FakeEnvironment(temp.newFolder("queued"), io)
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.QUESTION))
        io.runAll()
        idle()
        val attachment = vm.uiState.attachments.single()

        vm.onCommentChanged("How?")
        vm.onRemoveAttachment(attachment)
        vm.onPrimaryAction()
        io.runAll()
        idle()

        assertTrue(env.submitted.single().attachments.isEmpty())
        assertFalse(attachment.file.exists())
    }

    @Test
    fun `a restored screen of the same draft waits for the first screen's queued removal`() {
        // Newest-first execution: only one queue shared by both screens keeps the removal first.
        val io = QueueDispatcher(newestFirst = true)
        env = FakeEnvironment(temp.newFolder("shared"), io)
        val handle = SavedStateHandle()
        val args = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG)
        val first = viewModel(args, handle)
        io.runAll()
        idle()

        first.onRemoveAttachment(first.uiState.attachments.single())
        val restored = viewModel(args, handle.afterProcessDeath()) // lists the draft while the removal is queued
        io.runAll()
        idle()

        assertTrue(restored.uiState.attachments.isEmpty())
    }

    @Test
    fun `add screenshot hands the saved draft to capture mode and closes with ADD_ATTACHMENT, once`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        vm.onCommentChanged("Two screens")
        assertTrue(vm.uiState.canAddExtraScreenshot)
        vm.onAddExtraScreenshot()
        vm.onAddExtraScreenshot() // a double tap

        val request = env.captures.single()
        assertEquals(vm.draftId, request.draftId)
        assertEquals(ReportType.BUG, request.reportType)
        assertEquals("com.example.HostActivity", request.currentScreen)
        assertEquals("Two screens", request.state["feedbackkit.comment"])
        assertEquals("FORM", request.state["feedbackkit.step"])
        assertEquals(vm.draftId, request.state["feedbackkit.draftId"])
        assertEquals(DismissType.ADD_ATTACHMENT, vm.closed.value)
        assertEquals(DismissType.ADD_ATTACHMENT to ReportType.BUG, vm.dismissInfo())
        assertEquals(request, CaptureRequestJson.decode(captureFile(vm).readText()))
    }

    @Test
    fun `the draft stays on disk when the screen closes for a screenshot`() {
        val store = ViewModelStore()
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG), store = store)
        vm.onAddExtraScreenshot()
        store.clear()
        env.runDetachedBlocks()
        assertTrue(File(env.drafts.dir(vm.draftId), "screenshot.png").exists())
    }

    @Test
    fun `add screenshot does nothing when switched off, off the form or at the limit`() {
        env.ui { it.copy(attachmentTypes = AttachmentTypes(extraScreenshot = false)) }
        val off = viewModel(launchArgs(reportType = ReportType.BUG))
        assertFalse(off.uiState.canAddExtraScreenshot)
        off.onAddExtraScreenshot()
        assertNull(off.closed.value)

        env.ui { it.copy(attachmentTypes = AttachmentTypes()) }
        val menu = viewModel()
        menu.onAddExtraScreenshot()
        assertNull(menu.closed.value)

        val full = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        repeat(3) {
            full.onGalleryImagePicked(image())
            idle()
        }
        full.onAddExtraScreenshot()
        assertNull(full.closed.value)
        assertTrue(env.captures.isEmpty())
    }

    @Test
    fun `a refused capture mode closes the report as a cancel and its draft goes with it`() {
        env.acceptCapture = false
        val store = ViewModelStore()
        val refused = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG), store = store)
        refused.onAddExtraScreenshot()
        assertEquals(DismissType.CANCEL, refused.closed.value)
        assertTrue(env.captures.isEmpty())
        assertFalse(captureFile(refused).exists())

        store.clear()
        env.runDetachedBlocks()
        assertFalse(env.drafts.dir(refused.draftId).exists())
    }

    @Test
    fun `the reopened screen restores the draft and adds the extra screenshot`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        vm.onCommentChanged("Two screens")
        vm.onAddExtraScreenshot()
        val request = env.captures.single()
        val extra = screenshot()

        val back = reopened(request, extra)
        assertEquals(vm.draftId, back.draftId)
        assertEquals(ReportStep.FORM, back.uiState.step)
        assertEquals("Two screens", back.uiState.fields.comment)
        assertEquals(listOf(AttachmentKind.SCREENSHOT, AttachmentKind.EXTRA_SCREENSHOT), back.uiState.attachments.map { it.kind })
        assertFalse("moved, not copied", extra.exists())
        assertFalse("the saved capture mode is spent", captureFile(vm).exists())
        assertNull(back.uiState.notice)
        assertNull(back.closed.value)
    }

    @Test
    fun `a reopened screen restored after process death does not add the screenshot twice`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        vm.onAddExtraScreenshot()
        val request = env.captures.single()
        val handle = SavedStateHandle(request.state)
        val extra = screenshot()
        reopened(request, extra, handle = handle)

        val restored = reopened(request, extra, handle = handle.afterProcessDeath())
        assertEquals(2, restored.uiState.attachments.size)
        assertNull(restored.uiState.notice)
    }

    @Test
    fun `a failed or secure capture reopens the draft with the placeholder notice`() {
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        vm.onAddExtraScreenshot()
        val back = reopened(env.captures.single(), extra = null, failed = true)
        assertEquals(AttachNotice.SCREENSHOT_UNAVAILABLE, back.uiState.notice)
        assertTrue(back.uiState.attachments.isEmpty())
    }

    @Test
    fun `a screenshot that vanished before the reopen says it is unavailable`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        vm.onAddExtraScreenshot()
        val gone = File(temp.root, "capture/extra-gone.png") // taken, then the cache was cleared

        val back = reopened(env.captures.single(), gone)
        assertEquals(AttachNotice.SCREENSHOT_UNAVAILABLE, back.uiState.notice)
        assertEquals(listOf(AttachmentKind.SCREENSHOT), back.uiState.attachments.map { it.kind })
        assertFalse("the saved capture mode is spent all the same", captureFile(vm).exists())
    }

    @Test
    fun `a screenshot that no longer fits is refused with the limit notice`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        repeat(2) {
            vm.onGalleryImagePicked(image())
            idle()
        }
        vm.onAddExtraScreenshot()
        val request = env.captures.single()
        // Something filled the fourth slot while the user was capturing.
        env.drafts.addImage(vm.draftId, { "image/png" }, { byteArrayOf(1).inputStream() }, 4, 1_000)
        val extra = screenshot()

        val back = reopened(request, extra)
        assertEquals(4, back.uiState.attachments.size)
        assertEquals(AttachNotice.LIMIT_REACHED, back.uiState.notice)
        assertFalse(extra.exists())
    }

    @Test
    fun `leaving deletes the draft only after the submission finished and never cancels it`() {
        val store = ViewModelStore()
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.QUESTION), store = store)
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        idle()
        val draftDir = env.drafts.dir(vm.draftId)

        store.clear()
        assertFalse(env.result.isCancelled)
        assertTrue(draftDir.exists())

        env.result.complete("report-1")
        env.runDetachedBlocks()
        assertFalse(draftDir.exists())
    }

    @Test
    fun `cancelling deletes the draft when the screen goes away`() {
        val store = ViewModelStore()
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG), store = store)
        val draftDir = env.drafts.dir(vm.draftId)
        vm.onCancelRequested()
        assertEquals(DismissType.CANCEL, vm.closed.value)

        store.clear()
        env.runDetachedBlocks()
        assertFalse(draftDir.exists())
    }

    @Test
    fun `a screen finished from outside counts as a cancel and deletes the draft`() {
        val store = ViewModelStore()
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG), store = store)
        vm.onCommentChanged("Half a thought")
        val draftDir = env.drafts.dir(vm.draftId)

        vm.onScreenFinished()
        assertEquals(DismissType.CANCEL, vm.closed.value)
        store.clear()
        env.runDetachedBlocks()
        assertFalse(draftDir.exists())
    }

    @Test
    fun `a screen finished after a submit keeps SUBMIT as the reason`() {
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        env.result.complete("report-1")
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))

        vm.onScreenFinished()
        assertEquals(DismissType.SUBMIT, vm.closed.value)
    }

    @Test
    fun `a screen destroyed without closing keeps its draft for the restore`() {
        val handle = SavedStateHandle()
        val store = ViewModelStore()
        val args = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG)
        val vm = viewModel(args, handle, store)
        vm.onCommentChanged("Half a thought")
        val shot = vm.uiState.attachments.single().file

        // "Don't keep activities", or the system reclaimed the activity while the process lives.
        store.clear()
        env.runDetachedBlocks()
        assertTrue(env.drafts.dir(vm.draftId).exists())
        assertTrue(shot.exists())

        val restored = viewModel(args, handle.afterProcessDeath())
        assertEquals("Half a thought", restored.uiState.fields.comment)
        assertEquals(listOf(shot), restored.uiState.attachments.map { it.file })
    }

    @Test
    fun `a failed send does not count as closing and the draft stays when the screen is destroyed`() {
        val store = ViewModelStore()
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.QUESTION), store = store)
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        env.result.complete(null)
        idle()

        store.clear()
        env.runDetachedBlocks()
        assertTrue(env.drafts.dir(vm.draftId).exists())
    }

    @Test
    fun `back walks extended to form to menu and is ignored while sending`() {
        env.ui { it.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_OPTIONAL_FIELDS, options = setOf(Option.EMAIL_FIELD_HIDDEN)) }
        val vm = viewModel()
        vm.onTypeSelected(ReportType.BUG)
        vm.onPrimaryAction()
        assertEquals(ReportStep.EXTENDED, vm.uiState.step)
        vm.onBack()
        assertEquals(ReportStep.FORM, vm.uiState.step)
        vm.onBack()
        assertEquals(ReportStep.MENU, vm.uiState.step)

        vm.onTypeSelected(ReportType.BUG)
        vm.onPrimaryAction()
        vm.onPrimaryAction()
        idle()
        assertEquals(ReportStep.SENDING, vm.uiState.step)
        assertEquals("me@example.com", env.submitted.single().email) // hidden field: the identified email
        vm.onBack()
        vm.onCancelRequested()
        assertEquals(ReportStep.SENDING, vm.uiState.step)
        assertNull(vm.closed.value)
    }

    @Test
    fun `settings changed while the screen is open apply at once`() {
        val vm = viewModel(launchArgs(reportType = ReportType.FEEDBACK))
        assertEquals(EmailMode.REQUIRED, vm.uiState.rules!!.emailMode)

        env.ui { it.copy(options = setOf(Option.EMAIL_FIELD_HIDDEN), colorTheme = ColorTheme.DARK) }
        idle()
        assertEquals(EmailMode.HIDDEN, vm.uiState.rules!!.emailMode)
        assertEquals(ColorTheme.DARK, vm.uiState.colorTheme)
    }

    @Test
    fun `disabling the SDK closes an open screen with CANCEL and its draft goes with it`() {
        val store = ViewModelStore()
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG), store = store)
        vm.onCommentChanged("Half a thought")
        val draftDir = env.drafts.dir(vm.draftId)

        env.config.value = env.config.value.copy(enabled = false)
        idle()
        assertEquals(DismissType.CANCEL, vm.closed.value)
        store.clear()
        env.runDetachedBlocks()
        assertFalse(draftDir.exists())
    }

    @Test
    fun `switching bug reporting off leaves an open screen alone`() {
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        env.config.value = env.config.value.copy(bugReportingEnabled = false)
        idle()
        assertNull(vm.closed.value)
    }

    @Test
    fun `a send that fails because the SDK was disabled meanwhile closes instead of offering a retry`() {
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        idle()
        env.config.value = env.config.value.copy(enabled = false)
        idle()
        assertNull("the send in flight decides", vm.closed.value)

        env.result.complete(null) // the submitter refuses: the SDK is disabled
        idle()
        assertEquals(DismissType.CANCEL, vm.closed.value)
    }

    @Test
    fun `disabling the SDK on the thank-you screen still closes with SUBMIT`() {
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        env.result.complete("report-1")
        idle()
        env.config.value = env.config.value.copy(enabled = false)
        idle()
        assertNull(vm.closed.value)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_500))
        assertEquals(DismissType.SUBMIT, vm.closed.value)
    }

    @Test
    fun `a huge paste is capped so the saved state stays small`() {
        val handle = SavedStateHandle()
        val vm = viewModel(launchArgs(reportType = ReportType.FEEDBACK), handle)
        val huge = "x".repeat(500_000)
        vm.onEmailChanged(huge)
        vm.onCommentChanged(huge)
        vm.onStepsChanged(huge)
        vm.onActualChanged(huge)
        vm.onExpectedChanged(huge)
        assertEquals(ReportDraftViewModel.MAX_FIELD_LENGTH, vm.uiState.fields.comment.length)

        val savedChars = handle.keys().sumOf { (handle.get<Any?>(it) as? String)?.length ?: 0 }
        assertTrue("saved $savedChars chars", savedChars <= 5 * ReportDraftViewModel.MAX_FIELD_LENGTH + 200)
    }

    @Test
    fun `capping never splits an emoji`() {
        val vm = viewModel(launchArgs(reportType = ReportType.FEEDBACK))
        val emoji = "😀" // one code point, two chars
        val max = ReportDraftViewModel.MAX_FIELD_LENGTH

        vm.onCommentChanged("x".repeat(max - 1) + emoji)
        assertEquals("the emoji straddles the limit and is dropped whole", "x".repeat(max - 1), vm.uiState.fields.comment)

        vm.onCommentChanged("x".repeat(max - 2) + emoji + "tail")
        assertEquals("x".repeat(max - 2) + emoji, vm.uiState.fields.comment)
    }

    @Test
    fun `add image asks the screen for the photo picker while an image still fits`() {
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        assertTrue(vm.uiState.canAddGalleryImage)
        vm.onAddGalleryImage()
        assertTrue(vm.pickRequested())

        env.ui { it.copy(attachmentTypes = AttachmentTypes(gallery = false)) }
        idle()
        assertFalse(vm.uiState.canAddGalleryImage)
        vm.onAddGalleryImage()
        assertFalse(vm.pickRequested())
    }

    @Test
    fun `record screen is offered once a recorder exists and the type is on`() {
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        assertFalse("no recorder yet", vm.uiState.canRecordScreen)
        env.canRecord.value = true
        idle()
        assertTrue(vm.uiState.canRecordScreen)
        env.ui { it.copy(attachmentTypes = AttachmentTypes(screenRecording = false)) }
        idle()
        assertFalse(vm.uiState.canRecordScreen)
    }

    @Test
    fun `record screen asks the screen once and holds the other actions while the consent is open`() {
        env.canRecord.value = true
        env.ui { it.copy(options = setOf(Option.EMAIL_FIELD_OPTIONAL)) }
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        vm.onCommentChanged("Janky scroll")
        vm.onRecordScreen()
        vm.onRecordScreen()
        assertTrue(vm.recordRequested())
        vm.startRecording(host)
        vm.startRecording(host)
        assertFalse("answered", vm.recordRequested())
        assertEquals("a double tap asks once", 1, env.recordings.size)
        assertTrue(vm.uiState.recordingStarting)

        vm.onAddExtraScreenshot()
        vm.onAddGalleryImage()
        vm.onPrimaryAction()
        assertTrue(env.captures.isEmpty())
        assertFalse(vm.pickRequested())
        assertTrue(env.submitted.isEmpty())
    }

    @Test
    fun `record screen is refused off the form or without a recorder`() {
        val vm = viewModel() // the menu
        env.canRecord.value = true
        idle()
        vm.onRecordScreen()
        assertFalse(vm.recordRequested())

        env.canRecord.value = false
        idle()
        vm.onTypeSelected(ReportType.BUG)
        vm.onRecordScreen()
        assertFalse(vm.recordRequested())
    }

    @Test
    fun `record screen sends nothing once four attachments already fill the draft`() {
        env.canRecord.value = true
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        repeat(3) {
            vm.onGalleryImagePicked(image())
            idle()
        }
        assertEquals(4, vm.uiState.attachments.size)
        assertFalse(vm.uiState.canRecordScreen)

        vm.onRecordScreen()
        assertFalse(vm.recordRequested())
    }

    @Test
    fun `a recording in the draft opens no editor`() {
        val handle = SavedStateHandle(mapOf("feedbackkit.draftId" to "d-video"))
        File(env.drafts.dir("d-video"), "recording-1.mp4").apply {
            parentFile!!.mkdirs()
            writeBytes(byteArrayOf(0))
        }
        val vm = viewModel(launchArgs(reportType = ReportType.BUG), handle)
        val video = vm.uiState.attachments.single()
        assertEquals(AttachmentKind.SCREEN_RECORDING, video.kind)
        vm.onEditAttachment(video)
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertNull(vm.uiState.annotation)
    }

    @Test
    fun `a picked image is copied into the draft and shows after the screenshot`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        vm.onGalleryImagePicked(image(bytes = byteArrayOf(4, 5)))
        idle()
        assertEquals(listOf(AttachmentKind.SCREENSHOT, AttachmentKind.GALLERY_IMAGE), vm.uiState.attachments.map { it.kind })
        val copy = vm.uiState.attachments.last()
        assertEquals("image/png", copy.mimeType)
        assertTrue(copy.file.readBytes().contentEquals(byteArrayOf(4, 5)))
        assertNull(vm.uiState.notice)
    }

    @Test
    fun `an unsupported, oversized or unreadable image leaves the draft as it was and says why`() {
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        vm.onGalleryImagePicked(image(mime = "application/pdf"))
        idle()
        assertEquals(AttachNotice.IMAGE_UNSUPPORTED, vm.uiState.notice)

        vm.onGalleryImagePicked(image(bytes = ByteArray((AttachmentRules.GALLERY_MAX_BYTES + 1).toInt())))
        idle()
        assertEquals(AttachNotice.IMAGE_TOO_LARGE, vm.uiState.notice)

        vm.onGalleryImagePicked(image(bytes = null))
        idle()
        assertEquals(AttachNotice.IMAGE_FAILED, vm.uiState.notice)
        assertTrue(vm.uiState.attachments.isEmpty())
    }

    @Test
    fun `a picker answer that arrives when four attachments are already there is refused`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        repeat(3) {
            vm.onGalleryImagePicked(image())
            idle()
        }
        assertEquals(4, vm.uiState.attachments.size)
        assertFalse(vm.uiState.canAddGalleryImage)
        assertTrue(vm.uiState.attachmentLimitReached)

        vm.onGalleryImagePicked(image())
        idle()
        assertEquals(4, vm.uiState.attachments.size)
        assertEquals(AttachNotice.LIMIT_REACHED, vm.uiState.notice)
    }

    @Test
    fun `removing an attachment frees a slot and clears the notice`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        vm.onGalleryImagePicked(image(mime = "text/plain"))
        idle()
        assertEquals(AttachNotice.IMAGE_UNSUPPORTED, vm.uiState.notice)

        vm.onRemoveAttachment(vm.uiState.attachments.single())
        assertNull(vm.uiState.notice)
    }

    @Test
    fun `a notice survives process death`() {
        val handle = SavedStateHandle()
        val args = launchArgs(reportType = ReportType.BUG)
        val vm = viewModel(args, handle)
        vm.onGalleryImagePicked(image(mime = "text/plain"))
        idle()

        assertEquals(AttachNotice.IMAGE_UNSUPPORTED, viewModel(args, handle.afterProcessDeath()).uiState.notice)
    }

    @Test
    fun `picker answers while sending, after cancel or without a choice change nothing`() {
        val vm = viewModel(launchArgs(reportType = ReportType.QUESTION))
        vm.onGalleryImagePicked(null)
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        idle()
        vm.onGalleryImagePicked(image())
        idle()
        assertTrue(vm.uiState.attachments.isEmpty())

        val cancelled = viewModel(launchArgs(reportType = ReportType.QUESTION))
        cancelled.onCancelRequested()
        cancelled.onGalleryImagePicked(image())
        idle()
        assertTrue(cancelled.uiState.attachments.isEmpty())
    }

    @Test
    fun `a device without any picker gets a notice instead of a crash`() {
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        vm.onGalleryUnavailable()
        assertEquals(AttachNotice.IMAGE_FAILED, vm.uiState.notice)
    }

    @Test
    fun `an attachment removed while a picked image is still copying does not come back`() {
        // FIFO: the queued copy runs before the queued removal, exactly like a real large copy that
        // is still in flight when the user removes an older attachment.
        val io = QueueDispatcher()
        env = FakeEnvironment(temp.newFolder("racing"), io)
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        io.runAll()
        idle()
        val shot = vm.uiState.attachments.single()

        vm.onGalleryImagePicked(image())
        vm.onRemoveAttachment(shot)
        io.runAll()
        idle()

        assertEquals(listOf(AttachmentKind.GALLERY_IMAGE), vm.uiState.attachments.map { it.kind })
        assertFalse("the removal must not be undone by the copy's own listing", shot.file.exists())
    }

    private fun editing(
        args: FeedbackLaunchArgs = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG),
        handle: SavedStateHandle = SavedStateHandle(),
    ): ReportDraftViewModel {
        val vm = viewModel(args, handle)
        vm.onEditAttachment(vm.uiState.attachments.single())
        idle()
        return vm
    }

    private fun savedOps(vm: ReportDraftViewModel): List<EditOp> = EditsJson.decode(env.drafts.readEdits(vm.draftId, "screenshot.png")).ops()

    @Test
    fun `tapping a thumbnail opens the editor on that image with the pen`() {
        val vm = editing()
        assertEquals(ReportStep.ANNOTATE, vm.uiState.step)
        val editor = vm.uiState.annotation!!
        assertEquals("screenshot.png", editor.fileName)
        assertEquals(1000, editor.imageWidth)
        assertEquals(2000, editor.imageHeight)
        assertEquals(AnnotationTool.PEN, editor.tool)
        assertEquals(PenColor.RED, editor.penColor)
        assertTrue(editor.preview != null)
        assertFalse(editor.canUndo)
    }

    @Test
    fun `strokes, blurs and magnifiers make an undoable history saved to the draft after every step`() {
        val vm = editing()
        vm.onStrokeDrawn(listOf(10f, 10f, 50f, 50f), 8f)
        vm.onToolSelected(AnnotationTool.BLUR)
        vm.onBlurDrawn(0f, 0f, 100f, 100f)
        vm.onToolSelected(AnnotationTool.MAGNIFIER)
        vm.onMagnifierPlaced(500f, 500f)
        idle()

        val ops = vm.uiState.annotation!!.ops
        assertEquals(
            listOf(
                EditOp.Stroke(PenColor.RED.argb, 8f, listOf(10f, 10f, 50f, 50f)),
                EditOp.Blur(0f, 0f, 100f, 100f),
                EditOp.Magnifier(500f, 500f, 150f),
            ),
            ops,
        )
        assertEquals(ops, savedOps(vm))

        vm.onUndo()
        assertEquals(ops.take(2), vm.uiState.annotation!!.ops)
        assertEquals(ops.take(2), savedOps(vm))
    }

    @Test
    fun `the steps are written on the draft's queue, never on the main thread`() {
        val io = QueueDispatcher()
        env = FakeEnvironment(temp.newFolder("queued"), io)
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        io.runAll()
        idle()
        vm.onEditAttachment(vm.uiState.attachments.single())
        io.runAll()
        idle()

        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        assertNull("nothing is written before the queue runs", env.drafts.readEdits(vm.draftId, "screenshot.png"))
        io.runAll()
        assertEquals(listOf(EditOp.Stroke(PenColor.RED.argb, 4f, listOf(1f, 1f, 2f, 2f))), savedOps(vm))
    }

    @Test
    fun `moving a magnifier is one step and its radius stays within bounds`() {
        val vm = editing()
        vm.onMagnifierPlaced(500f, 500f)
        vm.onMagnifierChanged(0, 600f, 700f, 10_000f)
        assertEquals(listOf(EditOp.Magnifier(600f, 700f, 450f)), vm.uiState.annotation!!.ops)

        vm.onUndo()
        assertEquals(listOf(EditOp.Magnifier(500f, 500f, 150f)), vm.uiState.annotation!!.ops)
        vm.onMagnifierChanged(3, 1f, 1f, 100f) // no such magnifier
        assertEquals(1, vm.uiState.annotation!!.ops.size)
    }

    @Test
    fun `the editor comes back after process death with its steps and tool`() {
        val handle = SavedStateHandle()
        val args = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG)
        val vm = editing(args, handle)
        vm.onPenColorSelected(PenColor.BLUE)
        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        vm.onToolSelected(AnnotationTool.BLUR)

        val restored = viewModel(args, handle.afterProcessDeath())
        idle()
        assertEquals(ReportStep.ANNOTATE, restored.uiState.step)
        val editor = restored.uiState.annotation!!
        assertEquals(AnnotationTool.BLUR, editor.tool)
        assertEquals(PenColor.BLUE, editor.penColor)
        assertEquals(listOf(EditOp.Stroke(PenColor.BLUE.argb, 4f, listOf(1f, 1f, 2f, 2f))), editor.ops)
        assertTrue(editor.canUndo)
    }

    @Test
    fun `undo works on restored steps and saves the change`() {
        val handle = SavedStateHandle()
        val args = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG)
        val vm = editing(args, handle)
        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)

        val restored = viewModel(args, handle.afterProcessDeath())
        idle()
        assertTrue(restored.uiState.annotation!!.canUndo)
        restored.onUndo()
        idle()
        assertTrue(restored.uiState.annotation!!.ops.isEmpty())
        assertFalse(restored.uiState.annotation!!.canUndo)
        assertTrue("the edits file follows the undo", savedOps(restored).isEmpty())
        assertTrue(env.drafts.readEdits(restored.draftId, "screenshot.png") != null)
    }

    @Test
    fun `closing a restored editor before its steps loaded keeps them`() {
        // On the editor itself: in the ViewModel the queue runs the restored load right after the
        // listing that opens the editor, leaving no moment to close it in between.
        val io = QueueDispatcher()
        env = FakeEnvironment(temp.newFolder("restoring"), io)
        val draftId = "restored-draft"
        val image = File(env.drafts.dir(draftId).apply { mkdirs() }, "screenshot.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val steps = EditsJson.encode(EditHistory().add(EditOp.Stroke(PenColor.RED.argb, 4f, listOf(1f, 1f, 2f, 2f))))
        env.drafts.writeEdits(draftId, "screenshot.png", steps)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        var finished = false
        val editor = AnnotationEditor(
            draftId, DraftFile(AttachmentKind.SCREENSHOT, image, "image/png"), env, scope, scope, DirectDispatcher,
            AnnotationUiState("screenshot.png"), onSettingsChanged = {}, onFinished = { finished = true },
        )

        editor.load(restore = true)
        editor.onCancel() // the load is still queued
        io.runAll()
        idle()

        assertTrue(finished)
        assertEquals(steps, env.drafts.readEdits(draftId, "screenshot.png"))
        scope.cancel()
    }

    @Test
    fun `an attachment removed a moment before the tap does not open the editor`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        val shot = vm.uiState.attachments.single()
        vm.onRemoveAttachment(shot)
        vm.onEditAttachment(shot)
        idle()
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertNull(vm.uiState.annotation)
    }

    @Test
    fun `done writes the edit into the attachment and returns to the form with the new version`() {
        val vm = editing()
        val before = vm.uiState.attachments.single()
        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        vm.onAnnotationDone()
        idle()

        assertEquals(listOf(listOf<EditOp>(EditOp.Stroke(PenColor.RED.argb, 4f, listOf(1f, 1f, 2f, 2f)))), env.images.writes)
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertNull(vm.uiState.annotation)
        val after = vm.uiState.attachments.single()
        assertEquals("screenshot.png", after.fileName)
        assertTrue(after.file.readBytes().contentEquals(byteArrayOf(9, 9)))
        assertTrue("the strip must see a new version", after != before)
        assertNull(env.drafts.readEdits(vm.draftId, "screenshot.png"))
    }

    @Test
    fun `a double tap on done while the image is being written saves once`() {
        val io = QueueDispatcher()
        env = FakeEnvironment(temp.newFolder("held"), io)
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.BUG))
        io.runAll()
        idle()
        vm.onEditAttachment(vm.uiState.attachments.single())
        io.runAll()
        idle()

        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        vm.onAnnotationDone()
        assertTrue(vm.uiState.annotation!!.saving)
        vm.onAnnotationDone()
        vm.onStrokeDrawn(listOf(3f, 3f, 4f, 4f), 4f) // no edits while saving
        io.runAll()
        idle()

        assertEquals(1, env.images.writes.size)
        assertEquals(1, env.images.writes.single().size)
    }

    @Test
    fun `editing a gallery jpeg turns it into a png in the same place`() {
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        vm.onGalleryImagePicked(image(mime = "image/jpeg"))
        idle()
        val jpeg = vm.uiState.attachments.single()
        vm.onEditAttachment(jpeg)
        idle()
        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        vm.onAnnotationDone()
        idle()

        val png = vm.uiState.attachments.single()
        assertEquals(jpeg.fileName.substringBeforeLast('.') + ".png", png.fileName)
        assertEquals(AttachmentKind.GALLERY_IMAGE, png.kind)
        assertFalse(jpeg.file.exists())
    }

    @Test
    fun `a failed save stays in the editor, says so and keeps the original`() {
        val vm = editing()
        env.images.writeSucceeds = false
        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        vm.onAnnotationDone()
        idle()

        val editor = vm.uiState.annotation!!
        assertEquals(ReportStep.ANNOTATE, vm.uiState.step)
        assertTrue(editor.saveFailed)
        assertFalse(editor.saving)
        assertTrue(vm.uiState.attachments.single().file.readBytes().contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `done without changes goes back without rewriting the image`() {
        val vm = editing()
        vm.onAnnotationDone()
        idle()
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertTrue(env.images.writes.isEmpty())
    }

    @Test
    fun `cancel with changes asks first, discard drops them, and back works like cancel`() {
        val vm = editing()
        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        vm.onBack()
        assertTrue(vm.uiState.annotation!!.confirmingDiscard)
        vm.onAnnotationDiscardDismissed()
        assertFalse(vm.uiState.annotation!!.confirmingDiscard)

        vm.onAnnotationCancel()
        vm.onAnnotationDiscardConfirmed()
        idle()
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertNull(env.drafts.readEdits(vm.draftId, "screenshot.png"))
        assertTrue(env.images.writes.isEmpty())
    }

    @Test
    fun `an unreadable image says so instead of crashing and cancel leaves at once`() {
        env.images.loadable = false
        val vm = editing()
        assertTrue(vm.uiState.annotation!!.loadFailed)
        vm.onStrokeDrawn(listOf(1f, 1f, 2f, 2f), 4f)
        assertTrue(vm.uiState.annotation!!.ops.isEmpty())
        vm.onAnnotationCancel()
        assertEquals(ReportStep.FORM, vm.uiState.step)
    }

    @Test
    fun `a restored editor whose image is gone returns to the form`() {
        val handle = SavedStateHandle()
        val args = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG)
        val vm = editing(args, handle)
        env.drafts.remove(vm.draftId, "screenshot.png")

        val restored = viewModel(args, handle.afterProcessDeath())
        idle()
        assertEquals(ReportStep.FORM, restored.uiState.step)
        assertNull(restored.uiState.annotation)
    }

    @Test
    fun `the editor opens only from the form`() {
        val vm = viewModel(launchArgs(screenshot = screenshot(), reportType = ReportType.QUESTION))
        val shot = vm.uiState.attachments.single()
        vm.onCommentChanged("How?")
        vm.onPrimaryAction()
        idle()
        vm.onEditAttachment(shot)
        assertEquals(ReportStep.SENDING, vm.uiState.step)
        assertNull(vm.uiState.annotation)
    }

    @Test
    fun `disabling the SDK in the editor closes the screen with CANCEL`() {
        val vm = editing()
        env.config.value = env.config.value.copy(enabled = false)
        idle()
        assertEquals(DismissType.CANCEL, vm.closed.value)
    }

    private val host: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }

    /** The form with a comment, "Record screen" tapped and answered by the activity. */
    private fun recordingRequested(handle: SavedStateHandle = SavedStateHandle()): ReportDraftViewModel {
        env.canRecord.value = true
        val vm = viewModel(launchArgs(reportType = ReportType.BUG), handle)
        vm.onCommentChanged("Janky scroll")
        vm.onRecordScreen()
        vm.startRecording(host)
        return vm
    }

    private fun reopenedArgs(request: CaptureRequest, recording: File?, failed: Boolean = false) =
        FeedbackLaunchArgs(null, false, request.currentScreen, InvocationSource.MANUAL, request.reportType, screenshotRequested = false, resume = ResumeArgs(null, false, recording, failed))

    private fun reopenedWithRecording(request: CaptureRequest, recording: File?, failed: Boolean = false, store: ViewModelStore = ViewModelStore()): ReportDraftViewModel =
        viewModel(reopenedArgs(request, recording, failed), SavedStateHandle(request.state), store)

    /** The recording that started: the report stepped aside with this request. */
    private fun startedRecording(): CaptureRequest {
        recordingRequested()
        val (request, started, _) = env.recordings.single()
        started()
        return request
    }

    @Test
    fun `a granted recording steps the report aside with its draft and the reopened screen gets the video`() {
        val vm = recordingRequested()
        val (request, started, _) = env.recordings.single()
        assertSame(host, env.recordingHosts.single())
        assertEquals(vm.draftId, request.draftId)
        assertEquals("Janky scroll", request.state["feedbackkit.comment"])
        assertNull("the form stays while the user decides", vm.closed.value)

        started()
        assertEquals(DismissType.ADD_ATTACHMENT, vm.closed.value)
        assertFalse(vm.uiState.recordingStarting)
        assertFalse("recording mode is never saved as capture mode", captureFile(vm).exists())

        val video = temp.newFile("manual.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        val reopened = reopenedWithRecording(request, video)
        assertEquals(listOf(AttachmentKind.SCREEN_RECORDING), reopened.uiState.attachments.map { it.kind })
        assertEquals("Janky scroll", reopened.uiState.fields.comment)
        assertFalse("moved, not copied", video.exists())
        assertNull(reopened.uiState.notice)
    }

    @Test
    fun `a declined consent leaves the form as it was, without a notice`() {
        val vm = recordingRequested()
        env.recordings.single().third(true)
        assertFalse(vm.uiState.recordingStarting)
        assertNull(vm.uiState.notice)
        assertNull(vm.closed.value)
        assertTrue("the tile is back", vm.uiState.canRecordScreen)
        assertEquals("Janky scroll", vm.uiState.fields.comment)
    }

    @Test
    fun `a recording that cannot start says so`() {
        env.acceptRecording = false
        val vm = recordingRequested()
        assertFalse(vm.uiState.recordingStarting)
        assertEquals(AttachNotice.RECORDING_FAILED, vm.uiState.notice)

        env.acceptRecording = true
        vm.onRecordScreen()
        vm.startRecording(host)
        env.recordings.single().third(false)
        assertEquals(AttachNotice.RECORDING_FAILED, vm.uiState.notice)
        assertFalse(vm.uiState.recordingStarting)
    }

    @Test
    fun `an answer nobody asked for changes nothing`() {
        env.canRecord.value = true
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        vm.startRecording(host) // no "Record screen" before it
        assertTrue(env.recordings.isEmpty())
        vm.onRecordingStarted()
        vm.onRecordingNotStarted(refused = false)
        assertNull(vm.closed.value)
        assertNull(vm.uiState.notice)
    }

    @Test
    fun `the report steps aside once, however often the recording says it started`() {
        val vm = recordingRequested()
        val started = env.recordings.single().second
        started()
        started()
        vm.onRecordingNotStarted(refused = false)
        assertEquals(DismissType.ADD_ATTACHMENT, vm.closed.value)
        assertNull("no notice after the start", vm.uiState.notice)
    }

    @Test
    fun `a request the screen lost before answering it is answered by the next screen`() {
        env.canRecord.value = true
        val vm = viewModel(launchArgs(reportType = ReportType.BUG))
        vm.onRecordScreen()
        // The activity was recreated before it answered: the request is still there for the new one.
        assertTrue(vm.recordRequested())
        assertTrue(vm.recordRequested())
        vm.startRecording(host)
        assertFalse("answered once", vm.recordRequested())
        vm.startRecording(host)
        assertEquals(1, env.recordings.size)
    }

    @Test
    fun `while the consent is open the form holds still, but it can still be closed`() {
        env.ui { it.copy(reportTypes = setOf(ReportType.BUG, ReportType.QUESTION)) }
        env.canRecord.value = true
        val vm = viewModel()
        vm.onTypeSelected(ReportType.BUG)
        vm.onGalleryImagePicked(image())
        idle()
        vm.onCommentChanged("Janky scroll")
        vm.onRecordScreen()
        vm.startRecording(host)
        val image = vm.uiState.attachments.single()

        vm.onEditAttachment(image)
        vm.onRemoveAttachment(image)
        vm.onCommentChanged("typed under the dialog")
        vm.onBack() // back to the menu would change the type the pending recording reopens with
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertNull(vm.uiState.annotation)
        assertEquals(listOf(image), vm.uiState.attachments)
        assertTrue(image.file.exists())
        assertEquals("Janky scroll", vm.uiState.fields.comment)

        // Closing is not held: the coordinator drops the recording of a report closed during its consent.
        vm.onCancelRequested()
        vm.onCancelConfirmed()
        assertEquals(DismissType.CANCEL, vm.closed.value)
        env.recordings.single().second() // a start the coordinator would drop anyway
        assertEquals(DismissType.CANCEL, vm.closed.value)
    }

    @Test
    fun `a form restored after process death during the consent is an ordinary form, and the old answer goes nowhere`() {
        val handle = SavedStateHandle()
        recordingRequested(handle)
        val restored = viewModel(launchArgs(reportType = ReportType.BUG), handle.afterProcessDeath())
        assertFalse(restored.uiState.recordingStarting)
        assertTrue(restored.uiState.canRecordScreen)
        assertFalse("the consent is not asked again by itself", restored.recordRequested())
        assertEquals("Janky scroll", restored.uiState.fields.comment)

        // The dialog's answer reached a process that no longer waits for it: dropped.
        restored.onRecordingStarted()
        restored.onRecordingNotStarted(refused = false)
        assertNull(restored.closed.value)
        assertNull(restored.uiState.notice)
    }

    @Test
    fun `a lost recording and one over the limit come back as notices`() {
        val request = startedRecording()

        assertEquals(AttachNotice.RECORDING_FAILED, reopenedWithRecording(request, null, failed = true).uiState.notice)

        val draft = env.drafts.dir(request.draftId).apply { mkdirs() }
        (1..4).forEach { File(draft, "gallery-$it.png").writeBytes(byteArrayOf(1)) }
        val video = temp.newFile("late.mp4").apply { writeBytes(byteArrayOf(1)) }
        val full = reopenedWithRecording(request, video)
        assertEquals(AttachNotice.LIMIT_REACHED, full.uiState.notice)
        assertFalse("a refused recording is deleted", video.exists())
        assertTrue(full.uiState.attachments.none { it.kind == AttachmentKind.SCREEN_RECORDING })
    }

    @Test
    fun `a recording that vanished before the reopened screen took it says so`() {
        val request = startedRecording()
        val reopened = reopenedWithRecording(request, File(temp.root, "gone.mp4"))
        assertEquals(AttachNotice.RECORDING_FAILED, reopened.uiState.notice)
        assertTrue(reopened.uiState.attachments.isEmpty())
    }

    @Test
    fun `the reopened screen owns the video - its cancel deletes it with the draft`() {
        val request = startedRecording()
        val store = ViewModelStore()
        val video = temp.newFile("manual.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        val reopened = reopenedWithRecording(request, video, store = store)
        val adopted = reopened.uiState.attachments.single().file
        assertTrue(adopted.exists())

        reopened.onCancelRequested()
        reopened.onCancelConfirmed()
        assertEquals(DismissType.CANCEL, reopened.closed.value)
        store.clear()
        env.runDetachedBlocks()
        assertFalse(adopted.exists())
        assertFalse(env.drafts.dir(request.draftId).exists())
    }

    @Test
    fun `a reopened screen cleared before it took the video still takes it into the draft its cancel deletes`() {
        val request = startedRecording()
        val queue = QueueDispatcher()
        env = FakeEnvironment(env.drafts.dir(request.draftId).parentFile!!, io = queue)
        val store = ViewModelStore()
        val video = temp.newFile("manual.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        val reopened = reopenedWithRecording(request, video, store = store)
        reopened.onCancelRequested()
        reopened.onCancelConfirmed()
        store.clear() // before the draft's queue ran the adoption
        queue.runAll()
        assertFalse("the video left the recording cache", video.exists())
        // In the draft, where the cancel's queued delete (after this adoption on the same queue) takes it.
        assertTrue(env.drafts.dir(request.draftId).listFiles().orEmpty().any { it.name.startsWith("recording-") })
    }

    @Test
    fun `a video applied once is not taken again when the reopened screen is restored`() {
        val request = startedRecording()
        val video = temp.newFile("manual.mp4").apply { writeBytes(byteArrayOf(1, 2)) }
        val args = reopenedArgs(request, video)
        val handle = SavedStateHandle(request.state)
        val reopened = viewModel(args, handle)
        assertEquals(1, reopened.uiState.attachments.size)

        // Process death: the same intent comes back with the saved "applied" marker.
        val restored = viewModel(args, handle.afterProcessDeath())
        assertEquals(1, restored.uiState.attachments.size)
        assertNull(restored.uiState.notice)
    }

    @Test
    fun `a 30 MB recording goes into the submitted report whole`() {
        val request = startedRecording()
        val chunk = ByteArray(1024 * 1024) { it.toByte() }
        val video = temp.newFile("big.mp4").apply { outputStream().use { out -> repeat(30) { out.write(chunk) } } }
        val reopened = reopenedWithRecording(request, video)
        reopened.onPrimaryAction()
        idle()
        val sent = env.submitted.single().attachments.single()
        assertEquals(AttachmentKind.SCREEN_RECORDING, sent.kind)
        assertEquals("video/mp4", sent.mimeType)
        assertEquals(30L * 1024 * 1024, sent.file.length())
    }

    private class FakeImages : AnnotationImages {
        var loadable = true
        var writeSucceeds = true
        val writes = mutableListOf<List<EditOp>>()

        override fun load(file: File, maxWidth: Int, maxHeight: Int): LoadedImage? =
            if (loadable && file.isFile) LoadedImage(1000, 2000, null) else null

        // Not ImageBitmap(1, 2): Robolectric's Bitmap.createBitmap with a colour space returns null.
        override fun render(image: LoadedImage, ops: List<EditOp>): ImageBitmap = Bitmap.createBitmap(1, 2, Bitmap.Config.ARGB_8888).asImageBitmap()

        override fun writeEdited(file: File, ops: List<EditOp>, out: OutputStream): Boolean {
            writes += ops
            if (writeSucceeds) out.write(byteArrayOf(9, 9))
            return writeSucceeds
        }
    }

    /** Runs dispatched work on the spot, like Unconfined, but supports `limitedParallelism`. */
    private object DirectDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = block.run()
    }

    /** Holds dispatched work until [runAll]; [newestFirst] runs it in the worst order for ordering bugs. */
    private class QueueDispatcher(private val newestFirst: Boolean = false) : CoroutineDispatcher() {
        private val queue = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            queue.addLast(block)
        }

        fun runAll() {
            while (queue.isNotEmpty()) (if (newestFirst) queue.removeLast() else queue.removeFirst()).run()
        }
    }

    private fun autoClip(name: String = "auto.mp4"): File = temp.newFile(name).apply { writeBytes(byteArrayOf(1)) }

    private fun ReportDraftViewModel.kinds() = uiState.attachments.map { it.kind }

    @Test
    fun `the report opens at once with a placeholder and the automatic recording joins the draft when it comes`() {
        val token = env.autoClips.open()
        val args = launchArgs(screenshot = screenshot(), reportType = ReportType.BUG).copy(autoRecordingToken = token)
        val handle = SavedStateHandle()
        val vm = viewModel(args, handle)
        assertTrue(vm.uiState.autoClipPending)
        assertEquals(listOf(AttachmentKind.SCREENSHOT), vm.kinds())

        val clip = autoClip()
        env.autoClips.deliver(token, clip)
        idle()
        assertFalse(vm.uiState.autoClipPending)
        assertEquals(listOf(AttachmentKind.SCREENSHOT, AttachmentKind.AUTO_SCREEN_RECORDING), vm.kinds())
        assertFalse("moved, not copied", clip.exists())
        assertNull(vm.uiState.notice)

        val restored = viewModel(args, handle.afterProcessDeath())
        assertFalse("resolved: not awaited again", restored.uiState.autoClipPending)
        assertEquals(2, restored.uiState.attachments.size)
    }

    @Test
    fun `a clip that never comes drops the placeholder quietly`() {
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token))
        env.autoClips.deliver(token, null)
        idle()
        assertFalse(vm.uiState.autoClipPending)
        assertNull("the user never asked for this recording", vm.uiState.notice)
        assertTrue(vm.uiState.attachments.isEmpty())
    }

    @Test
    fun `a clip that never comes leaves the notice already shown as it is`() {
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token))
        vm.onGalleryUnavailable()
        env.autoClips.deliver(token, null)
        idle()
        assertFalse(vm.uiState.autoClipPending)
        assertEquals(AttachNotice.IMAGE_FAILED, vm.uiState.notice)
    }

    @Test
    fun `a clip that cannot join the draft drops the placeholder quietly`() {
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token))
        env.autoClips.deliver(token, File(temp.root, "vanished.mp4"))
        idle()
        assertFalse(vm.uiState.autoClipPending)
        assertNull(vm.uiState.notice)
        assertTrue(vm.uiState.attachments.isEmpty())
    }

    @Test
    fun `closing the report while its clip is prepared gives the clip up`() {
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token))
        vm.onCancelRequested()
        assertEquals(DismissType.CANCEL, vm.closed.value)
        val clip = autoClip()
        assertFalse(env.autoClips.deliver(token, clip))
        assertFalse("deleted, nobody will attach it", clip.exists())
    }

    @Test
    fun `sending does not wait for the clip and gives it up once the report is queued`() {
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.FEEDBACK).copy(autoRecordingToken = token))
        vm.onCommentChanged("Dark mode please")
        vm.onPrimaryAction()
        idle()
        assertEquals(1, env.submitted.size)
        assertTrue(env.submitted.single().attachments.none { it.kind == AttachmentKind.AUTO_SCREEN_RECORDING })
        env.result.complete("report-1")
        idle()
        assertEquals(ReportStep.SUCCESS, vm.uiState.step)
        assertFalse(vm.uiState.autoClipPending)
        val clip = autoClip()
        assertFalse(env.autoClips.deliver(token, clip))
        assertFalse(clip.exists())
    }

    @Test
    fun `the clip in preparation holds one of the four places and frees it once removed`() {
        val handle = SavedStateHandle(mapOf("feedbackkit.draftId" to "d-auto"))
        val draft = env.drafts.dir("d-auto").apply { mkdirs() }
        (1..3).forEach { File(draft, "gallery-$it.png").writeBytes(byteArrayOf(1)) }
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token), handle)
        assertEquals(3, vm.uiState.attachments.size)
        assertFalse(vm.uiState.canAddGalleryImage)
        assertFalse(vm.uiState.canAddExtraScreenshot)
        assertTrue(vm.uiState.attachmentLimitReached)

        // A picker answer already on its way when the place was taken: refused, the clip keeps its place.
        vm.onGalleryImagePicked(image())
        idle()
        assertEquals(AttachNotice.LIMIT_REACHED, vm.uiState.notice)
        assertEquals(3, vm.uiState.attachments.size)

        env.autoClips.deliver(token, autoClip())
        idle()
        assertEquals(4, vm.uiState.attachments.size)
        assertFalse(vm.uiState.canAddGalleryImage)
        vm.onRemoveAttachment(vm.uiState.attachments.single { it.kind == AttachmentKind.AUTO_SCREEN_RECORDING })
        assertTrue(vm.uiState.canAddGalleryImage)
    }

    @Test
    fun `a report restored in a new process drops the placeholder of a clip that went with the old one`() {
        val token = env.autoClips.open()
        val args = launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token)
        val handle = SavedStateHandle()
        assertTrue(viewModel(args, handle).uiState.autoClipPending)

        env.autoClips = PendingClips({ it.delete() }, env.logger) // the new process knows no token
        val restored = viewModel(args, handle.afterProcessDeath())
        assertFalse(restored.uiState.autoClipPending)
        assertNull(restored.uiState.notice)
    }

    @Test
    fun `a send that fails keeps the clip coming, and it joins the form it returns to`() {
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.FEEDBACK).copy(autoRecordingToken = token))
        vm.onCommentChanged("Dark mode please")
        env.submitFailure = IllegalStateException("queue full")
        vm.onPrimaryAction()
        idle()
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertTrue(vm.uiState.submitFailed)
        assertTrue("still coming", vm.uiState.autoClipPending)

        val clip = autoClip()
        assertTrue(env.autoClips.deliver(token, clip))
        idle()
        assertEquals(listOf(AttachmentKind.AUTO_SCREEN_RECORDING), vm.kinds())
    }

    @Test
    fun `a report back from a step-aside whose draft already holds the clip reserves no place for it`() {
        val handle = SavedStateHandle(mapOf("feedbackkit.draftId" to "d-back"))
        val draft = env.drafts.dir("d-back").apply { mkdirs() }
        (1..2).forEach { File(draft, "gallery-$it.png").writeBytes(byteArrayOf(1)) }
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token), handle)
        vm.onAddExtraScreenshot()
        val request = env.captures.single()
        // The clip comes while the report is stepped aside; the screen that left takes it into the draft.
        env.autoClips.deliver(token, autoClip())
        idle()
        assertTrue(File(draft, DraftStore.AUTO_RECORDING_FILE_NAME).exists())

        // The reopened screen still carries the token: two items and the clip leave room for the shot.
        val shot = temp.newFile("extra.png").apply { writeBytes(byteArrayOf(1)) }
        val reopened = reopened(request, shot)
        assertEquals(4, reopened.uiState.attachments.size)
        assertTrue(reopened.uiState.attachments.any { it.kind == AttachmentKind.EXTRA_SCREENSHOT })
        assertNull(reopened.uiState.notice)
        assertFalse(reopened.uiState.autoClipPending)
    }

    @Test
    fun `a report stepping aside for a screenshot takes its pending clip along`() {
        val token = env.autoClips.open()
        val vm = viewModel(launchArgs(reportType = ReportType.BUG).copy(autoRecordingToken = token))
        vm.onAddExtraScreenshot()
        assertEquals(DismissType.ADD_ATTACHMENT, vm.closed.value)
        val reopened = reopened(env.captures.single(), extra = null)
        assertTrue(reopened.uiState.autoClipPending)

        // Whichever screen takes it, the clip ends up in the one draft, and the reopened screen shows it.
        env.autoClips.deliver(token, autoClip())
        idle()
        assertFalse(reopened.uiState.autoClipPending)
        assertEquals(listOf(AttachmentKind.AUTO_SCREEN_RECORDING), reopened.kinds())
        assertNull(reopened.uiState.notice)
    }

    private class FakeEnvironment(draftRoot: File, io: CoroutineDispatcher = DirectDispatcher) : ReportEnvironment {
        override val config = MutableStateFlow(Config(enabled = true, logLevel = LogLevel.NONE, userEmail = "me@example.com", userName = null))
        override val logger = SdkLogger(LogLevel.NONE)
        override val drafts = DraftStore({ draftRoot }, logger, io = io)
        override val content = FakeContent()
        override val images = FakeImages()
        override val canRecord = MutableStateFlow(false)
        override var autoClips = PendingClips({ it.delete() }, logger)
        val submitted = mutableListOf<ReportDraft>()
        val result = CompletableDeferred<String?>()
        var submitFailure: Exception? = null
        private val detached = mutableListOf<suspend () -> Unit>()
        val captures = mutableListOf<CaptureRequest>()
        var acceptCapture = true

        override fun beginExtraCapture(request: CaptureRequest): Boolean {
            if (acceptCapture) captures += request
            return acceptCapture
        }

        val recordings = mutableListOf<Triple<CaptureRequest, () -> Unit, (Boolean) -> Unit>>()
        val recordingHosts = mutableListOf<Activity>()
        var acceptRecording = true

        override fun beginRecording(host: Activity, request: CaptureRequest, onStarted: () -> Unit, onNotStarted: (Boolean) -> Unit): Boolean {
            if (acceptRecording) {
                recordings += Triple(request, onStarted, onNotStarted)
                recordingHosts += host
            }
            return acceptRecording
        }

        override fun submit(draft: ReportDraft): Deferred<String?> {
            submitFailure?.let { throw it }
            submitted += draft
            return result
        }

        override fun runDetached(block: suspend () -> Unit) {
            detached += block
        }

        override fun displaySize(): Pair<Int, Int> = 1080 to 2400

        fun runDetachedBlocks() = runBlocking {
            val blocks = detached.toList()
            detached.clear()
            blocks.forEach { it() }
        }

        fun ui(transform: (ReportUiConfig) -> ReportUiConfig) {
            config.value = config.value.copy(ui = transform(config.value.ui))
        }
    }

    private class FakeContent : ContentReader {
        val entries = mutableMapOf<Uri, Pair<String?, ByteArray?>>()
        override fun mimeType(uri: Uri): String? = entries[uri]?.first
        override fun open(uri: Uri): InputStream? = entries[uri]?.second?.inputStream()
    }

    private val crash = ProactiveInfo(ProactiveTrigger.CRASH, "2026-09-28T10:00:00Z", "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom")

    private fun proactiveArgs() =
        FeedbackLaunchArgs(null, false, "com.example.HostActivity", InvocationSource.PROACTIVE, ReportType.FRUSTRATING_EXPERIENCE, screenshotRequested = false, proactive = crash)

    @Test
    fun `a proactive launch opens on its prompt with nothing to send yet`() {
        val vm = viewModel(proactiveArgs())
        assertEquals(ReportStep.PROACTIVE_PROMPT, vm.uiState.step)
        assertEquals(ReportType.FRUSTRATING_EXPERIENCE, vm.uiState.type)
        assertFalse(vm.uiState.primaryEnabled)
        assertFalse(vm.uiState.screenshotUnavailable)
        vm.onPrimaryAction()
        vm.onCommentChanged("typed over the prompt")
        assertTrue(env.submitted.isEmpty())
        assertEquals("", vm.uiState.fields.comment)
    }

    @Test
    fun `tell us opens the form without the extended step, and the report carries the proactive block`() {
        env.ui { it.copy(extendedState = ExtendedBugReport.State.ENABLED_WITH_REQUIRED_FIELDS) }
        val vm = viewModel(proactiveArgs())
        vm.onProactiveAccepted()
        assertEquals(ReportStep.FORM, vm.uiState.step)
        assertFalse(vm.uiState.canGoBack)
        assertEquals(PrimaryAction.SEND, vm.uiState.primaryAction)
        vm.onCommentChanged("It closed when I saved")
        vm.onPrimaryAction()
        idle()
        val draft = env.submitted.single()
        assertEquals(ReportType.FRUSTRATING_EXPERIENCE, draft.type)
        assertNull(draft.extended)
        assertEquals(crash, draft.proactive)
        assertTrue(draft.attachments.isEmpty())
    }

    @Test
    fun `not now, back and back from the form all close as a cancel of the proactive type`() {
        val declined = viewModel(proactiveArgs())
        declined.onProactiveDeclined()
        assertEquals(DismissType.CANCEL, declined.closed.value)
        assertEquals(DismissType.CANCEL to ReportType.FRUSTRATING_EXPERIENCE, declined.dismissInfo())

        val backed = viewModel(proactiveArgs())
        backed.onBack()
        assertEquals(DismissType.CANCEL, backed.closed.value)

        val fromForm = viewModel(proactiveArgs())
        fromForm.onProactiveAccepted()
        fromForm.onBack()
        assertEquals("back from the form never returns to the prompt", DismissType.CANCEL, fromForm.closed.value)
    }

    @Test
    fun `the prompt and its form come back after process death, the proactive block with them`() {
        val promptHandle = SavedStateHandle()
        viewModel(proactiveArgs(), promptHandle)
        assertEquals(ReportStep.PROACTIVE_PROMPT, viewModel(proactiveArgs(), promptHandle.afterProcessDeath()).uiState.step)

        val formHandle = SavedStateHandle()
        val first = viewModel(proactiveArgs(), formHandle)
        first.onProactiveAccepted()
        first.onCommentChanged("It closed on save")
        val restored = viewModel(proactiveArgs(), formHandle.afterProcessDeath())
        assertEquals(ReportStep.FORM, restored.uiState.step)
        assertEquals("It closed on save", restored.uiState.fields.comment)
        restored.onPrimaryAction()
        idle()
        assertEquals(crash, env.submitted.single().proactive)
    }

    @Test
    fun `the proactive form takes gallery images but never steps aside for a screenshot or a recording`() {
        env.canRecord.value = true
        val vm = viewModel(proactiveArgs())
        vm.onProactiveAccepted()
        assertTrue(vm.uiState.canAddGalleryImage)
        assertFalse(vm.uiState.canAddExtraScreenshot)
        assertFalse(vm.uiState.canRecordScreen)
        vm.onAddExtraScreenshot()
        vm.onRecordScreen()
        assertTrue(env.captures.isEmpty())
        assertFalse(vm.recordRequested())
        assertNull(vm.closed.value)
    }

    @Test
    fun `the prompt's answers change nothing on any other step`() {
        val bug = viewModel(launchArgs(reportType = ReportType.BUG))
        bug.onProactiveAccepted()
        bug.onProactiveDeclined()
        assertEquals(ReportStep.FORM, bug.uiState.step)
        assertNull(bug.closed.value)

        val prompt = viewModel(proactiveArgs())
        prompt.onProactiveAccepted()
        prompt.onProactiveDeclined()
        assertEquals(ReportStep.FORM, prompt.uiState.step)
        assertNull("not now is the prompt's own answer", prompt.closed.value)
    }
}
