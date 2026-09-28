package io.github.feedbacklib.android.internal.ui

import android.app.Activity
import android.net.Uri
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.annotate.AnnotationTool
import io.github.feedbacklib.android.internal.annotate.PenColor
import io.github.feedbacklib.android.internal.annotate.PenWidth
import io.github.feedbacklib.android.internal.core.Config
import io.github.feedbacklib.android.internal.core.enumOrNull
import io.github.feedbacklib.android.internal.invoke.CaptureRequest
import io.github.feedbacklib.android.internal.invoke.CaptureRequestJson
import io.github.feedbacklib.android.internal.report.AddImageResult
import io.github.feedbacklib.android.internal.report.DraftFile
import io.github.feedbacklib.android.internal.report.isVideo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The report draft (spec §6): survives rotation as a ViewModel and process death through
 * [SavedStateHandle]; its files live in the [ReportEnvironment.drafts] directory. Main thread.
 *
 * Every disk operation on the draft runs on
 * [io.github.feedbacklib.android.internal.report.DraftStore.queue] of its id, in the order it was asked
 * for — the one queue every screen of this draft shares: an attachment the user removed is gone
 * before the files of a later send are listed, and the screenshot is adopted before anything reads
 * or deletes the draft.
 *
 * Auto Screen Recording's clip (spec §7) comes after the screen opened; [AutoClipAttachment] waits for it.
 */
