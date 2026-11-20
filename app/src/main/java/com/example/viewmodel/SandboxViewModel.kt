package com.example.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.render.*
import com.example.simulation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

enum class SandboxToolTab {
    NONE,
    COPILOT,
    DRAG,
    SPAWN,
    SLINGSHOT,
    COLLIDE,
    WEAPONS,
    INSPECT
}

data class SpawnAimState(
    val targetPos: Vec3d? = null,
    val orbitParentId: Long? = null,
    val radiusFromParentAu: Double = 1.6,
    val requireConfirmSpawn: Boolean = true
)

data class CollisionSetupState(
    val bodyAId: Long = 3L,
    val bodyBId: Long = 5L,
    val impactAngleDeg: Float = 26f,
    val speedRatio: Float = 1.35f,
    val autoCinematic: Boolean = true
)

enum class SpawnCategory(val label: String) {
    BLACK_HOLES("Black Holes"),
    PLANETS("Planets & Moons"),
    STARS("Stars & Remnants"),
    SMALL("Comets & Asteroids")
}

data class SpawnTemplate(
    val id: String,
    val name: String,
    val category: SpawnCategory,
    val type: BodyType,
    val spectralClass: SpectralClass = SpectralClass.NONE,
    val massSolar: Double,
    val radiusKm: Double,
    val temperatureK: Double,
    val luminositySolar: Double = 0.0,
    val atmosphereDensity: Float = 0f,
    val atmosphereColor: Triple<Float, Float, Float> = Triple(0.35f, 0.65f, 1.0f),
    val albedoColor: Triple<Float, Float, Float> = Triple(0.7f, 0.7f, 0.7f),
    val hasClouds: Boolean = false,
    val ringSystem: RingSystem? = null
)

data class SandboxUiState(
    val currentPreset: SystemPreset = SystemPreset.SOLAR_SYSTEM,
    val activeToolTab: SandboxToolTab = SandboxToolTab.NONE,
    val selectedSpawnTemplate: SpawnTemplate = SpawnCatalog.all.first(),
    val selectedSpawnCategory: SpawnCategory = SpawnCategory.BLACK_HOLES,
    val selectedBodyId: Long? = 1L,
    val selectedTelemetry: BodyTelemetry? = null,
    val elapsedYears: Double = 0.0,
    val bodyCount: Int = 0,
    val config: SimulationConfig = SimulationConfig(),
    val graphicsSettings: GraphicsSettings = GraphicsSettings(),
    val fps: Float = 60f,
    val simTps: Float = 60f,
    val cinematicMode: Boolean = false,
    val cinematicState: CinematicDirectorState = CinematicDirectorState(),
    val eventBanner: String? = null,
    val activityFeed: List<String> = emptyList(),
    val screenLabels: List<ScreenBodyLabel> = emptyList(),
    val savedSlots: List<SavedSlotInfo> = emptyList(),
    val showPresetsDialog: Boolean = false,
    val showQualityDialog: Boolean = false,
    val showSaveLoadDialog: Boolean = false,
    val spawnStationary: Boolean = false,
    val spawnAim: SpawnAimState = SpawnAimState(),
    val collisionSetup: CollisionSetupState = CollisionSetupState(),
    val showEarthMapOverlay: Boolean = false,
    val allBodiesList: List<CelestialBody> = emptyList()
)

