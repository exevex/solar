package com.example.simulation

import kotlin.math.*
import kotlin.random.Random

enum class SystemPreset(val id: String, val title: String, val subtitle: String) {
    SOLAR_SYSTEM("solar_system", "Solar System", "NASA/JPL Sun, 8 planets, Pluto, moons & asteroid belt"),
    ALPHA_CENTAURI("alpha_centauri", "Alpha Centauri", "Triple star system with Alpha Cen A, B & Proxima b"),
    BINARY_STARS("binary_stars", "Binary Star System", "Twin F/K stars orbited by circumbinary worlds"),
    TRAPPIST_1("trappist_1", "TRAPPIST-1", "Ultra-cool red dwarf with 7 resonant rocky worlds"),
    GALAXY_COLLISION("galaxy_collision", "Galaxy Collision", "Two colliding spiral star clusters with central SMBHs"),
    SUN_RED_GIANT("sun_red_giant", "Sun → Red Giant", "Accelerated stellar evolution from G2V to White Dwarf"),
    BINARY_BLACK_HOLES("binary_black_holes", "Binary Black Holes", "Inspiraling supermassive black holes with accretion disks")
}

object SolarSystemPresets {

    private var nextId = 1L
    fun allocateId(): Long = nextId++

    fun resetIdCounter(minNext: Long) {
        if (minNext > nextId) nextId = minNext
    }

    fun buildPreset(preset: SystemPreset): SimulationSnapshot {
        nextId = 1L
        return when (preset) {
            SystemPreset.SOLAR_SYSTEM -> buildSolarSystem(acceleratedEvolution = false, presetId = preset.id, title = preset.title)
            SystemPreset.SUN_RED_GIANT -> buildSolarSystem(acceleratedEvolution = true, presetId = preset.id, title = preset.title)
            SystemPreset.ALPHA_CENTAURI -> buildAlphaCentauri()
            SystemPreset.BINARY_STARS -> buildBinaryStars()
            SystemPreset.TRAPPIST_1 -> buildTrappist1()
            SystemPreset.GALAXY_COLLISION -> buildGalaxyCollision()
            SystemPreset.BINARY_BLACK_HOLES -> buildBinaryBlackHoles()
        }
    }

