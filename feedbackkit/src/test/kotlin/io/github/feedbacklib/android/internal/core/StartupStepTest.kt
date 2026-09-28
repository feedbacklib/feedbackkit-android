package io.github.feedbacklib.android.internal.core

import io.github.feedbacklib.android.LogLevel
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class StartupStepTest {

    private val warnings = mutableListOf<String>()
    private val logger = SdkLogger(LogLevel.WARNING) { level, _, message, _ -> if (level == LogLevel.WARNING) warnings += message }

    @Test
    fun `a failing step is logged and the pass goes on`() = runBlocking {
        var next = false
        startupStep(logger, "cleanup failed") { throw IOException("disk") }
        startupStep(logger, "next failed") { next = true }
        assertEquals(listOf("cleanup failed"), warnings)
        assertTrue(next)
    }

    @Test
    fun `a cancelled startup stops at the step that saw it instead of logging it`() = runBlocking {
        var afterwards = false
        val pass = launch(start = CoroutineStart.UNDISPATCHED) {
            startupStep(logger, "cleanup failed") {
                currentCoroutineContext().cancel() // the SDK scope is cancelled while the step suspends
                yield()
            }
            afterwards = true
        }
        pass.join()
        assertTrue(pass.isCancelled)
        assertFalse(afterwards, "the next step must not run once the scope is cancelled")
        assertTrue(warnings.isEmpty(), "a cancellation is not a failure: $warnings")
    }
}
