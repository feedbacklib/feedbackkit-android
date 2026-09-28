package io.github.feedbacklib.android.internal.ui

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.SideEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.feedbacklib.android.DismissType
import io.github.feedbacklib.android.ReportType
import io.github.feedbacklib.android.internal.core.FeedbackKitRuntime
import io.github.feedbacklib.android.internal.core.SdkLog
import io.github.feedbacklib.android.internal.core.SdkLogger
import io.github.feedbacklib.android.internal.invoke.InvocationHooks
import io.github.feedbacklib.android.internal.invoke.LaunchRequest
import io.github.feedbacklib.android.internal.ui.screens.ReportRoot
import io.github.feedbacklib.android.internal.ui.theme.isDark
import io.github.feedbacklib.android.spi.SdkActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The report screen (spec §6): a translucent Compose activity hosting [ReportRoot]. It finishes when
 * the ViewModel asks. Each instance tells the invocation coordinator it is open (a screen recreated
 * after process death was launched by no invocation), and closing is reported to the coordinator and
 * the host exactly once — from onPause once finishing, so a new invocation is accepted as soon as
 * the host is back.
 *
 * Only an instance that is finishing reports, and [CloseReporter] reports once per instance: a
 * rotation reports nothing, and a new instance that sees an already requested close (rotated while
 * thanking the user, restored after process death) finishes and reports it itself — once.
 */
internal class FeedbackActivity : ComponentActivity(), SdkActivity {

    private var viewModel: ReportDraftViewModel? = null
    private lateinit var logger: SdkLogger
    private lateinit var closeReporter: CloseReporter
    private var appliedBars: Pair<Boolean, Boolean>? = null

    /** Guards against a double tap on "Add image" opening two system pickers at once. */
    private var pickerOpen = false

