package io.github.feedbacklib.android.recording.internal

import android.app.Activity
import android.content.Context
import android.content.Intent
import io.github.feedbacklib.android.spi.RecorderLog
import java.util.concurrent.ConcurrentHashMap

/**
 * The live sessions of this process, found by id from [ConsentActivity] and [RecordingService]
 * (their intents carry only the id), and who holds the one foreground service. A session holds it
 * from StartForegroundService to ReleaseService; the service stops when nobody does.
 *
 * A service asked for with startForegroundService must reach startForeground before it is stopped,
 * or the system crashes the app. So the last release stops it only once it runs in the foreground;
 * one still on its way to onStartCommand goes into the foreground there, sees nobody holds it and
 * stops itself. Holders and that flag change under one lock, so a release and a start never
 * interleave.
 */
internal object RecorderRuntime {

    /** Set by the provider when FeedbackKit creates the recorder. */
    @Volatile
    var log: RecorderLog = RecorderLog { _, _, _ -> }

    private val sessions = ConcurrentHashMap<String, ProjectionSession>()

    private val serviceLock = Any()

    // Under serviceLock.
    private val serviceHolders = mutableSetOf<String>()
    private var serviceForeground = false

    /** A new session: the privacy notice and the consent over [host]. Main thread. */
    fun start(host: Activity, sink: CaptureSink, events: SessionEvents, log: RecorderLog): ProjectionSession {
        val session = ProjectionSession(host.applicationContext, sink, events, log)
        sessions[session.id] = session
        session.launchConsent(host)
        return session
    }

    fun session(id: String?): ProjectionSession? = id?.let(sessions::get)

    fun remove(id: String) {
        sessions.remove(id)
    }

    val holdsService: Boolean
        get() = synchronized(serviceLock) { serviceHolders.isNotEmpty() }

    /** Android 14+ allows getMediaProjection only while this service runs in the foreground. */
    fun startService(context: Context, id: String) {
        synchronized(serviceLock) {
            serviceHolders += id
            try {
                context.startForegroundService(RecordingService.intent(context, id))
            } catch (e: Exception) {
                serviceHolders -= id
                throw e
            }
        }
    }

    fun releaseService(context: Context, id: String) {
        synchronized(serviceLock) {
            if (!serviceHolders.remove(id) || serviceHolders.isNotEmpty() || !serviceForeground) return
            serviceForeground = false
            log.guard("stop the screen recording service") { context.stopService(Intent(context, RecordingService::class.java)) }
        }
    }

    /**
     * The service reached onStartCommand, in the foreground or not; whether it must stop itself now:
     * nobody holds it any more, or it runs in the foreground for nobody.
     */
    fun onServiceStarted(inForeground: Boolean): Boolean = synchronized(serviceLock) {
        if (inForeground) serviceForeground = true
        serviceHolders.isEmpty() || !serviceForeground
    }

    fun onServiceDestroyed() {
        synchronized(serviceLock) { serviceForeground = false }
    }

    fun onServiceForeground(id: String?) {
        val session = session(id)
        if (session == null) log.d("The recording service started for a session that is gone") else session.onServiceReady()
    }

    fun onServiceFailed(id: String?) {
        session(id)?.onServiceFailed()
    }
}
