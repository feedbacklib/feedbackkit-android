package io.github.feedbacklib.android.internal.proactive

import android.app.Application
import android.os.Build
import io.github.feedbacklib.android.internal.core.SdkLogger
import java.io.File

/**
 * Whether this is the app's main process, the one named after its package (spec §8). Proactive
 * reporting runs only there: another process's crashes are not the app's session, and it must not
 * consume the main process's marker. A name that cannot be read counts as the main process.
 * API 28+ asks [Application.getProcessName]; API 26–27 reads `/proc/self/cmdline`, which the kernel
 * serves from memory, so build() on the main thread does no disk I/O either way.
 */
internal fun isMainProcess(app: Application, logger: SdkLogger): Boolean {
    val name = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            processNameOf(File("/proc/self/cmdline").readBytes())
        }
    } catch (e: Exception) {
        logger.w("Could not read the process name; taken as the main process", e)
        null
    }
    if (name.isNullOrEmpty() || name == app.packageName) return true
    logger.d("Proactive reporting stays off in process $name: it is not the app's main process")
    return false
}

/** The process name in `/proc/self/cmdline`: its first NUL-terminated argument, or null if empty. */
internal fun processNameOf(cmdline: ByteArray): String? =
    String(cmdline, Charsets.UTF_8).substringBefore('\u0000').trim().ifEmpty { null }