    private fun buildSolarSystem(acceleratedEvolution: Boolean, presetId: String, title: String): SimulationSnapshot {
        val bodies = mutableListOf<CelestialBody>()
        val sunId = allocateId()
        val sunMass = 1.0
        val sun = CelestialBody(
            id = sunId,
            name = "Sun",
            type = BodyType.STAR,
            spectralClass = SpectralClass.G,
            pos = Vec3d.ZERO,
            vel = Vec3d.ZERO,
            massSolar = sunMass,
            radiusKm = 696_340.0,
            temperatureK = 5778.0,
            luminositySolar = 1.0,
            rotationPeriodHours = 609.12, // 25.38 days
            axialTiltDeg = 7.25,
            albedoColor = kelvinToRgb(5778.0),
            seed = 0.42f,
            ageGyr = if (acceleratedEvolution) 9.8 else 4.6,
            initialMassSolar = 1.0,
            evolutionStage = EvolutionStage.MAIN_SEQUENCE
        )
        bodies.add(sun)

        data class PlanetSpec(
            val name: String,
            val type: BodyType,
            val massEarth: Double,
            val radiusKm: Double,
            val a: Double,
            val e: Double,
            val inc: Double,
            val omega: Double,
            val bigOmega: Double,
            val m0: Double,
            val tempK: Double,
            val rotHours: Double,
            val tiltDeg: Double,
            val atmoDensity: Float,
            val atmoColor: Triple<Float, Float, Float>,
            val albedo: Triple<Float, Float, Float>,
            val seed: Float,
            val clouds: Boolean = false,
            val cityLights: Boolean = false,
            val rings: RingSystem? = null
        )

        val specs = listOf(
            PlanetSpec(
                "Mercury", BodyType.TERRESTRIAL_PLANET, 0.0553, 2439.7,
                0.387098, 0.20563, 7.005, 29.124, 48.331, 174.796,
                440.0, 1407.6, 0.034, 0f, Triple(0f, 0f, 0f),
                Triple(0.58f, 0.54f, 0.50f), 1.7f
            ),
            PlanetSpec(
                "Venus", BodyType.TERRESTRIAL_PLANET, 0.815, 6051.8,
                0.723332, 0.00677, 3.395, 54.884, 76.680, 50.115,
                737.0, -5832.5, 177.36, 0.95f, Triple(0.95f, 0.78f, 0.42f),
                Triple(0.88f, 0.74f, 0.46f), 2.9f, clouds = true
            ),
            PlanetSpec(
                "Earth", BodyType.EARTH_LIKE, 1.0, 6371.0,
                1.000001, 0.01671, 0.000, 114.208, -11.26, 358.617,
                288.0, 23.934, 23.44, 0.65f, Triple(0.28f, 0.62f, 1.0f),
                Triple(0.20f, 0.48f, 0.82f), 4.13f, clouds = true, cityLights = true
            ),
            PlanetSpec(
                "Mars", BodyType.TERRESTRIAL_PLANET, 0.107, 3389.5,
                1.523679, 0.09341, 1.850, 286.502, 49.558, 19.373,
                210.0, 24.623, 25.19, 0.14f, Triple(0.85f, 0.48f, 0.30f),
                Triple(0.76f, 0.34f, 0.21f), 7.21f
            ),
            PlanetSpec(
                "Jupiter", BodyType.GAS_GIANT, 317.83, 69911.0,
                5.2044, 0.0489, 1.303, 273.867, 100.464, 20.020,
                165.0, 9.925, 3.13, 0.40f, Triple(0.82f, 0.68f, 0.50f),
                Triple(0.84f, 0.66f, 0.48f), 11.4f
            ),
            PlanetSpec(
                "Saturn", BodyType.GAS_GIANT, 95.159, 58232.0,
                9.5826, 0.0565, 2.485, 339.392, 113.665, 317.020,
                134.0, 10.656, 26.73, 0.35f, Triple(0.88f, 0.78f, 0.58f),
                Triple(0.88f, 0.78f, 0.56f), 15.8f,
                rings = RingSystem(1.28f, 2.38f, 0.86f, 0.79f, 0.66f, 0.88f, hasCassiniGap = true)
            ),
            PlanetSpec(
                "Uranus", BodyType.ICE_GIANT, 14.536, 25362.0,
                19.19126, 0.04717, 0.773, 96.998, 74.006, 142.238,
                76.0, -17.24, 97.77, 0.50f, Triple(0.48f, 0.86f, 0.92f),
                Triple(0.55f, 0.85f, 0.90f), 19.3f,
                rings = RingSystem(1.55f, 1.95f, 0.60f, 0.76f, 0.85f, 0.32f, hasCassiniGap = false)
            ),
            PlanetSpec(
                "Neptune", BodyType.ICE_GIANT, 17.147, 24622.0,
                30.07, 0.00859, 1.770, 273.187, 131.784, 256.228,
                72.0, 16.11, 28.32, 0.55f, Triple(0.24f, 0.48f, 0.95f),
                Triple(0.22f, 0.44f, 0.88f), 23.7f
            ),
            PlanetSpec(
                "Pluto", BodyType.DWARF_PLANET, 0.00218, 1188.3,
                39.482, 0.2488, 17.16, 113.834, 110.299, 14.53,
                44.0, -153.3, 122.53, 0.04f, Triple(0.65f, 0.72f, 0.85f),
                Triple(0.72f, 0.63f, 0.55f), 29.1f
            )
        )

        val planetIds = mutableMapOf<String, CelestialBody>()
        for (p in specs) {
            val massSolar = p.massEarth * AstroConstants.EARTH_MASS_SOLAR
            var (pos, vel) = orbitalElementsToState(
                centralMassSolar = sunMass,
                bodyMassSolar = massSolar,
                semiMajorAxisAu = p.a,
                eccentricity = p.e,
                inclinationDeg = p.inc,
                ascNodeDeg = p.bigOmega,
                argPeriapsisDeg = p.omega,
                meanAnomalyDeg = p.m0
            )
            // Replace the analytic fallback with a real DE441/JPL Horizons J2000 state
            // whenever a tracked Solar-System body has a published ephemeris vector.
            JplHorizonsJ2000.state(p.name)?.let { state ->
                val sunState = JplHorizonsJ2000.state("Sun")
                if (sunState != null) {
                    pos = state.position - sunState.position
                    vel = state.velocityAuPerYear - sunState.velocityAuPerYear
                }
            }
            val body = CelestialBody(
                id = allocateId(),
                name = p.name,
                type = p.type,
                pos = pos,
                vel = vel,
                massSolar = massSolar,
                radiusKm = p.radiusKm,
                temperatureK = p.tempK,
                rotationPeriodHours = p.rotHours,
                axialTiltDeg = p.tiltDeg,
                atmosphereDensity = p.atmoDensity,
                atmosphereColor = p.atmoColor,
                albedoColor = p.albedo,
                seed = p.seed,
                hasClouds = p.clouds,
                hasCityLights = p.cityLights,
                ringSystem = p.rings,
                parentBodyId = sunId
            )
            bodies.add(body)
            planetIds[p.name] = body
        }

        // Major moons use their physical barycentric orbital distances; enhanced planet rendering
        // never changes the simulated orbit.
        fun addMoon(
            parentName: String,
            name: String,
            massEarth: Double,
            radiusKm: Double,
            realSemiMajorKm: Double,
            ecc: Double,
            incDeg: Double,
            m0Deg: Double,
            tempK: Double,
            albedo: Triple<Float, Float, Float>,
            seed: Float
        ) {
            val parent = planetIds[parentName] ?: return
            val massSolar = massEarth * AstroConstants.EARTH_MASS_SOLAR
            val realAu = realSemiMajorKm / AstroConstants.AU_KM
            val orbitAu = realAu
            var (pos, vel) = orbitalElementsToState(
                centralMassSolar = parent.massSolar,
                bodyMassSolar = massSolar,
                semiMajorAxisAu = orbitAu,
                eccentricity = ecc,
                inclinationDeg = incDeg,
                ascNodeDeg = m0Deg * 0.3,
                argPeriapsisDeg = m0Deg * 0.7,
                meanAnomalyDeg = m0Deg,
                parentPos = parent.pos,
                parentVel = parent.vel
            )
            JplHorizonsJ2000.state(name)?.let { state ->
                val sunState = JplHorizonsJ2000.state("Sun")
                if (sunState != null) {
                    pos = state.position - sunState.position
                    vel = state.velocityAuPerYear - sunState.velocityAuPerYear
                }
            }
            bodies.add(
                CelestialBody(
                    id = allocateId(),
                    name = name,
                    type = BodyType.MOON,
                    pos = pos,
                    vel = vel,
                    massSolar = massSolar,
                    radiusKm = radiusKm,
                    temperatureK = tempK,
                    rotationPeriodHours = 60.0,
                    albedoColor = albedo,
                    seed = seed,
                    parentBodyId = parent.id
                )
            )
        }

        // Earth's Moon
        addMoon("Earth", "Moon", 0.0123, 1737.4, 384_400.0, 0.0549, 5.145, 45.0, 220.0, Triple(0.68f, 0.68f, 0.66f), 31.2f)
        // Mars Moons
        addMoon("Mars", "Phobos", 1.78e-9, 11.27, 9376.0, 0.0151, 1.09, 120.0, 233.0, Triple(0.45f, 0.40f, 0.36f), 33.4f)
        addMoon("Mars", "Deimos", 2.47e-10, 6.2, 23463.0, 0.0003, 1.79, 260.0, 233.0, Triple(0.48f, 0.44f, 0.39f), 34.8f)
        // Jupiter Galilean Moons
        addMoon("Jupiter", "Io", 0.01496, 1821.6, 421_700.0, 0.0041, 0.05, 10.0, 110.0, Triple(0.92f, 0.80f, 0.35f), 40.1f)
        addMoon("Jupiter", "Europa", 0.00804, 1560.8, 671_034.0, 0.009, 0.47, 100.0, 102.0, Triple(0.82f, 0.80f, 0.75f), 41.5f)
        addMoon("Jupiter", "Ganymede", 0.0248, 2634.1, 1_070_412.0, 0.0013, 0.20, 210.0, 110.0, Triple(0.62f, 0.58f, 0.52f), 42.9f)
        addMoon("Jupiter", "Callisto", 0.0180, 2410.3, 1_882_709.0, 0.0074, 0.19, 310.0, 134.0, Triple(0.46f, 0.44f, 0.41f), 44.3f)
        // Saturn Moons (outside rings!)
        addMoon("Saturn", "Enceladus", 1.8e-5, 252.1, 237_948.0, 0.0047, 0.02, 70.0, 75.0, Triple(0.92f, 0.95f, 0.98f), 51.0f)
        addMoon("Saturn", "Titan", 0.0225, 2574.7, 1_221_870.0, 0.0288, 0.35, 195.0, 94.0, Triple(0.86f, 0.65f, 0.32f), 52.6f)
        // Neptune Moon
        addMoon("Neptune", "Triton", 0.00359, 1353.4, 354_759.0, 0.000016, 156.8, 150.0, 38.0, Triple(0.75f, 0.72f, 0.70f), 61.2f)

        // Main Asteroid Belt (180 asteroids between 2.15 and 3.25 AU, rendered via GPU instancing!)
        val rng = Random(2026)
        for (i in 0 until 180) {
            val a = 2.15 + rng.nextDouble() * 1.12
            val e = rng.nextDouble() * 0.16
            val inc = (rng.nextDouble() - 0.5) * 14.0
            val m0 = rng.nextDouble() * 360.0
            val omega = rng.nextDouble() * 360.0
            val bigO = rng.nextDouble() * 360.0
            val mSolar = 1e-12 * (0.5 + rng.nextDouble() * 3.0)
            val (pos, vel) = orbitalElementsToState(sunMass, mSolar, a, e, inc, bigO, omega, m0)
            val shade = (0.42f + rng.nextFloat() * 0.25f)
            bodies.add(
                CelestialBody(
                    id = allocateId(),
                    name = "Asteroid-${i + 1}",
                    type = BodyType.ASTEROID,
                    pos = pos,
                    vel = vel,
                    massSolar = mSolar,
                    radiusKm = 80.0 + rng.nextDouble() * 240.0,
                    temperatureK = 175.0,
                    rotationPeriodHours = 6.0 + rng.nextDouble() * 24.0,
                    albedoColor = Triple(shade, shade * 0.94f, shade * 0.88f),
                    seed = 100f + i,
                    parentBodyId = sunId,
                    isBackgroundCluster = true
                )
            )
        }

        // Halley-like Comet in eccentric orbit
        val cometMass = 1e-14
        val (cPos, cVel) = orbitalElementsToState(sunMass, cometMass, 4.8, 0.76, 18.0, 58.0, 111.0, 325.0)
        bodies.add(
            CelestialBody(
                id = allocateId(),
                name = "Comet Halley-X",
                type = BodyType.COMET,
                pos = cPos,
                vel = cVel,
                massSolar = cometMass,
                radiusKm = 180.0,
                temperatureK = 190.0,
                atmosphereDensity = 0.35f,
                atmosphereColor = Triple(0.45f, 0.85f, 1.0f),
                albedoColor = Triple(0.75f, 0.85f, 0.95f),
                seed = 77.7f,
                parentBodyId = sunId
            )
        )

        return SimulationSnapshot(
            name = title,
            presetId = presetId,
            elapsedYears = 0.0,
            bodies = bodies,
            config = SimulationConfig(
                timeScaleYearsPerSec = if (acceleratedEvolution) 0.08 else 0.04,
                stellarEvolutionEnabled = acceleratedEvolution,
                stellarEvolutionRateGyrPerSec = if (acceleratedEvolution) 0.14 else 0.0
            )
        )
    }

