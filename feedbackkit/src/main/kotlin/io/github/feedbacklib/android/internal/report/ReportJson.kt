package io.github.feedbacklib.android.internal.report

import kotlinx.serialization.json.Json

/** The single JSON configuration for `report.json`. */
internal object ReportJson {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = true
        ignoreUnknownKeys = true
    }

    fun encode(payload: ReportPayload): String = json.encodeToString(ReportPayload.serializer(), payload)

    fun decode(text: String): ReportPayload = json.decodeFromString(ReportPayload.serializer(), text)
}
