package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val TzlilimDarkColorScheme = darkColorScheme(
    primary = VinylCoral,
    onPrimary = StudioObsidian,
    primaryContainer = VinylCoralContainer,
    onPrimaryContainer = OnVinylCoralContainer,
    secondary = HarmonicTeal,
    onSecondary = StudioObsidian,
    secondaryContainer = HarmonicTealContainer,
    onSecondaryContainer = OnHarmonicTealContainer,
    tertiary = AmberGold,
    onTertiary = StudioObsidian,
    background = StudioObsidian,
    onBackground = WarmOffWhite,
    surface = StudioSlate,
    onSurface = WarmOffWhite,
    surfaceVariant = StudioCard,
    onSurfaceVariant = MistGray,
    surfaceContainerHigh = StudioCardElevated,
    outline = OutlineSubtle,
    error = ErrorRose,
    onError = StudioObsidian
)

private val TzlilimLightColorScheme = lightColorScheme(
    primary = VinylCoralDark,
    onPrimary = DaylightSurface,
    primaryContainer = ColorTokens.LightCoralTint,
    onPrimaryContainer = ColorTokens.LightCoralInk,
    secondary = ColorTokens.LightTeal,
    onSecondary = DaylightSurface,
    secondaryContainer = ColorTokens.LightTealTint,
    onSecondaryContainer = ColorTokens.LightTealInk,
    background = DaylightIvory,
    onBackground = DaylightInk,
    surface = DaylightSurface,
    onSurface = DaylightInk,
    surfaceVariant = DaylightCard,
    onSurfaceVariant = DaylightMuted,
    outline = ColorTokens.LightOutline,
    error = ErrorRose,
    onError = DaylightSurface
)

private object ColorTokens {
    val LightCoralTint = androidx.compose.ui.graphics.Color(0xFFFFE5DF)
    val LightCoralInk = androidx.compose.ui.graphics.Color(0xFF5E1607)
    val LightTeal = androidx.compose.ui.graphics.Color(0xFF00897B)
    val LightTealTint = androidx.compose.ui.graphics.Color(0xFFD5F5F0)
    val LightTealInk = androidx.compose.ui.graphics.Color(0xFF003B34)
    val LightOutline = androidx.compose.ui.graphics.Color(0xFFD8D4CC)
}

@Composable
fun TzlilimTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) TzlilimDarkColorScheme else TzlilimLightColorScheme
    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content
    )
}
