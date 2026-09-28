package io.github.feedbacklib.android.internal.ui

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.test.core.app.ApplicationProvider
import io.github.feedbacklib.android.TextKey
import io.github.feedbacklib.android.internal.ui.theme.feedbackColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class TextsTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private fun resourcesFor(language: String): Resources =
        app.createConfigurationContext(Configuration(app.resources.configuration).apply { setLocale(Locale.forLanguageTag(language)) }).resources

    @Test
    fun `every text key has an English text and a Russian translation`() {
        val en = resourcesFor("en")
        val ru = resourcesFor("ru")
        TextKey.entries.forEach { key ->
            val english = en.getString(key.resId)
            assertTrue("$key has no English text", english.isNotBlank())
            assertNotEquals("$key is not translated to Russian", english, ru.getString(key.resId))
        }
    }

    @Test
    fun `the screenshot placeholder says what the spec says`() {
        assertEquals("Screenshot unavailable for this screen", resourcesFor("en").getString(TextKey.SCREENSHOT_UNAVAILABLE.resId))
        assertEquals("Скриншот недоступен для этого экрана", resourcesFor("ru").getString(TextKey.SCREENSHOT_UNAVAILABLE.resId))
    }

    @Test
    fun `every key has its own resource`() {
        assertEquals(TextKey.entries.size, TextKey.entries.map { it.resId }.toSet().size)
    }

    @Test
    fun `host overrides win over the resources`() {
        val texts = Texts(mapOf(TextKey.BUTTON_SEND to "Go")) { id -> resourcesFor("en").getString(id) }
        assertEquals("Go", texts[TextKey.BUTTON_SEND])
        assertEquals("Next", texts[TextKey.BUTTON_NEXT])
    }

    @Test
    fun `a blank override keeps the built-in text`() {
        val texts = Texts(mapOf(TextKey.BUTTON_SEND to "  ", TextKey.BUTTON_NEXT to "")) { id -> resourcesFor("en").getString(id) }
        assertEquals("Send", texts[TextKey.BUTTON_SEND])
        assertEquals("Next", texts[TextKey.BUTTON_NEXT])
    }

    @Test
    fun `a custom primary colour gets readable content, and none keeps the Material default`() {
        val light = feedbackColorScheme(dark = false, primaryColor = 0xFFFFEB3B.toInt())
        assertEquals(Color(0xFFFFEB3B.toInt()), light.primary)
        assertEquals(Color.Black, light.onPrimary)
        assertEquals(darkColorScheme().primary, feedbackColorScheme(dark = true, primaryColor = null).primary)
    }

    @Test
    fun `the proactive prompt says what the spec says`() {
        val ru = resourcesFor("ru")
        assertEquals("Похоже, что-то пошло не так. Расскажете, что случилось?", ru.getString(TextKey.PROACTIVE_PROMPT_MESSAGE.resId))
        assertEquals("Рассказать", ru.getString(TextKey.PROACTIVE_TELL_US.resId))
        assertEquals("Не сейчас", ru.getString(TextKey.PROACTIVE_NOT_NOW.resId))
        assertEquals("Looks like something went wrong. Would you tell us what happened?", resourcesFor("en").getString(TextKey.PROACTIVE_PROMPT_MESSAGE.resId))
    }
}
