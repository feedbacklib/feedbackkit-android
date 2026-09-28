package io.github.feedbacklib.android.internal.invoke

import android.Manifest
import android.app.Activity
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.github.feedbacklib.android.internal.core.SdkLogger
import java.util.Locale
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicLong

internal interface ScreenCaptureRegistrar {
    fun register(activity: Activity, callback: Activity.ScreenCaptureCallback)
    fun unregister(activity: Activity, callback: Activity.ScreenCaptureCallback)
}

@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal object PlatformScreenCaptureRegistrar : ScreenCaptureRegistrar {
    override fun register(activity: Activity, callback: Activity.ScreenCaptureCallback) =
        activity.registerScreenCaptureCallback(activity.mainExecutor, callback)

    override fun unregister(activity: Activity, callback: Activity.ScreenCaptureCallback) =
        activity.unregisterScreenCaptureCallback(callback)
}

/** Android 14+: the platform tells the resumed activity that the user took a screenshot. */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal class ScreenCaptureCallbackDetector(
    private val registrar: ScreenCaptureRegistrar,
    private val onScreenshot: () -> Unit,
    private val logger: SdkLogger,
) : ActivityDetector {

    private val callbacks = WeakHashMap<Activity, Activity.ScreenCaptureCallback>()

    override fun attach(activity: Activity) {
        if (callbacks.containsKey(activity)) return
        val callback = Activity.ScreenCaptureCallback {
            try {
                onScreenshot()
            } catch (e: Exception) {
                logger.e("Screenshot invocation failed", e)
            }
        }
        try {
            registrar.register(activity, callback)
            callbacks[activity] = callback
        } catch (e: Exception) {
            logger.w("Screenshot invocation unavailable on this screen", e)
        }
    }

    override fun detach(activity: Activity) {
        val callback = callbacks.remove(activity) ?: return
        try {
            registrar.unregister(activity, callback)
        } catch (e: Exception) {
            logger.w("Could not stop screenshot detection", e)
        }
    }
}

internal object ScreenshotHeuristics {

    private const val MAX_AGE_MILLIS = 10_000L

    fun isScreenshot(displayName: String?, path: String?, dateAddedSeconds: Long, nowMillis: Long): Boolean {
        if (nowMillis - dateAddedSeconds * 1_000 > MAX_AGE_MILLIS) return false
        val haystack = "${displayName.orEmpty()} ${path.orEmpty()}".lowercase(Locale.ROOT)
        return "screenshot" in haystack
    }
}

/**
 * Android 8–13: watches MediaStore for a fresh screenshot — only when the host already holds the
 * media read permission; the SDK never asks for it (spec §5). The query runs off the main thread
 * (a background [HandlerThread]) so a large media library cannot cause an ANR. A quick stop()/start()
 * cycle can leave the previous thread finishing an in-flight [android.database.ContentObserver.onChange]
 * concurrently with the new one, so both `observer` (to tell a stale callback from the active one) and
 * the debounce timestamp are safe to touch from either thread.
 */
internal class MediaStoreScreenshotDetector(
    private val context: Context,
    private val onScreenshot: () -> Unit,
    private val logger: SdkLogger,
    private val clock: () -> Long = System::currentTimeMillis,
) : ProcessDetector {

    private var thread: HandlerThread? = null
    @Volatile
    private var observer: ContentObserver? = null
    private var warned = false
    private val lastFiredAt = AtomicLong(0)

    override fun start() {
        if (observer != null) return
        if (!hasPermission()) {
            if (!warned) logger.w("Screenshot invocation needs the host's media read permission; it stays off")
            warned = true
            return
        }
        val startedThread = HandlerThread("feedbackkit-screenshots").apply { start() }
        val created = object : ContentObserver(Handler(startedThread.looper)) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (observer !== this) return
                onMediaChanged()
            }
        }
        try {
            context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, created)
            thread = startedThread
            observer = created
        } catch (e: Exception) {
            logger.w("Screenshot invocation unavailable", e)
            startedThread.quitSafely()
        }
    }

    override fun stop() {
        val current = observer ?: return
        observer = null
        try {
            context.contentResolver.unregisterContentObserver(current)
        } catch (e: Exception) {
            logger.w("Could not stop screenshot detection", e)
        }
        thread?.quitSafely()
        thread = null
    }

    private fun hasPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun onMediaChanged() {
        try {
            val now = clock()
            val last = lastFiredAt.get()
            if (now - last < DEBOUNCE_MILLIS) return
            if (!latestIsScreenshot(now)) return
            if (lastFiredAt.compareAndSet(last, now)) onScreenshot()
        } catch (e: Exception) {
            logger.w("Screenshot detection failed", e)
        }
    }

    @Suppress("DEPRECATION") // DATA is the only path column before Android 10.
    private fun latestIsScreenshot(now: Long): Boolean {
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            MediaStore.Images.Media.DATA
        }
        val projection = arrayOf(MediaStore.Images.Media.DISPLAY_NAME, pathColumn, MediaStore.Images.Media.DATE_ADDED)
        val args = Bundle().apply {
            putStringArray(ContentResolver.QUERY_ARG_SORT_COLUMNS, arrayOf(MediaStore.Images.Media.DATE_ADDED))
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            args,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return false
            return ScreenshotHeuristics.isScreenshot(cursor.getString(0), cursor.getString(1), cursor.getLong(2), now)
        }
        return false
    }

    private companion object {
        const val DEBOUNCE_MILLIS = 2_000L
    }
}

internal fun screenshotDetectors(
    context: Context,
    onScreenshot: () -> Unit,
    logger: SdkLogger,
): Pair<ProcessDetector?, ActivityDetector?> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        null to ScreenCaptureCallbackDetector(PlatformScreenCaptureRegistrar, onScreenshot, logger)
    } else {
        MediaStoreScreenshotDetector(context, onScreenshot, logger) to null
    }
