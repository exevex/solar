package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.render.ScreenBodyLabel
import com.example.simulation.BodyType
import com.example.simulation.SimulationConfig
import com.example.ui.theme.*
import com.example.viewmodel.SandboxToolTab
import com.example.viewmodel.SandboxUiState
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

@Composable
fun GlassHudSurface(
    modifier: Modifier = Modifier,
    borderColor: Color = HairlineBorder,
    content: @Composable () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = PanelObsidian,
        border = BorderStroke(1.dp, borderColor),
        tonalElevation = 4.dp
    ) {
        content()
    }
}

@Composable
fun TopTelemetryBar(
    uiState: SandboxUiState,
    onOpenPresets: () -> Unit,
    onOpenSaveLoad: () -> Unit,
    onOpenQuality: () -> Unit,
    onToggleOrbits: () -> Unit,
    onToggleLabels: () -> Unit,
    onResetCamera: () -> Unit,
    onToggleCinematic: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left: System Preset Pill & Telemetry Clock
        GlassHudSurface(borderColor = HairlineCyan) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onOpenPresets)
                        .padding(horizontal = 6.dp, vertical = 4.dp)
                        .testTag("presets_button"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Default.Public, contentDescription = stringResource(R.string.cd_presets), tint = CyanTelemetry, modifier = Modifier.size(18.dp))
                    Text(
                        text = uiState.currentPreset.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = TextSecondary, modifier = Modifier.size(16.dp))
                }

                VerticalDivider(modifier = Modifier.height(20.dp), color = HairlineBorder)

                Column {
                    Text(
                        text = formatSimTime(uiState.elapsedYears),
                        style = MaterialTheme.typography.labelMedium,
                        color = CyanTelemetry
                    )
                    Text(
                        text = "${uiState.bodyCount} bodies • ${uiState.fps.roundToInt()} FPS",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }
        }

        // Center Event Toast
        AnimatedVisibility(
            visible = uiState.eventBanner != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = PanelElevated,
                border = BorderStroke(1.dp, AmberStar.copy(alpha = 0.65f))
            ) {
                Text(
                    text = uiState.eventBanner.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = AmberWarm,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
        }

        // Right: Quick Toggles & Modals
        GlassHudSurface {
            Row(
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudIconButton(
                    icon = Icons.Default.TrackChanges,
                    contentDesc = stringResource(R.string.cd_toggle_trails),
                    active = uiState.config.showOrbits,
                    tag = "toggle_orbits_button",
                    onClick = onToggleOrbits
                )
                HudIconButton(
                    icon = Icons.AutoMirrored.Filled.Label,
                    contentDesc = stringResource(R.string.cd_toggle_labels),
                    active = uiState.config.showLabels,
                    tag = "toggle_labels_button",
                    onClick = onToggleLabels
                )
                HudIconButton(
                    icon = Icons.Default.CenterFocusStrong,
                    contentDesc = stringResource(R.string.cd_focus_body),
                    active = false,
                    tag = "reset_camera_button",
                    onClick = onResetCamera
                )
                HudIconButton(
                    icon = Icons.Default.Videocam,
                    contentDesc = stringResource(R.string.cd_cinematic_mode),
                    active = uiState.cinematicMode,
                    tag = "cinematic_button",
                    onClick = onToggleCinematic
                )
                HudIconButton(
                    icon = Icons.Default.Save,
                    contentDesc = stringResource(R.string.cd_save_load),
                    active = false,
                    tag = "save_load_button",
                    onClick = onOpenSaveLoad
                )
                HudIconButton(
                    icon = Icons.Default.Tune,
                    contentDesc = stringResource(R.string.cd_quality_settings),
                    active = false,
                    tag = "quality_settings_button",
                    onClick = onOpenQuality
                )
            }
        }
    }
}

@Composable
private fun HudIconButton(
    icon: ImageVector,
    contentDesc: String,
    active: Boolean,
    tag: String,
    onClick: () -> Unit
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .testTag(tag)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDesc,
            tint = if (active) CyanTelemetry else TextSecondary,
            modifier = Modifier.size(20.dp)
        )
    }
}