object SpawnCatalog {
    val all: List<SpawnTemplate> = listOf(
        // Planets & Moons
        SpawnTemplate(
            "earth_like", "Oceanic Terra", SpawnCategory.PLANETS, BodyType.EARTH_LIKE,
            massSolar = AstroConstants.EARTH_MASS_SOLAR, radiusKm = 6371.0, temperatureK = 288.0,
            atmosphereDensity = 0.65f, atmosphereColor = Triple(0.28f, 0.65f, 1.0f),
            albedoColor = Triple(0.20f, 0.48f, 0.82f), hasClouds = true
        ),
        SpawnTemplate(
            "rocky_mars", "Rocky Desert World", SpawnCategory.PLANETS, BodyType.TERRESTRIAL_PLANET,
            massSolar = 0.15 * AstroConstants.EARTH_MASS_SOLAR, radiusKm = 3600.0, temperatureK = 220.0,
            atmosphereDensity = 0.18f, atmosphereColor = Triple(0.88f, 0.48f, 0.30f),
            albedoColor = Triple(0.76f, 0.35f, 0.22f)
        ),
        SpawnTemplate(
            "gas_giant", "Gas Giant", SpawnCategory.PLANETS, BodyType.GAS_GIANT,
            massSolar = AstroConstants.JUPITER_MASS_SOLAR, radiusKm = 69911.0, temperatureK = 165.0,
            atmosphereDensity = 0.45f, atmosphereColor = Triple(0.85f, 0.68f, 0.50f),
            albedoColor = Triple(0.84f, 0.66f, 0.48f)
        ),
        SpawnTemplate(
            "ringed_giant", "Ringed Giant", SpawnCategory.PLANETS, BodyType.GAS_GIANT,
            massSolar = 95.0 * AstroConstants.EARTH_MASS_SOLAR, radiusKm = 58232.0, temperatureK = 135.0,
            atmosphereDensity = 0.35f, atmosphereColor = Triple(0.88f, 0.78f, 0.58f),
            albedoColor = Triple(0.88f, 0.78f, 0.56f),
            ringSystem = RingSystem(1.30f, 2.38f, 0.86f, 0.79f, 0.66f, 0.88f, true)
        ),
        SpawnTemplate(
            "ice_giant", "Ice Giant", SpawnCategory.PLANETS, BodyType.ICE_GIANT,
            massSolar = 17.0 * AstroConstants.EARTH_MASS_SOLAR, radiusKm = 24622.0, temperatureK = 72.0,
            atmosphereDensity = 0.55f, atmosphereColor = Triple(0.28f, 0.58f, 0.96f),
            albedoColor = Triple(0.24f, 0.48f, 0.90f)
        ),
        SpawnTemplate(
            "moon", "Cratered Moon", SpawnCategory.PLANETS, BodyType.MOON,
            massSolar = AstroConstants.MOON_MASS_SOLAR, radiusKm = 1737.0, temperatureK = 210.0,
            albedoColor = Triple(0.66f, 0.66f, 0.64f)
        ),

        // Stars & Remnants (All Spectral Classes!)
        SpawnTemplate(
            "star_o", "O-Type Blue Supergiant", SpawnCategory.STARS, BodyType.STAR, SpectralClass.O,
            massSolar = 25.0, radiusKm = 5_000_000.0, temperatureK = 35000.0, luminositySolar = 60000.0,
            albedoColor = kelvinToRgb(35000.0)
        ),
        SpawnTemplate(
            "star_b", "B-Type Blue-White", SpawnCategory.STARS, BodyType.STAR, SpectralClass.B,
            massSolar = 6.5, radiusKm = 2_400_000.0, temperatureK = 18000.0, luminositySolar = 1200.0,
            albedoColor = kelvinToRgb(18000.0)
        ),
        SpawnTemplate(
            "star_a", "A-Type White Star", SpawnCategory.STARS, BodyType.STAR, SpectralClass.A,
            massSolar = 2.0, radiusKm = 1_150_000.0, temperatureK = 8800.0, luminositySolar = 20.0,
            albedoColor = kelvinToRgb(8800.0)
        ),
        SpawnTemplate(
            "star_f", "F-Type Yellow-White", SpawnCategory.STARS, BodyType.STAR, SpectralClass.F,
            massSolar = 1.35, radiusKm = 890_000.0, temperatureK = 6700.0, luminositySolar = 3.2,
            albedoColor = kelvinToRgb(6700.0)
        ),
        SpawnTemplate(
            "star_g", "G-Type Solar Twin", SpawnCategory.STARS, BodyType.STAR, SpectralClass.G,
            massSolar = 1.0, radiusKm = 696_340.0, temperatureK = 5778.0, luminositySolar = 1.0,
            albedoColor = kelvinToRgb(5778.0)
        ),
        SpawnTemplate(
            "star_k", "K-Type Orange Dwarf", SpawnCategory.STARS, BodyType.STAR, SpectralClass.K,
            massSolar = 0.72, radiusKm = 520_000.0, temperatureK = 4400.0, luminositySolar = 0.25,
            albedoColor = kelvinToRgb(4400.0)
        ),
        SpawnTemplate(
            "star_m", "M-Type Red Dwarf", SpawnCategory.STARS, BodyType.STAR, SpectralClass.M,
            massSolar = 0.25, radiusKm = 240_000.0, temperatureK = 3100.0, luminositySolar = 0.015,
            albedoColor = kelvinToRgb(3100.0)
        ),
        SpawnTemplate(
            "red_giant", "Red Giant Star", SpawnCategory.STARS, BodyType.RED_GIANT, SpectralClass.M,
            massSolar = 1.4, radiusKm = 12_000_000.0, temperatureK = 3100.0, luminositySolar = 350.0,
            albedoColor = kelvinToRgb(3100.0)
        ),
        SpawnTemplate(
            "white_dwarf", "White Dwarf", SpawnCategory.STARS, BodyType.WHITE_DWARF,
            massSolar = 0.85, radiusKm = 7000.0, temperatureK = 26000.0, luminositySolar = 0.06,
            albedoColor = kelvinToRgb(26000.0)
        ),
        SpawnTemplate(
            "neutron_star", "Neutron Star (Pulsar)", SpawnCategory.STARS, BodyType.NEUTRON_STAR,
            massSolar = 1.8, radiusKm = 12.0, temperatureK = 180000.0, luminositySolar = 0.5,
            albedoColor = Triple(0.62f, 0.85f, 1.0f)
        ),

        // Small Bodies
        SpawnTemplate(
            "comet", "Icy Comet", SpawnCategory.SMALL, BodyType.COMET,
            massSolar = 1e-13, radiusKm = 180.0, temperatureK = 190.0,
            atmosphereDensity = 0.35f, atmosphereColor = Triple(0.45f, 0.85f, 1.0f),
            albedoColor = Triple(0.75f, 0.88f, 0.96f)
        ),
        SpawnTemplate(
            "asteroid", "Iron Asteroid", SpawnCategory.SMALL, BodyType.ASTEROID,
            massSolar = 1e-10, radiusKm = 450.0, temperatureK = 180.0,
            albedoColor = Triple(0.55f, 0.50f, 0.46f)
        ),

        // Black Holes (featured prominently!)
        SpawnTemplate(
            "bh_micro", "Primordial BH (3 M☉)", SpawnCategory.BLACK_HOLES, BodyType.BLACK_HOLE,
            massSolar = 3.0, radiusKm = 2.95325 * 3.0, temperatureK = 0.0
        ),
        SpawnTemplate(
            "bh_stellar", "Stellar Black Hole (12 M☉)", SpawnCategory.BLACK_HOLES, BodyType.BLACK_HOLE,
            massSolar = 12.0, radiusKm = 2.95325 * 12.0, temperatureK = 0.0
        ),
        SpawnTemplate(
            "bh_intermediate", "Intermediate BH (80 M☉)", SpawnCategory.BLACK_HOLES, BodyType.BLACK_HOLE,
            massSolar = 80.0, radiusKm = 2.95325 * 80.0, temperatureK = 0.0
        ),
        SpawnTemplate(
            "bh_smbh", "Supermassive BH (350 M☉)", SpawnCategory.BLACK_HOLES, BodyType.BLACK_HOLE,
            massSolar = 350.0, radiusKm = 2.95325 * 350.0, temperatureK = 0.0
        )
    )
}