    // Registration must happen before onCreate returns, as the Activity Result API requires; the
    // answer, including one that arrives after rotation or process death, reaches whichever instance
    // is current when it comes back.
    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        pickerOpen = false
        val vm = viewModel
        if (vm == null) {
            logger.d("A photo picker result arrived after the report screen already closed; ignoring it")
            return@registerForActivityResult
        }
        try {
            vm.onGalleryImagePicked(uri)
        } catch (e: Exception) {
            logger.e("Could not take the picked image", e)
        }
    }

    /** Thumbnails are only a decode-avoiding speed-up: dropping them under memory pressure is free. */
    @Suppress("OVERRIDE_DEPRECATION") // onLowMemory() is deprecated in favor of onTrimMemory() alone, but still called on API < 34
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onConfigurationChanged(newConfig: Configuration) = Unit
        override fun onLowMemory() = ThumbnailCache.clear()
        override fun onTrimMemory(level: Int) = ThumbnailCache.clear()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        applicationContext.registerComponentCallbacks(memoryCallbacks)
        val runtime = FeedbackKitRuntime.current
        logger = runtime?.logger ?: SdkLog.logger
        closeReporter = CloseReporter(
            onUiClosed = { dismissType -> InvocationHooks.onUiClosed?.invoke(dismissType) },
            dismissCallback = { FeedbackKitRuntime.current?.onDismissCallback },
            logger = logger,
        )
        // Every instance, launched or recreated after process death, pairs with the one close report.
        try {
            InvocationHooks.onUiOpened?.invoke()
        } catch (e: Exception) {
            logger.e("Could not report the opened FeedbackKit screen", e)
        }
        if (runtime == null) {
            logger.w("The FeedbackKit screen opened before build(); closing it")
            finish()
            return
        }
        try {
            val args = FeedbackLaunchArgs.fromIntent(intent)
            val screenshot = when {
                args.screenshot != null -> "taken"
                args.screenshotSecure -> "withheld (FLAG_SECURE)"
                else -> "unavailable"
            }
            logger.d("FeedbackKit screen opened by ${args.source}; screenshot $screenshot")

            // No disk I/O here: the ViewModel adopts the screenshot and lists the draft on its IO queue.
            val vm = ViewModelProvider(this, ReportDraftViewModel.factory(args, RuntimeReportEnvironment(runtime, applicationContext)))[ReportDraftViewModel::class.java]
            viewModel = vm
            // lifecycleScope, not composition: closes even if the thanks timer ends while in background.
            lifecycleScope.launch {
                vm.closed.filterNotNull().first()
                finish()
            }
            // PickVisualMedia shows the system photo picker, its Play services backport on older
            // devices, or the document picker — no storage permission either way (spec §6).
            lifecycleScope.launch {
                vm.pickImageRequests.collect {
                    if (pickerOpen) return@collect // a double tap must not open a second picker
                    pickerOpen = true
                    runPickerLaunch(logger, { pickerOpen = false; vm.onGalleryUnavailable() }) {
                        pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }
                }
            }
            // "Record screen" (spec §7): the consent opens over this screen, and only while it is
            // visible (Android 10+ drops an activity start from the background). A request this
            // instance did not get to — recreated first — waits for the next one.
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    vm.recordScreenRequests.collect {
                        try {
                            vm.startRecording(this@FeedbackActivity)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.e("Could not start the screen recording", e)
                            vm.onRecordingNotStarted(refused = false)
                        }
                    }
                }
            }
            // Edge to edge, so the IME arrives as insets the action bar pads by (with adjustResize
            // in the manifest, which API 26–29 need to dispatch them at all).
            enableEdgeToEdge()
            setContent {
                val state = vm.uiState
                val dark = isDark(state.colorTheme, isSystemInDarkTheme())
                // The menu and the proactive prompt sit over the dimmed host: those bars are on the scrim
                // (the menu's sheet covers the navigation bar, the prompt's centred card does not).
                val scrimUnderStatusBar = state.step == ReportStep.MENU || state.step == ReportStep.PROACTIVE_PROMPT
                val scrimUnderNavigationBar = state.step == ReportStep.PROACTIVE_PROMPT
                SideEffect { applySystemBars(statusBarDark = dark || scrimUnderStatusBar, navigationBarDark = dark || scrimUnderNavigationBar) }
                ReportRoot(state, vm)
            }
        } catch (e: Exception) {
            // Never crash the host (spec §10): close instead; onDestroy reports it.
            logger.e("Could not open the FeedbackKit screen; closing it", e)
            finish()
        }
    }

    override fun onPause() {
        super.onPause()
        if (isFinishing) reportClosed()
    }

    override fun onDestroy() {
        super.onDestroy()
        applicationContext.unregisterComponentCallbacks(memoryCallbacks)
        // Fallback: a finish() from onCreate never passes through onPause.
        if (isFinishing) {
            // Nothing needs this screen's thumbnails once it is really gone, not just recreated.
            ThumbnailCache.clear()
            reportClosed()
        }
    }

    private fun reportClosed() {
        if (closeReporter.reported) return
        val (dismissType, reportType) = try {
            viewModel?.onScreenFinished()
            viewModel?.dismissInfo()
        } catch (e: Exception) {
            logger.e("Could not tell why the FeedbackKit screen closed; reporting a cancel", e)
            null
        } ?: (DismissType.CANCEL to ReportType.BUG)
        closeReporter.report(dismissType, reportType)
    }

    /**
     * Status and navigation bar icons that read on what is behind them (the screen's own theme, or
     * the scrim), not on the host's theme. Applied only when they change, not on every keystroke.
     */
    private fun applySystemBars(statusBarDark: Boolean, navigationBarDark: Boolean) {
        val bars = statusBarDark to navigationBarDark
        if (bars == appliedBars) return
        appliedBars = bars
        enableEdgeToEdge(statusBarStyle = barStyle(statusBarDark), navigationBarStyle = barStyle(navigationBarDark))
    }

    /** A dark style draws light icons. */
    private fun barStyle(dark: Boolean): SystemBarStyle =
        if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)

    internal companion object {
        fun intent(context: Context, request: LaunchRequest): Intent =
            FeedbackLaunchArgs.from(request).toIntent(context).also { intent ->
                request.resume?.let { FeedbackLaunchArgs.putResumeState(intent, it.state) }
            }
    }
}

/**
 * Runs [launch]; a screen recreated mid-collection or torn down cancels it, and that must propagate
 * (controller ruling D6). Anything else means no photo or document picker exists on this device —
 * not only [android.content.ActivityNotFoundException], whatever a device's picker throws — so it is
 * logged and reported through [onUnavailable] rather than crashing the host.
 */
internal inline fun runPickerLaunch(logger: SdkLogger, onUnavailable: () -> Unit, launch: () -> Unit) {
    try {
        launch()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.w("Could not open the photo or document picker", e)
        onUnavailable()
    }
}