    private fun buildAlphaCentauri(): SimulationSnapshot {
        val bodies = mutableListOf<CelestialBody>()
        val mA = 1.10
        val mB = 0.907
        val totalM = mA + mB
        val a = 23.4
        val e = 0.5179
        val (_, _) = Pair(0, 0)
        val (relPos, relVel) = orbitalElementsToState(mA, mB, a * 0.45, e, 12.0, 20.0, 231.0, 65.0)
        val posA = relPos * (-mB / totalM)
        val velA = relVel * (-mB / totalM)
        val posB = relPos * (mA / totalM)
        val velB = relVel * (mA / totalM)

        val idA = allocateId()
        val idB = allocateId()
        bodies.add(
            CelestialBody(
                id = idA, name = "Alpha Centauri A", type = BodyType.STAR, spectralClass = SpectralClass.G,
                pos = posA, vel = velA, massSolar = mA, radiusKm = 851_000.0, temperatureK = 5790.0,
                luminositySolar = 1.519, albedoColor = kelvinToRgb(5790.0), seed = 1.1f,
                evolutionStage = EvolutionStage.MAIN_SEQUENCE
            )
        )
        bodies.add(
            CelestialBody(
                id = idB, name = "Alpha Centauri B", type = BodyType.STAR, spectralClass = SpectralClass.K,
                pos = posB, vel = velB, massSolar = mB, radiusKm = 601_000.0, temperatureK = 5260.0,
                luminositySolar = 0.50, albedoColor = kelvinToRgb(5260.0), seed = 2.2f,
                evolutionStage = EvolutionStage.MAIN_SEQUENCE
            )
        )

        // Proxima Centauri in wide outer orbit
        val mProx = 0.122
        val (pPos, pVel) = orbitalElementsToState(totalM, mProx, 18.0, 0.25, 22.0, 140.0, 40.0, 200.0)
        val idProx = allocateId()
        bodies.add(
            CelestialBody(
                id = idProx, name = "Proxima Centauri", type = BodyType.STAR, spectralClass = SpectralClass.M,
                pos = pPos, vel = pVel, massSolar = mProx, radiusKm = 107_000.0, temperatureK = 3042.0,
                luminositySolar = 0.0017, albedoColor = kelvinToRgb(3042.0), seed = 3.3f,
                evolutionStage = EvolutionStage.MAIN_SEQUENCE
            )
        )

        // Proxima b exoplanet
        val mPb = 1.07 * AstroConstants.EARTH_MASS_SOLAR
        val (pbPos, pbVel) = orbitalElementsToState(mProx, mPb, 0.45, 0.02, 4.0, 0.0, 0.0, 90.0, pPos, pVel)
        bodies.add(
            CelestialBody(
                id = allocateId(), name = "Proxima b", type = BodyType.EARTH_LIKE,
                pos = pbPos, vel = pbVel, massSolar = mPb, radiusKm = 6800.0, temperatureK = 265.0,
                atmosphereDensity = 0.55f, atmosphereColor = Triple(0.85f, 0.45f, 0.35f),
                albedoColor = Triple(0.30f, 0.50f, 0.72f), seed = 8.4f, hasClouds = true, parentBodyId = idProx
            )
        )

        // Inner planet orbiting Alpha Cen A
        val mAe = 1.8 * AstroConstants.EARTH_MASS_SOLAR
        val (aePos, aeVel) = orbitalElementsToState(mA, mAe, 1.25, 0.04, 2.0, 10.0, 30.0, 15.0, posA, velA)
        bodies.add(
            CelestialBody(
                id = allocateId(), name = "Chiron Prime", type = BodyType.EARTH_LIKE,
                pos = aePos, vel = aeVel, massSolar = mAe, radiusKm = 7400.0, temperatureK = 305.0,
                atmosphereDensity = 0.7f, atmosphereColor = Triple(0.32f, 0.70f, 1.0f),
                albedoColor = Triple(0.22f, 0.54f, 0.80f), seed = 9.1f, hasClouds = true, parentBodyId = idA
            )
        )

        return SimulationSnapshot("Alpha Centauri", SystemPreset.ALPHA_CENTAURI.id, 0.0, bodies, SimulationConfig(timeScaleYearsPerSec = 0.25))
    }