class SandboxViewModel(application: Application) : AndroidViewModel(application) {

    val engine = PhysicsEngine()
    val renderFrameRef = AtomicReference<RenderFrameState?>(engine.buildRenderFrameState(60f))
    private val saveRepo = SaveLoadRepository(application.filesDir)

    private val _uiState = MutableStateFlow(
        SandboxUiState(
            graphicsSettings = GraphicsSettings(
                tier = DeviceQualityDetector.detectBestTier(application),
                autoDetectedTier = DeviceQualityDetector.detectBestTier(application)
            ),
            savedSlots = saveRepo.listSaves()
        )
    )
    val uiState: StateFlow<SandboxUiState> = _uiState.asStateFlow()

    var glRenderer: SolarGLRenderer? = null
    private val rng = Random(System.currentTimeMillis())

    init {
        startSimulationLoop()
    }

    private fun startSimulationLoop() {
        viewModelScope.launch(Dispatchers.Default) {
            var lastNs = System.nanoTime()
            var uiAccum = 0.0
            var tpsFrames = 0
            var tpsAccum = 0.0
            var currentTps = 60f

            while (isActive) {
                val targetStepMs = if (_uiState.value.graphicsSettings.batterySaver) 33L else 16L
                val nowNs = System.nanoTime()
                val dtSec = ((nowNs - lastNs) / 1e9).coerceIn(0.001, 0.05)
                lastNs = nowNs

                engine.step(dtSec)
                tpsFrames++
                tpsAccum += dtSec
                if (tpsAccum >= 0.5) {
                    currentTps = (tpsFrames / tpsAccum).toFloat()
                    tpsFrames = 0
                    tpsAccum = 0.0
                }

                    val packet = engine.buildRenderFrameState(currentTps)
                renderFrameRef.set(packet)

                // Publish UI telemetry at ~12 Hz to keep Compose light and battery-friendly
                uiAccum += dtSec
                if (uiAccum >= 0.085) {
                    uiAccum = 0.0
                    val selId = _uiState.value.selectedBodyId
                    val selBody = selId?.let { id -> packet.bodies.firstOrNull { it.id == id } }
                    val telemetry = selBody?.let { engine.computeTelemetry(it) }
                    _uiState.update { state ->
                        state.copy(
                            selectedTelemetry = telemetry,
                            elapsedYears = packet.elapsedYears,
                            bodyCount = packet.bodies.size,
                            config = packet.config,
                            simTps = packet.simTps,
                            cinematicMode = packet.cinematicState.isActive,
                            cinematicState = packet.cinematicState,
                            eventBanner = packet.lastEventMessage,
                            activityFeed = packet.activityFeed,
                            allBodiesList = packet.bodies.filter { !it.isBackgroundCluster }
                        )
                    }
                }

                val elapsedMs = (System.nanoTime() - nowNs) / 1_000_000L
                val sleepMs = (targetStepMs - elapsedMs).coerceAtLeast(2L)
                delay(sleepMs)
            }
        }
    }

