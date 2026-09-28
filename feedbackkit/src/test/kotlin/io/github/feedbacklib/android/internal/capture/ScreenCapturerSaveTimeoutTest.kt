package io.github.feedbacklib.android.internal.capture

import android.app.Activity
import android.os.Looper
import io.github.feedbacklib.android.LogLevel
import io.github.feedbacklib.android.internal.core.LogSink
import io.github.feedbacklib.android.internal.core.SdkLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.util.concurrent.Executor

@RunWith(RobolectricTestRunner::class)
class ScreenCapturerSaveTimeoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val activity: Activity by lazy { Robolectric.buildActivity(Activity::class.java).setup().get() }
    private val dir by lazy { temp.newFolder("capture") }
    private val heldSaves = mutableListOf<Runnable>()
    private val logs = mutableListOf<Pair<LogLevel, String>>()
    private val results = mutableListOf<ScreenCapturer.Result>()
    private val looper by lazy { shadowOf(Looper.getMainLooper()) }

    private val capturer by lazy {
        ScreenCapturer(
            outputDir = { dir },
            logger = SdkLogger(LogLevel.VERBOSE, LogSink { level, _, message, _ -> logs += level to message }),
            ioExecutor = Executor { heldSaves += it },
            timeoutMillis = 1_000,
            saveTimeoutMillis = 3_000,
        )
    }

    private fun capture() {
        capturer.capture(activity) { results += it }
        looper.idleFor(Duration.ofMillis(100)) // PixelCopy answers well inside its own 1 s timeout
    }

    @Test
    fun `a save stuck on disk gives up after the timeout and its late file is deleted`() {
        capture()
        assertEquals("the PNG write was not handed to the io executor", 1, heldSaves.size)
        assertTrue(results.isEmpty())

        looper.idleFor(Duration.ofMillis(3_000))
        looper.idle() // the Failed result is posted back to the main thread
        assertEquals(listOf(ScreenCapturer.Result.Failed), results)

        heldSaves.single().run() // the disk finally answers
        looper.idle()
        assertEquals(1, results.size)
        // The late save did write its PNG (it says so), and the file is gone again.
        assertTrue(logs.toString(), logs.any { it.second.startsWith("The screenshot was saved after the timeout") })
        assertTrue(dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `a save in time delivers exactly one Saved with its file and no timeout warning`() {
        capture()
        heldSaves.single().run()
        looper.idleFor(Duration.ofMillis(5_000)) // well past the save timeout
        looper.idle()

        val saved = results.single() as ScreenCapturer.Result.Saved
        assertTrue(saved.file.isFile)
        assertEquals(listOf(saved.file), dir.listFiles()!!.toList())
        assertTrue(logs.toString(), logs.none { it.first == LogLevel.WARNING || it.first == LogLevel.ERROR })
    }

    @Test
    fun `a capture while a save is still outstanding fails at once instead of queueing another bitmap`() {
        capture()
        assertEquals(1, heldSaves.size)

        capture()
        assertEquals("no second full bitmap handed to the disk", 1, heldSaves.size)
        assertEquals(listOf<ScreenCapturer.Result>(ScreenCapturer.Result.Failed), results)

        heldSaves.single().run()
        looper.idle()
        assertTrue(results[1] is ScreenCapturer.Result.Saved)

        capture() // the disk is free again
        assertEquals(2, heldSaves.size)
    }
}
