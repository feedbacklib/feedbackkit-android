package io.github.feedbacklib.android.internal.core

/** Parses an enum by name without throwing: a missing or unknown [name] becomes `null`. */
internal inline fun <reified T : Enum<T>> enumOrNull(name: String?): T? {
    if (name == null) return null
    // Only "no such constant" means unknown; anything else is a bug and must not be hidden.
    return try {
        enumValueOf<T>(name)
    } catch (_: IllegalArgumentException) {
        null
    }
}
