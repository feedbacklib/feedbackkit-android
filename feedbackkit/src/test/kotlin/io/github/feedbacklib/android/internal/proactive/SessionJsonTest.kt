package io.github.feedbacklib.android.internal.proactive

import io.github.feedbacklib.android.internal.report.ProactiveTrigger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SessionJsonTest {

    @Test
    fun `each state survives a round trip`() {
        val session = SessionState("s-1", 1_000, 2_000, wentBackground = true, lastBackgroundAt = 2_000, lastAliveAt = 12_000)
        assertEquals(session, SessionJson.decodeSession(SessionJson.encodeSession(session)))
        val crash = CrashMarker(3_000, "java.lang.IllegalStateException", "java.lang.IllegalStateException: boom\n\tat com.example.Host.save(Host.kt:12)")
        assertEquals(crash, SessionJson.decodeCrash(SessionJson.encodeCrash(crash)))
        val shown = ProactiveState(4_000)
        assertEquals(shown, SessionJson.decodeProactive(SessionJson.encodeProactive(shown)))
        val waiting = ProactiveState(lastModalAt = 4_000, lastProcessedExitAt = 3_500, pending = PendingEvent(3_000, ProactiveTrigger.CRASH, "java.lang.IllegalStateException", "boom"))
        assertEquals(waiting, SessionJson.decodeProactive(SessionJson.encodeProactive(waiting)))
    }

    @Test
    fun `the field names are the spec's`() {
        val text = SessionJson.encodeSession(SessionState("s-1", 1, 2, wentBackground = true, lastBackgroundAt = 2, lastAliveAt = 3))
        listOf("\"sessionId\"", "\"startedAt\"", "\"lastForegroundAt\"", "\"wentBackground\"", "\"lastBackgroundAt\"", "\"lastAliveAt\"").forEach { assertTrue(text.contains(it), text) }
        val proactive = SessionJson.encodeProactive(ProactiveState(5, 6, PendingEvent(7, ProactiveTrigger.FORCE_RESTART)))
        listOf("\"lastModalAt\"", "\"lastProcessedExitAt\"", "\"pending\"", "\"detectedAt\"", "\"trigger\"").forEach { assertTrue(proactive.contains(it), proactive) }
    }

    @Test
    fun `damaged, foreign or empty text is no state`() {
        listOf("", "{", "null", "[]", "\u0000\u0001\u0002").forEach { text ->
            assertNull(SessionJson.decodeSession(text), text)
            assertNull(SessionJson.decodeCrash(text), text)
            assertNull(SessionJson.decodeProactive(text), text)
        }
        listOf(
            "{\"sessionId\":\"s\"}",
            "{\"sessionId\":\"s\",\"startedAt\":\"soon\",\"lastForegroundAt\":1,\"wentBackground\":false}",
        ).forEach { text ->
            assertNull(SessionJson.decodeSession(text), text)
            assertNull(SessionJson.decodeCrash(text), text)
        }
        assertNull(SessionJson.decodeProactive("{\"lastModalAt\":\"soon\"}"))
        assertNull(SessionJson.decodeProactive("{\"pending\":{\"detectedAt\":1,\"trigger\":\"LOW_BATTERY\"}}"), "a trigger this version does not know")
    }

    @Test
    fun `every field of proactive json is optional, and the previous version's file still reads`() {
        assertEquals(ProactiveState(), SessionJson.decodeProactive("{}"))
        assertEquals(ProactiveState(), SessionJson.decodeProactive("{\"sessionId\":\"s\"}"))
        assertEquals(ProactiveState(lastModalAt = 5), SessionJson.decodeProactive("{\"lastModalAt\":5}"))
    }

    @Test
    fun `the previous version's session json still reads, with no time of leaving and no tail`() {
        assertEquals(
            SessionState("s", 1, 2, wentBackground = true, lastBackgroundAt = 0, lastAliveAt = 0),
            SessionJson.decodeSession("{\"sessionId\":\"s\",\"startedAt\":1,\"lastForegroundAt\":2,\"wentBackground\":true}"),
        )
    }

    @Test
    fun `fields a newer version adds are ignored`() {
        assertEquals(ProactiveState(5), SessionJson.decodeProactive("{\"lastModalAt\":5,\"shownCount\":3}"))
        assertEquals(SessionState("s", 1, 2, true), SessionJson.decodeSession("{\"sessionId\":\"s\",\"startedAt\":1,\"lastForegroundAt\":2,\"wentBackground\":true,\"pid\":7}"))
    }
}
