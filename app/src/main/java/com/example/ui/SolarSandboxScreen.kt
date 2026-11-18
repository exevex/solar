package com.example.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.render.SolarGLRenderer
import com.example.render.SolarGLSurfaceView
import com.example.simulation.BodyArchetype
import com.example.simulation.BodyType
import com.example.ui.components.*
import com.example.viewmodel.SandboxToolTab
import com.example.viewmodel.SandboxViewModel

@Composable
fun SolarSandboxScreen(viewModel: SandboxViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val renderer = remember {
        SolarGLRenderer(
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
                    onAimSpawnWorld = { worldPos -> viewModel.aimSpawnAtWorldPos(worldPos) },
                    onTapSpawnWorld = { worldPos -> viewModel.spawnAtWorldPos(worldPos) },
                    onSlingshotAim = { pos, vel -> viewModel.onSlingshotAim(pos, vel) },
                    onSlingshotRelease = { pos, vel -> viewModel.onSlingshotRelease(pos, vel) },
                    onFlingMoveBody = { id, pos, vel -> viewModel.onFlingMoveBody(id, pos, vel) }
                )
            },
            update = { view ->
                view.interactionMode = viewModel.getDesiredInteractionMode()
                view.requireConfirmSpawn = uiState.spawnAim.requireConfirmSpawn
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
            // Event-Driven Cinematic Collision HUD
            if (uiState.cinematicMode) {
                CinematicCollisionOverlay(
                    cinematicState = uiState.cinematicState,
                    onTogglePause = { viewModel.toggleCinematicPaused() },
                    onReplay = { viewModel.replayCinematicCollision() },
                    onSetSpeed = { viewModel.setCinematicSpeed(it) },
                    onSetPerspective = { viewModel.setCinematicPerspective(it) },
                    onExit = { viewModel.exitCinematicMode() }
                )
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
                        onToggleHabitableZone = { viewModel.toggleHabitableZone() },
                        onToggleEarthMap = { viewModel.toggleEarthMapOverlay() },
                        onResetCamera = { viewModel.resetCameraView() },
                        onToggleCinematic = { viewModel.toggleCinematicMode() },
                        modifier = Modifier.align(Alignment.TopCenter)
                    )

                    if (uiState.config.showHabitableZone) {
                        HabitableZoneLegendPill(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 56.dp)
                        )
                    }

                    // Live Cosmic Activity & Selected Body Status Ticker
                    CosmicStatusTicker(
                        uiState = uiState,
                        modifier = Modifier.align(Alignment.TopStart)
                    )

                    // Left Tool Rail
                    ToolRail(
                        activeTab = uiState.activeToolTab,
                        onSelectTab = { viewModel.setToolTab(it) },
                        modifier = Modifier.align(Alignment.CenterStart)
                    )

                    // Live Earth Surface Map Preview HUD window
                    if (uiState.showEarthMapOverlay) {
                        val earthBody = uiState.allBodiesList.firstOrNull {
                            it.name.equals("Earth", ignoreCase = true)
                        } ?: uiState.allBodiesList.firstOrNull {
                            it.archetype == BodyArchetype.EARTH || it.type == BodyType.EARTH_LIKE
                        }
                        EarthMapPreviewCard(
                            earthBody = earthBody,
                            onClose = { viewModel.toggleEarthMapOverlay() },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(
                                    start = 78.dp,
                                    bottom = if (uiState.activeToolTab == SandboxToolTab.SPAWN) 152.dp else 74.dp
                                )
                                .width(238.dp)
                        )
                    }

                    // Right Context Tool Drawer
                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 60.dp, bottom = 76.dp, end = 12.dp)
                    ) {
                        when (uiState.activeToolTab) {
                            SandboxToolTab.SLINGSHOT -> SlingshotHintCard(
                                template = uiState.selectedSpawnTemplate,
                                onSwitchToSpawnCatalog = { viewModel.setToolTab(SandboxToolTab.SPAWN) },
                                onClose = { viewModel.setToolTab(SandboxToolTab.NONE) }
                            )
                            SandboxToolTab.COLLIDE -> CollisionLabPanel(
                                uiState = uiState,
                                onSelectBodyA = { viewModel.selectCollisionBodyA(it) },
                                onSelectBodyB = { viewModel.selectCollisionBodyB(it) },
                                onAngleChange = { viewModel.setCollisionImpactAngle(it) },
                                onSpeedChange = { viewModel.setCollisionSpeedRatio(it) },
                                onToggleAutoCinematic = { viewModel.toggleCollisionAutoCinematic() },
                                onLaunchCustom = { viewModel.launchCustomCollision() },
                                onLaunchPreset = { viewModel.launchPresetCollision(it) },
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
                                onEditBody = { viewModel.editSelectedBody(transform = it) },
                                onEditDensity = { viewModel.editBodyDensity(it) },
                                onNudgeOrbit = { viewModel.nudgeSelectedOrbitSpeed(it) },
                                onClose = { viewModel.setToolTab(SandboxToolTab.NONE) }
                            )
                            SandboxToolTab.SPAWN, SandboxToolTab.DRAG, SandboxToolTab.NONE -> Unit
                        }
                    }

                    // Bottom Dock: Wide Horizontal Spawn Tray OR Time Control Dock
                    if (uiState.activeToolTab == SandboxToolTab.SPAWN) {
                        SpawnDrawer(
                            uiState = uiState,
                            onSelectCategory = { viewModel.selectSpawnCategory(it) },
                            onSelectTemplate = { viewModel.selectSpawnTemplate(it) },
                            onToggleSpawnStationary = { viewModel.toggleSpawnStationary() },
                            onToggleConfirmSpawn = { viewModel.toggleConfirmBeforeSpawn() },
                            onNudgeRadius = { viewModel.nudgeSpawnRadius(it) },
                            onCycleOrbitParent = {
                                val majors = uiState.allBodiesList.filter { !it.isBackgroundCluster }
                                val cur = uiState.spawnAim.orbitParentId
                                val ids = listOf<Long?>(null) + majors.take(4).map { it.id }
                                val nextIdx = (ids.indexOf(cur) + 1) % ids.size
                                viewModel.setSpawnOrbitParent(ids[nextIdx])
                            },
                            onPlaceHere = { viewModel.confirmSpawnAtAimedPos() },
                            onTogglePause = { viewModel.togglePause() },
                            onClose = { viewModel.setToolTab(SandboxToolTab.NONE) },
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    } else {
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
                onToggleHabitableZone = { viewModel.toggleHabitableZone() },
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
