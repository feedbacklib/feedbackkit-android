package io.github.feedbacklib.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class FeedbackKitInfoTest {

    @Test
    fun `version is the one declared in gradle properties`() {
        val expected = System.getProperty("feedbackkit.expectedVersion")
        assertEquals(expected, FeedbackKitInfo.VERSION)
    }

    @Test
    fun `no public BuildConfig class ships beside the API`() {
        // Javac's BuildConfig is invisible to the ABI dump; the version comes from a
        // generated internal constant instead.
        assertThrows(ClassNotFoundException::class.java) {
            Class.forName("io.github.feedbacklib.android.BuildConfig")
        }
    }
}
