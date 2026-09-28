package io.github.feedbacklib.android.recording.internal

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * The mediaProjection foreground service (spec §7). startForeground comes first, before anything that
 * could fail: a service started with startForegroundService that never calls it crashes the app. Only
 * then is the session told it may ask for the projection.
 *
 * If startForeground itself throws, the session is told and ends as "not started", and the service
 * stops itself; the system may still crash the app for the missing startForeground — that part is out
 * of reach of any catch.
 */
internal class RecordingService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val log = RecorderRuntime.log
        val id = intent?.getStringExtra(EXTRA_SESSION_ID)
        val foreground = try {
            val notification = RecordingNotification.build(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(RecordingNotification.ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(RecordingNotification.ID, notification)
            }
            true
        } catch (e: Exception) {
            log.e("The screen recording service could not run in the foreground", e)
            false
        }
        val idle = RecorderRuntime.onServiceStarted(foreground)
        log.guard("tell the screen recording session about its service") {
            if (foreground) RecorderRuntime.onServiceForeground(id) else RecorderRuntime.onServiceFailed(id)
        }
        // Started for nobody (a session already released it), or of no use: stop, unless a later start
        // is already on its way.
        if (idle) log.guard("stop the idle screen recording service") { stopSelf(startId) }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        RecorderRuntime.onServiceDestroyed()
        RecorderRuntime.log.guard("leave the foreground") { stopForeground(STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SESSION_ID = "feedbackkit.recording.session"

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, RecordingService::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
