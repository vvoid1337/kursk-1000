package com.kursk1000.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val KurskColorScheme = lightColorScheme(
    primary = HeritageRed,
    onPrimary = OnHeritageRed,
    primaryContainer = HeritageRedContainer,
    onPrimaryContainer = OnHeritageRedContainer,
    secondary = WarmBrown,
    onSecondary = OnWarmBrown,
    secondaryContainer = FactsPanel,
    onSecondaryContainer = OnFactsPanel,
    tertiary = MutedGreen,
    onTertiary = OnMutedGreen,
    background = PaperSurface,
    onBackground = OnSurfaceInk,
    surface = PaperSurface,
    onSurface = OnSurfaceInk,
    surfaceVariant = SurfacePlaceholder,
    onSurfaceVariant = OnSurfaceMuted,
    outline = OutlineHairline,
    outlineVariant = OutlineSoft,
    error = ErrorRed,
    onError = OnError,
)

@Composable
fun Kursk1000Theme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = KurskColorScheme,
        typography = Typography,
        content = content
    )
}