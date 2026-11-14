package com.example.ui.components

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.graphics.vector.ImageVector
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
    onSpawnQuickOrbit: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val filtered = remember(uiState.selectedSpawnCategory) {
        SpawnCatalog.all.filter { it.category == uiState.selectedSpawnCategory }
    }

    GlassHudSurface(
        modifier = modifier.width(295.dp).fillMaxHeight(),
        borderColor = EmeraldOrbit.copy(alpha = 0.55f)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Spawn Body", style = MaterialTheme.typography.titleMedium, color = EmeraldOrbit)
                IconButton(onClick = onClose, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cd_close_panel), tint = TextSecondary)
                }
            }
            Text(
                text = "Tap anywhere in space to place, or switch to Sling mode to aim velocity.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary
            )
            Spacer(Modifier.height(8.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(SpawnCategory.entries) { cat ->
                    val active = cat == uiState.selectedSpawnCategory
                    FilterChip(
                        selected = active,
                        onClick = { onSelectCategory(cat) },
                        label = { Text(cat.label, style = MaterialTheme.typography.labelSmall) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = EmeraldOrbit.copy(alpha = 0.22f),
                            selectedLabelColor = EmeraldOrbit
                        )
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filtered) { tpl ->
                    val sel = tpl.id == uiState.selectedSpawnTemplate.id
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (sel) EmeraldOrbit.copy(alpha = 0.16f) else PanelElevated,
                        border = BorderStroke(1.dp, if (sel) EmeraldOrbit else HairlineBorder),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectTemplate(tpl) }
                            .testTag("spawn_tpl_${tpl.id}")
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Box(
                                    modifier = Modifier
                                        .size(16.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (tpl.type == BodyType.BLACK_HOLE) Color.Black
                                            else Color(tpl.albedoColor.first, tpl.albedoColor.second, tpl.albedoColor.third)
                                        )
                                        .border(1.dp, if (tpl.type == BodyType.BLACK_HOLE) PurpleAccretion else Color.White.copy(alpha = 0.4f), CircleShape)
                                )
                                Column {
                                    Text(tpl.name, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                    Text(
                                        text = if (tpl.type == BodyType.BLACK_HOLE) "${tpl.massSolar} M☉"
                                        else if (tpl.type.isLuminous) "${tpl.temperatureK.toInt()} K • ${tpl.massSolar} M☉"
                                        else "R = ${tpl.radiusKm.toInt()} km",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                            }
                            if (sel) Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldOrbit, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onSpawnQuickOrbit,
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("spawn_orbit_btn"),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldOrbit, contentColor = SpaceVoid)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Spawn in Circular Orbit")
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

            // Live Telemetry Grid
            Surface(shape = RoundedCornerShape(10.dp), color = PanelElevated, border = BorderStroke(1.dp, HairlineBorder)) {
                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    TelemetryRow("Speed", String.format("%.2f km/s", telemetry.speedKmS))
                    TelemetryRow("Surface Gravity", String.format("%.2f g", telemetry.surfaceGravityG))
                    TelemetryRow("Escape Velocity", String.format("%.2f km/s", telemetry.escapeVelocityKmS))
                    TelemetryRow("Temperature", String.format("%.0f K (%.0f °C)", b.temperatureK, b.temperatureK - 273.15))
                    TelemetryRow("Density", String.format("%.2f g/cm³", telemetry.densityGCm3))
                    if (telemetry.orbitalPeriodDays != null && telemetry.semiMajorAxisAu != null) {
                        TelemetryRow("Orbit Period", String.format("%.1f days", telemetry.orbitalPeriodDays))
                        TelemetryRow("Semi-Major / Ecc", String.format("%.3f AU • e=%.3f", telemetry.semiMajorAxisAu, telemetry.eccentricity ?: 0.0))
                    }
                    if (b.type == BodyType.BLACK_HOLE) {
                        TelemetryRow("Schwarzschild R", String.format("%.1f km", telemetry.schwarzschildKm))
                    }
                }
            }

            Text("Live Property Editor", style = MaterialTheme.typography.titleSmall, color = CyanTelemetry)

            // Radius Slider (log)
            val minR = ln(200.0); val maxR = ln(15_000_000.0)
            val rFrac = ((ln(b.radiusKm.coerceIn(200.0, 15_000_000.0)) - minR) / (maxR - minR)).toFloat()
            LabeledSlider("Radius: ${formatRadiusKm(b.radiusKm)}", rFrac) { f ->
                val newR = kotlin.math.exp(minR + f * (maxR - minR))
                onEditBody { it.copy(radiusKm = newR) }
            }

            // Mass Slider (log)
            val minM = ln(1e-9); val maxM = ln(500.0)
            val mFrac = ((ln(b.massSolar.coerceIn(1e-9, 500.0)) - minM) / (maxM - minM)).toFloat()
            LabeledSlider("Mass: ${formatMass(b.massSolar)}", mFrac) { f ->
                val newM = kotlin.math.exp(minM + f * (maxM - minM))
                onEditBody { it.copy(massSolar = newM) }
            }

            // Density Slider
            val dFrac = (telemetry.densityGCm3.coerceIn(0.2, 25.0) / 25.0).toFloat()
            LabeledSlider("Density: ${String.format("%.2f g/cm³", telemetry.densityGCm3)}", dFrac) { f ->
                onEditDensity(0.2 + f * 24.8)
            }

            // Temperature Slider
            val tFrac = (b.temperatureK.coerceIn(20.0, 35000.0) / 35000.0).toFloat()
            LabeledSlider("Temperature: ${b.temperatureK.toInt()} K", tFrac) { f ->
                val newT = 20.0 + f * 34980.0
                onEditBody {
                    it.copy(
                        temperatureK = newT,
                        albedoColor = if (it.type.isLuminous) kelvinToRgb(newT) else it.albedoColor,
                        crustHeat = if (!it.type.isLuminous && newT > 1200.0) ((newT - 1200.0) / 3000.0).toFloat().coerceIn(0f, 1f) else it.crustHeat
                    )
                }
            }

            // Luminosity (for stars)
            if (b.type.isLuminous) {
                val lFrac = (ln(b.luminositySolar.coerceIn(0.01, 5000.0)) / ln(5000.0)).toFloat().coerceIn(0f, 1f)
                LabeledSlider("Luminosity: ${String.format("%.2f L☉", b.luminositySolar)}", lFrac) { f ->
                    val newLum = 0.01 * (500000.0).pow(f.toDouble())
                    onEditBody { it.copy(luminositySolar = newLum) }
                }
            }

            // Atmosphere, Axial Tilt, Spin
            LabeledSlider("Atmosphere: ${(b.atmosphereDensity * 100).toInt()}%", b.atmosphereDensity) { f ->
                onEditBody { it.copy(atmosphereDensity = f) }
            }
            LabeledSlider("Axial Tilt: ${b.axialTiltDeg.toInt()}°", (b.axialTiltDeg.toFloat() / 180f).coerceIn(0f, 1f)) { f ->
                onEditBody { it.copy(axialTiltDeg = (f * 180.0)) }
            }
            val spinRateFrac = (1f - (kotlin.math.abs(b.rotationPeriodHours).toFloat() / 240f).coerceIn(0f, 1f))
            LabeledSlider("Spin Speed", spinRateFrac) { f ->
                val hours = (240.0 * (1.0 - f)).coerceAtLeast(1.5)
                onEditBody { it.copy(rotationPeriodHours = hours) }
            }

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
private fun TelemetryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        Text(value, style = MaterialTheme.typography.labelMedium, color = TextPrimary)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, onValueChange: (Float) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextPrimary)
        Slider(
            value = value.coerceIn(0f, 1f),
            onValueChange = onValueChange,
            modifier = Modifier.height(26.dp),
            colors = SliderDefaults.colors(
                thumbColor = AmberStar,
                activeTrackColor = AmberStar,
                inactiveTrackColor = HairlineBorder
            )
        )
    }
}

private fun formatRadiusKm(km: Double): String {
    return if (km >= 100_000) String.format("%.2fM km", km / 1_000_000.0)
    else String.format("%.0f km", km)
}

private fun formatMass(mSolar: Double): String {
    return if (mSolar >= 0.05) String.format("%.2f M☉", mSolar)
    else String.format("%.2f M⊕", mSolar / AstroConstants.EARTH_MASS_SOLAR)
}