    // ==================== CAMERA & SELECTION ====================

    fun selectBody(id: Long?) {
        glRenderer?.selectedBodyId = id
        if (id != null && _uiState.value.activeToolTab != SandboxToolTab.DRAG) {
            glRenderer?.focusedBodyId = id
            glRenderer?.manualPanOffset = Vec3d.ZERO
        }
        val body = id?.let { engine.getBodyById(it) }
        val telemetry = body?.let { engine.computeTelemetry(it) }
        _uiState.update {
            it.copy(
                selectedBodyId = id,
                selectedTelemetry = telemetry,
                activeToolTab = if (id != null && it.activeToolTab == SandboxToolTab.NONE) SandboxToolTab.INSPECT else it.activeToolTab
            )
        }
    }

    fun focusAndZoomToBody(id: Long) {
        val b = engine.getBodyById(id) ?: return
        glRenderer?.focusedBodyId = id
        glRenderer?.selectedBodyId = id
        glRenderer?.manualPanOffset = Vec3d.ZERO
        val rVis = b.getVisualRadiusAu(engine.config.trueScaleMode)
        glRenderer?.distanceAu = (rVis * 5.2).coerceIn(0.03, 40.0)
        selectBody(id)
    }

    fun resetCameraView() {
        val firstId = engine.getBodiesSnapshot().firstOrNull()?.id
        glRenderer?.focusedBodyId = firstId
        glRenderer?.manualPanOffset = Vec3d.ZERO
        glRenderer?.yawDeg = 28f
        glRenderer?.pitchDeg = 32f
        glRenderer?.distanceAu = 4.6
    }

    fun toggleCinematicMode() {
        if (engine.cinematicState.isActive) {
            engine.exitCinematicMode()
        } else {
            engine.startCinematicShowcase(_uiState.value.selectedBodyId)
        }
        syncCinematicImmediate()
    }

    fun toggleCinematicPaused() {
        engine.setCinematicPaused(!engine.cinematicState.isPaused)
        syncCinematicImmediate()
    }

    fun setCinematicSpeed(speed: Float) {
        engine.setCinematicPlaybackSpeed(speed)
        syncCinematicImmediate()
    }

    fun setCinematicPerspective(p: CinematicPerspective) {
        engine.setCinematicPerspective(p)
        syncCinematicImmediate()
    }

    fun replayCinematicCollision() {
        engine.replayCinematicCollision()
        syncCinematicImmediate()
    }

    fun exitCinematicMode() {
        engine.exitCinematicMode()
        glRenderer?.cinematicMode = false
        syncCinematicImmediate()
    }

    private fun syncCinematicImmediate() {
        val cs = engine.cinematicState
        glRenderer?.cinematicMode = cs.isActive
        _uiState.update {
            it.copy(
                cinematicMode = cs.isActive,
                cinematicState = cs,
                config = engine.config
            )
        }
    }

    fun updateScreenLabels(labels: List<ScreenBodyLabel>) {
        _uiState.update { it.copy(screenLabels = labels) }
    }

    fun updateFps(fps: Float) {
        _uiState.update { it.copy(fps = fps) }
    }

    // ==================== TIME CONTROLS ====================

    fun togglePause() {
        engine.updateConfig { it.copy(isPaused = !it.isPaused, isRewinding = false) }
        syncConfigImmediate()
    }

    fun toggleRewind() {
        engine.updateConfig { it.copy(isRewinding = !it.isRewinding, isPaused = false) }
        syncConfigImmediate()
    }

    fun setTimeScale(yearsPerSec: Double) {
        engine.updateConfig { it.copy(timeScaleYearsPerSec = yearsPerSec.coerceIn(1e-7, 500.0), isPaused = false, isRewinding = false) }
        syncConfigImmediate()
    }

    fun toggleOrbits() {
        engine.updateConfig { it.copy(showOrbits = !it.showOrbits) }
        syncConfigImmediate()
    }

    fun toggleLabels() {
        engine.updateConfig { it.copy(showLabels = !it.showLabels) }
        syncConfigImmediate()
    }

    fun toggleEventText() {
        engine.updateConfig { it.copy(showEventText = !it.showEventText) }
        syncConfigImmediate()
    }

    fun toggleTrueScale() {
        engine.updateConfig { it.copy(trueScaleMode = !it.trueScaleMode) }
        syncConfigImmediate()
    }

    fun toggleHabitableZone() {
        engine.updateConfig { it.copy(showHabitableZone = !it.showHabitableZone) }
        syncConfigImmediate()
    }