    private fun buildBinaryStars(): SimulationSnapshot {
        val bodies = mutableListOf<CelestialBody>()
        val m1 = 1.25
        val m2 = 0.85
        val total = m1 + m2
        val (relPos, relVel) = orbitalElementsToState(m1, m2, 0.55, 0.08, 0.0, 0.0, 0.0, 0.0)
        val p1 = relPos * (-m2 / total); val v1 = relVel * (-m2 / total)
        val p2 = relPos * (m1 / total); val v2 = relVel * (m1 / total)

        bodies.add(
            CelestialBody(
                id = allocateId(), name = "Helios A (F-Type)", type = BodyType.STAR, spectralClass = SpectralClass.F,
                pos = p1, vel = v1, massSolar = m1, radiusKm = 880_000.0, temperatureK = 6800.0,
                luminositySolar = 2.6, albedoColor = kelvinToRgb(6800.0), seed = 1.4f, evolutionStage = EvolutionStage.MAIN_SEQUENCE
            )
        )
        bodies.add(
            CelestialBody(
                id = allocateId(), name = "Helios B (K-Type)", type = BodyType.STAR, spectralClass = SpectralClass.K,
                pos = p2, vel = v2, massSolar = m2, radiusKm = 550_000.0, temperatureK = 4300.0,
                luminositySolar = 0.35, albedoColor = kelvinToRgb(4300.0), seed = 2.9f, evolutionStage = EvolutionStage.MAIN_SEQUENCE
            )
        )

        val planetData = listOf(
            Triple("Kepler-16b", 1.8, BodyType.GAS_GIANT),
            Triple("Tatooine Prime", 2.9, BodyType.TERRESTRIAL_PLANET),
            Triple("Azure Circumbinary", 4.6, BodyType.EARTH_LIKE)
        )
        planetData.forEachIndexed { idx, (name, a, type) ->
            val m = if (type == BodyType.GAS_GIANT) 180.0 * AstroConstants.EARTH_MASS_SOLAR else 1.4 * AstroConstants.EARTH_MASS_SOLAR
            val (pos, vel) = orbitalElementsToState(total, m, a, 0.03, idx * 2.5, idx * 60.0, 0.0, idx * 115.0)
            bodies.add(
                CelestialBody(
                    id = allocateId(), name = name, type = type, pos = pos, vel = vel,
                    massSolar = m, radiusKm = if (type == BodyType.GAS_GIANT) 62000.0 else 7100.0,
                    temperatureK = 360.0 - idx * 55.0,
                    atmosphereDensity = 0.6f,
                    atmosphereColor = if (type == BodyType.EARTH_LIKE) Triple(0.3f, 0.7f, 1.0f) else Triple(0.9f, 0.65f, 0.4f),
                    albedoColor = if (type == BodyType.EARTH_LIKE) Triple(0.25f, 0.55f, 0.85f) else Triple(0.82f, 0.62f, 0.40f),
                    seed = 12f + idx * 5f,
                    hasClouds = type != BodyType.TERRESTRIAL_PLANET,
                    ringSystem = if (type == BodyType.GAS_GIANT) RingSystem(1.3f, 2.1f, 0.85f, 0.75f, 0.6f, 0.7f, true) else null
                )
            )
        }
        return SimulationSnapshot("Binary Star System", SystemPreset.BINARY_STARS.id, 0.0, bodies, SimulationConfig(timeScaleYearsPerSec = 0.05))
    }

