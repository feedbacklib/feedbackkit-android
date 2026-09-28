package io.github.feedbacklib.android.internal.ui

import androidx.lifecycle.SavedStateHandle
import io.github.feedbacklib.android.AttachmentKind
import io.github.feedbacklib.android.internal.recording.PendingClips
import io.github.feedbacklib.android.internal.report.DraftFile
import io.github.feedbacklib.android.internal.report.DraftStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Auto Screen Recording's clip of one report draft (spec §7), for [ReportDraftViewModel]. The clip
 * comes after the screen opened: while it is made, a placeholder holds its place among the four
 * attachments, and its token stays in [handle] until the clip joins the draft, never comes, or the
 * report is sent or closed (then the clip is given up). Main thread.
 */
internal class AutoClipAttachment(
    private val handle: SavedStateHandle,
    private val draftId: String,
    private val env: ReportEnvironment,
    /** Where the clip joins the draft: outlives the screen, like the ViewModel's other adoptions. */
    private val detached: CoroutineScope,
) {
    /** Whether a clip is still awaited: its token is in the saved state. */
    val isAwaited: Boolean
        get() = handle.get<String>(STATE_KEY) != null

    /** A new draft keeps the token its invocation handed over; a restored one has it saved already. */
    fun claim(token: String?) {
        token?.let { handle[STATE_KEY] = it }
    }

    /**
     * Waits in [scope] for the clip. Once it comes it joins the draft on the draft's queue, detached,
     * so it is not left in the cache should the screen be cleared first. [onResolved] then gets the
     * draft's automatic recording, or null: no clip, or one that cannot join, fails quietly — the user
     * never asked for this recording, so the placeholder just goes and no notice shows. A token this
     * process does not know (process death) answers null at once; a token given up meanwhile (send or
     * close) answers nothing.
     */
    fun await(scope: CoroutineScope, onResolved: (DraftFile?) -> Unit) {
        val token = handle.get<String>(STATE_KEY) ?: return
        scope.launch {
            val clip = env.autoClips.await(token)
            // Given up meanwhile (send or close): the registry deleted what came.
            if (handle.get<String>(STATE_KEY) != token) return@launch
            handle.remove<String>(STATE_KEY)
            val adopted = try {
                detached.async(env.drafts.queue(draftId)) {
                    try {
                        with(env.drafts) {
                            // Without a clip here, another screen of this draft may have taken it
                            // (this one reopened after stepping aside): what counts is the draft.
                            if (clip != null && adoptAutoRecording(draftId, clip) == null) {
                                null
                            } else {
                                attachments(draftId).firstOrNull { it.kind == AttachmentKind.AUTO_SCREEN_RECORDING }
                            }
                        }
                    } catch (e: Exception) {
                        // Nobody awaits it once this screen is cleared: logged here.
                        if (e !is CancellationException) env.logger.e("The automatic screen recording could not join the report", e)
                        throw e
                    }
                }.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null // logged above
            }
            if (adopted == null) env.logger.w("The automatic screen recording did not come; the report goes on without it")
            onResolved(adopted)
        }
    }

    /** The report is sent or closed: a clip still being made is not wanted any more. True when one was awaited. */
    fun abandon(): Boolean {
        val token = handle.get<String>(STATE_KEY) ?: return false
        handle.remove<String>(STATE_KEY)
        env.autoClips.abandon(token)
        return true
    }

    /**
     * The four places less the one the clip in preparation holds (spec §7). Decided on the draft's
     * queue from its files: the screen this one replaced may already have put the clip in.
     */
    fun maxUserAttachments(store: DraftStore, clipPending: Boolean): Int {
        val reserved = clipPending && store.attachments(draftId).none { it.kind == AttachmentKind.AUTO_SCREEN_RECORDING }
        return AttachmentRules.MAX_USER_ATTACHMENTS - if (reserved) 1 else 0
    }

    companion object {
        /** The token of the automatic recording still awaited; removed once it is resolved. */
        const val STATE_KEY: String = PendingClips.STATE_KEY
    }
}
