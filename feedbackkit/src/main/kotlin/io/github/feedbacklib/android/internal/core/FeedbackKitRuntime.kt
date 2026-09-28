package io.github.feedbacklib.android.internal.core

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.os.Trace
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.LocalReportSender
import io.github.feedbacklib.android.OnDismissCallback
import io.github.feedbacklib.android.OnInvokeCallback
import io.github.feedbacklib.android.OnReportSubmitHandler
import io.github.feedbacklib.android.ReportSender
import io.github.feedbacklib.android.internal.invoke.CaptureRequest
import io.github.feedbacklib.android.internal.invoke.CaptureRequestJson
import io.github.feedbacklib.android.internal.invoke.InvocationSystem
import io.github.feedbacklib.android.internal.proactive.AndroidExitReasons
import io.github.feedbacklib.android.internal.proactive.ExitReasons
import io.github.feedbacklib.android.internal.proactive.ProactiveEvent
import io.github.feedbacklib.android.internal.proactive.ProactiveReporting
import io.github.feedbacklib.android.internal.proactive.SessionStore
import io.github.feedbacklib.android.internal.proactive.isMainProcess
import io.github.feedbacklib.android.internal.queue.ReportStore
import io.github.feedbacklib.android.internal.queue.UploadRunner
import io.github.feedbacklib.android.internal.queue.UploadScheduler
import io.github.feedbacklib.android.internal.queue.UploadWorkerBinding
import io.github.feedbacklib.android.internal.queue.WorkManagerUploadScheduler
import io.github.feedbacklib.android.internal.recording.PendingClips
import io.github.feedbacklib.android.internal.recording.Recorders
import io.github.feedbacklib.android.internal.recording.serviceLoaderProviders
import io.github.feedbacklib.android.internal.report.AppFileAttachments
import io.github.feedbacklib.android.internal.report.AppInfoCollector
import io.github.feedbacklib.android.internal.report.DeviceInfoCollector
import io.github.feedbacklib.android.internal.report.DraftStore
import io.github.feedbacklib.android.internal.report.ReportDraft
import io.github.feedbacklib.android.internal.report.ReportSubmitter
import io.github.feedbacklib.android.spi.ScreenRecorderProvider
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * The SDK's object graph, built by hand (no DI framework — spec §3). Directories are resolved
 * lazily and first touched from [scope], so build() on the main thread does no disk I/O.
 */
