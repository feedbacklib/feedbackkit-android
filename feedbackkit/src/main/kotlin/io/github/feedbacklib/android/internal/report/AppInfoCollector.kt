package io.github.feedbacklib.android.internal.report

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import io.github.feedbacklib.android.FeedbackKitInfo

/** Host app context for `report.json` (spec §9). Never throws. */
internal class AppInfoCollector(private val context: Context) {

    fun collect(): AppInfo {
        val info = packageInfo()
        return AppInfo(
            packageName = context.packageName,
            versionName = info?.versionName,
            versionCode = info?.let(::versionCode) ?: 0L,
            sdkVersion = FeedbackKitInfo.VERSION,
        )
    }

    private fun packageInfo(): PackageInfo? =
        try {
            val manager = context.packageManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, 0)
            }
        } catch (e: PackageManager.NameNotFoundException) {
            null
        } catch (e: RuntimeException) {
            null
        }

    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }
}
