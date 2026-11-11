package com.jellydirect.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// "Ember": warm ink background, ember-orange accent, gold highlights. Every Material role is set explicitly,
// otherwise the library's default (purple) tints leak into the nav bar, dialogs and text fields.
private val Scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF1A0A04),
    primaryContainer = Color(0xFF4A1F10),
    onPrimaryContainer = Color(0xFFFFDCCF),
    inversePrimary = Color(0xFFB3401D),
    secondary = Gold,
    onSecondary = Color(0xFF1F1400),
    secondaryContainer = Color(0xFF3A2A14),
    onSecondaryContainer = Color(0xFFFFE2B8),
    tertiary = Gold,
    onTertiary = Color(0xFF1F1400),
    tertiaryContainer = Color(0xFF4A3510),
    onTertiaryContainer = Color(0xFFFFE2B8),
    background = Bg,
    onBackground = Color(0xFFF4EFEA),
    surface = Surface1,
    onSurface = Color(0xFFF4EFEA),
    surfaceVariant = Surface2,
    onSurfaceVariant = Color(0xFFB0A8AE),
    surfaceTint = Accent,
    inverseSurface = Color(0xFFF4EFEA),
    inverseOnSurface = Color(0xFF231F26),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF2A0A0A),
    errorContainer = Color(0xFF5A1A1A),
    onErrorContainer = Color(0xFFFFDAD6),
    outline = Color(0xFF4A444C),
    outlineVariant = Color(0xFF2E2A31),
    scrim = Color.Black,
    surfaceBright = Color(0xFF2C2730),
    surfaceDim = Bg,
    surfaceContainerLowest = Bg,
    surfaceContainerLow = Color(0xFF121014),
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Color(0xFF2C2730),
)

private val Type = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun JellyTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, typography = Type, content = content)
}
