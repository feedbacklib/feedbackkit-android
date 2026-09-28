package io.github.feedbacklib.android

import io.github.feedbacklib.android.internal.core.ProactiveSettings
import io.github.feedbacklib.android.internal.core.SdkLog
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Proactive reporting: after the app crashed, froze (ANR) or was closed by force and restarted within
 * seconds, FeedbackKit asks on the next start — "Looks like something went wrong. Would you tell us
 * what happened?" — and "Tell us" opens a [ReportType.FRUSTRATING_EXPERIENCE] report that carries the
 * crash's exception and stack trace. Nothing is sent unless the user sends that report: a crash on its
 * own is never reported. Off by default. Pass it to [BugReporting.setProactiveReportingConfigurations].
 */
public class ProactiveReportingConfigs private constructor(
    /** Whether the prompt may show; false by default. */
    public val isEnabled: Boolean,
    /** At least this long between two prompts; 24 hours by default. */
    public val gapBetweenModals: Duration,
    /** How long the prompt waits after the first screen of the app has shown; 2 seconds by default. */
    public val modalDelayAfterDetection: Duration,
) {

    public class Builder {
        private var enabled = false
        private var gap: Duration = DEFAULT_GAP
        private var delay: Duration = DEFAULT_DELAY

        /** Switches the prompt on or off; off by default. */
        public fun isEnabled(enabled: Boolean): Builder = apply { this.enabled = enabled }

        /**
         * The shortest time between two prompts; 24 hours by default. Zero asks after every crash or
         * force restart. A negative value is ignored with a warning in logcat.
         */
        public fun setGapBetweenModals(gap: Duration): Builder = apply { this.gap = checked("setGapBetweenModals()", gap, this.gap) }

        /** [setGapBetweenModals] in [unit]s. */
        public fun setGapBetweenModals(gap: Long, unit: TimeUnit): Builder = setGapBetweenModals(Duration.ofMillis(unit.toMillis(gap)))

        /**
         * How long the prompt waits once the first screen of the app is showing; 2 seconds by default,
         * at most an hour. A negative value is ignored with a warning in logcat.
         */
        public fun setModalDelayAfterDetection(delay: Duration): Builder = apply { this.delay = checked("setModalDelayAfterDetection()", delay, this.delay) }

        /** [setModalDelayAfterDetection] in [unit]s. */
        public fun setModalDelayAfterDetection(delay: Long, unit: TimeUnit): Builder = setModalDelayAfterDetection(Duration.ofMillis(unit.toMillis(delay)))

        public fun build(): ProactiveReportingConfigs = ProactiveReportingConfigs(enabled, gap, delay)

        private fun checked(call: String, value: Duration, current: Duration): Duration {
            if (!value.isNegative) return value
            SdkLog.logger.w("$call ignored: the duration must not be negative")
            return current
        }
    }

    override fun toString(): String =
        "ProactiveReportingConfigs(isEnabled=$isEnabled, gapBetweenModals=$gapBetweenModals, modalDelayAfterDetection=$modalDelayAfterDetection)"

    private companion object {
        val DEFAULT_GAP: Duration = Duration.ofMillis(ProactiveSettings.DEFAULT_GAP_MILLIS)
        val DEFAULT_DELAY: Duration = Duration.ofMillis(ProactiveSettings.DEFAULT_DELAY_MILLIS)
    }
}
