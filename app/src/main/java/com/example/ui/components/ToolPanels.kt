package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.simulation.*
import com.example.ui.theme.*
import com.example.viewmodel.*
import kotlin.math.ln
import kotlin.math.pow

@Composable
fun SpawnDrawer(
    uiState: SandboxUiState,
    onSelectCategory: (SpawnCategory) -> Unit,
    onSelectTemplate: (SpawnTemplate) -> Unit,
    onToggleSpawnStationary: () -> Unit,
    onSpawnQuickOrbit: () -> Unit,
    onTogglePause: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filtered = remember(uiState.selectedSpawnCategory) {
        SpawnCatalog.all.filter { it.category == uiState.selectedSpawnCategory }
    }

    GlassHudSurface(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 78.dp, end = 12.dp, bottom = 8.dp),
        borderColor = EmeraldOrbit.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Header row: Category pills + action buttons (leaves all cards completely unobstructed!)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(SpawnCategory.entries) { cat ->
                        val active = cat == uiState.selectedSpawnCategory
                        val catAccent = if (cat == SpawnCategory.BLACK_HOLES) PurpleAccretion else EmeraldOrbit
                        FilterChip(
                            selected = active,
                            onClick = { onSelectCategory(cat) },
                            label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = catAccent.copy(alpha = 0.24f),
                                selectedLabelColor = catAccent
                            )
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (uiState.spawnStationary) AmberWarm.copy(alpha = 0.18f) else CyanTelemetry.copy(alpha = 0.14f),
                    border = BorderStroke(1.dp, if (uiState.spawnStationary) AmberWarm else CyanTelemetry.copy(alpha = 0.65f)),
                    modifier = Modifier
                        .heightIn(min = 38.dp)
                        .clickable { onToggleSpawnStationary() }
                        .testTag("spawn_velocity_mode_btn")
                ) {
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = if (uiState.spawnStationary) "Drop Still" else "Circular Orbit",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.spawnStationary) AmberWarm else CyanTelemetry
                        )
                    }
                }

                Button(
                    onClick = onSpawnQuickOrbit,
                    modifier = Modifier.heightIn(min = 38.dp).testTag("spawn_orbit_btn"),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = EmeraldOrbit, contentColor = SpaceVoid)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Quick Place", style = MaterialTheme.typography.labelMedium)
                }

                IconButton(onClick = onTogglePause, modifier = Modifier.size(36.dp).testTag("spawn_play_pause_btn")) {
                    Icon(
                        imageVector = if (uiState.config.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = stringResource(R.string.cd_play_pause),
                        tint = if (uiState.config.isPaused) AmberWarm else CyanTelemetry
                    )
                }

                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_panel), tint = TextSecondary)
                }
            }

            // Wide multi-item horizontal card strip showing 4-5 objects at once without cramped scrolling!
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(filtered) { tpl ->
                    val sel = tpl.id == uiState.selectedSpawnTemplate.id
                    val isBh = tpl.type == BodyType.BLACK_HOLE
                    val highlight = if (isBh) PurpleAccretion else EmeraldOrbit
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) highlight.copy(alpha = 0.20f) else PanelElevated,
                        border = BorderStroke(if (sel) 1.5.dp else 1.dp, if (sel) highlight else HairlineBorder),
                        modifier = Modifier
                            .width(166.dp)
                            .heightIn(min = 58.dp)
                            .clickable { onSelectTemplate(tpl) }
                            .testTag("spawn_tpl_${tpl.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isBh) Color.Black
                                        else Color(tpl.albedoColor.first, tpl.albedoColor.second, tpl.albedoColor.third)
                                    )
                                    .border(
                                        width = if (isBh) 2.dp else 1.dp,
                                        color = if (isBh) AmberWarm else Color.White.copy(alpha = 0.45f),
                                        shape = CircleShape
                                    )
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = tpl.name,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                    maxLines = 1
                                )
                                Text(
                                    text = when {
                                        isBh -> "${tpl.massSolar} M☉ • Event Horizon"
                                        tpl.type.isLuminous -> "${tpl.temperatureK.toInt()} K • ${tpl.massSolar} M☉"
                                        else -> "R = ${formatRadiusKm(tpl.radiusKm)}"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isBh) PurpleAccretion else TextSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SlingshotHintCard(
    template: SpawnTemplate,
    onSwitchToSpawnCatalog: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassHudSurface(modifier = modifier.width(275.dp), borderColor = HairlineCyan) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Slingshot Launch", style = MaterialTheme.typography.titleMedium, color = CyanTelemetry)
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_panel), tint = TextSecondary)
                }
            }
            Text(
                text = "Touch & drag back anywhere in space to aim ${template.name}. A cyan arc previews the real N-body trajectory.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            OutlinedButton(
                onClick = onSwitchToSpawnCatalog,
                modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                border = BorderStroke(1.dp, HairlineCyan)
            ) {
                Text("Ammo: ${template.name}", color = CyanTelemetry, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun WeaponsPanel(
    uiState: SandboxUiState,
    onAsteroidStrike: () -> Unit,
    onNuke: () -> Unit,
    onSupernova: () -> Unit,
    onGammaBurst: () -> Unit,
    onSplitPlanet: () -> Unit,
    onRogueStar: () -> Unit,
    onToggleHawking: () -> Unit,
    onToggleStellarEvolution: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val targetName = uiState.selectedTelemetry?.body?.name ?: "Target"
    GlassHudSurface(
        modifier = modifier.width(290.dp).fillMaxHeight(),
        borderColor = DangerNova.copy(alpha = 0.6f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Weapons & Events", style = MaterialTheme.typography.titleMedium, color = DangerNova)
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_panel), tint = TextSecondary)
                }
            }
            Text("Active target: $targetName", style = MaterialTheme.typography.labelMedium, color = AmberWarm)

            WeaponButton("Asteroid Strike", "High-speed kinetic impactor", Icons.Default.Flare, DangerNova, "weapon_asteroid", onAsteroidStrike)
            WeaponButton("Thermonuclear Strike", "Surface plasma crater & crust melt", Icons.Default.Emergency, AmberStar, "weapon_nuke", onNuke)
            WeaponButton("Planet Splitter", "Cleave selected body into two halves", Icons.Default.ContentCut, DangerNova, "weapon_split", onSplitPlanet)
            WeaponButton("Trigger Supernova", "Detonate star into remnant + nebula", Icons.Default.WbSunny, CyanTelemetry, "weapon_supernova", onSupernova)
            WeaponButton("Gamma-Ray Burst", "Bipolar relativistic beam across system", Icons.Default.Bolt, CyanTelemetry, "weapon_grb", onGammaBurst)
            WeaponButton("Rogue Star Flyby", "Launch intruder K-star through system", Icons.Default.BlurOn, AmberWarm, "weapon_rogue", onRogueStar)

            HorizontalDivider(color = HairlineBorder)
            Text("Cosmic Physics Toggles", style = MaterialTheme.typography.titleSmall, color = TextPrimary)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hawking Evaporation", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("Black holes radiate mass & flash", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Switch(
                    checked = uiState.config.hawkingEvaporation,
                    onCheckedChange = { onToggleHawking() },
                    modifier = Modifier.testTag("toggle_hawking")
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Stellar Evolution", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                    Text("Main Sequence → Red Giant → Remnant", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                }
                Switch(
                    checked = uiState.config.stellarEvolutionEnabled,
                    onCheckedChange = { onToggleStellarEvolution() },
                    modifier = Modifier.testTag("toggle_evolution")
                )
            }
        }
    }
}

