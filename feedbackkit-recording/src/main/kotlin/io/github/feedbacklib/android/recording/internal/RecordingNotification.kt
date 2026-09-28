package io.github.feedbacklib.android.recording.internal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import io.github.feedbacklib.android.recording.R

/** The ongoing notification of the mediaProjection service; a tap brings the app back. */
internal object RecordingNotification {

    const val ID: Int = 0x464B52 // "FKR"
    private const val CHANNEL = "feedbackkit_recording"

    /**
     * Never throws: the service must reach startForeground whatever happens here, so a failure falls
     * back to the bare notification with the system's own icon.
     */
    fun build(context: Context): Notification {
        RecorderRuntime.log.guard("create the screen recording notification channel") { ensureChannel(context) }
        return try {
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
                ?.let { PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE) }
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.feedbackkit_recording_ic_notification)
                .setContentTitle(context.getString(R.string.feedbackkit_recording_notification_title))
                .setContentText(context.getString(R.string.feedbackkit_recording_notification_text))
                .setOngoing(true)
                .setContentIntent(open)
                .build()
        } catch (e: Exception) {
            RecorderRuntime.log.w("Could not build the screen recording notification", e)
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setOngoing(true)
                .build()
        }
    }

    /** Every time: creating an existing channel updates its name to the current locale. */
    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.feedbackkit_recording_channel), NotificationManager.IMPORTANCE_LOW),
        )
    }
}
