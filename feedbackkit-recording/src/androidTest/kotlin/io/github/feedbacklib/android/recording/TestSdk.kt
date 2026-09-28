package io.github.feedbacklib.android.recording

import android.app.Application
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.feedbacklib.android.BugReporting
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.FeedbackKit
import io.github.feedbacklib.android.InvocationEvent
import io.github.feedbacklib.android.ReportType
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * FeedbackKit as a host starts it, once per test process: this module cannot reach :feedbackkit's
 * internal resetForTests(), and a second build() is ignored anyway. Tags mirror ReportTestTags, which
 * is internal to :feedbackkit.
 */
internal object TestSdk {

    const val COMMENT = "feedbackkit.comment"
    const val RECORD_SCREEN = "feedbackkit.recordScreen"

    /** ReportTestTags.AUTO_CLIP_PENDING: the automatic recording's placeholder. */
    const val AUTO_CLIP_PENDING = "feedbackkit.autoClipPending"

    /** DraftStore.AUTO_RECORDING_FILE_NAME. */
    const val AUTO_RECORDING_FILE = "auto-recording.mp4"

    /** The strip's remove button of a recording (English device locale). */
    const val REMOVE_RECORDING = "Remove recording"

    /** ReportTestTags.attachment(fileName). */
    fun attachment(fileName: String): String = "feedbackkit.attachment.$fileName"

    val dismissals = CopyOnWriteArrayList<Pair<DismissType, ReportType>>()

    private var built = false

    /** Builds FeedbackKit on the first call; later calls only switch it back on and reapply the settings. */
    @Synchronized
    fun ensureBuilt(app: Application) {
        if (!built) {
            FeedbackKit.Builder(app, "recording-cid").setInvocationEvents(InvocationEvent.NONE).build()
            built = true
        }
        FeedbackKit.enable()
        FeedbackKit.identifyUser("tester@example.com", null)
        BugReporting.setReportTypes(ReportType.BUG)
        BugReporting.setOnDismissCallback { type, reportType -> dismissals += type to reportType }
    }

    fun draftFiles(app: Application): List<File> =
        File(app.filesDir, "feedbackkit/drafts").listFiles().orEmpty().flatMap { it.listFiles().orEmpty().toList() }

    /** Closes the open report without sending it: the close button, then "Discard" when asked. */
    fun discardReport(compose: ComposeTestRule) {
        compose.onNodeWithContentDescription("Close").performClick()
        compose.waitForIdle()
        // A report with nothing to lose closes at once, and then there is no hierarchy to ask.
        val asked = runCatching { compose.onAllNodesWithText("Discard").fetchSemanticsNodes().isNotEmpty() }.getOrDefault(false)
        if (asked) compose.onNodeWithText("Discard").performClick()
        // Once the report screen is gone there is no Compose hierarchy left, and fetching throws: closed.
        compose.waitUntil(10_000) {
            runCatching { compose.onAllNodesWithTag(COMMENT).fetchSemanticsNodes().isEmpty() }.getOrDefault(true)
        }
    }
}