    private fun buildTrappist1(): SimulationSnapshot {
        val bodies = mutableListOf<CelestialBody>()
        val starMass = 0.0898
        val starId = allocateId()
        bodies.add(
            CelestialBody(
                id = starId, name = "TRAPPIST-1", type = BodyType.STAR, spectralClass = SpectralClass.M,
                pos = Vec3d.ZERO, vel = Vec3d.ZERO, massSolar = starMass, radiusKm = 84_180.0,
                temperatureK = 2566.0, luminositySolar = 0.00055, albedoColor = kelvinToRgb(2566.0),
                seed = 5.5f, evolutionStage = EvolutionStage.MAIN_SEQUENCE
            )
        )
        // Compact scaled orbits (scaled 18x from 0.011-0.062 AU for clean 3D clarity while preserving resonant ratios)
        val planets = listOf(
            Triple("TRAPPIST-1b", 0.21, 1.374),
            Triple("TRAPPIST-1c", 0.29, 1.308),
            Triple("TRAPPIST-1d", 0.41, 0.388),
            Triple("TRAPPIST-1e", 0.54, 0.692),
            Triple("TRAPPIST-1f", 0.70, 1.039),
            Triple("TRAPPIST-1g", 0.86, 1.321),
            Triple("TRAPPIST-1h", 1.12, 0.326)
        )
        planets.forEachIndexed { i, (name, a, mEarth) ->
            val mSolar = mEarth * AstroConstants.EARTH_MASS_SOLAR
            val (pos, vel) = orbitalElementsToState(starMass, mSolar, a, 0.006, (i % 3) * 0.8, i * 45.0, 0.0, i * 52.0)
            val isHabitable = i in 3..5
            bodies.add(
                CelestialBody(
                    id = allocateId(),
                    name = name,
                    type = if (isHabitable) BodyType.EARTH_LIKE else BodyType.TERRESTRIAL_PLANET,
                    pos = pos, vel = vel, massSolar = mSolar,
                    radiusKm = 5200.0 + i * 350.0,
                    temperatureK = 400.0 - i * 34.0,
                    atmosphereDensity = if (isHabitable) 0.7f else 0.25f,
                    atmosphereColor = if (isHabitable) Triple(0.35f, 0.75f, 0.95f) else Triple(0.95f, 0.55f, 0.35f),
                    albedoColor = if (isHabitable) Triple(0.24f, 0.55f, 0.78f) else Triple(0.68f, 0.42f, 0.35f),
                    seed = 20f + i * 3.7f,
                    hasClouds = isHabitable,
                    parentBodyId = starId
                )
            )
        }
        return SimulationSnapshot("TRAPPIST-1", SystemPreset.TRAPPIST_1.id, 0.0, bodies, SimulationConfig(timeScaleYearsPerSec = 0.06))
    }