    fun toggleSpawnStationary() {
        _uiState.update { it.copy(spawnStationary = !it.spawnStationary) }
        _uiState.value.spawnAim.targetPos?.let { aimSpawnAtWorldPos(it) }
    }

    fun toggleConfirmBeforeSpawn() {
        _uiState.update { state ->
            val next = !state.spawnAim.requireConfirmSpawn
            state.copy(spawnAim = state.spawnAim.copy(requireConfirmSpawn = next))
        }
    }

    fun toggleEarthMapOverlay() {
        _uiState.update { it.copy(showEarthMapOverlay = !it.showEarthMapOverlay) }
    }

    fun toggleHawkingEvaporation() {
        engine.updateConfig { it.copy(hawkingEvaporation = !it.hawkingEvaporation) }
        syncConfigImmediate()
    }

    fun toggleStellarEvolution() {
        engine.updateConfig {
            val next = !it.stellarEvolutionEnabled
            it.copy(
                stellarEvolutionEnabled = next,
                stellarEvolutionRateGyrPerSec = if (next && it.stellarEvolutionRateGyrPerSec == 0.0) 0.12 else it.stellarEvolutionRateGyrPerSec
            )
        }
        syncConfigImmediate()
    }

    fun setIntegrator(mode: IntegratorMode) {
        engine.updateConfig { it.copy(integrator = mode) }
        syncConfigImmediate()
    }

    private fun syncConfigImmediate() {
        _uiState.update { it.copy(config = engine.config) }
    }

    // ==================== TOOL MODES, AIM-AND-PLACE SPAWN & SLINGSHOT ====================

    fun setToolTab(tab: SandboxToolTab) {
        _uiState.update {
            val next = if (it.activeToolTab == tab) SandboxToolTab.NONE else tab
            if (next != SandboxToolTab.SPAWN) {
                glRenderer?.spawnPreviewPos = null
                glRenderer?.spawnPreviewTrajectory = emptyList()
            }
            it.copy(activeToolTab = next)
        }
        if (_uiState.value.activeToolTab == SandboxToolTab.SPAWN) {
            val existing = _uiState.value.spawnAim.targetPos
            if (existing != null) {
                aimSpawnAtWorldPos(existing)
            } else {
                val focus = _uiState.value.selectedBodyId?.let { engine.getBodyById(it) }
                    ?: engine.getBodiesSnapshot().firstOrNull { !it.isBackgroundCluster }
                val basePos = focus?.pos ?: Vec3d.ZERO
                val offset = 1.6 + (_uiState.value.bodyCount % 4) * 0.45
                aimSpawnAtWorldPos(basePos + Vec3d(offset, 0.0, 0.0))
            }
        }
    }

    fun getDesiredInteractionMode(): InteractionMode {
        return when (_uiState.value.activeToolTab) {
            SandboxToolTab.DRAG -> InteractionMode.DRAG_BODY
            SandboxToolTab.SPAWN -> InteractionMode.SPAWN_TAP
            SandboxToolTab.SLINGSHOT -> InteractionMode.SLINGSHOT_LAUNCH
            else -> InteractionMode.ORBIT_SELECT
        }
    }

    fun selectSpawnCategory(cat: SpawnCategory) {
        val firstInCat = SpawnCatalog.all.firstOrNull { it.category == cat } ?: SpawnCatalog.all.first()
        _uiState.update { it.copy(selectedSpawnCategory = cat, selectedSpawnTemplate = firstInCat) }
        _uiState.value.spawnAim.targetPos?.let { aimSpawnAtWorldPos(it) }
    }

    fun selectSpawnTemplate(template: SpawnTemplate) {
        _uiState.update { it.copy(selectedSpawnTemplate = template) }
        _uiState.value.spawnAim.targetPos?.let { aimSpawnAtWorldPos(it) }
    }

    fun aimSpawnAtWorldPos(worldPos: Vec3d) {
        val state = _uiState.value
        val tpl = state.selectedSpawnTemplate
        val bodies = engine.getBodiesSnapshot().filter { !it.isBackgroundCluster }
        val parent = state.spawnAim.orbitParentId?.let { pid -> bodies.firstOrNull { it.id == pid } }
            ?: bodies.maxByOrNull { it.massSolar / (worldPos - it.pos).lengthSq().coerceAtLeast(1e-6) }
        val radiusFromParent = if (parent != null) (worldPos - parent.pos).length() else worldPos.length()
        val launchVel = if (state.spawnStationary) Vec3d.ZERO else engine.computeCircularOrbitVelocityForParent(worldPos, parent?.id)
        val previewTraj = if (state.spawnStationary) {
            emptyList()
        } else {
            engine.predictTrajectory(worldPos, launchVel, steps = 56, stepDt = 0.024)
        }
        val previewRad = templateVisualRadiusAu(tpl, engine.config.trueScaleMode)

        glRenderer?.spawnPreviewPos = worldPos
        glRenderer?.spawnPreviewRadiusAu = previewRad
        glRenderer?.spawnPreviewTrajectory = previewTraj

        _uiState.update {
            it.copy(
                spawnAim = it.spawnAim.copy(
                    targetPos = worldPos,
                    radiusFromParentAu = radiusFromParent
                )
            )
        }
    }

