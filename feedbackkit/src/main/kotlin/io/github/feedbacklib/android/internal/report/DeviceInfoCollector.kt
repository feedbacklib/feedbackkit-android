package io.github.feedbacklib.android.internal.report

import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.StatFs
import java.util.Locale

/** Device context for `report.json` (spec §9). Never throws. */
internal class DeviceInfoCollector(private val context: Context) {

    fun collect(): DeviceInfo {
        val resources = context.resources
        val metrics = resources.displayMetrics
        return DeviceInfo(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            locale = Locale.getDefault().toLanguageTag(),
            orientation = orientation(resources.configuration.orientation),
            screen = "${metrics.widthPixels}x${metrics.heightPixels}@${metrics.densityDpi}",
            freeMemoryMb = freeMemoryMb(),
            freeDiskMb = freeDiskMb(),
            networkType = networkType(),
        )
    }

    private fun orientation(value: Int): String = when (value) {
        Configuration.ORIENTATION_PORTRAIT -> "portrait"
        Configuration.ORIENTATION_LANDSCAPE -> "landscape"
        else -> "undefined"
    }

    private fun freeMemoryMb(): Long =
        try {
            val activityManager = context.getSystemService(ActivityManager::class.java) ?: return -1
            val info = ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(info)
            info.availMem / BYTES_IN_MB
        } catch (e: RuntimeException) {
            -1
        }

    private fun freeDiskMb(): Long =
        try {
            StatFs(context.filesDir.path).availableBytes / BYTES_IN_MB
        } catch (e: RuntimeException) {
            -1
        }

    private fun networkType(): String {
        return try {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return "unknown"
            val capabilities = connectivity.getNetworkCapabilities(connectivity.activeNetwork) ?: return "none"
            when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                else -> "other"
            }
        } catch (e: RuntimeException) {
            "unknown"
        }
    }

    private companion object {
        const val BYTES_IN_MB = 1024L * 1024L
    }
}