    private fun buildGalaxyCollision(): SimulationSnapshot {
        val bodies = mutableListOf<CelestialBody>()
        val rng = Random(9911)

        fun createSpiralCluster(
            namePrefix: String,
            centerPos: Vec3d,
            bulkVel: Vec3d,
            smbhMass: Double,
            starCount: Int,
            tiltXDeg: Double,
            clockwise: Boolean
        ) {
            val bhId = allocateId()
            bodies.add(
                CelestialBody(
                    id = bhId, name = "$namePrefix Core SMBH", type = BodyType.BLACK_HOLE,
                    pos = centerPos, vel = bulkVel, massSolar = smbhMass, radiusKm = 2.95 * smbhMass,
                    temperatureK = 0.0, accretionActivity = 0.85f, seed = rng.nextFloat() * 100f
                )
            )
            val cosT = cos(Math.toRadians(tiltXDeg))
            val sinT = sin(Math.toRadians(tiltXDeg))
            val sign = if (clockwise) -1.0 else 1.0
            for (i in 0 until starCount) {
                val r = 0.45 + (i.toDouble() / starCount).pow(0.75) * 4.8 + rng.nextDouble() * 0.25
                val armOffset = (i % 2) * PI
                val angle = armOffset + r * 1.15 + (rng.nextDouble() - 0.5) * 0.55
                val lx = r * cos(angle)
                val lz = r * sin(angle)
                val ly = (rng.nextDouble() - 0.5) * 0.18
                // Circular orbital speed around SMBH
                val vOrb = sqrt(AstroConstants.G_AU * smbhMass / r)
                val lvx = -sign * vOrb * sin(angle)
                val lvz = sign * vOrb * cos(angle)
                val lvy = (rng.nextDouble() - 0.5) * 0.3

                // Tilt cluster plane
                val px = lx
                val py = ly * cosT - lz * sinT
                val pz = ly * sinT + lz * cosT
                val vx = lvx
                val vy = lvy * cosT - lvz * sinT
                val vz = lvy * sinT + lvz * cosT

                val temp = 3200.0 + rng.nextDouble() * 14000.0
                val spec = when {
                    temp > 12000 -> SpectralClass.B
                    temp > 7500 -> SpectralClass.A
                    temp > 5200 -> SpectralClass.G
                    else -> SpectralClass.M
                }
                bodies.add(
                    CelestialBody(
                        id = allocateId(),
                        name = "$namePrefix Star-${i + 1}",
                        type = BodyType.STAR,
                        spectralClass = spec,
                        pos = centerPos + Vec3d(px, py, pz),
                        vel = bulkVel + Vec3d(vx, vy, vz),
                        massSolar = 0.8 + rng.nextDouble() * 1.5,
                        radiusKm = 550_000.0,
                        temperatureK = temp,
                        luminositySolar = 1.0,
                        albedoColor = kelvinToRgb(temp),
                        seed = rng.nextFloat() * 50f,
                        isBackgroundCluster = true
                    )
                )
            }
        }

        createSpiralCluster("Andromeda", Vec3d(-3.8, 0.6, -1.2), Vec3d(2.4, -0.3, 0.7), 120.0, 95, 24.0, false)
        createSpiralCluster("Milky Way", Vec3d(3.8, -0.6, 1.2), Vec3d(-2.4, 0.3, -0.7), 100.0, 95, -18.0, true)

        return SimulationSnapshot("Galaxy Collision", SystemPreset.GALAXY_COLLISION.id, 0.0, bodies, SimulationConfig(timeScaleYearsPerSec = 0.08))
    }