    fun setSpawnOrbitParent(parentId: Long?) {
        _uiState.update { it.copy(spawnAim = it.spawnAim.copy(orbitParentId = parentId)) }
        _uiState.value.spawnAim.targetPos?.let { aimSpawnAtWorldPos(it) }
    }

    fun nudgeSpawnRadius(factor: Double) {
        val state = _uiState.value
        val curPos = state.spawnAim.targetPos ?: Vec3d(1.6, 0.0, 0.0)
        val bodies = engine.getBodiesSnapshot().filter { !it.isBackgroundCluster }
        val parent = state.spawnAim.orbitParentId?.let { pid -> bodies.firstOrNull { it.id == pid } }
            ?: bodies.maxByOrNull { it.massSolar / (curPos - it.pos).lengthSq().coerceAtLeast(1e-6) }
        val center = parent?.pos ?: Vec3d.ZERO
        val rel = curPos - center
        val curR = rel.length().coerceAtLeast(0.08)
        val dir = if (rel.lengthSq() > 1e-8) rel.normalized() else Vec3d(1.0, 0.0, 0.0)
        val nextR = (curR * factor).coerceIn(0.08, 60.0)
        aimSpawnAtWorldPos(center + dir * nextR)
    }

    fun confirmSpawnAtAimedPos() {
        val target = _uiState.value.spawnAim.targetPos ?: Vec3d(1.6 + (_uiState.value.bodyCount % 5) * 0.45, 0.0, 0.0)
        spawnAtWorldPos(target)
    }

    private fun templateVisualRadiusAu(tpl: SpawnTemplate, trueScale: Boolean): Float {
        val dummy = CelestialBody(
            id = 0L, name = tpl.name, type = tpl.type,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO,
            massSolar = tpl.massSolar, radiusKm = tpl.radiusKm, temperatureK = tpl.temperatureK
        )
        return dummy.getVisualRadiusAu(trueScale).toFloat()
    }

    fun spawnAtWorldPos(worldPos: Vec3d, explicitVel: Vec3d? = null) {
        val tpl = _uiState.value.selectedSpawnTemplate
        val parentId = _uiState.value.spawnAim.orbitParentId
        val vel = explicitVel ?: if (_uiState.value.spawnStationary) Vec3d.ZERO else engine.computeCircularOrbitVelocityForParent(worldPos, parentId)
        val archetype = when (tpl.id) {
            "earth_like" -> BodyArchetype.EARTH
            "rocky_mars" -> BodyArchetype.MARS
            "gas_giant" -> BodyArchetype.JUPITER
            "ringed_giant" -> BodyArchetype.SATURN
            "ice_giant" -> BodyArchetype.NEPTUNE
            "moon" -> BodyArchetype.LUNA
            "comet" -> BodyArchetype.COMET
            else -> BodyArchetype.GENERIC_ROCK
        }
        val body = CelestialBody(
            id = 0L,
            name = tpl.name,
            type = tpl.type,
            archetype = archetype,
            spectralClass = tpl.spectralClass,
            pos = worldPos,
            vel = vel,
            massSolar = tpl.massSolar,
            radiusKm = tpl.radiusKm,
            temperatureK = tpl.temperatureK,
            luminositySolar = tpl.luminositySolar,
            atmosphereDensity = tpl.atmosphereDensity,
            atmosphereColor = tpl.atmosphereColor,
            albedoColor = tpl.albedoColor,
            seed = rng.nextFloat() * 100f,
            hasClouds = tpl.hasClouds,
            ringSystem = tpl.ringSystem,
            accretionActivity = 0.0f,
            evolutionStage = if (tpl.type.isLuminous) EvolutionStage.MAIN_SEQUENCE else EvolutionStage.NONE
        )
        val newId = engine.spawnBody(body)
        if (_uiState.value.activeToolTab == SandboxToolTab.SPAWN) {
            glRenderer?.selectedBodyId = newId
            _uiState.update { it.copy(selectedBodyId = newId) }
            // Keep preview slightly offset so user can inspect/place more cleanly
            aimSpawnAtWorldPos(worldPos)
        } else {
            selectBody(newId)
        }
    }

    // ==================== COLLISION LAB SCENARIO CONTROLS ====================

    fun selectCollisionBodyA(id: Long) {
        _uiState.update { it.copy(collisionSetup = it.collisionSetup.copy(bodyAId = id)) }
    }

    fun selectCollisionBodyB(id: Long) {
        _uiState.update { it.copy(collisionSetup = it.collisionSetup.copy(bodyBId = id)) }
    }

