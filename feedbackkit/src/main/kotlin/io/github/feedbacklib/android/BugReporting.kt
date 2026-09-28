package io.github.feedbacklib.android

import io.github.feedbacklib.android.internal.core.AttachmentTypes
import io.github.feedbacklib.android.internal.core.ExtendedHints
import io.github.feedbacklib.android.internal.core.ProactiveSettings
import io.github.feedbacklib.android.internal.core.SdkLog
import io.github.feedbacklib.android.internal.core.normalizeEvents

/**
 * How users open the report screen. Setters called before `build()` are remembered; after it they
 * apply immediately. Nothing here throws into the host (spec §10).
 */
public object BugReporting {

    /**
     * How the user can open FeedbackKit; defaults to [InvocationEvent.SHAKE]. Pass
     * [InvocationEvent.NONE], or no events at all, to leave only manual invocation via
     * [FeedbackKit.show]. A call made through `FeedbackKit.Builder.setInvocationEvents` wins if it
     * comes later, e.g. at `build()`, the same as every other setting here (spec §4).
     */
    @JvmStatic
    public fun setInvocationEvents(vararg events: InvocationEvent) {
        val normalized = normalizeEvents(events)
        FeedbackKit.updateSetting(
            "setInvocationEvents()",
            { it.copy(invocationEvents = normalized) },
            { runtime -> runtime.updateConfig { it.copy(invocationEvents = normalized) } },
        )
    }

    /** Default 650; higher means a less sensitive device. Values ≤ 0 are ignored. */
    @JvmStatic
    public fun setShakingThreshold(threshold: Int) {
        if (threshold <= 0) {
            SdkLog.logger.w("setShakingThreshold($threshold) ignored: the threshold must be positive")
            return
        }
        FeedbackKit.updateSetting(
            "setShakingThreshold()",
            { it.copy(shakingThreshold = threshold) },
            { runtime -> runtime.updateConfig { it.copy(shakingThreshold = threshold) } },
        )
    }

    /** Which side of the screen the floating button snaps to; defaults to [FloatingButtonEdge.RIGHT]. */
    @JvmStatic
    public fun setFloatingButtonEdge(edge: FloatingButtonEdge) {
        FeedbackKit.updateSetting(
            "setFloatingButtonEdge()",
            { it.copy(floatingButtonEdge = edge) },
            { runtime -> runtime.updateConfig { it.copy(floatingButtonEdge = edge) } },
        )
    }

    /** Distance from the top of the screen (below the status bar), in dp. Negative values become 0. */
    @JvmStatic
    public fun setFloatingButtonOffset(yDp: Int) {
        val offset = yDp.coerceAtLeast(0)
        FeedbackKit.updateSetting(
            "setFloatingButtonOffset()",
            { it.copy(floatingButtonOffsetDp = offset) },
            { runtime -> runtime.updateConfig { it.copy(floatingButtonOffsetDp = offset) } },
        )
    }

    /** See [OnInvokeCallback]; `null` removes it. */
    @JvmStatic
    public fun setOnInvokeCallback(callback: OnInvokeCallback?) {
        FeedbackKit.updateSetting(
            "setOnInvokeCallback()",
            { it.copy(onInvokeCallback = callback) },
            { runtime -> runtime.onInvokeCallback = callback },
        )
    }

    /**
     * Report types offered in the menu, shown in the order BUG, FEEDBACK, QUESTION; with one type the
     * menu is skipped. Defaults to all three. [ReportType.FRUSTRATING_EXPERIENCE] belongs to proactive
     * reporting and is ignored here; a call that leaves no type is ignored with a warning.
     */
    @JvmStatic
    public fun setReportTypes(vararg types: ReportType) {
        if (ReportType.FRUSTRATING_EXPERIENCE in types) {
            SdkLog.logger.w("setReportTypes(): FRUSTRATING_EXPERIENCE is used only by proactive reporting and is ignored")
        }
        val offered = types.filterTo(linkedSetOf()) { it != ReportType.FRUSTRATING_EXPERIENCE }
        if (offered.isEmpty()) {
            SdkLog.logger.w("setReportTypes() ignored: pass at least one of BUG, FEEDBACK, QUESTION")
            return
        }
        FeedbackKit.updateUi("setReportTypes()") { it.copy(reportTypes = offered) }
    }

