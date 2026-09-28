package io.github.feedbacklib.android.recording.internal

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import io.github.feedbacklib.android.recording.R
import io.github.feedbacklib.android.spi.SdkActivity
import kotlin.math.roundToInt

/**
 * Transparent host of the screen capture consent (spec §7), in two steps shared by manual and auto
 * recording: our privacy notice (hidden fields are not masked, protected screens are not recorded),
 * then the system dialog. Cancel or back on the notice is a refusal, as on the system dialog. An
 * [SdkActivity], so FeedbackKit neither counts it as the host screen nor puts controls over it. On
 * API 34+ the consent is for the whole default display: no single-app choice to explain.
 *
 * The step survives recreation (outState), and every way out — an answer, a dismissal, a failure to
 * open — reaches the session exactly once.
 */
internal class ConsentActivity : Activity(), SdkActivity {

    private val log get() = RecorderRuntime.log

    private var sessionId: String? = null
    private var answered = false

    /** The notice was accepted: the system dialog comes next. */
    private var acknowledged = false

    /** The system dialog was asked for: its answer comes to onActivityResult, also after recreation. */
    private var requested = false

    private var notice: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
        if (RecorderRuntime.session(sessionId) == null) {
            // The process died while the dialog was up: nobody waits for this answer any more.
            answered = true
            finishQuietly()
            return
        }
        acknowledged = savedInstanceState?.getBoolean(STATE_ACKNOWLEDGED) ?: false
        requested = savedInstanceState?.getBoolean(STATE_REQUESTED) ?: false
        when {
            requested -> Unit
            acknowledged -> requestConsent()
            else -> showNotice()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ACKNOWLEDGED, acknowledged)
        outState.putBoolean(STATE_REQUESTED, requested)
    }

    private fun showNotice() {
        try {
            val dialog = AlertDialog.Builder(this, R.style.feedbackkit_recording_NoticeTheme)
                .setMessage(R.string.feedbackkit_recording_privacy_notice)
                .setPositiveButton(R.string.feedbackkit_recording_privacy_continue) { _, _ -> onNoticeAccepted() }
                .setNegativeButton(R.string.feedbackkit_recording_privacy_cancel) { _, _ -> onNoticeDeclined() }
                .setOnCancelListener { onNoticeDeclined() } // back
                .create()
            dialog.setCanceledOnTouchOutside(false) // an answer, not a stray tap
            notice = dialog
            dialog.show()
            val touchTarget = (MIN_TOUCH_TARGET_DP * resources.displayMetrics.density).roundToInt()
            for (which in intArrayOf(DialogInterface.BUTTON_POSITIVE, DialogInterface.BUTTON_NEGATIVE)) {
                dialog.getButton(which)?.apply {
                    minHeight = touchTarget
                    minimumHeight = touchTarget
                    minWidth = touchTarget
                    minimumWidth = touchTarget
                }
            }
        } catch (e: Exception) {
            log.w("The screen recording privacy notice could not open", e)
            lose()
        }
    }

    private fun onNoticeAccepted() {
        notice = null
        acknowledged = true
        requestConsent()
    }

    private fun onNoticeDeclined() {
        notice = null
        if (answered) return
        answered = true
        RecorderRuntime.session(sessionId)?.onConsent(RESULT_CANCELED, null)
        finishQuietly()
    }

    private fun requestConsent() {
        try {
            val manager = checkNotNull(getSystemService(MediaProjectionManager::class.java))
            val request = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
            } else {
                manager.createScreenCaptureIntent()
            }
            startActivityForResult(request, REQUEST_CONSENT)
            requested = true
        } catch (e: Exception) {
            log.w("The screen capture consent could not open", e)
            lose()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CONSENT || answered) return
        answered = true
        RecorderRuntime.session(sessionId)?.onConsent(resultCode, data)
        finishQuietly()
    }

    override fun onDestroy() {
        // Dismissed, not cancelled: a recreation shows the notice again, a finish is handled below.
        notice?.let { dialog -> log.guard("close the privacy notice") { dialog.dismiss() } }
        notice = null
        super.onDestroy()
        if (isFinishing && !answered) {
            answered = true
            RecorderRuntime.session(sessionId)?.onConsentLost()
        }
    }

    /** Could not ask: the session ends as "not started", not as a refusal. */
    private fun lose() {
        if (!answered) {
            answered = true
            RecorderRuntime.session(sessionId)?.onConsentLost()
        }
        finishQuietly()
    }

    private fun finishQuietly() {
        finish()
        @Suppress("DEPRECATION") // overrideActivityTransition needs API 34; this one works everywhere
        overridePendingTransition(0, 0)
    }

    companion object {
        private const val EXTRA_SESSION_ID = "feedbackkit.recording.session"
        private const val STATE_ACKNOWLEDGED = "feedbackkit.recording.acknowledged"
        private const val STATE_REQUESTED = "feedbackkit.recording.requested"
        private const val REQUEST_CONSENT = 0x464B
        private const val MIN_TOUCH_TARGET_DP = 48

        fun intent(context: Context, sessionId: String): Intent =
            Intent(context, ConsentActivity::class.java).putExtra(EXTRA_SESSION_ID, sessionId)
    }
}
