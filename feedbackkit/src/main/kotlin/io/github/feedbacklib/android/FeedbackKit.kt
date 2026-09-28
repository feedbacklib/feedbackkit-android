package io.github.feedbacklib.android

import android.app.Application
import android.net.Uri
import android.view.View
import androidx.annotation.ColorInt
import io.github.feedbacklib.android.internal.capture.PrivateViewRegistry
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.ReportUiConfig
import io.github.feedbacklib.android.internal.core.SdkLog
import io.github.feedbacklib.android.internal.core.Settings
import io.github.feedbacklib.android.internal.core.normalizeEvents

/**
 * Entry point. Initialise once in `Application.onCreate`:
 * ```
 * FeedbackKit.Builder(this, "CID").build()
 * ```
 * Calls made before build() never throw: setters are remembered and applied at build(),
 * actions are ignored with a warning in logcat. No call into this facade throws into the host,
 * except the deliberate [IllegalArgumentException] for a blank `cid` in [Builder]'s constructor
 * (spec §10).
 */
public object FeedbackKit {

    private val lock = Any()
    private var pending = Settings()

    /**
     * Configures and starts FeedbackKit. Call [build] once, in `Application.onCreate`: the SDK
     * learns which screen is visible from activity lifecycle callbacks registered at build().
     */
    public class Builder(
        private val application: Application,
        private val cid: String,
    ) {
        private var sender: ReportSender? = null
        private var logLevel: LogLevel = LogLevel.WARNING

        // null (not this Builder's own default) so an earlier BugReporting.setInvocationEvents()
        // call is not silently overwritten by build() when the Builder itself was never asked
        // (spec §4: setters called before build() are remembered and applied at build()).
        private var invocationEvents: Set<InvocationEvent>? = null

        // null (not this Builder's own default) so an earlier FeedbackKit.setColorTheme() call is
        // not silently overwritten by build() when the Builder itself was never asked.
        private var colorTheme: ColorTheme? = null

        init {
            require(cid.isNotBlank()) { "cid must not be blank" }
        }

        /** Where reports go; defaults to [LocalReportSender]. */
        public fun setReportSender(sender: ReportSender): Builder = apply { this.sender = sender }

        /** Verbosity of the SDK's own logcat output; defaults to [LogLevel.WARNING]. */
        public fun setSdkDebugLogsLevel(level: LogLevel): Builder = apply { logLevel = level }

        /**
         * How the user can open FeedbackKit; defaults to [InvocationEvent.SHAKE]. Pass
         * [InvocationEvent.NONE], or no events at all, to leave only manual invocation via
         * [FeedbackKit.show]. Takes precedence over an earlier [BugReporting.setInvocationEvents]
         * call, the same way any other setting made through this Builder wins at `build()`.
         */
        public fun setInvocationEvents(vararg events: InvocationEvent): Builder = apply { invocationEvents = normalizeEvents(events) }

        /**
         * Light, dark or following the system (default) for the FeedbackKit screen. Takes precedence
         * over an earlier [FeedbackKit.setColorTheme] call, like every other Builder setting.
         */
        public fun setColorTheme(theme: ColorTheme): Builder = apply { colorTheme = theme }

        /**
         * Starts FeedbackKit with [state] (enabled by default). Call it in `Application.onCreate`;
         * after a late build(), invocations work from the next host screen onwards. A second
         * build() is ignored with a warning.
         */
        @JvmOverloads
        public fun build(state: FeatureState = FeatureState.ENABLED) {
            install(application, cid, sender, logLevel, invocationEvents, colorTheme, state)
        }
    }

    /** `false` until build(), and while disabled. */
    @JvmStatic
    public val isEnabled: Boolean
        get() = FeedbackKitRuntime.current?.config?.value?.enabled ?: false

    @JvmStatic
    public fun enable() {
        guarded("enable()") { runtimeOrWarn("enable()")?.setEnabled(true) }
    }

    @JvmStatic
    public fun disable() {
        guarded("disable()") { runtimeOrWarn("disable()")?.setEnabled(false) }
    }

    /** Pre-fills the report form's email; the user is not identified beyond that. */
    @JvmStatic
    public fun identifyUser(email: String?, name: String?) {
        updateSetting(
            "identifyUser()",
            { it.copy(userEmail = email, userName = name) },
            { runtime -> runtime.identifyUser(email, name) },
        )
    }

    /**
     * Accent colour of the FeedbackKit screen: buttons, focused fields, progress. The alpha channel
     * is ignored. Applies to an open screen at once.
     */
    @JvmStatic
    public fun setPrimaryColor(@ColorInt color: Int) {
        val opaque = color or OPAQUE_ALPHA
        updateUi("setPrimaryColor()") { it.copy(primaryColor = opaque) }
    }

    /** Light, dark or following the system (default). Applies to an open screen at once. */
    @JvmStatic
    public fun setColorTheme(theme: ColorTheme) {
        updateUi("setColorTheme()") { it.copy(colorTheme = theme) }
    }

    /**
     * Replaces texts of the FeedbackKit screen whatever the device language. Keys left out, and
     * blank values, keep the built-in English or Russian text. Each call replaces the previous map.
     */
    @JvmStatic
    public fun setCustomTexts(texts: Map<TextKey, String>) {
        // Read the host's map inside updateUi's guard, and only once however often the update retries.
        val kept by lazy(LazyThreadSafetyMode.NONE) {
            texts.filterValues { it.isNotBlank() }.also {
                if (it.size < texts.size) SdkLog.logger.w("setCustomTexts(): blank texts are ignored")
            }
        }
        updateUi("setCustomTexts()") { it.copy(customTexts = kept) }
    }