    /** Replaces the form options (none by default: email required, comment optional). */
    @JvmStatic
    public fun setOptions(vararg options: Option) {
        val chosen = options.toSet()
        FeedbackKit.updateUi("setOptions()") { it.copy(options = chosen) }
    }

    /**
     * Makes the comment of [types] (all types when none are given) required with at least [count]
     * characters; surrounding whitespace does not count. A counter is shown under the field.
     * [count] ≤ 0 removes the minimum.
     */
    @JvmStatic
    public fun setCommentMinimumCharacterCount(count: Int, vararg types: ReportType) {
        val minimum = count.coerceAtLeast(0)
        val targets = if (types.isEmpty()) ReportType.entries.toSet() else types.toSet()
        FeedbackKit.updateUi("setCommentMinimumCharacterCount()") { ui ->
            ui.copy(
                commentMinimums = if (minimum == 0) ui.commentMinimums - targets else ui.commentMinimums + targets.associateWith { minimum },
            )
        }
    }

    /** Second step of a bug report (steps, actual, expected result); disabled by default. */
    @JvmStatic
    public fun setExtendedBugReportState(state: ExtendedBugReport.State) {
        FeedbackKit.updateUi("setExtendedBugReportState()") { it.copy(extendedState = state) }
    }

    /** Placeholders of the extended step's fields; `null` or blank keeps the built-in text. */
    @JvmStatic
    public fun setExtendedBugReportHints(steps: String?, actual: String?, expected: String?) {
        val hints = ExtendedHints(
            steps = steps?.takeIf { it.isNotBlank() },
            actual = actual?.takeIf { it.isNotBlank() },
            expected = expected?.takeIf { it.isNotBlank() },
        )
        FeedbackKit.updateUi("setExtendedBugReportHints()") { it.copy(extendedHints = hints) }
    }

    /**
     * Which attachments the report screen offers; all are enabled by default. At most four
     * attachments fit one report, the invocation screenshot included.
     * - [initialScreenshot]: the screenshot taken when FeedbackKit is invoked. With `false` none is
     *   taken and the form shows no "screenshot unavailable" placeholder; applies from the next
     *   invocation.
     * - [extraScreenshot]: "Add screenshot" in the form. The report screen steps aside so the user
     *   can capture any screen of the app, then opens again with the draft.
     * - [gallery]: images from the system photo picker. No storage permission is needed.
     * - [screenRecording]: "Record screen" in the form, when the optional `feedbackkit-recording`
     *   artifact is present. A notice that the whole screen is recorded comes first, then the system
     *   asks for consent every time; the report screen steps aside while a Stop control with a timer
     *   stays over the app, for at most 60 s. Video is not masked: private views are recorded as they
     *   are, only FLAG_SECURE windows are left out (they come out black).
     * Changes reach an open report screen at once.
     */
    @JvmStatic
    public fun setAttachmentTypesEnabled(initialScreenshot: Boolean, extraScreenshot: Boolean, gallery: Boolean, screenRecording: Boolean) {
        val types = AttachmentTypes(initialScreenshot, extraScreenshot, gallery, screenRecording)
        FeedbackKit.updateUi("setAttachmentTypesEnabled()") { it.copy(attachmentTypes = types) }
    }

    /**
     * Where the Stop control of a screen recording sits over the app's screens; defaults to
     * [RecordingButtonPosition.BOTTOM_RIGHT]. Applies at once, to a recording in progress too.
     */
    @JvmStatic
    public fun setVideoRecordingButtonPosition(position: RecordingButtonPosition) {
        FeedbackKit.updateSetting(
            "setVideoRecordingButtonPosition()",
            { it.copy(recordingButtonPosition = position) },
            { runtime -> runtime.updateConfig { it.copy(recordingButtonPosition = position) } },
        )
    }

