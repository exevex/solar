package com.example.simulation

enum class BodyType(val label: String, val isLuminous: Boolean, val isCompact: Boolean) {
    STAR("Main Sequence Star", true, false),
    RED_GIANT("Red Giant", true, false),
    WHITE_DWARF("White Dwarf", true, true),
    NEUTRON_STAR("Neutron Star", true, true),
    BLACK_HOLE("Black Hole", false, true),
    TERRESTRIAL_PLANET("Terrestrial Planet", false, false),
    EARTH_LIKE("Oceanic Terra", false, false),
    GAS_GIANT("Gas Giant", false, false),
    ICE_GIANT("Ice Giant", false, false),
    DWARF_PLANET("Dwarf Planet", false, false),
    MOON("Moon", false, false),
    ASTEROID("Asteroid", false, false),
    COMET("Comet", false, false),
    DEBRIS("Molten Debris", false, false)
}

/**
 * NASA / JPL visual surface archetypes for accurate procedural PBR shading.
 */
enum class BodyArchetype(val shaderId: Int) {
    GENERIC_EARTH(0),
    GENERIC_ROCK(1),
    GENERIC_GAS(2),
    GENERIC_ICE(3),
    COMET(4),
    EARTH(5),
    MARS(6),
    MERCURY(7),
    VENUS(8),
    LUNA(9),
    JUPITER(10),
    SATURN(11),
    URANUS(12),
    NEPTUNE(13),
    PLUTO(14),
    IO(15),
    EUROPA(16),
    TITAN(17),
    ENCELADUS(18)
}

enum class SpectralClass(val label: String, val tempK: Double, val massSolar: Double, val radiusKm: Double, val luminositySolar: Double) {
    O("O-Type Blue Super", 35000.0, 28.0, 5_500_000.0, 80000.0),
    B("B-Type Blue-White", 18000.0, 6.5, 2_400_000.0, 1200.0),
    A("A-Type White", 8800.0, 2.0, 1_150_000.0, 20.0),
    F("F-Type Yellow-White", 6700.0, 1.35, 890_000.0, 3.2),
    G("G-Type Solar", 5778.0, 1.0, 696_340.0, 1.0),
    K("K-Type Orange Dwarf", 4400.0, 0.72, 520_000.0, 0.25),
    M("M-Type Red Dwarf", 3100.0, 0.25, 240_000.0, 0.015),
    NONE("Non-Stellar", 0.0, 0.0, 0.0, 0.0)
}

enum class EvolutionStage(val label: String) {
    NONE("Stable"),
    MAIN_SEQUENCE("Main Sequence"),
    SUBGIANT("Subgiant"),
    RED_GIANT("Red Giant"),
    SUPERNOVA("Supernova"),
    WHITE_DWARF("White Dwarf"),
    NEUTRON_STAR("Neutron Star"),
    BLACK_HOLE("Black Hole")
}

enum class IntegratorMode(val label: String) {
    VELOCITY_VERLET("Velocity Verlet (Symplectic)"),
    RK4("Runge-Kutta 4 (RK4)")
}

data class CraterMark(
    val nx: Float,
    val ny: Float,
    val nz: Float,
    val radiusRad: Float,
    val heat: Float
)

data class RingSystem(
    val innerRadiusRatio: Float = 1.35f,
    val outerRadiusRatio: Float = 2.45f,
    val colorR: Float = 0.85f,
    val colorG: Float = 0.78f,
    val colorB: Float = 0.65f,
    val opacity: Float = 0.85f,
    val hasCassiniGap: Boolean = true
)