    fun setCollisionImpactAngle(deg: Float) {
        _uiState.update { it.copy(collisionSetup = it.collisionSetup.copy(impactAngleDeg = deg.coerceIn(0f, 84f))) }
    }

    fun setCollisionSpeedRatio(ratio: Float) {
        _uiState.update { it.copy(collisionSetup = it.collisionSetup.copy(speedRatio = ratio.coerceIn(0.6f, 4.0f))) }
    }

    fun toggleCollisionAutoCinematic() {
        _uiState.update { it.copy(collisionSetup = it.collisionSetup.copy(autoCinematic = !it.collisionSetup.autoCinematic)) }
    }

    fun launchCustomCollision() {
        val setup = _uiState.value.collisionSetup
        val launched = engine.launchCollisionScenario(
            bodyAId = setup.bodyAId,
            bodyBId = setup.bodyBId,
            impactAngleDeg = setup.impactAngleDeg.toDouble(),
            speedVsEscapeRatio = setup.speedRatio.toDouble(),
            startCinematic = setup.autoCinematic
        )
        if (launched) {
            glRenderer?.focusedBodyId = setup.bodyAId
            glRenderer?.selectedBodyId = setup.bodyAId
            if (setup.autoCinematic) {
                _uiState.update { it.copy(activeToolTab = SandboxToolTab.NONE) }
                syncCinematicImmediate()
            } else {
                syncConfigImmediate()
            }
        }
    }

    fun launchPresetCollision(preset: CollisionScenarioPreset) {
        val setup = _uiState.value.collisionSetup
        engine.launchPresetCollisionScenario(
            preset = preset,
            impactAngleDeg = preset.defaultAngleDeg.toDouble(),
            speedVsEscapeRatio = preset.defaultSpeedRatio.toDouble(),
            startCinematic = setup.autoCinematic
        )
        glRenderer?.focusedBodyId = 2L
        glRenderer?.selectedBodyId = 2L
        glRenderer?.manualPanOffset = Vec3d.ZERO
        _uiState.update {
            it.copy(
                selectedBodyId = 2L,
                activeToolTab = if (setup.autoCinematic) SandboxToolTab.NONE else it.activeToolTab,
                collisionSetup = setup.copy(
                    bodyAId = 2L,
                    bodyBId = 3L,
                    impactAngleDeg = preset.defaultAngleDeg,
                    speedRatio = preset.defaultSpeedRatio
                )
            )
        }
        syncCinematicImmediate()
    }

    fun onSlingshotAim(spawnPos: Vec3d, launchVel: Vec3d) {
        val pts = engine.predictTrajectory(spawnPos, launchVel, steps = 76, stepDt = 0.015)
        glRenderer?.slingshotPreview = SlingshotPreview(spawnPos, launchVel, pts)
    }

    fun onSlingshotRelease(spawnPos: Vec3d, launchVel: Vec3d) {
        glRenderer?.slingshotPreview = null
        spawnAtWorldPos(spawnPos, launchVel)
    }

    fun onFlingMoveBody(bodyId: Long, newPos: Vec3d, newVel: Vec3d?) {
        engine.updateBody(bodyId) { b ->
            if (newVel != null) b.copy(pos = newPos, vel = newVel) else b.copy(pos = newPos)
        }
    }

    // ==================== LIVE OBJECT EDITING ====================

    fun editSelectedBody(
        scaleRadiusWithMass: Boolean = true,
        transform: (CelestialBody) -> CelestialBody
    ) {
        val id = _uiState.value.selectedBodyId ?: return
        engine.updateBodyCoupled(id, scaleRadiusWithMass, transform)
        engine.getBodyById(id)?.let { updated ->
            _uiState.update { it.copy(selectedTelemetry = engine.computeTelemetry(updated)) }
        }
    }

    fun editBodyDensity(newDensityGCm3: Double) {
        editSelectedBody(scaleRadiusWithMass = false) { b ->
            val currentD = b.densityGCm3().coerceAtLeast(1e-4)
            val ratio = (newDensityGCm3 / currentD).coerceIn(1e-4, 1e4)
            b.copy(massSolar = (b.massSolar * ratio).coerceAtLeast(1e-18))
        }
    }

    fun nudgeSelectedOrbitSpeed(factor: Double) {
        editSelectedBody { b -> b.copy(vel = b.vel * factor) }
    }

    fun deleteSelectedBody() {
        val id = _uiState.value.selectedBodyId ?: return
        engine.removeBody(id)
        val nextId = engine.getBodiesSnapshot().firstOrNull()?.id
        selectBody(nextId)
    }

    // ==================== WEAPONS & COSMIC EVENTS ====================

    fun fireAsteroidStrike() {
        val target = _uiState.value.selectedBodyId ?: engine.getBodiesSnapshot().getOrNull(1)?.id ?: return
        engine.fireAsteroidStrike(target)
    }