    /**
     * **Beta — for internal testing only.** Keeps recording the app's screen so a bug report can carry
     * what happened just before FeedbackKit was opened. Off by default; nothing blocks it in release
     * builds, so switch it on only in builds meant for testers.
     *
     * Needs the `feedbackkit-recording` artifact; without it the call only logs a warning. Once on,
     * consent is asked the first time the app is in the foreground (at once if a screen is showing):
     * a notice that the whole screen is recorded, then the system screen capture dialog. A declined
     * consent is not asked again until the app process restarts. While on, a notification shows the
     * recording. Segments of 10 s are recorded while the app is in the foreground (the last four are
     * kept) and paused in the background; when FeedbackKit is invoked, the last 30 s join the report
     * as an attachment the user can remove, counting towards its four attachments.
     * [FeatureState.DISABLED] of [setState], and [FeedbackKit.disable], stop it.
     *
     * Video is not masked: the whole display is recorded, and views registered with
     * [FeedbackKit.addPrivateViews] or `Modifier.feedbackKitPrivate` show as they are. Only windows
     * with FLAG_SECURE are left out (they come out black).
     */
    @JvmStatic
    public fun setAutoScreenRecordingEnabled(enabled: Boolean) {
        FeedbackKit.updateSetting(
            "setAutoScreenRecordingEnabled()",
            { it.copy(autoScreenRecording = enabled) },
            { runtime -> runtime.updateConfig { it.copy(autoScreenRecording = enabled) } },
        )
    }

    /**
     * Proactive reporting (see [ProactiveReportingConfigs]); off by default. Call it before `build()`:
     * what happened to the previous run of the app is looked at once, at `build()`, and the prompt
     * waits for the first screen of the app. A later call still applies to a prompt not shown yet.
     *
     * FeedbackKit leaves a marker when the app crashes and then hands the crash on to the handler that
     * was installed before it (a crash reporter's, or the system's), so crashes go on exactly as they
     * would. A crash reporter set up after `build()` should hand crashes on too, or FeedbackKit learns
     * of Java crashes only from the system (Android 11+). The prompt closes with
     * [OnDismissCallback] reporting [ReportType.FRUSTRATING_EXPERIENCE]; the host's [OnInvokeCallback]
     * is not called for it. Crashes are never sent by themselves.
     */
    @JvmStatic
    public fun setProactiveReportingConfigurations(configs: ProactiveReportingConfigs) {
        val settings = ProactiveSettings.from(configs)
        FeedbackKit.updateSetting(
            "setProactiveReportingConfigurations()",
            { it.copy(proactive = settings) },
            { runtime -> runtime.updateConfig { it.copy(proactive = settings) } },
        )
    }

    /** See [OnDismissCallback]; `null` removes it. */
    @JvmStatic
    public fun setOnDismissCallback(callback: OnDismissCallback?) {
        FeedbackKit.updateSetting(
            "setOnDismissCallback()",
            { it.copy(onDismissCallback = callback) },
            { runtime -> runtime.onDismissCallback = callback },
        )
    }

    /**
     * Opens the FeedbackKit screen straight on the form for [type], skipping the menu, exactly as an
     * invocation would (screenshot included). Ignored in the same cases as [FeedbackKit.show], and for
     * [ReportType.FRUSTRATING_EXPERIENCE], which only proactive reporting opens. Works for a type left
     * out of [setReportTypes] too: that list only shapes the menu.
     */
    @JvmStatic
    public fun show(type: ReportType) {
        if (type == ReportType.FRUSTRATING_EXPERIENCE) {
            SdkLog.logger.w("show(FRUSTRATING_EXPERIENCE) ignored: that type is opened only by proactive reporting")
            return
        }
        FeedbackKit.showReport("BugReporting.show()", type)
    }

    /**
     * [FeatureState.DISABLED] switches off every way to open the report screen — invocation events and
     * the show() calls; queued reports are still delivered. [FeedbackKit.disable] switches off the whole
     * SDK. Enabled by default.
     */
    @JvmStatic
    public fun setState(state: FeatureState) {
        val enabled = state == FeatureState.ENABLED
        FeedbackKit.updateSetting(
            "setState()",
            { it.copy(bugReportingEnabled = enabled) },
            { runtime -> runtime.updateConfig { it.copy(bugReportingEnabled = enabled) } },
        )
    }
}