internal class ReportDraftViewModel(
    private val handle: SavedStateHandle,
    private val args: FeedbackLaunchArgs,
    private val env: ReportEnvironment,
    private val emails: EmailValidator,
    private val successDisplayMillis: Long = SUCCESS_DISPLAY_MILLIS,
    /** Where the editor redraws its preview. */
    private val cpu: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel(), ReportActions {

    val draftId: String = handle.get<String>(KEY_DRAFT_ID) ?: UUID.randomUUID().toString().also { handle[KEY_DRAFT_ID] = it }

    /** Disk writes that must happen even if this ViewModel is cleared right after asking for them. */
    private val detached = CoroutineScope(
        SupervisorJob() + CoroutineExceptionHandler { _, e -> env.logger.e("Report draft file operation failed", e) },
    )

    private val autoClip = AutoClipAttachment(handle, draftId, env, detached)

    /** No saved step (or one this version does not know): a new draft rather than a restored one. */
    private val firstStart: Boolean = enumOrNull<ReportStep>(handle.get<String>(KEY_STEP)) == null

    private var config: Config by mutableStateOf(env.config.value)
    private var draft: DraftState by mutableStateOf(restoreOrStart(env.config.value))
    private var canRecord: Boolean by mutableStateOf(env.canRecord.value)
    private val closeRequest = MutableStateFlow<DismissType?>(null)

    /** The queued submission; `null` before a send reached the submitter and after a failed one. */
    private var submission: Deferred<String?>? = null

    private val pickImage = Channel<Unit>(Channel.CONFLATED)

    /** One element per "Add image" the screen should answer with the photo picker (FeedbackActivity does). */
    val pickImageRequests: Flow<Unit> = pickImage.receiveAsFlow()

    /**
     * "Record screen" asked for and not yet handed to [startRecording]. A state, not a one-shot event:
     * an activity recreated before it answered (the element of a channel would be lost with the
     * cancelled collector) finds the request still here. Not saved: after process death there is no
     * recording to wait for, and the consent is never asked again unprompted.
     */
    private val recordingAsked = MutableStateFlow(false)

    /** Emits while "Record screen" waits for the screen to open the consent over itself (FeedbackActivity). */
    val recordScreenRequests: Flow<Unit> = recordingAsked.filter { it }.map { }

    /** The annotation editor while ReportStep.ANNOTATE is open; its steps are in the draft, its settings in [handle]. */
    private var editor: AnnotationEditor? by mutableStateOf(null)

    /** What the screens draw. Snapshot state, so text fields see their own edits synchronously. */
    val uiState: ReportUiState by derivedStateOf { draft.toUiState(config.ui, emails, canRecord).copy(annotation = editor?.state) }

    /** Non-null once the screen should close, with the reason OnDismissCallback will get. */
    val closed: StateFlow<DismissType?> = closeRequest

    init {
        // Killed while thanking the user: the report was queued, so just close.
        if (draft.step == ReportStep.SUCCESS) closeRequest.value = DismissType.SUBMIT
        viewModelScope.launch {
            env.config.collect {
                config = it
                if (!it.enabled) onSdkDisabled()
            }
        }
        viewModelScope.launch { env.canRecord.collect { canRecord = it } }
        loadAttachments()
        awaitAutoClip()
    }

    override fun onTypeSelected(type: ReportType) =
        update { if (it.step == ReportStep.MENU) it.copy(step = ReportStep.FORM, type = type) else it }

    override fun onProactiveAccepted() =
        update { if (it.step == ReportStep.PROACTIVE_PROMPT) it.copy(step = ReportStep.FORM) else it }

    override fun onProactiveDeclined() {
        if (draft.step == ReportStep.PROACTIVE_PROMPT) close(DismissType.CANCEL)
    }

    override fun onEmailChanged(value: String) = updateFields { it.copy(email = value.capped()) }

    override fun onCommentChanged(value: String) = updateFields { it.copy(comment = value.capped()) }

    override fun onStepsChanged(value: String) = updateFields { it.copy(steps = value.capped()) }

    override fun onActualChanged(value: String) = updateFields { it.copy(actual = value.capped()) }

    override fun onExpectedChanged(value: String) = updateFields { it.copy(expected = value.capped()) }

    override fun onRemoveAttachment(file: DraftFile) {
        // While sending, the submitter copies the listed files outside the serial queue: hands off.
        if (!editable || draft.recordingStarting) return
        update { it.copy(attachments = it.attachments - file, notice = null) }
        // Queued now, so it runs before the file listing of any send the user starts after this.
        detached.launch(env.drafts.queue(draftId)) { env.drafts.remove(draftId, file.fileName) }
    }

    override fun onAddGalleryImage() {
        if (draft.step != ReportStep.FORM || closeRequest.value != null || !uiState.canAddGalleryImage || draft.recordingStarting) return
        update { it.copy(notice = null) }
        pickImage.trySend(Unit)
    }

    /**
     * "Add screenshot" (spec §6): the draft is handed to capture mode and the screen closes with
     * ADD_ATTACHMENT, keeping its files; Capture or Cancel reopens it with the same draft id and
     * state. Capture mode begins before the close is even requested, in this same main-thread turn,
     * so no invocation can slip in between. Bug reporting switched off does not refuse it (this
     * report is already open); refused only with FeedbackKit disabled or stopped, the report closes
     * as a cancel — as onSdkDisabled would close it anyway — and its draft goes the usual way.
     */
    override fun onAddExtraScreenshot() {
        val type = draft.type ?: return
        if (draft.step != ReportStep.FORM || closeRequest.value != null || !uiState.canAddExtraScreenshot || draft.recordingStarting) return
        update { it.copy(notice = null, submitFailed = false) }
        val request = CaptureRequest(draftId, type, resumeState(), args.currentScreen)
        val begun = try {
            env.beginExtraCapture(request)
        } catch (e: Exception) {
            env.logger.e("Could not start the screenshot capture mode", e)
            false
        }
        if (!begun) {
            env.logger.w("The screenshot capture mode could not start; closing the report")
            close(DismissType.CANCEL)
            return
        }
        // Beside the draft, so a new process can bring capture mode back (spec §6).
        val json = CaptureRequestJson.encode(request)
        detached.launch(env.drafts.queue(draftId)) { env.drafts.writeCaptureState(draftId, json) }
        close(DismissType.ADD_ATTACHMENT)
    }

    /**
     * "Record screen" (spec §7): asks FeedbackActivity to open the consent over itself. Until the
     * recording answers, the form holds still (DraftState.recordingStarting): the tile is off, and
     * attachments, text, the step and the send wait. The dialog covers the form anyway, the reopened
     * report starts from the state taken at [startRecording], and a second answer must never arrive.
     * Closing the report stays possible: the coordinator then drops the recording (spec §7).
     */
    override fun onRecordScreen() {
        if (draft.type == null || draft.step != ReportStep.FORM || closeRequest.value != null) return
        if (!uiState.canRecordScreen || draft.recordingStarting) return
        update { it.copy(notice = null, submitFailed = false, recordingStarting = true) }
        recordingAsked.value = true
    }

    /**
     * FeedbackActivity answers [recordScreenRequests] with itself: the consent opens over it (spec §7).
     * The callbacks hold this ViewModel, not the activity, so a rotation under the dialog changes
     * nothing. Recording mode writes no capture.json: a new process could only revive it as capture
     * mode, and a recording does not outlive its process anyway. Main thread.
     */
    fun startRecording(host: Activity) {
        if (!recordingAsked.value) return
        recordingAsked.value = false
        val type = draft.type
        if (!draft.recordingStarting || type == null || draft.step != ReportStep.FORM || closeRequest.value != null) {
            update { it.copy(recordingStarting = false) } // closing: nothing to record for
            return
        }
        val request = CaptureRequest(draftId, type, resumeState(), args.currentScreen)
        val begun = try {
            env.beginRecording(host, request, onStarted = ::onRecordingStarted, onNotStarted = ::onRecordingNotStarted)
        } catch (e: Exception) {
            env.logger.e("Could not start the screen recording", e)
            false
        }
        if (!begun) onRecordingNotStarted(refused = false)
    }

    /** Recording: the report steps aside with ADD_ATTACHMENT, keeping its draft, and Stop takes over (spec §7). */
    fun onRecordingStarted() {
        if (!awaitingRecording) return
        update { it.copy(recordingStarting = false) }
        close(DismissType.ADD_ATTACHMENT)
    }

    /** Declined (the user chose, so no notice) or failed, which the notice says. */
    fun onRecordingNotStarted(refused: Boolean) {
        if (!awaitingRecording) return
        update { it.copy(recordingStarting = false, notice = if (refused) null else AttachNotice.RECORDING_FAILED) }
    }

    /** Handed to the recorder and not answered yet: only then does an answer mean anything. */
    private val awaitingRecording: Boolean
        get() = draft.recordingStarting && !recordingAsked.value

    /** What the reopened screen starts from: the draft's own saved keys, never the editor's. */
    private fun resumeState(): Map<String, Any?> = RESUME_KEYS.associateWith { handle.get<Any?>(it) }

    /**
     * The photo picker's answer (spec §6); `null` when the user backed out. Copied on the draft's
     * queue. Applies the result to the current in-memory attachments rather than re-listing the
     * draft from disk: while a large copy is still running on the queue, the user may have already
     * removed another attachment (an eager, synchronous update, see [onRemoveAttachment]) whose
     * queued disk delete has not run yet — a fresh listing taken now would still see that file and
     * bring it back into the strip.
     */
    fun onGalleryImagePicked(uri: Uri?) {
        if (uri == null || !editable || closeRequest.value != null) return
        val clipPending = draft.autoClipPending
        viewModelScope.launch {
            val result = env.drafts.onQueue(draftId) {
                val maxAttachments = autoClip.maxUserAttachments(this, clipPending)
                addImage(
                    draftId,
                    mimeType = { env.content.mimeType(uri) },
                    open = { env.content.open(uri) },
                    maxAttachments = maxAttachments,
                    maxBytes = AttachmentRules.GALLERY_MAX_BYTES,
                )
            }
            update {
                it.copy(
                    attachments = if (result is AddImageResult.Added) it.attachments + result.file else it.attachments,
                    notice = result.toNotice(),
                )
            }
        }
    }

    override fun onEditAttachment(file: DraftFile) {
        if (draft.step != ReportStep.FORM || closeRequest.value != null || editor != null || draft.recordingStarting) return
        val target = draft.attachments.firstOrNull { it.fileName == file.fileName }?.takeIf { !it.kind.isVideo } ?: return
        update { it.copy(step = ReportStep.ANNOTATE, notice = null) }
        openEditor(target, restore = false)
    }

    override fun onToolSelected(tool: AnnotationTool) { editor?.onToolSelected(tool) }

    override fun onPenColorSelected(color: PenColor) { editor?.onPenColorSelected(color) }

    override fun onPenWidthSelected(width: PenWidth) { editor?.onPenWidthSelected(width) }

    override fun onStrokeDrawn(points: List<Float>, width: Float) { editor?.onStrokeDrawn(points, width) }

    override fun onBlurDrawn(left: Float, top: Float, right: Float, bottom: Float) { editor?.onBlurDrawn(left, top, right, bottom) }

    override fun onMagnifierPlaced(centerX: Float, centerY: Float) { editor?.onMagnifierPlaced(centerX, centerY) }

    override fun onMagnifierChanged(index: Int, centerX: Float, centerY: Float, radius: Float) {
        editor?.onMagnifierChanged(index, centerX, centerY, radius)
    }

    override fun onUndo() { editor?.onUndo() }

    override fun onAnnotationDone() { editor?.onDone() }

    override fun onAnnotationCancel() { editor?.onCancel() }

    override fun onAnnotationDiscardConfirmed() { editor?.onDiscardConfirmed() }

    override fun onAnnotationDiscardDismissed() { editor?.onDiscardDismissed() }

    /** Neither the photo picker nor a document picker exists on this device. */
    fun onGalleryUnavailable() = update { it.copy(notice = AttachNotice.IMAGE_FAILED) }

    override fun onPrimaryAction() {
        val ui = uiState
        val rules = ui.rules ?: return
        // A stray tap after CANCEL, before the activity finishes, must not queue a report.
        if (!ui.primaryEnabled || closeRequest.value != null || draft.recordingStarting) return
        when (ui.step) {
            ReportStep.FORM -> if (rules.hasExtendedStep) update { it.copy(step = ReportStep.EXTENDED) } else send(rules)
            ReportStep.EXTENDED -> send(rules)
            ReportStep.MENU, ReportStep.SENDING, ReportStep.SUCCESS, ReportStep.ANNOTATE, ReportStep.PROACTIVE_PROMPT -> Unit
        }
    }

    override fun onBack() {
        // Back in the editor is its cancel: it asks before dropping unsaved steps.
        if (draft.step == ReportStep.ANNOTATE) {
            editor?.onCancel()
            return
        }
        when (val action = ReportNavigation.back(draft.step, draft.menuShown)) {
            // Not while the consent is open: the pending recording reopens the form with this type.
            is BackAction.GoTo -> if (!draft.recordingStarting) update { it.copy(step = action.step, submitFailed = false) }
            BackAction.RequestCancel -> onCancelRequested()
            BackAction.Ignore -> Unit
        }
    }

    override fun onCancelRequested() {
        val current = draft
        // The editor has its own close button (onAnnotationCancel); this one cancels the report.
        if (current.step == ReportStep.SENDING || current.step == ReportStep.SUCCESS || current.step == ReportStep.ANNOTATE) return
        if (ReportNavigation.needsCancelConfirmation(current.fields, current.emailPrefill)) {
            update { it.copy(confirmingCancel = true) }
        } else {
            close(DismissType.CANCEL)
        }
    }

    override fun onCancelConfirmed() = close(DismissType.CANCEL)

    override fun onCancelDismissed() = update { it.copy(confirmingCancel = false) }

    /** What OnDismissCallback receives when the screen closes; CANCEL when nothing decided otherwise. */
    fun dismissInfo(): Pair<DismissType, ReportType> =
        (closeRequest.value ?: DismissType.CANCEL) to ReportNavigation.dismissReportType(draft.type, ReportNavigation.menuTypes(config.ui))

    /**
     * The activity is finishing. When nothing here asked for it (the host cleared the task, called
     * finishAffinity(), or the screen failed to open), it is a cancel: the draft goes with the screen.
     */
    fun onScreenFinished() {
        if (closeRequest.value != null) return
        closeRequest.value = DismissType.CANCEL
        abandonAutoClip()
    }

    /**
     * Never called on rotation. The draft is deleted only when the screen really closed (submit or
     * cancel) or its report is queued. An activity destroyed without finishing ("Don't keep
     * activities", reclaimed by the system) comes back from the saved state and still needs its
     * files; a draft that never comes back is left to [io.github.feedbacklib.android.internal.report.DraftStore.purgeStale].
     */
    override fun onCleared() {
        if (closeRequest.value == DismissType.ADD_ATTACHMENT) return // capture mode keeps the draft for the reopened screen
        val pending = submission
        if (closeRequest.value == null && pending == null) return
        val id = draftId
        val drafts = env.drafts
        val logger = env.logger
        env.runDetached {
            // The store copies the draft's files while queueing: wait for that before deleting them.
            try {
                pending?.await()
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                logger.d("The report submission did not finish normally; deleting its draft anyway", e)
            }
            drafts.onQueue(id) { delete(id) }
        }
    }

    private fun send(rules: FormRules) {
        val current = draft
        val type = current.type ?: return
        if (closeRequest.value != null) return
        // SENDING is set before anything suspends and left only by this coroutine's last statement,
        // so the step check in onPrimaryAction is what makes a double tap send once.
        update { it.copy(step = ReportStep.SENDING, submitFailed = false) }
        viewModelScope.launch {
            val id = try {
                val files = env.drafts.onQueue(draftId) { attachments(draftId) }
                val pending = env.submit(current.toReportDraft(type, rules, config.userEmail, files, args.currentScreen, args.proactive))
                submission = pending
                pending.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive() // this screen went away: propagate
                env.logger.w("The report submission was cancelled", e)
                null
            } catch (e: Exception) {
                env.logger.e("The report could not be submitted", e)
                null
            }
            if (id != null) {
                // The send did not wait for a clip still being made; queued without it, it is given up
                // (spec §7). A failed send keeps it coming for the form it returns to.
                abandonAutoClip()
                update { it.copy(step = ReportStep.SUCCESS) }
                delay(successDisplayMillis)
                close(DismissType.SUBMIT)
            } else {
                submission = null
                update { it.copy(step = ReportNavigation.stepBeforeSend(rules), submitFailed = true) }
                // Refused because the host disabled the SDK meanwhile: a retry would fail the same way.
                if (!config.enabled) onSdkDisabled()
            }
        }
    }

    private fun loadAttachments() {
        viewModelScope.launch {
            // The host may have switched the invocation screenshot off between the capture and now.
            val keepScreenshot = config.ui.attachmentTypes.initialScreenshot
            // Back from capture or recording mode, and not yet applied (a restore of this screen must not apply it twice).
            val resume = args.resume?.takeIf { handle.get<Boolean>(KEY_RESUME_APPLIED) != true }
            val clipPending = draft.autoClipPending
            // Detached: what was handed over (a recording is megabytes) reaches the draft even if this
            // screen is cleared first, so the draft's delete queued after it takes that too.
            // An async failure never reaches the scope's handler, and nobody awaits it once this
            // screen is cleared: log it here.
            val (files, extra, recorded) = detached.async(env.drafts.queue(draftId)) {
                try {
                    with(env.drafts) {
                        // A no-op after the first time: the source is gone once adopted or dropped.
                        val shot = args.screenshot
                        if (shot != null) {
                            if (keepScreenshot) adoptScreenshot(draftId, shot) else shot.delete()
                        }
                        var extra: AddImageResult? = null
                        var recorded: AddImageResult? = null
                        if (resume != null) {
                            val maxAttachments = autoClip.maxUserAttachments(this, clipPending)
                            clearCaptureState(draftId) // the saved capture mode is spent
                            // A shot or video that vanished before this (cache cleared) comes back as Failed.
                            extra = resume.extraScreenshot?.let { adoptExtraScreenshot(draftId, it, maxAttachments) }
                            recorded = resume.recording?.let { adoptRecording(draftId, it, maxAttachments) }
                        }
                        Triple(attachments(draftId), extra, recorded)
                    }
                } catch (e: Exception) {
                    if (e !is CancellationException) env.logger.e("Report draft attachments failed to load", e)
                    throw e
                }
            }.await()
            if (resume != null) handle[KEY_RESUME_APPLIED] = true
            update { state ->
                // A screenshot taken but not moved into the draft (the capture file vanished, the
                // move failed) is unavailable. Only on a first start: after a restore the draft
                // lacks it because the user removed it, and the saved flag already says the rest.
                val lost = firstStart && keepScreenshot && args.screenshot != null && files.none { it.kind == AttachmentKind.SCREENSHOT }
                val notice = when {
                    resume == null -> state.notice
                    resume.extraScreenshotFailed -> AttachNotice.SCREENSHOT_UNAVAILABLE
                    resume.recordingFailed -> AttachNotice.RECORDING_FAILED
                    extra == null && recorded == null -> state.notice // Cancel: nothing was taken
                    extra == AddImageResult.LimitReached || recorded == AddImageResult.LimitReached -> AttachNotice.LIMIT_REACHED
                    // Taken, but gone or not movable into the draft: for the user it is unavailable.
                    extra != null && extra !is AddImageResult.Added -> AttachNotice.SCREENSHOT_UNAVAILABLE
                    recorded != null && recorded !is AddImageResult.Added -> AttachNotice.RECORDING_FAILED
                    else -> null
                }
                state.copy(attachments = files, screenshotUnavailable = state.screenshotUnavailable || lost, notice = notice)
            }
            // Restored in the editor (process death): reopen it on the same file, or fall back to
            // the form when that file is gone.
            if (draft.step == ReportStep.ANNOTATE && editor == null) {
                val target = files.firstOrNull { it.fileName == handle.get<String>(KEY_EDITING) }
                if (target != null) {
                    openEditor(target, restore = true)
                } else {
                    handle.remove<String>(KEY_EDITING)
                    update { it.copy(step = ReportStep.FORM) }
                }
            }
        }
    }

    private fun awaitAutoClip() = autoClip.await(viewModelScope) { adopted ->
        // Added to what the strip shows rather than listed anew: see onGalleryImagePicked.
        update { state ->
            state.copy(
                autoClipPending = false,
                attachments = if (adopted == null) state.attachments else state.attachments.filter { it.fileName != adopted.fileName } + adopted,
            )
        }
    }

    /** The report is sent or closed: a clip still being made is not wanted any more (spec §7). */
    private fun abandonAutoClip() {
        if (autoClip.abandon()) update { it.copy(autoClipPending = false) }
    }

    private fun openEditor(target: DraftFile, restore: Boolean) {
        handle[KEY_EDITING] = target.fileName
        val initial = AnnotationUiState(
            fileName = target.fileName,
            tool = enumOrNull<AnnotationTool>(handle.get<String>(KEY_TOOL)) ?: AnnotationTool.PEN,
            penColor = enumOrNull<PenColor>(handle.get<String>(KEY_PEN_COLOR)) ?: PenColor.RED,
            penWidth = enumOrNull<PenWidth>(handle.get<String>(KEY_PEN_WIDTH)) ?: PenWidth.THIN,
            confirmingDiscard = restore && handle.get<Boolean>(KEY_CONFIRMING_DISCARD) == true,
        )
        editor = AnnotationEditor(draftId, target, env, viewModelScope, detached, cpu, initial, ::saveEditorSettings) { saved ->
            onEditorFinished(target, saved)
        }.also { it.load(restore) }
    }

    /** Tool, colour and width stay for the rest of the draft, across process death too. */
    private fun saveEditorSettings(state: AnnotationUiState) {
        handle[KEY_TOOL] = state.tool.name
        handle[KEY_PEN_COLOR] = state.penColor.name
        handle[KEY_PEN_WIDTH] = state.penWidth.name
        handle[KEY_CONFIRMING_DISCARD] = state.confirmingDiscard
    }

    /**
     * Back to the form. A saved edit replaces its attachment in the strip in place (the same
     * incremental update as a picked image, see [onGalleryImagePicked]); its new modification time
     * and size give the thumbnail a new cache key, so the strip shows the edited image.
     */
    private fun onEditorFinished(target: DraftFile, saved: DraftFile?) {
        editor = null
        handle.remove<String>(KEY_EDITING)
        handle[KEY_CONFIRMING_DISCARD] = false
        update { state ->
            state.copy(
                step = ReportStep.FORM,
                attachments = if (saved == null) state.attachments else state.attachments.map { if (it.fileName == target.fileName) saved else it },
            )
        }
    }

    /**
     * FeedbackKit.disable() while the screen is open: the host turned the SDK off, so the screen
     * closes as a cancel and the draft goes with it. A send in flight decides for itself (thanks and
     * SUBMIT if it was queued in time), and the thank-you screen closes on its own.
     * BugReporting.setState(DISABLED) only stops new invocations and does not come here.
     */
    private fun onSdkDisabled() {
        if (draft.step == ReportStep.SENDING || draft.step == ReportStep.SUCCESS) return
        env.logger.d("FeedbackKit was disabled; closing the report screen")
        close(DismissType.CANCEL)
    }

    private fun close(reason: DismissType) {
        update { it.copy(confirmingCancel = false) }
        if (closeRequest.value != null) return
        closeRequest.value = reason
        // A report stepping aside keeps it: the reopened screen awaits the same token.
        if (reason != DismissType.ADD_ATTACHMENT) abandonAutoClip()
    }

    /** Only the form and the extended step take edits; the menu, the proactive prompt, sending and thanks do not. */
    private val editable: Boolean
        get() = draft.step == ReportStep.FORM || draft.step == ReportStep.EXTENDED

    private fun updateFields(transform: (DraftFields) -> DraftFields) {
        // Not while the consent is open: the reopened report starts from the state taken for it.
        if (!editable || draft.recordingStarting) return
        update { it.copy(fields = transform(it.fields), submitFailed = false) }
    }

    private fun update(transform: (DraftState) -> DraftState) {
        val next = transform(draft)
        if (next == draft) return
        draft = next
        save(next)
    }

    private fun restoreOrStart(current: Config): DraftState {
        val savedStep = enumOrNull<ReportStep>(handle.get<String>(KEY_STEP))
        if (savedStep == null) {
            val start = ReportNavigation.start(args.reportType, ReportNavigation.menuTypes(current.ui), proactive = args.proactive != null)
            val prefill = current.userEmail.orEmpty()
            autoClip.claim(args.autoRecordingToken)
            return DraftState(
                step = start.step,
                type = start.type,
                menuShown = start.menuShown,
                fields = DraftFields(email = prefill),
                emailPrefill = prefill,
                // Only a screenshot that was asked for and is still wanted can be "unavailable".
                screenshotUnavailable = args.screenshotRequested && args.screenshot == null && current.ui.attachmentTypes.initialScreenshot,
                autoClipPending = args.autoRecordingToken != null,
            ).also(::save)
        }
        val type = enumOrNull<ReportType>(handle.get<String>(KEY_TYPE))
        val rules = type?.let { FormRules.from(current.ui, it) }
        val step = when {
            rules == null -> ReportStep.MENU
            // The send was lost with the process; the user can send again.
            savedStep == ReportStep.SENDING -> ReportNavigation.stepBeforeSend(rules)
            // The new process may configure the extended step off: there is no such step to return to.
            savedStep == ReportStep.EXTENDED && !rules.hasExtendedStep -> ReportStep.FORM
            // Killed between entering the editor and remembering which file it edits.
            savedStep == ReportStep.ANNOTATE && handle.get<String>(KEY_EDITING) == null -> ReportStep.FORM
            else -> savedStep
        }
        return DraftState(
            step = step,
            type = type,
            menuShown = handle.get<Boolean>(KEY_MENU_SHOWN) ?: (type == null),
            fields = DraftFields(
                email = handle.get<String>(KEY_EMAIL).orEmpty(),
                comment = handle.get<String>(KEY_COMMENT).orEmpty(),
                steps = handle.get<String>(KEY_STEPS).orEmpty(),
                actual = handle.get<String>(KEY_ACTUAL).orEmpty(),
                expected = handle.get<String>(KEY_EXPECTED).orEmpty(),
            ),
            emailPrefill = handle.get<String>(KEY_EMAIL_PREFILL).orEmpty(),
            screenshotUnavailable = handle.get<Boolean>(KEY_SCREENSHOT_UNAVAILABLE) ?: false,
            confirmingCancel = handle.get<Boolean>(KEY_CONFIRMING_CANCEL) ?: false,
            notice = enumOrNull<AttachNotice>(handle.get<String>(KEY_NOTICE)),
            autoClipPending = autoClip.isAwaited,
        )
    }

    private fun save(state: DraftState) {
        handle[KEY_STEP] = state.step.name
        handle[KEY_TYPE] = state.type?.name
        handle[KEY_MENU_SHOWN] = state.menuShown
        handle[KEY_EMAIL] = state.fields.email
        handle[KEY_COMMENT] = state.fields.comment
        handle[KEY_STEPS] = state.fields.steps
        handle[KEY_ACTUAL] = state.fields.actual
        handle[KEY_EXPECTED] = state.fields.expected
        handle[KEY_EMAIL_PREFILL] = state.emailPrefill
        handle[KEY_SCREENSHOT_UNAVAILABLE] = state.screenshotUnavailable
        handle[KEY_CONFIRMING_CANCEL] = state.confirmingCancel
        handle[KEY_NOTICE] = state.notice?.name
    }

    companion object {
        const val SUCCESS_DISPLAY_MILLIS: Long = 1_500
        const val MAX_FIELD_LENGTH: Int = 4_000

        private const val KEY_DRAFT_ID = "feedbackkit.draftId"
        private const val KEY_STEP = "feedbackkit.step"
        private const val KEY_TYPE = "feedbackkit.type"
        private const val KEY_MENU_SHOWN = "feedbackkit.menuShown"
        private const val KEY_EMAIL = "feedbackkit.email"
        private const val KEY_COMMENT = "feedbackkit.comment"
        private const val KEY_STEPS = "feedbackkit.steps"
        private const val KEY_ACTUAL = "feedbackkit.actual"
        private const val KEY_EXPECTED = "feedbackkit.expected"
        private const val KEY_EMAIL_PREFILL = "feedbackkit.emailPrefill"
        private const val KEY_SCREENSHOT_UNAVAILABLE = "feedbackkit.screenshotUnavailable"
        private const val KEY_CONFIRMING_CANCEL = "feedbackkit.confirmingCancel"
        private const val KEY_NOTICE = "feedbackkit.notice"
        private const val KEY_EDITING = "feedbackkit.editing"
        private const val KEY_TOOL = "feedbackkit.tool"
        private const val KEY_PEN_COLOR = "feedbackkit.penColor"
        private const val KEY_PEN_WIDTH = "feedbackkit.penWidth"
        private const val KEY_CONFIRMING_DISCARD = "feedbackkit.confirmingDiscard"
        private const val KEY_RESUME_APPLIED = "feedbackkit.resumeApplied"

        /** The token of the automatic recording still awaited; removed once it is resolved. */
        private const val KEY_AUTO_CLIP = AutoClipAttachment.STATE_KEY

        /**
         * Keys a reopened screen restores from; never the editor's, the notice or the resume marker.
         * The awaited clip's token travels: the reopened screen awaits it in place of this one.
         */
        private val RESUME_KEYS = listOf(
            KEY_DRAFT_ID, KEY_STEP, KEY_TYPE, KEY_MENU_SHOWN, KEY_EMAIL, KEY_COMMENT,
            KEY_STEPS, KEY_ACTUAL, KEY_EXPECTED, KEY_EMAIL_PREFILL, KEY_SCREENSHOT_UNAVAILABLE, KEY_AUTO_CLIP,
        )

        /**
         * Keeps the saved state far below the 1 MB binder limit, whatever the user pastes. Cuts at a
         * code point boundary, so an emoji straddling the limit is dropped whole, never split.
         */
        private fun String.capped(): String {
            if (length <= MAX_FIELD_LENGTH) return this
            val end = if (Character.isHighSurrogate(this[MAX_FIELD_LENGTH - 1])) MAX_FIELD_LENGTH - 1 else MAX_FIELD_LENGTH
            return substring(0, end)
        }

        fun factory(args: FeedbackLaunchArgs, env: ReportEnvironment): ViewModelProvider.Factory = viewModelFactory {
            initializer { ReportDraftViewModel(createSavedStateHandle(), args, env, PatternsEmailValidator) }
        }
    }
}