    private fun buildBinaryBlackHoles(): SimulationSnapshot {
        val bodies = mutableListOf<CelestialBody>()
        val m1 = 42.0
        val m2 = 28.0
        val total = m1 + m2
        val (relPos, relVel) = orbitalElementsToState(m1, m2, 0.85, 0.04, 5.0, 0.0, 0.0, 0.0)
        val p1 = relPos * (-m2 / total); val v1 = relVel * (-m2 / total)
        val p2 = relPos * (m1 / total); val v2 = relVel * (m1 / total)

        bodies.add(
            CelestialBody(
                id = allocateId(), name = "Cygnus BH-Alpha", type = BodyType.BLACK_HOLE,
                pos = p1, vel = v1, massSolar = m1, radiusKm = 2.95 * m1,
                temperatureK = 0.0, accretionActivity = 1.0f, seed = 1.0f
            )
        )
        bodies.add(
            CelestialBody(
                id = allocateId(), name = "Cygnus BH-Beta", type = BodyType.BLACK_HOLE,
                pos = p2, vel = v2, massSolar = m2, radiusKm = 2.95 * m2,
                temperatureK = 0.0, accretionActivity = 0.9f, seed = 2.0f
            )
        )

        // Orbiting sacrificial stars & gas giants for tidal disruption fireworks!
        val rng = Random(404)
        for (i in 0 until 6) {
            val a = 2.2 + i * 0.75
            val (pos, vel) = orbitalElementsToState(total, 1.0, a, 0.18, (i - 2) * 4.0, i * 45.0, 15.0, i * 60.0)
            val isStar = i % 2 == 0
            bodies.add(
                CelestialBody(
                    id = allocateId(),
                    name = if (isStar) "Accretion Star ${i + 1}" else "Tidal Giant ${i + 1}",
                    type = if (isStar) BodyType.STAR else BodyType.GAS_GIANT,
                    spectralClass = if (isStar) SpectralClass.B else SpectralClass.NONE,
                    pos = pos, vel = vel,
                    massSolar = if (isStar) 1.5 else 250.0 * AstroConstants.EARTH_MASS_SOLAR,
                    radiusKm = if (isStar) 900_000.0 else 72_000.0,
                    temperatureK = if (isStar) 14000.0 else 220.0,
                    luminositySolar = if (isStar) 8.0 else 0.0,
                    atmosphereDensity = if (isStar) 0f else 0.5f,
                    albedoColor = if (isStar) kelvinToRgb(14000.0) else Triple(0.85f, 0.55f, 0.35f),
                    seed = 10f + rng.nextFloat() * 30f
                )
            )
        }

        return SimulationSnapshot("Binary Black Holes", SystemPreset.BINARY_BLACK_HOLES.id, 0.0, bodies, SimulationConfig(timeScaleYearsPerSec = 0.03))
    }
}
