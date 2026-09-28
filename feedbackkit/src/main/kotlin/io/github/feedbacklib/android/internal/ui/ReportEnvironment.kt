package io.github.feedbacklib.android.internal.ui

import android.app.Activity
import android.content.Context
import android.net.Uri
import io.github.feedbacklib.android.internal.annotate.AndroidAnnotationImages
import io.github.feedbacklib.android.internal.annotate.AnnotationImages
import io.github.feedbacklib.android.internal.core.Config
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.CaptureRequest
import io.github.feedbacklib.android.internal.recording.PendingClips
import io.github.feedbacklib.android.internal.report.DraftStore
import io.github.feedbacklib.android.internal.report.ReportDraft
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/** Reads what the photo picker returned; a seam for tests. Blocking — call on the draft's queue. */
internal interface ContentReader {
    fun mimeType(uri: Uri): String?
    fun open(uri: Uri): InputStream?
}

/** What the report screen needs from the SDK; a seam for tests. */
internal interface ReportEnvironment {
    val config: StateFlow<Config>
    val drafts: DraftStore
    val logger: SdkLogger
    val content: ContentReader
    val images: AnnotationImages

    /** Whether feedbackkit-recording provides a recorder (spec §3); false until it is known. */
    val canRecord: StateFlow<Boolean>

    /** Where the invocation's automatic recording comes from, after the report has opened (spec §7). */
    val autoClips: PendingClips

    /** Screen size in px: the editor's preview needs no more. */
    fun displaySize(): Pair<Int, Int>

    /** Queues [draft] in the SDK's scope: cancelling the await does not cancel the submission. */
    fun submit(draft: ReportDraft): Deferred<String?>

    /** Runs [block] in the SDK's scope, outliving the screen. */
    fun runDetached(block: suspend () -> Unit)

    /** Starts capture mode for an extra screenshot (spec §6); false when it cannot start now. Main thread. */
    fun beginExtraCapture(request: CaptureRequest): Boolean

    /**
     * Starts a manual recording (spec §7): the consent opens over [host], the report screen itself.
     * False when it cannot start now. [onStarted] and [onNotStarted] come on the main thread, once.
     */
    fun beginRecording(host: Activity, request: CaptureRequest, onStarted: () -> Unit, onNotStarted: (refused: Boolean) -> Unit): Boolean
}

internal class RuntimeReportEnvironment(private val runtime: FeedbackKitRuntime, context: Context) : ReportEnvironment {
    override val config: StateFlow<Config>
        get() = runtime.config
    override val drafts: DraftStore
        get() = runtime.drafts
    override val logger: SdkLogger
        get() = runtime.logger

    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override val images: AnnotationImages = AndroidAnnotationImages

    override val canRecord: StateFlow<Boolean>
        get() = runtime.recorders.available

    override val autoClips: PendingClips
        get() = runtime.autoClips

    override fun displaySize(): Pair<Int, Int> = appContext.resources.displayMetrics.let { it.widthPixels to it.heightPixels }

    override val content: ContentReader = object : ContentReader {
        override fun mimeType(uri: Uri): String? = resolver.getType(uri)
        override fun open(uri: Uri): InputStream? = resolver.openInputStream(uri)
    }

    override fun submit(draft: ReportDraft): Deferred<String?> = runtime.submitReport(draft)

    override fun runDetached(block: suspend () -> Unit) {
        runtime.scope.launch { block() }
    }

    override fun beginExtraCapture(request: CaptureRequest): Boolean = runtime.invocation.beginExtraCapture(request)

    override fun beginRecording(host: Activity, request: CaptureRequest, onStarted: () -> Unit, onNotStarted: (refused: Boolean) -> Unit): Boolean =
        runtime.invocation.beginRecording(host, request, onStarted, onNotStarted)
}
