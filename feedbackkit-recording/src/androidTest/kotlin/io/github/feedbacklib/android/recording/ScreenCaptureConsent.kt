package io.github.feedbacklib.android.recording

import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.fail
import java.util.regex.Pattern

/**
 * Answers the two consent steps: FeedbackKit's privacy notice (Continue / Cancel, found in this
 * package by their string resources), then the system screen capture dialog. With
 * MediaProjectionConfig.createConfigForDefaultDisplay (API 34+) that one has no single-app choice:
 * just Start and Cancel, as `button1`/`button2` or by text.
 */
internal object ScreenCaptureConsent {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    private val device: UiDevice
        get() = UiDevice.getInstance(instrumentation)

    private val START = Pattern.compile("(?i)^(start|start now|start recording)$")
    private val CANCEL = Pattern.compile("(?i)^(cancel|don.t allow)$")

    /** The dialog shows only on an awake, unlocked screen. */
    fun prepareDevice() {
        device.wakeUp()
        device.executeShellCommand("wm dismiss-keyguard")
    }

    /** Our notice's text, as the user reads it. */
    val noticeText: String
        get() = string(R.string.feedbackkit_recording_privacy_notice)

    /** Waits until our privacy notice is on screen. */
    fun awaitNotice() {
        if (!device.wait(Until.hasObject(notice()), 15_000)) fail("The privacy notice did not show")
    }

    /** Whether our privacy notice shows within [timeoutMillis]: false proves nobody asked. */
    fun noticeAppears(timeoutMillis: Long): Boolean = device.wait(Until.hasObject(notice()), timeoutMillis)

    /** Continue on our notice; the system dialog comes next. */
    fun continueNotice() = answerNotice(R.string.feedbackkit_recording_privacy_continue)

    /** Cancel on our notice: a refusal, no system dialog. */
    fun cancelNotice() = answerNotice(R.string.feedbackkit_recording_privacy_cancel)

    /** Continue on our notice, then Start on the system dialog. */
    fun accept() {
        continueNotice()
        tap(By.res("android", "button1"), By.text(START), "Start")
    }

    /** Continue on our notice, then Cancel on the system dialog. */
    fun decline() {
        continueNotice()
        tap(By.res("android", "button2"), By.text(CANCEL), "Cancel")
    }

    private fun answerNotice(label: Int) {
        awaitNotice()
        // Buttons may show their label in capitals: match it whatever the case.
        val pattern = Pattern.compile("(?i)^" + Pattern.quote(string(label)) + "$")
        val button = device.findObject(By.pkg(packageName()).text(pattern))
            ?: throw AssertionError("The privacy notice has no ${string(label)} button")
        button.click()
        // Gone before the system dialog is looked for: both use android:id/button1 and button2.
        if (!device.wait(Until.gone(notice()), 5_000)) fail("The privacy notice stayed on screen")
    }

    private fun notice(): BySelector = By.pkg(packageName()).text(noticeText)

    private fun packageName(): String = instrumentation.targetContext.packageName

    private fun string(id: Int): String = instrumentation.targetContext.getString(id)

    private fun tap(byId: BySelector, byText: BySelector, what: String) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            val button = device.findObject(byId) ?: device.findObject(byText)
            if (button != null) {
                button.click()
                return
            }
            Thread.sleep(200)
        }
        fail("The screen capture consent dialog showed no $what button")
    }
}