@Composable
fun ToolRail(
    activeTab: SandboxToolTab,
    onSelectTab: (SandboxToolTab) -> Unit,
    modifier: Modifier = Modifier
) {
    GlassHudSurface(modifier = modifier.padding(start = 12.dp)) {
        Column(
            modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            RailButton(
                icon = Icons.Default.TouchApp,
                label = "Orbit",
                selected = activeTab == SandboxToolTab.NONE,
                accent = CyanTelemetry,
                tag = "tool_orbit",
                onClick = { onSelectTab(SandboxToolTab.NONE) }
            )
            RailButton(
                icon = Icons.Default.AddCircleOutline,
                label = "Spawn",
                selected = activeTab == SandboxToolTab.SPAWN,
                accent = EmeraldOrbit,
                tag = "tool_spawn",
                onClick = { onSelectTab(SandboxToolTab.SPAWN) }
            )
            RailButton(
                icon = Icons.Default.RocketLaunch,
                label = "Sling",
                selected = activeTab == SandboxToolTab.SLINGSHOT,
                accent = CyanTelemetry,
                tag = "tool_slingshot",
                onClick = { onSelectTab(SandboxToolTab.SLINGSHOT) }
            )
            RailButton(
                icon = Icons.Default.Whatshot,
                label = "Smash",
                selected = activeTab == SandboxToolTab.WEAPONS,
                accent = DangerNova,
                tag = "tool_weapons",
                onClick = { onSelectTab(SandboxToolTab.WEAPONS) }
            )
            RailButton(
                icon = Icons.Default.Analytics,
                label = "Edit",
                selected = activeTab == SandboxToolTab.INSPECT,
                accent = AmberStar,
                tag = "tool_inspect",
                onClick = { onSelectTab(SandboxToolTab.INSPECT) }
            )
        }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) accent.copy(alpha = 0.18f) else Color.Transparent)
            .border(
                width = if (selected) 1.dp else 0.dp,
                color = if (selected) accent.copy(alpha = 0.7f) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 6.dp)
            .testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = if (selected) accent else TextSecondary, modifier = Modifier.size(20.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = if (selected) TextPrimary else TextSecondary)
    }
}

@Composable
fun TimeControlDock(
    config: SimulationConfig,
    onTogglePause: () -> Unit,
    onToggleRewind: () -> Unit,
    onResetPreset: () -> Unit,
    onTimeScaleChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    // Logarithmic mapping from 1e-6 yr/s (real-time ~ 31x) to 120 yr/s (~3.7 billion x / millions of x)
    val minLog = ln(0.00001)
    val maxLog = ln(120.0)
    val currentSlider = ((ln(config.timeScaleYearsPerSec.coerceIn(0.00001, 120.0)) - minLog) / (maxLog - minLog)).toFloat()

    GlassHudSurface(modifier = modifier.padding(bottom = 10.dp)) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            IconButton(
                onClick = onToggleRewind,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("rewind_button")
            ) {
                Icon(
                    Icons.Default.FastRewind,
                    contentDescription = stringResource(R.string.cd_rewind),
                    tint = if (config.isRewinding) PurpleAccretion else TextSecondary
                )
            }
            IconButton(
                onClick = onTogglePause,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (config.isPaused) AmberStar.copy(alpha = 0.2f) else CyanTelemetry.copy(alpha = 0.2f))
                    .testTag("play_pause_button")
            ) {
                Icon(
                    imageVector = if (config.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    contentDescription = stringResource(R.string.cd_play_pause),
                    tint = if (config.isPaused) AmberWarm else CyanTelemetry
                )
            }
            IconButton(
                onClick = onResetPreset,
                modifier = Modifier.minimumInteractiveComponentSize().testTag("reset_preset_button")
            ) {
                Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.cd_reset_system), tint = TextSecondary)
            }

            Column(modifier = Modifier.width(170.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = when {
                            config.isPaused -> "PAUSED"
                            config.isRewinding -> "REWINDING"
                            else -> formatTimeRate(config.timeScaleYearsPerSec)
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (config.isRewinding) PurpleAccretion else CyanTelemetry
                    )
                }
                Slider(
                    value = currentSlider,
                    onValueChange = { frac ->
                        val rate = kotlin.math.exp(minLog + frac * (maxLog - minLog))
                        onTimeScaleChange(rate)
                    },
                    modifier = Modifier.height(26.dp).testTag("time_scale_slider"),
                    colors = SliderDefaults.colors(
                        thumbColor = CyanTelemetry,
                        activeTrackColor = CyanTelemetry,
                        inactiveTrackColor = HairlineBorder
                    )
                )
            }

            // Quick warp chips
            listOf("1d/s" to 0.00274, "1mo/s" to 0.083, "1yr/s" to 1.0, "10M×" to 0.317).forEach { (lbl, rate) ->
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (kotlin.math.abs(config.timeScaleYearsPerSec - rate) < rate * 0.25 && !config.isPaused)
                        CyanTelemetry.copy(alpha = 0.22f) else Color.Transparent,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier
                        .heightIn(min = 36.dp)
                        .clickable { onTimeScaleChange(rate) }
                        .testTag("warp_$lbl")
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(lbl, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                    }
                }
            }
        }
    }
}

