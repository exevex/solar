package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.render.SolarGLRenderer
import com.example.render.SolarGLSurfaceView
import com.example.simulation.Vec3d
import com.example.ui.components.*
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.PanelObsidian
import com.example.viewmodel.SandboxToolTab
import com.example.viewmodel.SandboxViewModel

@Composable
fun SolarSandboxScreen(viewModel: SandboxViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current.applicationContext

    val renderer = remember(context) {
        SolarGLRenderer(
            context = context,
            frameStateRef = viewModel.renderFrameRef,
            onLabelsUpdated = { labels -> viewModel.updateScreenLabels(labels) },
            onFpsUpdated = { fps -> viewModel.updateFps(fps) }
        ).also { viewModel.glRenderer = it }
    }

    LaunchedEffect(uiState.graphicsSettings) {
        renderer.graphicsSettings = uiState.graphicsSettings
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 1. Full-Bleed Hardware-Accelerated OpenGL ES 3.0 Space Viewport
        AndroidView(
            factory = { ctx ->
                SolarGLSurfaceView(
                    context = ctx,
                    renderer = renderer,
                    onTapBody = { id -> viewModel.selectBody(id) },
                    onTapSpawnWorld = { worldPos -> viewModel.spawnAtWorldPos(worldPos) },
                    onSlingshotAim = { pos, vel -> viewModel.onSlingshotAim(pos, vel) },
                    onSlingshotRelease = { pos, vel -> viewModel.onSlingshotRelease(pos, vel) },
                    onFlingMoveBody = { id, pos, vel -> viewModel.onFlingMoveBody(id, pos, vel) }
                )
            },
            update = { view ->
                view.interactionMode = viewModel.getDesiredInteractionMode()
            },
            modifier = Modifier.fillMaxSize()
        )

        // 2. Projected Screen-Space Body Labels & Reticle
        if (!uiState.cinematicMode) {
            LabelsOverlay(
                labels = uiState.screenLabels,
                showLabels = uiState.config.showLabels,
                onSelectBody = { viewModel.selectBody(it) }
            )
        }

        // 3. Safe-Area Landscape HUD Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            // Cinematic Exit Pill
            if (uiState.cinematicMode) {
                Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                    color = PanelObsidian,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                ) {
                    androidx.compose.material3.TextButton(onClick = { viewModel.toggleCinematicMode() }) {
                        Text("Exit Cinematic Mode", color = CyanTelemetry)
                    }
                }
            }

            AnimatedVisibility(
                visible = !uiState.cinematicMode,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    // Top Telemetry & Settings Bar
                    TopTelemetryBar(
                        uiState = uiState,
                        onOpenPresets = { viewModel.setPresetsDialogVisible(true) },
                        onOpenSaveLoad = { viewModel.setSaveLoadDialogVisible(true) },
                        onOpenQuality = { viewModel.setQualityDialogVisible(true) },
                        onToggleOrbits = { viewModel.toggleOrbits() },
                        onToggleLabels = { viewModel.toggleLabels() },
                        onResetCamera = { viewModel.resetCameraView() },
                        onToggleCinematic = { viewModel.toggleCinematicMode() },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    // Left Tool Rail
                    ToolRail(
                        activeTab = uiState.activeToolTab,
                        onSelectTab = { viewModel.setToolTab(it) },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )

                    // Right Context Tool Drawer
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 60.dp, bottom = 76.dp, end = 12.dp)
                    ) {
                        when (uiState.activeToolTab) {
                            SandboxToolTab.SPAWN -> SpawnDrawer(
                                uiState = uiState,
                                onSelectCategory = { viewModel.selectSpawnCategory(it) },
                                onSelectTemplate = { viewModel.selectSpawnTemplate(it) },
                                onSpawnQuickOrbit = {
                                    val r = 1.6 + (uiState.bodyCount % 5) * 0.45
                                    viewModel.spawnAtWorldPos(Vec3d(r, 0.0, 0.0))
                                },
                                onClose = { viewModel.setToolTab(SandboxToolTab.NONE) }
                            )
                            SandboxToolTab.SLINGSHOT -> SlingshotHintCard(
                                template = uiState.selectedSpawnTemplate,
                                onSwitchToSpawnCatalog = { viewModel.setToolTab(SandboxToolTab.SPAWN) },
                                onClose = { viewModel.setToolTab(SandboxToolTab.NONE) }
                            )
                            SandboxToolTab.WEAPONS -> WeaponsPanel(
                                uiState = uiState,
                                onAsteroidStrike = { viewModel.fireAsteroidStrike() },
                                onNuke = { viewModel.fireNuke() },
                                onSupernova = { viewModel.triggerSupernova() },
                                onGammaBurst = { viewModel.fireGammaRayBurst() },
                                onSplitPlanet = { viewModel.splitSelectedPlanet() },
                                onRogueStar = { viewModel.launchRogueStar() },
                                onToggleHawking = { viewModel.toggleHawkingEvaporation() },
                                onToggleStellarEvolution = { viewModel.toggleStellarEvolution() },
                                onClose = { viewModel.setToolTab(SandboxToolTab.NONE) }
                            )
                            SandboxToolTab.INSPECT -> InspectorPanel(
                                telemetry = uiState.selectedTelemetry,
                                onFocusBody = { viewModel.focusAndZoomToBody(it) },
                                onDeleteBody = { viewModel.deleteSelectedBody() },
                                onEditBody = { viewModel.editSelectedBody(it) },
                                onEditDensity = { viewModel.editBodyDensity(it) },
                                onNudgeOrbit = { viewModel.nudgeSelectedOrbitSpeed(it) },
                                onClose = { viewModel.setToolTab(SandboxToolTab.NONE) }
                            )
                            SandboxToolTab.NONE -> Unit
                        }
                    }

                    // Bottom Time Control Dock
                    TimeControlDock(
                        config = uiState.config,
                        onTogglePause = { viewModel.togglePause() },
                        onToggleRewind = { viewModel.toggleRewind() },
                        onResetPreset = { viewModel.resetCurrentPreset() },
                        onTimeScaleChange = { viewModel.setTimeScale(it) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    )
                }
            }
        }

        // 4. Modals
        if (uiState.showPresetsDialog) {
            PresetsDialog(
                uiState = uiState,
                onLoadPreset = { viewModel.loadPreset(it) },
                onJumpToBody = { viewModel.focusAndZoomToBody(it) },
                onDismiss = { viewModel.setPresetsDialogVisible(false) }
            )
        }
        if (uiState.showQualityDialog) {
            QualitySettingsDialog(
                uiState = uiState,
                onSelectTier = { viewModel.setQualityTier(it) },
                onAutoDetect = { viewModel.autoDetectQuality() },
                onToggleBatterySaver = { viewModel.toggleBatterySaver() },
                onBloomChange = { viewModel.setBloomIntensity(it) },
                onIntegratorChange = { viewModel.setIntegrator(it) },
                onToggleTrueScale = { viewModel.toggleTrueScale() },
                onToggleEventText = { viewModel.toggleEventText() },
                onDismiss = { viewModel.setQualityDialogVisible(false) }
            )
        }
        if (uiState.showSaveLoadDialog) {
            SaveLoadDialog(
                savedSlots = uiState.savedSlots,
                defaultTitle = "${uiState.currentPreset.title} Save",
                onSave = { viewModel.saveCurrentState(it) },
                onLoad = { viewModel.loadSavedSlot(it) },
                onDelete = { viewModel.deleteSavedSlot(it) },
                onDismiss = { viewModel.setSaveLoadDialogVisible(false) }
            )
        }
    }
}
