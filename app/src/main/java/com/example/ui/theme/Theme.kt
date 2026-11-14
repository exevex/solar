package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val CosmicColorScheme = darkColorScheme(
    primary = CyanTelemetry,
    onPrimary = SpaceVoid,
    primaryContainer = CyanMuted,
    onPrimaryContainer = TextPrimary,
    secondary = AmberStar,
    onSecondary = SpaceVoid,
    tertiary = PurpleAccretion,
    onTertiary = TextPrimary,
    background = SpaceVoid,
    onBackground = TextPrimary,
    surface = PanelObsidian,
    onSurface = TextPrimary,
    surfaceVariant = PanelElevated,
    onSurfaceVariant = TextSecondary,
    outline = HairlineBorder,
    error = DangerNova,
    onError = TextPrimary
)

@Composable
fun SolarSandboxTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = CosmicColorScheme,
        typography = SolarTypography,
        content = content
    )
}
