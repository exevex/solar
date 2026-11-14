package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.render.QualityTier
import com.example.simulation.IntegratorMode
import com.example.simulation.SavedSlotInfo
import com.example.simulation.SystemPreset
import com.example.ui.theme.*
import com.example.viewmodel.SandboxUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun PresetsDialog(
    uiState: SandboxUiState,
    onLoadPreset: (SystemPreset) -> Unit,
    onJumpToBody: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, HairlineCyan),
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
        ) {
            Row(modifier = Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(modifier = Modifier.weight(1.2f)) {
                    Text("Cosmic Presets", style = MaterialTheme.typography.titleMedium, color = CyanTelemetry)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(SystemPreset.entries) { preset ->
                            val active = preset == uiState.currentPreset
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = if (active) CyanTelemetry.copy(alpha = 0.16f) else PanelObsidian,
                                border = BorderStroke(1.dp, if (active) CyanTelemetry else HairlineBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onLoadPreset(preset) }
                                    .testTag("preset_${preset.id}")
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(preset.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                    Text(preset.subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                                }
                            }
                        }
                    }
                }

                VerticalDivider(color = HairlineBorder)

                Column(modifier = Modifier.weight(0.8f)) {
                    Text("System Objects", style = MaterialTheme.typography.titleMedium, color = AmberWarm)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(uiState.allBodiesList, key = { it.id }) { body ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = PanelObsidian,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onJumpToBody(body.id)
                                        onDismiss()
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(body.name, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                                    Text(body.type.label, style = MaterialTheme.typography.labelSmall, color = TextDim)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QualitySettingsDialog(
    uiState: SandboxUiState,
    onSelectTier: (QualityTier) -> Unit,
    onAutoDetect: () -> Unit,
    onToggleBatterySaver: () -> Unit,
    onBloomChange: (Float) -> Unit,
    onIntegratorChange: (IntegratorMode) -> Unit,
    onToggleTrueScale: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, HairlineCyan),
            modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Graphics & Physics Settings", style = MaterialTheme.typography.titleMedium, color = CyanTelemetry)
                    OutlinedButton(onClick = onAutoDetect, modifier = Modifier.testTag("auto_detect_quality_btn")) {
                        Text("Auto-Detect (${uiState.graphicsSettings.autoDetectedTier.label})", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    QualityTier.entries.forEach { tier ->
                        val sel = tier == uiState.graphicsSettings.tier
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = if (sel) CyanTelemetry.copy(alpha = 0.2f) else PanelObsidian,
                            border = BorderStroke(1.dp, if (sel) CyanTelemetry else HairlineBorder),
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable { onSelectTier(tier) }
                                .testTag("quality_${tier.name}")
                        ) {
                            Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(6.dp)) {
                                Text(tier.label, style = MaterialTheme.typography.titleSmall, color = if (sel) CyanTelemetry else TextPrimary)
                            }
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("Battery Saver (30 FPS Cap)", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Reduces render scale & physics rate", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(checked = uiState.graphicsSettings.batterySaver, onCheckedChange = { onToggleBatterySaver() })
                }

                Column {
                    Text("HDR Bloom Intensity: ${(uiState.graphicsSettings.bloomIntensity * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                    Slider(
                        value = uiState.graphicsSettings.bloomIntensity / 2f,
                        onValueChange = { onBloomChange(it * 2f) }
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        Text("True Astronomical Scale", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
                        Text("Off = Sandbox Clear View • On = Exact 1:1 km/AU radii", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                    Switch(checked = uiState.config.trueScaleMode, onCheckedChange = { onToggleTrueScale() })
                }

                Text("N-Body Integrator", style = MaterialTheme.typography.labelMedium, color = TextSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IntegratorMode.entries.forEach { mode ->
                        val active = mode == uiState.config.integrator
                        FilterChip(
                            selected = active,
                            onClick = { onIntegratorChange(mode) },
                            label = { Text(mode.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Done", color = CyanTelemetry) }
                }
            }
        }
    }
}

@Composable
fun SaveLoadDialog(
    savedSlots: List<SavedSlotInfo>,
    defaultTitle: String,
    onSave: (String) -> Unit,
    onLoad: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var slotName by remember { mutableStateOf(defaultTitle) }
    val dateFmt = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = PanelElevated,
            border = BorderStroke(1.dp, HairlineCyan),
            modifier = Modifier.fillMaxWidth().heightIn(max = 350.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Save / Load Simulation States", style = MaterialTheme.typography.titleMedium, color = CyanTelemetry)

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = slotName,
                        onValueChange = { slotName = it },
                        label = { Text("Save Name") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = { onSave(slotName) },
                        modifier = Modifier.heightIn(min = 48.dp).testTag("confirm_save_btn")
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Save")
                    }
                }

                if (savedSlots.isEmpty()) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Text("No saved states yet. Save your current system above.", style = MaterialTheme.typography.bodySmall, color = TextSecondary)
                    }
                } else {
                    LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(savedSlots, key = { it.fileName }) { slot ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = PanelObsidian,
                                border = BorderStroke(1.dp, HairlineBorder),
                                modifier = Modifier.fillMaxWidth().clickable { onLoad(slot.fileName) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(slot.title, style = MaterialTheme.typography.titleSmall, color = TextPrimary)
                                        Text(
                                            text = "${slot.bodyCount} bodies • ${formatSimTime(slot.elapsedYears)} • ${dateFmt.format(Date(slot.savedAtEpochMs))}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextSecondary
                                        )
                                    }
                                    IconButton(onClick = { onLoad(slot.fileName) }) {
                                        Icon(Icons.Default.FolderOpen, contentDescription = "Load", tint = CyanTelemetry)
                                    }
                                    IconButton(onClick = { onDelete(slot.fileName) }) {
                                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = DangerNova)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