internal class FeedbackKitRuntime private constructor(
    private val app: Application,
    val cid: String,
    val logger: SdkLogger,
    initialConfig: Config,
    val sender: ReportSender,
    private val scheduler: UploadScheduler,
    recorderProviders: () -> Iterator<ScreenRecorderProvider>,
    /** Wall clock of build(). */
    private val startedAt: Long,
) {

    // First, and lazy: no disk I/O here, and the crash handler below may need it at once.
    private val root by lazy { File(app.filesDir, ROOT_DIR) }

    // logger is a primary constructor property, already initialised before this runs.
    val scope: CoroutineScope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> logger.e("Unexpected SDK failure", e) },
    )

    private val mutableConfig = MutableStateFlow(initialConfig)
    val config: StateFlow<Config> = mutableConfig

    @Volatile
    var submitHandler: OnReportSubmitHandler? = null

    @Volatile
    var onInvokeCallback: OnInvokeCallback? = null

    @Volatile
    var onDismissCallback: OnDismissCallback? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Session files one read or write after another: detection, the heartbeat, the time of the prompt.
    // Lazy: first used on the startup pass, never in build().
    private val sessionIo by lazy { Dispatchers.IO.limitedParallelism(1) }

    /**
     * Proactive reporting (spec §8), in the app's main process only; in front of the host's crash
     * handler from here on. The rest of it — the exit reasons, the session and its heartbeat — is made
     * on the startup pass.
     */
    val proactive: ProactiveReporting? = if (isMainProcess(app, logger)) {
        ProactiveReporting(
            store = SessionStore({ File(root, SESSION_DIR) }, logger),
            exits = ExitReasons { AndroidExitReasons(app, logger).history() },
            logger = logger,
            startedAt = startedAt,
            io = { task -> scope.launch(sessionIo) { task.run() } },
        )
    } else {
        null
    }

    /** The optional screen recorder (spec §3); found on the first startup step. */
    val recorders: Recorders = Recorders(app, logger, recorderProviders)

    private val deleteInBackground: (File) -> Unit = { file -> scope.launch { file.delete() } }

    /** Auto Screen Recording's clips on their way from an invocation to its report (spec §7). */
    val autoClips: PendingClips = PendingClips(deleteInBackground, logger)

    val invocation: InvocationSystem = InvocationSystem(
        app = app,
        config = { config.value },
        onInvoke = { onInvokeCallback?.onInvoke() },
        logger = logger,
        deleteFile = deleteInBackground,
        onCaptureAbandoned = ::onCaptureAbandoned,
        onCaptureDropped = { request -> clearSavedCapture(request.draftId) },
        recorder = { recorders.recorder },
        recorderKnown = { recorders.discovered },
        autoClips = autoClips,
        onProactiveResolved = { event, shownAt -> scope.launch(sessionIo) { proactive?.resolve(event, shownAt) } },
    )

    val store: ReportStore by lazy { ReportStore(File(root, REPORTS_DIR), logger) }

    val appFiles: AppFileAttachments by lazy {
        AppFileAttachments(app.contentResolver, { File(root, APP_FILES_DIR) }, logger, scope)
    }

    val drafts: DraftStore by lazy { DraftStore({ File(root, DRAFTS_DIR) }, logger) }

    val submitter: ReportSubmitter by lazy {
        val deviceInfo = DeviceInfoCollector(app)
        val appInfo = AppInfoCollector(app)
        ReportSubmitter(
            cid = cid,
            isEnabled = { config.value.enabled },
            store = store,
            scheduler = scheduler,
            deviceInfo = deviceInfo::collect,
            appInfo = appInfo::collect,
            appFiles = appFiles::snapshot,
            submitHandler = { submitHandler },
            handlerScope = scope,
            logger = logger,
        )
    }

    /**
     * Queues [draft] in the SDK's own scope, so closing the screen that started it cannot cancel it.
     * Await the result if you need the id; cancelling the await does not cancel the submission.
     */
    fun submitReport(draft: ReportDraft): Deferred<String?> = scope.async { submitter.submit(draft) }

    fun setEnabled(enabled: Boolean) {
        mutableConfig.update { it.copy(enabled = enabled) }
        invocation.refresh()
        if (enabled) scope.launch { scheduleIfPending() }
    }

    fun identifyUser(email: String?, name: String?) {
        mutableConfig.update { it.copy(userEmail = email, userName = name) }
    }

    /** Report screen settings apply to an open screen at once (spec §3); no invocation refresh needed. */
    fun updateUi(transform: (ReportUiConfig) -> ReportUiConfig) {
        mutableConfig.update { it.copy(ui = transform(it.ui)) }
    }

    /** Updates [config] and re-applies invocation settings (spec §5); safe from any thread. */
    fun updateConfig(transform: (Config) -> Config) {
        mutableConfig.update(transform)
        invocation.refresh()
    }

    /**
     * Tests only: runs [block] on `sessionIo`, after every session file read and write queued before it,
     * so a test can look at the session files without racing the runtime's own writes.
     */
    suspend fun <T> onSessionIo(block: () -> T): T = withContext(sessionIo) { block() }

    private fun uploadRunnerOrNull(): UploadRunner? =
        if (config.value.enabled) UploadRunner(store, sender, logger) else null

    private suspend fun startUp() {
        // First: what the previous run left is read before this session's first heartbeat overwrites it (spec §8).
        detectPreviousRun()
        // Before the lookup: once a recorder is known, a recording's path is ready without disk I/O on main.
        invocation.resolveDirectories()
        startupStep(logger, "Screen recorder lookup failed") { recorders.discover() }
        // Settings that depend on the recorder (auto recording, stage 6) are applied again now it is known.
        invocation.refresh()
        startupStep(logger, "Report queue cleanup failed") { store.cleanUp() }
        startupStep(logger, "App file cleanup failed") { appFiles.purgeOrphans() }
        invocation.purgeStaleCaptures()
        // One cutoff for both sweeps: a draft the restore brings back is never one the purge deletes.
        val staleCutoff = drafts.staleCutoff()
        // Before purgeStale, on this same pass: the saved mode is read from the drafts as they are.
        restorePendingCapture(staleCutoff)
        drafts.purgeStale(staleCutoff)
        scheduleIfPending()
    }

    /**
     * Reads what the previous run left (spec §8) on `sessionIo`, then makes and starts this session's
     * heartbeat on the main thread, so its first write comes after the read, and hands the event, if any,
     * to the prompt, which waits for its host screen. Background thread: the session id is made here, as
     * the first UUID of a process can wait for the random source. A failed detection still starts the
     * session. Nothing of this in a process other than the main one.
     */
    private suspend fun detectPreviousRun() {
        val proactive = proactive ?: return
        var event: ProactiveEvent? = null
        startupStep(logger, "Proactive reporting detection failed") { event = withContext(sessionIo) { proactive.detect() } }
        val sessionId = UUID.randomUUID().toString()
        mainHandler.post {
            try {
                // Made now, it hears the activity tracker from here on: where the app is comes along.
                val session = proactive.newSession(foreground = invocation.isForeground) ?: return@post
                if (invocation.addTrackerListener(session)) session.begin(sessionId)
            } catch (e: Exception) {
                logger.w("Could not start the session heartbeat", e)
            }
        }
        event?.let(invocation::offerProactive)
    }

    /**
     * A report the previous process left in capture mode (spec §6): the controls come back on the
     * first resumed host screen. A report screen already open (the system restored it) wins; the
     * saved mode is then dropped and its draft goes with purgeStale. Background thread.
     */
    private suspend fun restorePendingCapture(staleCutoff: Long) {
        val pending = drafts.pendingCapture(staleCutoff) ?: return
        val request = CaptureRequestJson.decode(pending.json)?.takeIf { it.draftId == pending.draftId }
        if (request == null) {
            logger.w("A saved screenshot capture state could not be read; it is dropped")
            drafts.onQueue(pending.draftId) { clearCaptureState(pending.draftId) }
            return
        }
        mainHandler.post {
            val restored = try {
                invocation.restoreExtraCapture(request)
            } catch (e: Exception) {
                logger.e("Could not restore the screenshot capture mode", e)
                false
            }
            if (restored) {
                logger.d("A report was waiting for an extra screenshot; its controls are back")
            } else {
                clearSavedCapture(request.draftId)
            }
        }
    }

    /** A saved capture mode that will not come back: its `capture.json` goes, on the draft's queue. */
    private fun clearSavedCapture(draftId: String) {
        scope.launch { drafts.onQueue(draftId) { clearCaptureState(draftId) } }
    }

    /**
     * A report that stepped aside for a screenshot or a recording will not come back — FeedbackKit
     * was disabled, or it could not reopen: it is dropped, which the host hears as a cancel. Main thread.
     */
    private fun onCaptureAbandoned(request: CaptureRequest) {
        logger.d("A report waiting in screenshot capture or recording mode will not come back; it is discarded")
        // The automatic recording it still awaited goes with it (spec §7).
        (request.state[PendingClips.STATE_KEY] as? String)?.let(autoClips::abandon)
        scope.launch { drafts.onQueue(request.draftId) { delete(request.draftId) } }
        try {
            onDismissCallback?.onDismiss(DismissType.CANCEL, request.reportType)
        } catch (e: Exception) {
            logger.e("onDismissCallback threw; ignored", e)
        }
    }

    private fun scheduleIfPending() {
        try {
            if (config.value.enabled && store.pending().isNotEmpty()) scheduler.schedule()
        } catch (e: Exception) {
            logger.w("Could not schedule delivery of queued reports", e)
        }
    }

    companion object {
        private const val ROOT_DIR = "feedbackkit"
        private const val REPORTS_DIR = "reports"
        private const val APP_FILES_DIR = "app-files"
        private const val DRAFTS_DIR = "drafts"
        private const val SESSION_DIR = "session"

        @Volatile
        var current: FeedbackKitRuntime? = null
            private set

        /**
         * [recorderProviders] is the ServiceLoader lookup; tests replace it. Main thread (build()): its
         * blocks are `android.os.Trace` sections, for a system trace of the start (§10 budget).
         */
        fun create(
            app: Application,
            cid: String,
            settings: Settings,
            recorderProviders: () -> Iterator<ScreenRecorderProvider> = ::serviceLoaderProviders,
        ): FeedbackKitRuntime = traced("FeedbackKit.create") {
            val logger = SdkLogger(settings.logLevel)
            SdkLog.logger = logger
            val runtime = traced("FeedbackKit.runtime") {
                FeedbackKitRuntime(
                    app = app,
                    cid = cid,
                    logger = logger,
                    initialConfig = Config(
                        enabled = settings.enabled,
                        logLevel = settings.logLevel,
                        userEmail = settings.userEmail,
                        userName = settings.userName,
                        invocationEvents = settings.invocationEvents,
                        shakingThreshold = settings.shakingThreshold,
                        floatingButtonEdge = settings.floatingButtonEdge,
                        floatingButtonOffsetDp = settings.floatingButtonOffsetDp,
                        recordingButtonPosition = settings.recordingButtonPosition,
                        autoScreenRecording = settings.autoScreenRecording,
                        bugReportingEnabled = settings.bugReportingEnabled,
                        proactive = settings.proactive,
                        ui = settings.ui,
                    ),
                    sender = settings.sender ?: LocalReportSender(app),
                    scheduler = WorkManagerUploadScheduler(app),
                    recorderProviders = recorderProviders,
                    startedAt = System.currentTimeMillis(),
                )
            }
            runtime.submitHandler = settings.submitHandler
            runtime.onInvokeCallback = settings.onInvokeCallback
            runtime.onDismissCallback = settings.onDismissCallback
            current = runtime
            UploadWorkerBinding.runnerProvider = { current?.uploadRunnerOrNull() }
            traced("FeedbackKit.invocation.start") { runtime.invocation.start() }
            traced("FeedbackKit.startUp.launch") { runtime.scope.launch { runtime.startUp() } }
            runtime
        }

        private inline fun <T> traced(section: String, block: () -> T): T {
            Trace.beginSection(section)
            try {
                return block()
            } finally {
                Trace.endSection()
            }
        }

        fun resetForTests() {
            current?.proactive?.stop()
            current?.invocation?.stop()
            current?.scope?.cancel()
            current = null
            UploadWorkerBinding.runnerProvider = null
            SdkLog.logger = SdkLogger()
        }
    }
}

/**
 * One step of the startup pass: a failure is logged and the pass goes on to the next step, but a
 * cancellation of the calling coroutine (the SDK scope was cancelled) propagates. A step that
 * suspends on a cancelled scope throws CancellationException, which is an Exception too.
 */
internal suspend fun startupStep(logger: SdkLogger, failure: String, step: suspend () -> Unit) {
    try {
        step()
    } catch (e: Exception) {
        currentCoroutineContext().ensureActive() // the SDK scope really was cancelled → propagate
        logger.w(failure, e)
    }
}