@Composable
private fun WeaponButton(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    tag: String,
    onClick: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = PanelElevated,
        border = BorderStroke(1.dp, accent.copy(alpha = 0.45f)),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clickable(onClick = onClick)
            .testTag(tag)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(22.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
            }
        }
    }
}

@Composable
fun InspectorPanel(
    telemetry: BodyTelemetry?,
    onFocusBody: (Long) -> Unit,
    onDeleteBody: () -> Unit,
    onEditBody: ((CelestialBody) -> CelestialBody) -> Unit,
    onEditDensity: (Double) -> Unit,
    onNudgeOrbit: (Double) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    GlassHudSurface(
        modifier = modifier.width(310.dp).fillMaxHeight(),
        borderColor = AmberStar.copy(alpha = 0.55f)
    ) {
        if (telemetry == null) {
            Box(modifier = Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text("Tap any planet, star, or black hole to inspect and edit live.", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
            }
            return@GlassHudSurface
        }
        val b = telemetry.body
        Column(
            modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(b.name, style = MaterialTheme.typography.titleMedium, color = AmberWarm)
                    Text(
                        text = if (b.evolutionStage != EvolutionStage.NONE) "${b.type.label} • ${b.evolutionStage.label}" else b.type.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = { onFocusBody(b.id) }, modifier = Modifier.size(36.dp).testTag("focus_selected_btn")) {
                    Icon(Icons.Default.CenterFocusStrong, contentDescription = stringResource(R.string.cd_focus_body), tint = CyanTelemetry)
                }
                IconButton(onClick = onDeleteBody, modifier = Modifier.size(36.dp).testTag("delete_selected_btn")) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.cd_delete_body), tint = DangerNova)
                }
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_panel), tint = TextSecondary)
                }
            }

            // Live Status + Telemetry Grid
            val statusIsCritical = b.tidalStress > 0.12f || b.crustHeat > 0.35f
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = (if (statusIsCritical) DangerNova else CyanTelemetry).copy(alpha = 0.14f),
                border = BorderStroke(1.dp, (if (statusIsCritical) DangerNova else CyanTelemetry).copy(alpha = 0.55f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = telemetry.statusSummary,
                    style = MaterialTheme.typography.labelMedium,
                    color = if (statusIsCritical) DangerNova else CyanTelemetry,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }

            Surface(shape = RoundedCornerShape(10.dp), color = PanelElevated, border = BorderStroke(1.dp, HairlineBorder)) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TelemetryRow("Speed", String.format("%.2f km/s", telemetry.speedKmS))
                    TelemetryRow("Surface Gravity", String.format("%.2f g", telemetry.surfaceGravityG))
                    TelemetryRow("Escape Velocity", String.format("%.2f km/s", telemetry.escapeVelocityKmS))
                    TelemetryRow("Temperature", formatTemperatureK(b.temperatureK))
                    TelemetryRow("Density", String.format("%.2f g/cm³", telemetry.densityGCm3))
                    if (b.type.isLuminous && b.spectralClass != SpectralClass.NONE) {
                        TelemetryRow("Spectral / Luminosity", "${b.spectralClass.label} • ${formatCompactDouble(b.luminositySolar)} L☉")
                    }
                    if (telemetry.orbitalPeriodDays != null && telemetry.semiMajorAxisAu != null) {
                        TelemetryRow("Orbit Period", String.format("%.1f days", telemetry.orbitalPeriodDays))
                        TelemetryRow("Semi-Major / Ecc", String.format("%.3f AU • e=%.3f", telemetry.semiMajorAxisAu, telemetry.eccentricity ?: 0.0))
                    }
                    if (b.type == BodyType.BLACK_HOLE) {
                        TelemetryRow("Schwarzschild R", formatRadiusKm(telemetry.schwarzschildKm))
                        if (b.absorbedMassSolar > 0.0) {
                            TelemetryRow("Absorbed Mass", formatMass(b.absorbedMassSolar))
                        }
                    }
                }
            }

            if (b.archetype == BodyArchetype.EARTH || b.type == BodyType.EARTH_LIKE) {
                EarthMapPreviewCard(
                    earthBody = b,
                    onClose = null,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Live Property Editor", style = MaterialTheme.typography.titleSmall, color = CyanTelemetry)
                Text("Release re-centers ∞", style = MaterialTheme.typography.labelSmall, color = TextDim)
            }

            // Radius Slider (Infinite Self-Recentering)
            if (b.type != BodyType.BLACK_HOLE) {
                InfiniteValueSlider(
                    bodyId = b.id,
                    label = "Radius: ${formatRadiusKm(b.radiusKm)}",
                    currentValue = b.radiusKm,
                    minFloor = 1.0,
                    testTagPrefix = "slider_radius",
                    onValueChange = { newR ->
                        onEditBody { it.copy(radiusKm = newR) }
                    }
                )
            }

            // Mass Slider (Infinite Self-Recentering — push 500 -> lift -> 500 in center -> push higher ∞!)
            InfiniteValueSlider(
                bodyId = b.id,
                label = "Mass: ${formatMass(b.massSolar)}",
                currentValue = b.massSolar,
                minFloor = 1e-16,
                testTagPrefix = "slider_mass",
                onValueChange = { newM ->
                    onEditBody { it.copy(massSolar = newM) }
                }
            )

            // Density Slider (Infinite Self-Recentering)
            if (b.type != BodyType.BLACK_HOLE) {
                InfiniteValueSlider(
                    bodyId = b.id,
                    label = "Density: ${formatCompactDouble(telemetry.densityGCm3)} g/cm³",
                    currentValue = telemetry.densityGCm3,
                    minFloor = 0.01,
                    testTagPrefix = "slider_density",
                    onValueChange = { newD ->
                        onEditDensity(newD)
                    }
                )
            }

            // Temperature Slider (Infinite Self-Recentering)
            if (b.type != BodyType.BLACK_HOLE) {
                InfiniteValueSlider(
                    bodyId = b.id,
                    label = "Temperature: ${formatTemperatureK(b.temperatureK)}",
                    currentValue = b.temperatureK,
                    minFloor = 1.0,
                    seedIfZero = 100.0,
                    testTagPrefix = "slider_temp",
                    onValueChange = { newT ->
                        onEditBody {
                            it.copy(
                                temperatureK = newT,
                                albedoColor = if (it.type.isLuminous) kelvinToRgb(newT) else it.albedoColor,
                                crustHeat = if (!it.type.isLuminous && newT > 1200.0) ((newT - 1200.0) / 3000.0).toFloat().coerceIn(0f, 1f) else it.crustHeat
                            )
                        }
                    }
                )
            }

            // Luminosity (Infinite Self-Recentering for stars)
            if (b.type.isLuminous) {
                InfiniteValueSlider(
                    bodyId = b.id,
                    label = "Luminosity: ${formatCompactDouble(b.luminositySolar)} L☉",
                    currentValue = b.luminositySolar,
                    minFloor = 1e-4,
                    seedIfZero = 0.5,
                    testTagPrefix = "slider_luminosity",
                    onValueChange = { newLum ->
                        onEditBody { it.copy(luminositySolar = newLum) }
                    }
                )
            }

            // Atmosphere (Infinite Self-Recentering)
            if (!b.type.isLuminous && b.type != BodyType.BLACK_HOLE) {
                InfiniteValueSlider(
                    bodyId = b.id,
                    label = "Atmosphere: ${(b.atmosphereDensity * 100).toInt()}%",
                    currentValue = b.atmosphereDensity.toDouble(),
                    minFloor = 0.0,
                    seedIfZero = 0.08,
                    snapZeroBelow = 0.005,
                    testTagPrefix = "slider_atmo",
                    onValueChange = { newA ->
                        onEditBody { it.copy(atmosphereDensity = newA.toFloat()) }
                    }
                )
            }

            // Axial Tilt (0..180 deg)
            val tiltFrac = (b.axialTiltDeg.toFloat() / 180f).coerceIn(0f, 1f)
            Column {
                Text("Axial Tilt: ${b.axialTiltDeg.toInt()}°", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                Slider(
                    value = tiltFrac,
                    onValueChange = { f -> onEditBody { it.copy(axialTiltDeg = f * 180.0) } },
                    modifier = Modifier.height(26.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = AmberStar,
                        activeTrackColor = AmberStar,
                        inactiveTrackColor = HairlineBorder
                    )
                )
            }

            // Spin Rate (Infinite Self-Recentering rotations/day)
            val rotPerDay = (24.0 / kotlin.math.abs(b.rotationPeriodHours).coerceAtLeast(1e-4))
            InfiniteValueSlider(
                bodyId = b.id,
                label = "Spin Rate: ${formatCompactDouble(rotPerDay)} rot/day",
                currentValue = rotPerDay,
                minFloor = 0.005,
                seedIfZero = 1.0,
                testTagPrefix = "slider_spin",
                onValueChange = { newRotDay ->
                    val sign = if (b.rotationPeriodHours < 0.0) -1.0 else 1.0
                    val newHours = (24.0 / newRotDay.coerceAtLeast(1e-4)) * sign
                    onEditBody { it.copy(rotationPeriodHours = newHours) }
                }
            )

            // Orbit Velocity Nudge
            Text("Orbit Velocity Control", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onNudgeOrbit(0.85) }, modifier = Modifier.weight(1f)) {
                    Text("-15% Speed", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedButton(onClick = { onNudgeOrbit(1.15) }, modifier = Modifier.weight(1f)) {
                    Text("+15% Speed", style = MaterialTheme.typography.labelSmall)
                }
            }

            // Albedo / Tint Color Swatches
            if (b.type != BodyType.BLACK_HOLE) {
                Text("Surface Tint", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Triple(0.22f, 0.52f, 0.88f),
                        Triple(0.78f, 0.36f, 0.22f),
                        Triple(0.86f, 0.72f, 0.46f),
                        Triple(0.45f, 0.85f, 0.65f),
                        Triple(0.78f, 0.48f, 0.92f),
                        Triple(0.85f, 0.85f, 0.88f)
                    ).forEach { col ->
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color(col.first, col.second, col.third))
                                .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                                .clickable { onEditBody { it.copy(albedoColor = col) } }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EarthMapPreviewCard(
    earthBody: CelestialBody?,
    onClose: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    GlassHudSurface(
        modifier = modifier.testTag("earth_map_preview_card"),
        borderColor = EmeraldOrbit.copy(alpha = 0.65f)
    ) {
        if (earthBody == null) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Earth Surface Map", style = MaterialTheme.typography.titleSmall, color = EmeraldOrbit)
                    if (onClose != null) {
                        IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_panel), tint = TextSecondary)
                        }
                    }
                }
                Text(
                    "No Earth-like world active in system. Load Solar System or spawn an Oceanic Terra planet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
            return@GlassHudSurface
        }

        val tempBucket = (earthBody.temperatureK / 2.0).toInt()
        val atmoBucket = (earthBody.atmosphereDensity * 100f).toInt()
        val heatBucket = (earthBody.crustHeat * 25f).toInt()
        val tidalBucket = (earthBody.tidalStress * 25f).toInt()
        val craterCount = earthBody.craters.size

        val (bmp, metrics) = remember(earthBody.id, tempBucket, atmoBucket, heatBucket, tidalBucket, craterCount) {
            EarthGeography.renderLiveEarthMap(earthBody, width = 256, height = 128)
        }
        val imageBitmap = remember(bmp) { bmp.asImageBitmap() }

        val climateAccent = when {
            metrics.moltenPct > 5 -> DangerNova
            metrics.habitabilityPct >= 60 -> EmeraldOrbit
            metrics.iceCoverPct > 45 -> CyanTelemetry
            else -> AmberWarm
        }

        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${earthBody.name} Surface Map", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
                    Text(metrics.climateLabel, style = MaterialTheme.typography.labelSmall, color = climateAccent)
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = climateAccent.copy(alpha = 0.18f),
                    border = BorderStroke(1.dp, climateAccent.copy(alpha = 0.65f))
                ) {
                    Text(
                        text = "${metrics.habitabilityPct}% Hab",
                        style = MaterialTheme.typography.labelSmall,
                        color = climateAccent,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
                if (onClose != null) {
                    IconButton(onClick = onClose, modifier = Modifier.size(30.dp)) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_panel), tint = TextSecondary)
                    }
                }
            }

            Image(
                bitmap = imageBitmap,
                contentDescription = "${earthBody.name} equirectangular terrain preview map",
                contentScale = ContentScale.FillWidth,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(2.0f)
                    .clip(RoundedCornerShape(8.dp))
                    .border(1.dp, HairlineBorder, RoundedCornerShape(8.dp))
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Ocean ${metrics.liquidOceanPct}%", style = MaterialTheme.typography.labelSmall, color = CyanTelemetry)
                Text("Ice ${metrics.iceCoverPct}%", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                Text("Magma ${metrics.moltenPct}%", style = MaterialTheme.typography.labelSmall, color = if (metrics.moltenPct > 0) DangerNova else TextDim)
            }
        }
    }
}

