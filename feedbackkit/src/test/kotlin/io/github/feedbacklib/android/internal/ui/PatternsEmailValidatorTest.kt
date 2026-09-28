package io.github.feedbacklib.android.internal.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PatternsEmailValidatorTest {

    @Test
    fun `accepts ordinary addresses and rejects the rest`() {
        assertTrue(PatternsEmailValidator.isValid("tester@example.com"))
        assertTrue(PatternsEmailValidator.isValid("a.b+c@sub.example.org"))
        assertFalse(PatternsEmailValidator.isValid("tester@"))
        assertFalse(PatternsEmailValidator.isValid("not an email"))
        assertFalse(PatternsEmailValidator.isValid(""))
    }
}
