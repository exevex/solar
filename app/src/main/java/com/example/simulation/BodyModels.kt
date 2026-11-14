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
    val tidalStress: Float = 0f,
    val isBackgroundCluster: Boolean = false
) {
    /** Actual physical radius in AU */
    val physicsRadiusAu: Double
        get() = (radiusKm / AstroConstants.AU_KM).coerceAtLeast(1e-7)

    /**
     * Visual radius in AU for sandbox playability.
     * In Enhanced Sandbox mode, planets & moons are scaled so they remain visible alongside orbits,
     * while True Scale mode uses exact physical radii.
     */
    fun getVisualRadiusAu(trueScale: Boolean): Double {
        if (trueScale) return physicsRadiusAu
        return when (type) {
            BodyType.STAR, BodyType.RED_GIANT ->
                (physicsRadiusAu * 18.0).coerceIn(0.015, 0.45)
            BodyType.WHITE_DWARF, BodyType.NEUTRON_STAR ->
                (physicsRadiusAu * 120.0).coerceIn(0.008, 0.028)
            BodyType.BLACK_HOLE -> {
                // Visual Schwarzschild + accretion horizon radius
                val rsAu = AstroConstants.SCHWARZSCHILD_SUN_AU * massSolar
                (rsAu * 3500.0).coerceIn(0.012, 0.16)
            }
            BodyType.GAS_GIANT ->
                (physicsRadiusAu * 155.0).coerceIn(0.022, 0.095)
            BodyType.ICE_GIANT ->
                (physicsRadiusAu * 210.0).coerceIn(0.016, 0.065)
            BodyType.EARTH_LIKE, BodyType.TERRESTRIAL_PLANET ->
                (physicsRadiusAu * 380.0).coerceIn(0.009, 0.036)
            BodyType.DWARF_PLANET ->
                (physicsRadiusAu * 650.0).coerceIn(0.006, 0.018)
            BodyType.MOON ->
                (physicsRadiusAu * 480.0).coerceIn(0.0035, 0.014)
            BodyType.ASTEROID, BodyType.COMET ->
                (physicsRadiusAu * 1200.0).coerceIn(0.0025, 0.009)
            BodyType.DEBRIS ->
                (physicsRadiusAu * 900.0).coerceIn(0.002, 0.008)
        }
    }

    /** Surface gravity in m/s^2 */
    fun surfaceGravityMs2(): Double {
        val mKg = massSolar * AstroConstants.SOLAR_MASS_KG
        val rM = (radiusKm * 1000.0).coerceAtLeast(1.0)
        return AstroConstants.G_SI * mKg / (rM * rM)
    }

    /** Escape velocity in km/s */
    fun escapeVelocityKmS(): Double {
        val mKg = massSolar * AstroConstants.SOLAR_MASS_KG
        val rM = (radiusKm * 1000.0).coerceAtLeast(1.0)
        return kotlin.math.sqrt(2.0 * AstroConstants.G_SI * mKg / rM) / 1000.0
    }

    /** Bulk density in g/cm^3 */
    fun densityGCm3(): Double {
        val mGrams = massSolar * AstroConstants.SOLAR_MASS_KG * 1000.0
        val rCm = (radiusKm * 100_000.0).coerceAtLeast(1.0)
        val vol = (4.0 / 3.0) * Math.PI * rCm * rCm * rCm
        return mGrams / vol
    }

    /** Schwarzschild radius in km */
    fun schwarzschildRadiusKm(): Double {
        return 2.95325 * massSolar
    }
}

/** Transient particle / shockwave events rendered by the OpenGL GPU particle & ring pass */
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
    val schwarzschildKm: Double
)

data class SimulationConfig(
    val isPaused: Boolean = false,
    val timeScaleYearsPerSec: Double = 0.04, // ~14.6 days/sec default
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
