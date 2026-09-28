package io.github.feedbacklib.android.sample

import android.content.Context
import androidx.core.content.edit
import io.github.feedbacklib.android.ProactiveReportingConfigs
import java.util.concurrent.TimeUnit

/**
 * Sample settings that FeedbackKit needs before build(): proactive reporting looks at the previous run
 * once, when SampleApplication starts. Kept in SharedPreferences so they survive the crash they test.
 * On by default, with no gap: the sample exists to try it.
 */
object SampleSettings {
    private const val PREFS = "feedbackkit-sample"
    private const val PROACTIVE = "proactive"
    private const val PROACTIVE_NO_GAP = "proactiveNoGap"

    fun proactiveEnabled(context: Context): Boolean = prefs(context).getBoolean(PROACTIVE, true)

    fun proactiveNoGap(context: Context): Boolean = prefs(context).getBoolean(PROACTIVE_NO_GAP, true)

    fun setProactive(context: Context, enabled: Boolean, noGap: Boolean) {
        prefs(context).edit {
            putBoolean(PROACTIVE, enabled)
            putBoolean(PROACTIVE_NO_GAP, noGap)
        }
    }

    fun proactiveConfigs(context: Context): ProactiveReportingConfigs =
        ProactiveReportingConfigs.Builder()
            .isEnabled(proactiveEnabled(context))
            .apply { if (proactiveNoGap(context)) setGapBetweenModals(0, TimeUnit.SECONDS) }
            .build()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