    fun fireNuke() {
        val target = _uiState.value.selectedBodyId ?: engine.getBodiesSnapshot().getOrNull(1)?.id ?: return
        engine.fireNuke(target)
    }

    fun triggerSupernova() {
        val target = _uiState.value.selectedBodyId
            ?: engine.getBodiesSnapshot().firstOrNull { it.type.isLuminous }?.id
            ?: return
        engine.triggerSupernova(target)
    }

    fun fireGammaRayBurst() {
        engine.fireGammaRayBurst(_uiState.value.selectedBodyId)
    }

    fun splitSelectedPlanet() {
        val target = _uiState.value.selectedBodyId ?: return
        engine.splitPlanet(target)
    }

    fun launchRogueStar() {
        engine.launchRogueStar()
    }

    // ==================== PRESETS, SAVE/LOAD & QUALITY ====================

    fun loadPreset(preset: SystemPreset) {
        val snapshot = SolarSystemPresets.buildPreset(preset)
        engine.loadSnapshot(snapshot)
        val major = snapshot.bodies.filter { !it.isBackgroundCluster }
        val focusId = major.firstOrNull()?.id
        val defA = major.getOrNull(1)?.id ?: focusId ?: 1L
        val defB = major.getOrNull(2)?.id ?: major.firstOrNull()?.id ?: 2L
        glRenderer?.focusedBodyId = focusId
        glRenderer?.selectedBodyId = focusId
        glRenderer?.manualPanOffset = Vec3d.ZERO
        glRenderer?.pitchDeg = 32f
        glRenderer?.distanceAu = when (preset) {
            SystemPreset.TRAPPIST_1 -> 2.4
            SystemPreset.BINARY_BLACK_HOLES -> 4.8
            SystemPreset.GALAXY_COLLISION -> 14.0
            else -> 4.6
        }
        _uiState.update {
            it.copy(
                currentPreset = preset,
                selectedBodyId = focusId,
                showPresetsDialog = false,
                cinematicMode = false,
                cinematicState = CinematicDirectorState(),
                collisionSetup = it.collisionSetup.copy(bodyAId = defA, bodyBId = defB)
            )
        }
    }

    fun resetCurrentPreset() {
        loadPreset(_uiState.value.currentPreset)
    }

    fun saveCurrentState(slotName: String) {
        val snap = engine.createSnapshot(slotName.ifBlank { "${_uiState.value.currentPreset.title} Save" })
        saveRepo.saveSnapshot(snap)
        _uiState.update { it.copy(savedSlots = saveRepo.listSaves()) }
    }

    fun loadSavedSlot(fileName: String) {
        val snap = saveRepo.loadSave(fileName) ?: return
        engine.loadSnapshot(snap)
        val major = snap.bodies.filter { !it.isBackgroundCluster }
        val focusId = major.firstOrNull()?.id
        glRenderer?.focusedBodyId = focusId
        glRenderer?.selectedBodyId = focusId
        _uiState.update {
            it.copy(
                selectedBodyId = focusId,
                showSaveLoadDialog = false,
                cinematicMode = false,
                cinematicState = CinematicDirectorState()
            )
        }
    }

    fun deleteSavedSlot(fileName: String) {
        saveRepo.deleteSave(fileName)
        _uiState.update { it.copy(savedSlots = saveRepo.listSaves()) }
    }

    fun setQualityTier(tier: QualityTier) {
        val next = _uiState.value.graphicsSettings.copy(tier = tier)
        glRenderer?.graphicsSettings = next
        _uiState.update { it.copy(graphicsSettings = next) }
    }

    fun toggleBatterySaver() {
        val cur = _uiState.value.graphicsSettings
        val next = cur.copy(batterySaver = !cur.batterySaver)
        glRenderer?.graphicsSettings = next
        _uiState.update { it.copy(graphicsSettings = next) }
    }

    fun autoDetectQuality() {
        val detected = DeviceQualityDetector.detectBestTier(getApplication())
        val next = _uiState.value.graphicsSettings.copy(tier = detected, autoDetectedTier = detected)
        glRenderer?.graphicsSettings = next
        _uiState.update { it.copy(graphicsSettings = next) }
    }

    fun setBloomIntensity(valFloat: Float) {
        val next = _uiState.value.graphicsSettings.copy(bloomIntensity = valFloat.coerceIn(0f, 2f))
        glRenderer?.graphicsSettings = next
        _uiState.update { it.copy(graphicsSettings = next) }
    }

    fun setPresetsDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showPresetsDialog = visible) }
    }

    fun setQualityDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showQualityDialog = visible) }
    }

    fun setSaveLoadDialogVisible(visible: Boolean) {
        _uiState.update { it.copy(showSaveLoadDialog = visible, savedSlots = saveRepo.listSaves()) }
    }
}