    /**
     * Opens the FeedbackKit screen, exactly as if the user triggered an invocation event. Safe from
     * any thread. The call is ignored when the SDK is disabled or not built yet, when the
     * FeedbackKit screen is already open, or when no host screen is visible, and while bug
     * reporting is switched off with [BugReporting.setState].
     */
    @JvmStatic
    public fun show() {
        showReport("show()", null)
    }

    internal fun showReport(call: String, type: ReportType?) {
        guarded(call) { runtimeOrWarn(call)?.invocation?.show(type) }
    }

    /** Blacks out [views] in every screenshot the SDK takes (spec §5); works before `build()` too. */
    @JvmStatic
    public fun addPrivateViews(vararg views: View) {
        guarded("addPrivateViews()") { PrivateViewRegistry.add(*views) }
    }

    /** Reverses [addPrivateViews]. */
    @JvmStatic
    public fun removePrivateViews(vararg views: View) {
        guarded("removePrivateViews()") { PrivateViewRegistry.remove(*views) }
    }

    /**
     * Attaches up to three host files (each at most 5 MB) to every report; read when a report is
     * queued. They are never shown in the report screen and do not count towards its four
     * attachments. Safe from any thread: nothing is written on the caller's thread.
     */
    @JvmStatic
    public fun addFileAttachment(uri: Uri, fileName: String) {
        guarded("addFileAttachment()") { runtimeOrWarn("addFileAttachment()")?.appFiles?.add(uri, fileName) }
    }

    /**
     * Attaches up to three host files (each at most 5 MB) to every report. They are never shown in
     * the report screen and do not count towards its four attachments. Safe from any thread:
     * nothing is written on the caller's thread.
     */
    @JvmStatic
    public fun addFileAttachment(bytes: ByteArray, fileName: String) {
        guarded("addFileAttachment()") { runtimeOrWarn("addFileAttachment()")?.appFiles?.add(bytes, fileName) }
    }

    /** Forgets the attached host files; the files themselves are not touched. */
    @JvmStatic
    public fun clearFileAttachments() {
        guarded("clearFileAttachments()") { runtimeOrWarn("clearFileAttachments()")?.appFiles?.clear() }
    }

    /** See [OnReportSubmitHandler]; `null` removes it. */
    @JvmStatic
    public fun onReportSubmitHandler(handler: OnReportSubmitHandler?) {
        updateSetting(
            "onReportSubmitHandler()",
            { it.copy(submitHandler = handler) },
            { runtime -> runtime.submitHandler = handler },
        )
    }

    private fun install(
        application: Application,
        cid: String,
        sender: ReportSender?,
        logLevel: LogLevel,
        invocationEvents: Set<InvocationEvent>?,
        colorTheme: ColorTheme?,
        state: FeatureState,
    ) {
        guarded("build()") {
            synchronized(lock) {
                if (FeedbackKitRuntime.current != null) {
                    SdkLog.logger.w("FeedbackKit is already initialised; this build() is ignored")
                    return@guarded
                }
                FeedbackKitRuntime.create(
                    application,
                    cid,
                    pending.copy(
                        enabled = state == FeatureState.ENABLED,
                        logLevel = logLevel,
                        sender = sender,
                        // null means the Builder was never asked: keep whatever setInvocationEvents()
                        // before build() already put in `pending` (or its own SHAKE default).
                        invocationEvents = invocationEvents ?: pending.invocationEvents,
                        // null means the Builder was never asked: keep whatever setColorTheme() before
                        // build() already put in `pending` (or its own SYSTEM default).
                        ui = if (colorTheme == null) pending.ui else pending.ui.copy(colorTheme = colorTheme),
                    ),
                )
                pending = Settings()
            }
        }
    }

    private fun runtimeOrWarn(call: String): FeedbackKitRuntime? {
        val runtime = FeedbackKitRuntime.current
        if (runtime == null) SdkLog.logger.w("$call ignored: call FeedbackKit.Builder(...).build() first")
        return runtime
    }

    /** Defence in depth (spec §10): a host callback or system failure must never reach the caller. */
    private inline fun guarded(call: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            SdkLog.logger.e("$call failed", e)
        }
    }

    /**
     * Common shape for every setter that must work before `build()` (remembered in [pending]) and
     * after it (applied to the running [FeedbackKitRuntime] straight away). Guarded (spec §10).
     */
    internal fun updateSetting(
        call: String,
        pendingUpdate: (Settings) -> Settings,
        runtimeUpdate: (FeedbackKitRuntime) -> Unit,
    ) {
        guarded(call) {
            synchronized(lock) {
                val runtime = FeedbackKitRuntime.current
                if (runtime == null) {
                    pending = pendingUpdate(pending)
                } else {
                    runtimeUpdate(runtime)
                }
            }
        }
    }

    /** [updateSetting] for report screen settings (spec §6). */
    internal fun updateUi(call: String, transform: (ReportUiConfig) -> ReportUiConfig) {
        updateSetting(call, { it.copy(ui = transform(it.ui)) }, { runtime -> runtime.updateUi(transform) })
    }

    private const val OPAQUE_ALPHA: Int = -0x1000000 // 0xFF000000

    internal fun resetForTests() {
        synchronized(lock) {
            FeedbackKitRuntime.resetForTests()
            pending = Settings()
        }
    }
}
