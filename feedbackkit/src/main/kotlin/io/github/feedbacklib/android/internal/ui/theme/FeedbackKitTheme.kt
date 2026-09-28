package io.github.feedbacklib.android.internal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import io.github.feedbacklib.android.ColorTheme
import kotlin.math.pow

internal fun isDark(theme: ColorTheme, systemDark: Boolean): Boolean = when (theme) {
    ColorTheme.LIGHT -> false
    ColorTheme.DARK -> true
    ColorTheme.SYSTEM -> systemDark
}

/** WCAG relative luminance of an opaque ARGB colour, 0 (black) to 1 (white). */
internal fun relativeLuminance(argb: Int): Double {
    fun channel(shift: Int): Double {
        val c = ((argb shr shift) and 0xFF) / 255.0
        return if (c <= 0.03928) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0)
}

/** Black or white, whichever has the higher contrast on [argb]. */
internal fun contentColorOn(argb: Int): Int {
    val luminance = relativeLuminance(argb)
    val onBlack = (luminance + 0.05) / 0.05
    val onWhite = 1.05 / (luminance + 0.05)
    return if (onBlack >= onWhite) BLACK else WHITE
}

/** Material 3 baseline colours with the host's primary colour, if it set one. */
internal fun feedbackColorScheme(dark: Boolean, primaryColor: Int?): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    if (primaryColor == null) return base
    val primary = Color(primaryColor)
    return base.copy(primary = primary, onPrimary = Color(contentColorOn(primaryColor)), surfaceTint = primary)
}

@Composable
internal fun FeedbackKitTheme(colorTheme: ColorTheme, primaryColor: Int?, content: @Composable () -> Unit) {
    val dark = isDark(colorTheme, isSystemInDarkTheme())
    MaterialTheme(colorScheme = feedbackColorScheme(dark, primaryColor), content = content)
}

private const val BLACK: Int = -0x1000000 // 0xFF000000
private const val WHITE: Int = -0x1 // 0xFFFFFFFF
