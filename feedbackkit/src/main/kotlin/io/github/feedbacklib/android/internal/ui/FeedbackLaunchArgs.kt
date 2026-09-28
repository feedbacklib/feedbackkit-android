package io.github.feedbacklib.android.internal.ui

import android.content.Context
import android.content.Intent
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.enumOrNull
import io.github.feedbacklib.android.internal.invoke.InvocationSource
import io.github.feedbacklib.android.internal.invoke.LaunchRequest
import io.github.feedbacklib.android.internal.report.ProactiveInfo
import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import java.io.File

/** A launch that reopens a report after capture or recording mode (spec §6, §7): what the mode gave. */
internal data class ResumeArgs(
    val extraScreenshot: File?,
    val extraScreenshotFailed: Boolean,
    val recording: File? = null,
    val recordingFailed: Boolean = false,
)

/** What an invocation hands to [FeedbackActivity], and back out of its intent (spec §5). */
internal data class FeedbackLaunchArgs(
    val screenshot: File?,
    val screenshotSecure: Boolean,
    val currentScreen: String?,
    val source: InvocationSource,
    val reportType: ReportType?,
    val screenshotRequested: Boolean = true,
    val resume: ResumeArgs? = null,
    /** Auto Screen Recording's clip, to await from [io.github.feedbacklib.android.internal.recording.PendingClips] (spec §7). */
    val autoRecordingToken: String? = null,
    /**
     * Proactive reporting's prompt (spec §8): what happened to the previous run; the type is then FRUSTRATING_EXPERIENCE.
     * It goes into the report's `proactive` block as is (spec §9). `detectedAt` is the moment of detection — the time
     * of this run's `FeedbackKit.Builder.build()`, ISO-8601 UTC — not when the previous run crashed: the crash
     * marker's own time stays on the device, since §9 has no field for it.
     */
    val proactive: ProactiveInfo? = null,
) {

    fun toIntent(context: Context): Intent =
        Intent(context, FeedbackActivity::class.java)
            .putExtra(EXTRA_SCREENSHOT, screenshot?.absolutePath)
            .putExtra(EXTRA_SECURE, screenshotSecure)
            .putExtra(EXTRA_CURRENT_SCREEN, currentScreen)
            .putExtra(EXTRA_SOURCE, source.name)
            .putExtra(EXTRA_REPORT_TYPE, reportType?.name)
            .putExtra(EXTRA_SCREENSHOT_REQUESTED, screenshotRequested)
            .putExtra(EXTRA_AUTO_RECORDING_TOKEN, autoRecordingToken)
            .apply {
                if (proactive != null) {
                    putExtra(EXTRA_PROACTIVE_TRIGGER, proactive.trigger.name)
                    putExtra(EXTRA_PROACTIVE_DETECTED_AT, proactive.detectedAt)
                    putExtra(EXTRA_PROACTIVE_EXCEPTION, proactive.exception)
                    putExtra(EXTRA_PROACTIVE_STACKTRACE, proactive.stacktrace)
                }
            }
            .apply {
                if (resume != null) {
                    putExtra(EXTRA_RESUME, true)
                    putExtra(EXTRA_EXTRA_SCREENSHOT, resume.extraScreenshot?.absolutePath)
                    putExtra(EXTRA_EXTRA_SCREENSHOT_FAILED, resume.extraScreenshotFailed)
                    putExtra(EXTRA_RECORDING, resume.recording?.absolutePath)
                    putExtra(EXTRA_RECORDING_FAILED, resume.recordingFailed)
                }
            }

    companion object {
        const val EXTRA_SCREENSHOT = "feedbackkit.screenshot"
        const val EXTRA_SECURE = "feedbackkit.screenshotSecure"
        const val EXTRA_CURRENT_SCREEN = "feedbackkit.currentScreen"
        const val EXTRA_SOURCE = "feedbackkit.source"
        const val EXTRA_REPORT_TYPE = "feedbackkit.reportType"
        const val EXTRA_SCREENSHOT_REQUESTED = "feedbackkit.screenshotRequested"
        const val EXTRA_RESUME = "feedbackkit.resume"
        const val EXTRA_EXTRA_SCREENSHOT = "feedbackkit.extraScreenshot"
        const val EXTRA_EXTRA_SCREENSHOT_FAILED = "feedbackkit.extraScreenshotFailed"
        const val EXTRA_RECORDING = "feedbackkit.recording"
        const val EXTRA_RECORDING_FAILED = "feedbackkit.recordingFailed"
        const val EXTRA_AUTO_RECORDING_TOKEN = "feedbackkit.autoRecordingToken"
        const val EXTRA_PROACTIVE_TRIGGER = "feedbackkit.proactiveTrigger"
        const val EXTRA_PROACTIVE_DETECTED_AT = "feedbackkit.proactiveDetectedAt"
        const val EXTRA_PROACTIVE_EXCEPTION = "feedbackkit.proactiveException"
        const val EXTRA_PROACTIVE_STACKTRACE = "feedbackkit.proactiveStacktrace"
        private const val STATE_KEY_PREFIX = "feedbackkit."
        private val LAUNCH_EXTRAS = setOf(
            EXTRA_SCREENSHOT, EXTRA_SECURE, EXTRA_CURRENT_SCREEN, EXTRA_SOURCE, EXTRA_REPORT_TYPE,
            EXTRA_SCREENSHOT_REQUESTED, EXTRA_RESUME, EXTRA_EXTRA_SCREENSHOT, EXTRA_EXTRA_SCREENSHOT_FAILED,
            EXTRA_RECORDING, EXTRA_RECORDING_FAILED, EXTRA_AUTO_RECORDING_TOKEN,
            EXTRA_PROACTIVE_TRIGGER, EXTRA_PROACTIVE_DETECTED_AT, EXTRA_PROACTIVE_EXCEPTION, EXTRA_PROACTIVE_STACKTRACE,
        )

        fun from(request: LaunchRequest): FeedbackLaunchArgs =
            FeedbackLaunchArgs(
                request.screenshot,
                request.screenshotSecure,
                request.currentScreen,
                request.source,
                request.reportType,
                request.screenshotRequested,
                request.resume?.let { ResumeArgs(it.extraScreenshot, it.extraScreenshotFailed, it.recording, it.recordingFailed) },
                request.autoRecordingToken,
                request.proactive,
            )

        /**
         * Never throws: a missing or unknown extra falls back to a manual launch without a type.
         * FRUSTRATING_EXPERIENCE comes only with the proactive block that goes with it (spec §4, §8), and a
         * proactive launch never carries a screenshot or an Auto Screen Recording clip: the user asked for nothing.
         */
        fun fromIntent(intent: Intent): FeedbackLaunchArgs {
            val proactive = enumOrNull<ProactiveTrigger>(intent.getStringExtra(EXTRA_PROACTIVE_TRIGGER))?.let { trigger ->
                ProactiveInfo(
                    trigger = trigger,
                    detectedAt = intent.getStringExtra(EXTRA_PROACTIVE_DETECTED_AT).orEmpty(),
                    exception = intent.getStringExtra(EXTRA_PROACTIVE_EXCEPTION),
                    stacktrace = intent.getStringExtra(EXTRA_PROACTIVE_STACKTRACE),
                )
            }
            return FeedbackLaunchArgs(
                screenshot = if (proactive == null) intent.getStringExtra(EXTRA_SCREENSHOT)?.let(::File) else null,
                screenshotSecure = proactive == null && intent.getBooleanExtra(EXTRA_SECURE, false),
                currentScreen = intent.getStringExtra(EXTRA_CURRENT_SCREEN),
                source = enumOrNull<InvocationSource>(intent.getStringExtra(EXTRA_SOURCE)) ?: InvocationSource.MANUAL,
                reportType = if (proactive != null) {
                    ReportType.FRUSTRATING_EXPERIENCE
                } else {
                    enumOrNull<ReportType>(intent.getStringExtra(EXTRA_REPORT_TYPE))?.takeIf { it != ReportType.FRUSTRATING_EXPERIENCE }
                },
                screenshotRequested = proactive == null && intent.getBooleanExtra(EXTRA_SCREENSHOT_REQUESTED, true),
                resume = if (intent.getBooleanExtra(EXTRA_RESUME, false)) {
                    ResumeArgs(
                        intent.getStringExtra(EXTRA_EXTRA_SCREENSHOT)?.let(::File),
                        intent.getBooleanExtra(EXTRA_EXTRA_SCREENSHOT_FAILED, false),
                        intent.getStringExtra(EXTRA_RECORDING)?.let(::File),
                        intent.getBooleanExtra(EXTRA_RECORDING_FAILED, false),
                    )
                } else {
                    null
                },
                autoRecordingToken = if (proactive == null) intent.getStringExtra(EXTRA_AUTO_RECORDING_TOKEN) else null,
                proactive = proactive,
            )
        }

        /**
         * Puts the report screen's saved [state] into [intent]; the new screen's SavedStateHandle
         * takes intent extras as its defaults, so it starts exactly where the old one stopped. Only
         * the screen's own `feedbackkit.*` keys with String, Boolean or null values travel, and never
         * over the launch's own extras.
         */
        fun putResumeState(intent: Intent, state: Map<String, Any?>) {
            state.forEach { (key, value) ->
                if (!key.startsWith(STATE_KEY_PREFIX) || key in LAUNCH_EXTRAS) return@forEach
                when (value) {
                    is String -> intent.putExtra(key, value)
                    is Boolean -> intent.putExtra(key, value)
                    null -> intent.putExtra(key, null as String?)
                    else -> Unit
                }
            }
        }
    }
}