@Composable
fun LabelsOverlay(
    labels: List<ScreenBodyLabel>,
    showLabels: Boolean,
    onSelectBody: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    if (!showLabels) return
    val density = LocalDensity.current
    Box(modifier = modifier.fillMaxSize()) {
        // Draw crisp selection reticle on Canvas
        val selected = labels.firstOrNull { it.isSelected }
        if (selected != null) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val c = Offset(selected.xPx, selected.yPx)
                drawCircle(
                    color = CyanTelemetry.copy(alpha = 0.75f),
                    radius = with(density) { 20.dp.toPx() },
                    center = c,
                    style = Stroke(width = with(density) { 1.5.dp.toPx() })
                )
            }
        }

        for (lbl in labels) {
            val offsetY = with(density) { 16.dp.roundToPx() }
            val accent = when {
                lbl.isSelected -> CyanTelemetry
                lbl.type == BodyType.BLACK_HOLE -> PurpleAccretion
                lbl.type.isLuminous -> AmberWarm
                else -> TextSecondary
            }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = PanelObsidian,
                border = BorderStroke(if (lbl.isSelected) 1.dp else 0.5.dp, accent.copy(alpha = 0.75f)),
                modifier = Modifier
                    .offset { IntOffset(lbl.xPx.roundToInt() - 30, lbl.yPx.roundToInt() + offsetY) }
                    .clickable { onSelectBody(lbl.id) }
            ) {
                Text(
                    text = lbl.name,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lbl.isSelected) TextPrimary else accent,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp),
                    maxLines = 1
                )
            }
        }
    }
}

fun formatSimTime(years: Double): String {
    val absY = kotlin.math.abs(years)
    return when {
        absY < 1.0 / 365.25 -> String.format("%.1f hrs", years * 8766.0)
        absY < 1.0 -> String.format("%.1f days", years * 365.25)
        absY < 1000.0 -> String.format("%.2f yrs", years)
        else -> String.format("%.2f kyr", years / 1000.0)
    }
}

fun formatTimeRate(yearsPerSec: Double): String {
    val secondsPerSec = yearsPerSec * 31_557_600.0
    return when {
        secondsPerSec < 3600 -> String.format("%.0f× real", secondsPerSec)
        secondsPerSec < 86400 -> String.format("%.1f hr/s", secondsPerSec / 3600.0)
        yearsPerSec < 0.08 -> String.format("%.1f day/s", yearsPerSec * 365.25)
        yearsPerSec < 1.0 -> String.format("%.1f mo/s", yearsPerSec * 12.0)
        else -> String.format("%.1f yr/s", yearsPerSec)
    }
}
