package io.github.feedbacklib.android.internal.core

import io.github.feedbacklib.android.LogLevel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test

class SdkLoggerTest {

    private data class Entry(val level: LogLevel, val tag: String, val message: String, val throwable: Throwable?)

    private val written = mutableListOf<Entry>()
    private val sink = LogSink { level, tag, message, throwable -> written += Entry(level, tag, message, throwable) }

    private fun logAllLevels(logger: SdkLogger) {
        logger.e("e"); logger.w("w"); logger.i("i"); logger.d("d"); logger.v("v")
    }

    @Test
    fun `warning level writes errors and warnings only`() {
        logAllLevels(SdkLogger(LogLevel.WARNING, sink))
        assertEquals(listOf("e", "w"), written.map { it.message })
    }

    @Test
    fun `none level writes nothing, not even errors`() {
        logAllLevels(SdkLogger(LogLevel.NONE, sink))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `verbose level writes everything with matching levels`() {
        logAllLevels(SdkLogger(LogLevel.VERBOSE, sink))
        assertEquals(
            listOf(LogLevel.ERROR, LogLevel.WARNING, LogLevel.INFO, LogLevel.DEBUG, LogLevel.VERBOSE),
            written.map { it.level },
        )
    }

    @Test
    fun `level change at runtime applies to the next message`() {
        val logger = SdkLogger(LogLevel.ERROR, sink)
        logger.i("before")
        logger.level = LogLevel.INFO
        logger.i("after")
        assertEquals(listOf("after"), written.map { it.message })
    }

    @Test
    fun `tag and throwable reach the sink`() {
        val error = IllegalStateException("boom")
        SdkLogger(LogLevel.ERROR, sink).e("failed", error)
        assertEquals(SdkLogger.TAG, written.single().tag)
        assertSame(error, written.single().throwable)
    }

    @Test
    fun `a failing sink never propagates to the caller`() {
        val logger = SdkLogger(LogLevel.VERBOSE) { _, _, _, _ -> throw RuntimeException("sink is broken") }
        try {
            logAllLevels(logger)
        } catch (_: Throwable) {
            fail("Logger must not throw when sink fails")
        }
    }

    @Test
    fun `default level is warning`() {
        assertEquals(LogLevel.WARNING, SdkLogger(sink = sink).level)
    }

    @Test
    fun `log by level follows the same threshold as the named calls`() {
        val logger = SdkLogger(LogLevel.WARNING, sink)
        val boom = IllegalStateException("boom")
        logger.log(LogLevel.ERROR, "e", boom)
        logger.log(LogLevel.WARNING, "w")
        logger.log(LogLevel.DEBUG, "d")
        logger.log(LogLevel.NONE, "none")
        assertEquals(listOf(Entry(LogLevel.ERROR, SdkLogger.TAG, "e", boom), Entry(LogLevel.WARNING, SdkLogger.TAG, "w", null)), written)
    }
}