@Composable
private fun TelemetryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
    }
}

/**
 * Self-recentering infinite slider: rests at center (0.5), dragging right scales up to 12x,
 * dragging left scales down to 1/12x, and releasing snaps thumb back to center with the new value
 * anchored so the user can keep increasing/decreasing indefinitely.
 */
@Composable
private fun InfiniteValueSlider(
    bodyId: Long,
    label: String,
    currentValue: Double,
    minFloor: Double = 1e-16,
    seedIfZero: Double = 1.0,
    snapZeroBelow: Double = -1.0,
    stepMultiplierPerHalfDrag: Double = 12.0,
    testTagPrefix: String,
    onValueChange: (Double) -> Unit
) {
    var sliderPos by remember(bodyId) { mutableFloatStateOf(0.5f) }
    var isDragging by remember(bodyId) { mutableStateOf(false) }
    var anchorValue by remember(bodyId) { mutableDoubleStateOf(currentValue) }

    LaunchedEffect(currentValue, isDragging) {
        if (!isDragging) {
            anchorValue = currentValue
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = TextPrimary,
                modifier = Modifier.weight(1f)
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = PanelElevated,
                    border = BorderStroke(1.dp, HairlineBorder),
                    modifier = Modifier
                        .clickable {
                            val base = if (currentValue <= minFloor) seedIfZero else currentValue
                            val next = (base * 0.5).let {
                                if (snapZeroBelow > 0.0 && it < snapZeroBelow) 0.0 else it.coerceAtLeast(minFloor)
                            }
                            anchorValue = next
                            onValueChange(next)
                        }
                        .testTag("${testTagPrefix}_half")
                ) {
                    Text("÷2", style = MaterialTheme.typography.labelSmall, color = TextSecondary, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = PanelElevated,
                    border = BorderStroke(1.dp, AmberStar.copy(alpha = 0.55f)),
                    modifier = Modifier
                        .clickable {
                            val base = if (currentValue <= minFloor) seedIfZero else currentValue
                            val next = (base * 2.0).coerceAtLeast(minFloor)
                            anchorValue = next
                            onValueChange(next)
                        }
                        .testTag("${testTagPrefix}_double")
                ) {
                    Text("×2", style = MaterialTheme.typography.labelSmall, color = AmberWarm, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                }
            }
        }
        Slider(
            value = sliderPos,
            onValueChange = { pos ->
                isDragging = true
                sliderPos = pos
                val base = if (anchorValue <= minFloor) seedIfZero else anchorValue
                val deltaNorm = (pos - 0.5f) * 2.0
                val raw = base * kotlin.math.exp(ln(stepMultiplierPerHalfDrag) * deltaNorm)
                val next = if (snapZeroBelow > 0.0 && raw < snapZeroBelow) 0.0 else raw.coerceAtLeast(minFloor)
                onValueChange(next)
            },
            onValueChangeFinished = {
                isDragging = false
                sliderPos = 0.5f
                anchorValue = currentValue
            },
            modifier = Modifier.height(26.dp).testTag(testTagPrefix),
            colors = SliderDefaults.colors(
                thumbColor = AmberStar,
                activeTrackColor = AmberStar,
                inactiveTrackColor = HairlineBorder
            )
        )
    }
}

