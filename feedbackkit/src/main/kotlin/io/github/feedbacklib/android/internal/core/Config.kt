package io.github.feedbacklib.android.internal.core

import io.github.feedbacklib.android.FloatingButtonEdge
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.OnDismissCallback
import io.github.feedbacklib.android.OnInvokeCallback
import io.github.feedbacklib.android.OnReportSubmitHandler
import io.github.feedbacklib.android.ProactiveReportingConfigs
import io.github.feedbacklib.android.RecordingButtonPosition
import io.github.feedbacklib.android.ReportSender
import java.time.Duration

/** Runtime-changeable configuration. */
internal data class Config(
    val enabled: Boolean,
    val logLevel: LogLevel,
    val userEmail: String?,
    val userName: String?,
    val invocationEvents: Set<InvocationEvent> = setOf(InvocationEvent.SHAKE),
    val shakingThreshold: Int = DEFAULT_SHAKING_THRESHOLD,
    val floatingButtonEdge: FloatingButtonEdge = FloatingButtonEdge.RIGHT,
    val floatingButtonOffsetDp: Int = DEFAULT_FLOATING_BUTTON_OFFSET_DP,
    val recordingButtonPosition: RecordingButtonPosition = RecordingButtonPosition.BOTTOM_RIGHT,
    /** Auto Screen Recording [beta] (spec §7); only takes effect with feedbackkit-recording. */
    val autoScreenRecording: Boolean = false,
    val bugReportingEnabled: Boolean = true,
    /** Proactive reporting (spec §8); detection runs whatever this says, the prompt only when enabled. */
    val proactive: ProactiveSettings = ProactiveSettings(),
    val ui: ReportUiConfig = ReportUiConfig(),
) {
    /** Whether anything may open the report screen: the SDK and bug reporting are both on. */
    val canInvoke: Boolean
        get() = enabled && bugReportingEnabled

    companion object {
        const val DEFAULT_SHAKING_THRESHOLD: Int = 650
        const val DEFAULT_FLOATING_BUTTON_OFFSET_DP: Int = 200
    }
}

/** Everything build() starts the runtime with: Builder values plus setters called before build(). */
internal data class Settings(
    val enabled: Boolean = true,
    val logLevel: LogLevel = LogLevel.WARNING,
    val sender: ReportSender? = null,
    val userEmail: String? = null,
    val userName: String? = null,
    val submitHandler: OnReportSubmitHandler? = null,
    val invocationEvents: Set<InvocationEvent> = setOf(InvocationEvent.SHAKE),
    val shakingThreshold: Int = Config.DEFAULT_SHAKING_THRESHOLD,
    val floatingButtonEdge: FloatingButtonEdge = FloatingButtonEdge.RIGHT,
    val floatingButtonOffsetDp: Int = Config.DEFAULT_FLOATING_BUTTON_OFFSET_DP,
    val recordingButtonPosition: RecordingButtonPosition = RecordingButtonPosition.BOTTOM_RIGHT,
    val autoScreenRecording: Boolean = false,
    val bugReportingEnabled: Boolean = true,
    val proactive: ProactiveSettings = ProactiveSettings(),
    val onInvokeCallback: OnInvokeCallback? = null,
    val ui: ReportUiConfig = ReportUiConfig(),
    val onDismissCallback: OnDismissCallback? = null,
)

/** `NONE` anywhere in the set means "manual only". */
internal fun normalizeEvents(events: Array<out InvocationEvent>): Set<InvocationEvent> =
    if (InvocationEvent.NONE in events) emptySet() else events.toSet()

/** [ProactiveReportingConfigs] as the runtime reads it (spec §8), in milliseconds. */
internal data class ProactiveSettings(
    val enabled: Boolean = false,
    val gapMillis: Long = DEFAULT_GAP_MILLIS,
    val delayMillis: Long = DEFAULT_DELAY_MILLIS,
) {
    companion object {
        const val DEFAULT_GAP_MILLIS: Long = 24 * 60 * 60 * 1000L
        const val DEFAULT_DELAY_MILLIS: Long = 2_000L

        /** The longest wait before the prompt: a Handler delay far beyond it overflows and fires at once. */
        const val MAX_DELAY_MILLIS: Long = 60 * 60 * 1000L

        fun from(configs: ProactiveReportingConfigs): ProactiveSettings = ProactiveSettings(
            enabled = configs.isEnabled,
            gapMillis = configs.gapBetweenModals.millisSaturated(),
            delayMillis = configs.modalDelayAfterDetection.millisSaturated().coerceAtMost(MAX_DELAY_MILLIS),
        )

        private fun Duration.millisSaturated(): Long =
            try {
                toMillis()
            } catch (e: ArithmeticException) {
                Long.MAX_VALUE // a duration beyond Long milliseconds: "never" is what the host meant
            }
    }
}