data class CelestialBody(
    val id: Long,
    val name: String,
    val type: BodyType,
    val archetype: BodyArchetype = when (type) {
        BodyType.EARTH_LIKE -> BodyArchetype.GENERIC_EARTH
        BodyType.GAS_GIANT -> BodyArchetype.GENERIC_GAS
        BodyType.ICE_GIANT -> BodyArchetype.GENERIC_ICE
        BodyType.COMET -> BodyArchetype.COMET
        else -> BodyArchetype.GENERIC_ROCK
    },
    val spectralClass: SpectralClass = SpectralClass.NONE,
    val pos: Vec3d,
    val vel: Vec3d,
    val massSolar: Double,
    val radiusKm: Double,
    val temperatureK: Double,
    val luminositySolar: Double = 0.0,
    val rotationPeriodHours: Double = 24.0,
    val axialTiltDeg: Double = 0.0,
    val spinAngleDeg: Double = 0.0,
    val atmosphereDensity: Float = 0f,
    val atmosphereColor: Triple<Float, Float, Float> = Triple(0.35f, 0.65f, 1.0f),
    val albedoColor: Triple<Float, Float, Float> = Triple(0.7f, 0.7f, 0.7f),
    val seed: Float = 1.0f,
    val hasClouds: Boolean = false,
    val hasCityLights: Boolean = false,
    val ringSystem: RingSystem? = null,
    val craters: List<CraterMark> = emptyList(),
    val crustHeat: Float = 0f,
    val ageGyr: Double = 4.6,
    val initialMassSolar: Double = massSolar,
    val evolutionStage: EvolutionStage = EvolutionStage.NONE,
    val trail: List<Vec3d> = emptyList(),
    val parentBodyId: Long? = null,
    val accretionActivity: Float = 0f,
    val absorbedMassSolar: Double = 0.0,
    val tidalStress: Float = 0f,
    val tidalStretchDir: Vec3d = Vec3d(1.0, 0.0, 0.0),
    val tidalStretchAmount: Float = 0f,
    val tidalTargetBhId: Long? = null,
    val isBackgroundCluster: Boolean = false
) {
    val physicsRadiusAu: Double
        get() = (radiusKm / AstroConstants.AU_KM).coerceAtLeast(1e-7)

    fun getVisualRadiusAu(trueScale: Boolean): Double {
        if (trueScale) return physicsRadiusAu
        return when (type) {
            BodyType.STAR, BodyType.RED_GIANT ->
                (physicsRadiusAu * 18.0).coerceIn(0.015, 0.55)
            BodyType.WHITE_DWARF, BodyType.NEUTRON_STAR ->
                (physicsRadiusAu * 120.0).coerceIn(0.008, 0.032)
            BodyType.BLACK_HOLE -> {
                val rsAu = AstroConstants.SCHWARZSCHILD_SUN_AU * massSolar
                (rsAu * 3600.0).coerceIn(0.014, 0.24)
            }
            BodyType.GAS_GIANT ->
                (physicsRadiusAu * 155.0).coerceIn(0.022, 0.11)
            BodyType.ICE_GIANT ->
                (physicsRadiusAu * 210.0).coerceIn(0.016, 0.075)
            BodyType.EARTH_LIKE, BodyType.TERRESTRIAL_PLANET ->
                (physicsRadiusAu * 380.0).coerceIn(0.009, 0.042)
            BodyType.DWARF_PLANET ->
                (physicsRadiusAu * 650.0).coerceIn(0.006, 0.02)
            BodyType.MOON ->
                (physicsRadiusAu * 480.0).coerceIn(0.0035, 0.015)
            BodyType.ASTEROID, BodyType.COMET ->
                (physicsRadiusAu * 1200.0).coerceIn(0.0025, 0.009)
            BodyType.DEBRIS ->
                (physicsRadiusAu * 900.0).coerceIn(0.002, 0.008)
        }
    }

    fun surfaceGravityMs2(): Double {
        val mKg = massSolar * AstroConstants.SOLAR_MASS_KG
        val rM = (radiusKm * 1000.0).coerceAtLeast(1.0)
        return AstroConstants.G_SI * mKg / (rM * rM)
    }

    fun escapeVelocityKmS(): Double {
        val mKg = massSolar * AstroConstants.SOLAR_MASS_KG
        val rM = (radiusKm * 1000.0).coerceAtLeast(1.0)
        return kotlin.math.sqrt(2.0 * AstroConstants.G_SI * mKg / rM) / 1000.0
    }

    fun densityGCm3(): Double {
        val mGrams = massSolar * AstroConstants.SOLAR_MASS_KG * 1000.0
        val rCm = (radiusKm * 100_000.0).coerceAtLeast(1.0)
        val vol = (4.0 / 3.0) * Math.PI * rCm * rCm * rCm
        return mGrams / vol
    }

    fun schwarzschildRadiusKm(): Double = 2.95325 * massSolar
}

/**
 * Live curved plasma filament pulled from a tidally-stretched star/planet spiraling into a Black Hole's accretion disk.
 */
data class TidalStreamFilament(
    val points: List<Vec3d>,
    val color: Triple<Float, Float, Float>,
    val intensity: Float
)

data class ShockwaveEvent(
    val id: Long,
    val pos: Vec3d,
    val maxRadiusAu: Double,
    val ageSec: Float,
    val durationSec: Float,
    val color: Triple<Float, Float, Float>,
    val isGravitationalWave: Boolean = false
)

data class DebrisParticle(
    var x: Double,
    var y: Double,
    var z: Double,
    var vx: Double,
    var vy: Double,
    var vz: Double,
    var life: Float,
    var maxLife: Float,
    var size: Float,
    var r: Float,
    var g: Float,
    var b: Float,
    var targetBlackHoleId: Long = -1L
)

data class GammaBurstBeam(
    val id: Long,
    val origin: Vec3d,
    val dir: Vec3d,
    val ageSec: Float,
    val durationSec: Float,
    val color: Triple<Float, Float, Float>
)

data class BodyTelemetry(
    val body: CelestialBody,
    val speedKmS: Double,
    val surfaceGravityG: Double,
    val escapeVelocityKmS: Double,
    val densityGCm3: Double,
    val orbitalPeriodDays: Double?,
    val semiMajorAxisAu: Double?,
    val eccentricity: Double?,
    val rocheLimitAu: Double?,
    val schwarzschildKm: Double,
    val statusSummary: String
)

data class SimulationConfig(
    val isPaused: Boolean = false,
    val timeScaleYearsPerSec: Double = 0.04,
    val isRewinding: Boolean = false,
    val integrator: IntegratorMode = IntegratorMode.VELOCITY_VERLET,
    val hawkingEvaporation: Boolean = false,
    val stellarEvolutionEnabled: Boolean = false,
    val stellarEvolutionRateGyrPerSec: Double = 0.0,
    val trueScaleMode: Boolean = false,
    val showOrbits: Boolean = true,
    val showLabels: Boolean = true
)

data class SimulationSnapshot(
    val name: String,
    val presetId: String,
    val elapsedYears: Double,
    val bodies: List<CelestialBody>,
    val config: SimulationConfig,
    val savedAtEpochMs: Long = System.currentTimeMillis()
)