private fun formatRadiusKm(km: Double): String {
    return when {
        km >= 1_000_000_000.0 -> String.format("%.2fB km", km / 1_000_000_000.0)
        km >= 1_000_000.0 -> String.format("%.2fM km", km / 1_000_000.0)
        km >= 10_000.0 -> String.format("%.0fk km", km / 1_000.0)
        else -> String.format("%.0f km", km)
    }
}

private fun formatMass(mSolar: Double): String {
    return when {
        mSolar >= 1_000_000.0 -> String.format("%.2e M☉", mSolar)
        mSolar >= 1000.0 -> String.format("%.0f M☉", mSolar)
        mSolar >= 0.05 -> String.format("%.2f M☉", mSolar)
        else -> String.format("%.2f M⊕", mSolar / AstroConstants.EARTH_MASS_SOLAR)
    }
}

private fun formatTemperatureK(tempK: Double): String {
    return when {
        tempK >= 1_000_000.0 -> String.format("%.2e K", tempK)
        tempK >= 100_000.0 -> String.format("%.0f K", tempK)
        else -> String.format("%.0f K (%.0f °C)", tempK, tempK - 273.15)
    }
}

private fun formatCompactDouble(v: Double): String {
    return when {
        v >= 1_000_000.0 -> String.format("%.2e", v)
        v >= 1000.0 -> String.format("%.0f", v)
        v >= 1.0 -> String.format("%.2f", v)
        else -> String.format("%.3f", v)
    }
}
