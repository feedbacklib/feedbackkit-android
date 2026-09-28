package io.github.feedbacklib.android.internal.report

import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.ReportType
import kotlinx.serialization.Serializable

/** `report.json`, schema version 1 (spec §9). Property names are the wire contract. */
@Serializable
internal data class ReportPayload(
    val schemaVersion: Int = SCHEMA_VERSION,
    val id: String,
    val cid: String,
    val type: ReportType,
    val createdAt: String,
    val email: String?,
    val comment: String,
    val extended: ExtendedFields?,
    val proactive: ProactiveInfo?,
    val tags: List<String>,
    val userAttributes: Map<String, String>,
    val userData: String?,
    val consoleLog: List<String>,
    val attachments: List<AttachmentMeta>,
    val device: DeviceInfo,
    val app: AppInfo,
    val currentScreen: String?,
) {
    companion object {
        const val SCHEMA_VERSION: Int = 1
    }
}

@Serializable
internal data class ExtendedFields(
    val steps: String,
    val actual: String,
    val expected: String,
)

@Serializable
internal enum class ProactiveTrigger { CRASH, FORCE_RESTART }

@Serializable
internal data class ProactiveInfo(
    val trigger: ProactiveTrigger,
    val detectedAt: String,
    val exception: String?,
    val stacktrace: String?,
)

@Serializable
internal data class AttachmentMeta(
    val id: String,
    val kind: AttachmentKind,
    val fileName: String,
    val mimeType: String,
    val sizeBytes: Long,
)

@Serializable
internal data class DeviceInfo(
    val manufacturer: String,
    val model: String,
    val osVersion: String,
    val apiLevel: Int,
    val locale: String,
    val orientation: String,
    val screen: String,
    val freeMemoryMb: Long,
    val freeDiskMb: Long,
    val networkType: String,
) {
    /** Used when the device context could not be collected, so the report is queued anyway. */
    companion object {
        val UNKNOWN: DeviceInfo = DeviceInfo(
            manufacturer = "",
            model = "",
            osVersion = "",
            apiLevel = 0,
            locale = "",
            orientation = "undefined",
            screen = "",
            freeMemoryMb = -1,
            freeDiskMb = -1,
            networkType = "unknown",
        )
    }
}

@Serializable
internal data class AppInfo(
    val packageName: String,
    val versionName: String?,
    val versionCode: Long,
    val sdkVersion: String,
) {
    companion object {
        /** Used when the app context could not be collected, so the report is queued anyway. */
        fun unknown(sdkVersion: String): AppInfo = AppInfo(
            packageName = "",
            versionName = null,
            versionCode = 0,
            sdkVersion = sdkVersion,
        )
    }
}
