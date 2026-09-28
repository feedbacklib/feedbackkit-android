package io.github.feedbacklib.android.recording.internal

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Surface
import io.github.feedbacklib.android.spi.RecorderLog
import io.github.feedbacklib.android.spi.RecordingStopReason
import java.util.UUID

/** How a session ended, told on the main thread. */
internal interface SessionEvents {
    fun started(startedAtMillis: Long)
    fun notStarted(refused: Boolean)
    fun finished(reason: RecordingStopReason, usable: Boolean)
}

/**
 * One MediaProjection from consent to release (spec §7). [SessionMachine] decides the order —
 * the privacy notice and the consent in [ConsentActivity], then the mediaProjection foreground
 * service, and only once it runs in the foreground getMediaProjection, the virtual display and [sink] —
 * and this class carries its commands out on its own thread, where MediaRecorder and the projection
 * deliver their callbacks too. Every call into the machine happens on that thread. [events] hear the
 * outcome on the main thread. Nothing here throws into the host.
 *
 * The time between NotifyStarted and the end is the recording's length; the video can be shorter,
 * because the display yields frames only when the screen changes (see [CaptureSink]).
 */
internal class ProjectionSession(
    private val context: Context,
    val sink: CaptureSink,
    private val events: SessionEvents,
    private val log: RecorderLog,
    private val videoSpec: () -> VideoSpec = { displayVideoSpec(context).forAvcEncoder(log) },
) : SinkEvents {

    val id: String = UUID.randomUUID().toString()

    private val thread = HandlerThread(THREAD_NAME).apply { start() }
    private val handler = Handler(thread.looper)
    private val main = Handler(Looper.getMainLooper())
    private val machine = SessionMachine()

    /** The wall-clock limit of [sink]: stops the recording whatever the frames are doing. */
    private val limit = RecordingLimit(
        sink.limitMillis,
        object : DelayedRunner {
            override fun postDelayed(action: Runnable, delayMillis: Long) {
                handler.postDelayed(action, delayMillis)
            }

            override fun remove(action: Runnable) = handler.removeCallbacks(action)
        },
    ) {
        runGuarded {
            log.i("The screen recording reached its ${sink.limitMillis} ms limit")
            perform(machine.limitReached())
        }
    }

    /** A service start the system never answers must not leave the session STARTING for ever. */
    private val serviceTimeout = Runnable {
        runGuarded {
            if (machine.state == SessionState.STARTING) log.w("The screen recording service did not start in time")
            perform(machine.serviceFailed())
        }
    }

    // Session thread only.
    private var resultCode = 0
    private var consent: Intent? = null
    private var projection: MediaProjection? = null
    private var callback: MediaProjection.Callback? = null
    private var display: VirtualDisplay? = null
    private var startedAt = 0L
    private var usable = false

    /** False once the system stopped the projection: a paused auto recording cannot resume then. */
    @Volatile
    var projectionAlive: Boolean = false
        private set

    /**
     * Tests only: `SystemClock.elapsedRealtime()` when the session issued StopCapture, 0 before. It
     * tells the moment a stop was decided (the limit, a request, the system) apart from the time the
     * recorder then takes to finalise the file. Written on the session thread before the outcome is
     * posted to the main thread.
     */
    @Volatile
    internal var stopIssuedAtMillis: Long = 0L
        private set

    /** The privacy notice and then the consent dialog over [host]. Main thread. */
    fun launchConsent(host: Activity) {
        try {
            host.startActivity(ConsentActivity.intent(host, id))
        } catch (e: Exception) {
            log.w("Could not ask for the screen capture consent", e)
            onConsentLost()
        }
    }

    fun onConsent(resultCode: Int, data: Intent?) {
        post {
            if (resultCode == Activity.RESULT_OK && data != null) {
                this.resultCode = resultCode
                consent = data
                perform(machine.consentGranted())
            } else {
                perform(machine.consentRefused())
            }
        }
    }

    fun onConsentLost() {
        post { perform(machine.consentUnavailable()) }
    }

    fun onServiceReady() {
        post { perform(machine.serviceReady()) }
    }

    fun onServiceFailed() {
        post { perform(machine.serviceFailed()) }
    }

    /** Idempotent; a session still waiting for consent ends when the answer comes. */
    fun stop() {
        post { perform(machine.stopRequested()) }
    }

    /** Tests only: stops the projection as the shade or the status bar chip does, callback left in place. */
    fun stopProjectionForTest() {
        post { projection?.stop() }
    }

    // MediaRecorder's own limit: the second guard behind [limit]; the machine ignores whichever comes second.
    // Posted even from the session thread: the sink calls these from inside its MediaRecorder's own
    // listener, which is no place to stop and release that recorder.
    override fun onLimitReached() {
        post { perform(machine.limitReached()) }
    }

    override fun onError(what: Int, extra: Int) {
        post {
            log.w("The screen recorder failed ($what/$extra)")
            perform(machine.captureFailed())
        }
    }

    override fun onSurfaceChanged(surface: Surface) {
        if (Looper.myLooper() == thread.looper) attachSurface(surface) else post { attachSurface(surface) }
    }

    /** Session thread: the display stops drawing into the recorder (pause, clip, a segment's end, stop). */
    override fun detachSurface() {
        display?.surface = null
    }

    /** Session thread: the display draws into [surface] (resume, a restarted segment). */
    internal fun attachSurface(surface: Surface) {
        display?.surface = surface
    }

    /** Session thread only, like every other use of the machine. */
    internal val state: SessionState
        get() = machine.state

    /**
     * Runs [block] on the session thread; a failure there fails the session instead of the host.
     * False once the session ended and its thread no longer takes work.
     */
    internal fun post(block: () -> Unit): Boolean {
        val posted = handler.post { runGuarded(block) }
        if (!posted) log.d("A screen recording event arrived after its session ended")
        return posted
    }

    /** Runs [block] on the main thread, guarded: how every outcome reaches FeedbackKit. */
    internal fun onMain(block: () -> Unit) {
        main.post { log.guard("deliver a screen recording result") { block() } }
    }

    /** Session thread: [block], and on a failure the end of the session, whatever state it was in. */
    private fun runGuarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            log.e("Screen recording failed", e)
            // Whatever the state, the session ends: captureFailed fits RECORDING and a started capture,
            // serviceFailed a start before the projection, consentUnavailable a failure while consenting.
            log.guard("end the failed screen recording") {
                perform(machine.captureFailed().ifEmpty { machine.serviceFailed() }.ifEmpty { machine.consentUnavailable() })
            }
        }
    }

    /** Runs [commands] in order, each to completion: abortStart's StopCapture ends before ReleaseService. */
    private fun perform(commands: List<SessionCommand>) = commands.forEach(::execute)

    private fun execute(command: SessionCommand) {
        limit.follow(command) // armed on NotifyStarted, cancelled by every end
        when (command) {
            SessionCommand.StartForegroundService -> startService()
            SessionCommand.StartCapture -> {
                handler.removeCallbacks(serviceTimeout)
                startCapture()
            }
            SessionCommand.StopCapture -> {
                stopIssuedAtMillis = SystemClock.elapsedRealtime()
                stopCapture()
            }
            SessionCommand.ReleaseService -> RecorderRuntime.releaseService(context, id)
            SessionCommand.NotifyStarted -> {
                val at = startedAt
                onMain { events.started(at) }
            }
            is SessionCommand.NotifyNotStarted -> {
                // Whatever a half-started capture wrote is nobody's: the listener hears "not started".
                log.guard("delete the unused screen recording") { sink.discard() }
                end()
                onMain { events.notStarted(command.refused) }
            }
            is SessionCommand.NotifyFinished -> {
                val ok = usable
                end()
                onMain { events.finished(command.reason, ok) }
            }
        }
    }

    private fun startService() {
        try {
            RecorderRuntime.startService(context, id)
        } catch (e: Exception) {
            log.w("Could not start the screen recording service", e)
            perform(machine.serviceFailed())
            return
        }
        handler.postDelayed(serviceTimeout, SERVICE_TIMEOUT_MILLIS)
    }

    /**
     * Runs only once the service is in the foreground (Android 14+ refuses getMediaProjection before).
     * A throw from here reaches [runGuarded]: captureFailed then aborts the start, StopCapture first.
     */
    private fun startCapture() {
        val data = consent
        consent = null // single use on Android 14+
        val manager = context.getSystemService(MediaProjectionManager::class.java)
        val mp = data?.let { manager?.getMediaProjection(resultCode, it) }
        if (mp == null) {
            log.w("The system gave no screen capture")
            perform(machine.captureFailed())
            return
        }
        projection = mp
        projectionAlive = true
        val cb = object : MediaProjection.Callback() {
            override fun onStop() {
                projectionAlive = false
                post { perform(machine.projectionStopped()) }
            }
        }
        callback = cb
        mp.registerCallback(cb, handler) // before createVirtualDisplay: Android 14 requires it
        val video = videoSpec()
        val surface = sink.prepare(video, this)
        display = mp.createVirtualDisplay(
            DISPLAY_NAME, video.width, video.height, video.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, handler,
        )
        sink.start()
        startedAt = SystemClock.elapsedRealtime()
        perform(machine.captureStarted())
    }

    /** Every step guarded, so every StopCapture is answered with captureStopped. */
    private fun stopCapture() {
        // No frame after the encoder's end: the display stops drawing into its surface first.
        log.guard("detach the virtual display") { detachSurface() }
        usable = try {
            sink.stop()
        } catch (e: Exception) {
            log.w("Could not finish the screen recording file", e)
            false
        }
        log.guard("release the virtual display") { display?.release() }
        display = null
        projection?.let { mp ->
            callback?.let { cb -> log.guard("unregister the capture callback") { mp.unregisterCallback(cb) } }
            log.guard("stop the screen capture") { mp.stop() }
        }
        projection = null
        callback = null
        projectionAlive = false
        perform(machine.captureStopped(usable))
    }

    private fun end() {
        handler.removeCallbacks(serviceTimeout)
        limit.cancel()
        RecorderRuntime.remove(id)
        thread.quitSafely() // messages already queued still run; later posts are refused
    }

    private companion object {
        const val THREAD_NAME = "feedbackkit-recording"
        const val DISPLAY_NAME = "feedbackkit-recording"

        /** Well past the 10 s the system itself gives a foreground service start. */
        const val SERVICE_TIMEOUT_MILLIS = 15_000L
    }
}
