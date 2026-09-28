package io.github.feedbacklib.android.internal.ui

import android.util.Patterns

/** The platform's email pattern (spec §6). */
internal object PatternsEmailValidator : EmailValidator {
    override fun isValid(email: String): Boolean = Patterns.EMAIL_ADDRESS.matcher(email).matches()
}
