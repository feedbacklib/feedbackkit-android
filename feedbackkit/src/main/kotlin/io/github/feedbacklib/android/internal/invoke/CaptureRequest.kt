package io.github.feedbacklib.android.internal.invoke

import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.enumOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * A report waiting for an extra screenshot (spec §6): what reopens it. [state] is the report
 * screen's saved state — String, Boolean or null values under its own `feedbackkit.*` keys — which
 * the reopening intent carries, so the new screen's SavedStateHandle starts from it.
 */
internal data class CaptureRequest(
    val draftId: String,
    val reportType: ReportType,
    val state: Map<String, Any?>,
    val currentScreen: String?,
)

/** How a report reopens after capture or recording mode: its saved state and what the mode gave. */
internal data class ResumeRequest(
    val state: Map<String, Any?>,
    val extraScreenshot: File?,
    val extraScreenshotFailed: Boolean,
    /** A finished manual screen recording (spec §7), still in the recording cache. */
    val recording: File? = null,
    /** A recording ended without usable video. */
    val recordingFailed: Boolean = false,
)

/** [CaptureRequest] as `capture.json` in the draft, so capture mode survives process death. */
internal object CaptureRequestJson {

    private const val VERSION = 1

    fun encode(request: CaptureRequest): String = buildJsonObject {
        put("version", VERSION)
        put("draftId", request.draftId)
        put("reportType", request.reportType.name)
        put("currentScreen", request.currentScreen)
        putJsonObject("state") {
            request.state.forEach { (key, value) ->
                when (value) {
                    is String -> put(key, value)
                    is Boolean -> put(key, value)
                    null -> put(key, JsonNull)
                    else -> Unit // the report screen saves nothing else
                }
            }
        }
    }.toString()

    /** Never throws: a damaged, foreign or newer file is no request. */
    fun decode(text: String): CaptureRequest? =
        try {
            val root = Json.parseToJsonElement(text).jsonObject
            if (root["version"]?.jsonPrimitive?.intOrNull != VERSION) return null
            val draftId = root["draftId"]?.jsonPrimitive?.contentOrNull ?: return null
            val type = enumOrNull<ReportType>(root["reportType"]?.jsonPrimitive?.contentOrNull) ?: return null
            // As encode(): text, flags and null; anything else (a number, an object) is dropped.
            val state = buildMap {
                root["state"]?.jsonObject?.forEach { (key, value) ->
                    when {
                        value is JsonNull -> put(key, null)
                        value is JsonPrimitive && value.isString -> put(key, value.content)
                        value is JsonPrimitive -> value.booleanOrNull?.let { put(key, it) }
                    }
                }
            }
            CaptureRequest(draftId, type, state, root["currentScreen"]?.jsonPrimitive?.contentOrNull)
        } catch (e: IllegalArgumentException) { // SerializationException and a non-object root are both
            null
        }
}
