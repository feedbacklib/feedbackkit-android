package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.FeedbackKitInfo
import io.github.feedbacklib.android.OnReportSubmitHandler
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.queue.ReportStore
import io.github.feedbacklib.android.internal.queue.UploadScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * Turns a draft into a queued report: runs the host's [OnReportSubmitHandler] (at most
 * [handlerTimeoutMillis]), adds device/app context and host files, writes it to the store and
 * schedules delivery. Never throws except for coroutine cancellation.
 */
internal class ReportSubmitter(
    private val cid: String,
    private val isEnabled: () -> Boolean,
    private val store: ReportStore,
    private val scheduler: UploadScheduler,
    private val deviceInfo: () -> DeviceInfo,
    private val appInfo: () -> AppInfo,
    private val appFiles: suspend () -> List<ReportStore.NewAttachment>,
    private val submitHandler: () -> OnReportSubmitHandler?,
    private val handlerScope: CoroutineScope,
    private val logger: SdkLogger,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val handlerTimeoutMillis: Long = HANDLER_TIMEOUT_MILLIS,
) {

    /**
     * @return the queued report's id, or null when nothing was queued.
     *
     * Runs in the caller's coroutine and is cancelled with it — UI code must go through
     * FeedbackKitRuntime.submitReport, which runs in the SDK scope.
     */
    suspend fun submit(draft: ReportDraft): String? {
        if (!isEnabled()) {
            logger.w("Report not submitted: FeedbackKit is disabled")
            return null
        }
        return try {
            val additions = runHandler(draft)
            withContext(Dispatchers.IO) { queue(draft, additions, appFiles()) }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller really was cancelled → propagate
            logger.e("Report could not be saved", e)
            null
        } catch (e: Exception) {
            logger.e("Report could not be saved", e)
            null
        }
    }

    private suspend fun runHandler(draft: ReportDraft): ReportAdditions {
        val handler = submitHandler() ?: return ReportAdditions.EMPTY
        val report = MutableReportImpl(draft.type, draft.email, draft.comment)
        // Runs in the SDK's own scope: a blocking handler keeps its thread after the timeout,
        // but nothing waits for it and its later changes go nowhere.
        val call = handlerScope.async { handler.onReportSubmit(report) }
        return try {
            val finished = withTimeoutOrNull(handlerTimeoutMillis) {
                call.await()
                true
            }
            if (finished == true) {
                report.additions()
            } else {
                call.cancel()
                logger.w("onReportSubmitHandler took longer than $handlerTimeoutMillis ms; its changes are ignored")
                ReportAdditions.EMPTY
            }
        } catch (e: CancellationException) {
            currentCoroutineContext().ensureActive() // the caller really was cancelled → propagate
            logger.w("onReportSubmitHandler was cancelled; its changes are ignored", e)
            ReportAdditions.EMPTY
        } catch (e: Exception) {
            logger.w("onReportSubmitHandler threw; its changes are ignored", e)
            ReportAdditions.EMPTY
        }
    }

    private fun queue(draft: ReportDraft, additions: ReportAdditions, hostFiles: List<ReportStore.NewAttachment>): String {
        val id = newId()
        val device = try {
            deviceInfo()
        } catch (e: Exception) {
            logger.w("Device context unavailable; report queued without it", e)
            DeviceInfo.UNKNOWN
        }
        val app = try {
            appInfo()
        } catch (e: Exception) {
            logger.w("App context unavailable; report queued without it", e)
            AppInfo.unknown(FeedbackKitInfo.VERSION)
        }
        val payload = ReportPayload(
            id = id,
            cid = cid,
            type = draft.type,
            createdAt = Instant.ofEpochMilli(clock()).toString(),
            email = draft.email,
            comment = draft.comment,
            extended = draft.extended,
            proactive = draft.proactive,
            tags = additions.tags,
            userAttributes = additions.userAttributes,
            userData = additions.userData,
            consoleLog = additions.consoleLog,
            attachments = emptyList(),
            device = device,
            app = app,
            currentScreen = draft.currentScreen,
        )
        val attachments = draft.attachments.map { it.toNewAttachment() } +
            hostFiles +
            additions.files.map { it.toNewAttachment() }
        store.enqueue(payload, attachments)
        try {
            scheduler.schedule()
        } catch (e: Exception) {
            logger.w("Could not schedule delivery; the report stays queued until the next start", e)
        }
        return id
    }

    private fun DraftAttachment.toNewAttachment() = ReportStore.NewAttachment(
        meta = AttachmentMeta(newId(), kind, fileName, mimeType, sizeBytes = 0),
        open = file.opener(),
    )

    private fun MutableReportImpl.AddedFile.toNewAttachment() = ReportStore.NewAttachment(
        meta = AttachmentMeta(newId(), AttachmentKind.APP_FILE, fileName, MimeTypes.guess(fileName), sizeBytes = 0),
        maxBytes = AppFileAttachments.MAX_FILE_BYTES.toLong(),
        open = file.opener(),
    )

    private fun File.opener(): () -> java.io.InputStream? = { takeIf { it.exists() }?.inputStream() }

    private companion object {
        const val HANDLER_TIMEOUT_MILLIS = 5_000L
    }
}
