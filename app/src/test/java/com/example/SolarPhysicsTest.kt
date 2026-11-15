package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.simulation.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SolarPhysicsTest {

    @Test
    fun `app name matches Solar Sandbox`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Solar Sandbox", context.getString(R.string.app_name))
    }

    @Test
    fun `earth completes 1 AU orbit with Velocity Verlet accurately`() {
        val engine = PhysicsEngine()
        // Set time scale to 0.5 yr/sec and step 2.0 real seconds = 1.0 Julian year
        engine.updateConfig { it.copy(timeScaleYearsPerSec = 0.5, integrator = IntegratorMode.VELOCITY_VERLET) }
        val earthStart = engine.getBodiesSnapshot().first { it.name == "Earth" }
        val startDist = earthStart.pos.length()
        assertEquals(1.0, startDist, 0.03)

        // Advance 1.0 year in 50 steps of 0.04s
        repeat(50) {
            engine.step(0.04)
        }
        val earthEnd = engine.getBodiesSnapshot().first { it.name == "Earth" }
        val endDist = earthEnd.pos.length()
        assertEquals(startDist, endDist, 0.04)
        // Earth should return near its starting position after 1 full year
        val drift = (earthEnd.pos - earthStart.pos).length()
        assertTrue("Expected Earth orbital return drift < 0.1 AU, got $drift", drift < 0.10)
    }

    @Test
    fun `RK4 integrator preserves binary star stability`() {
        val engine = PhysicsEngine()
        engine.loadSnapshot(SolarSystemPresets.buildPreset(SystemPreset.BINARY_STARS))
        engine.updateConfig { it.copy(integrator = IntegratorMode.RK4, timeScaleYearsPerSec = 0.1) }
        repeat(30) { engine.step(0.033) }
        val stars = engine.getBodiesSnapshot().filter { it.type == BodyType.STAR }
        assertEquals(2, stars.size)
        stars.forEach { s ->
            assertFalse(s.pos.x.isNaN())
            assertFalse(s.vel.y.isNaN())
        }
    }

    @Test
    fun `all 7 cosmic presets build with valid finite coordinates`() {
        for (preset in SystemPreset.entries) {
            val snap = SolarSystemPresets.buildPreset(preset)
            assertTrue("Preset ${preset.name} should have bodies", snap.bodies.isNotEmpty())
            for (b in snap.bodies) {
                assertFalse("NaN pos in ${preset.name}/${b.name}", b.pos.x.isNaN() || b.pos.y.isNaN() || b.pos.z.isNaN())
                assertFalse("NaN vel in ${preset.name}/${b.name}", b.vel.x.isNaN() || b.vel.y.isNaN() || b.vel.z.isNaN())
                assertTrue("Non-positive mass in ${b.name}", b.massSolar > 0.0)
            }
        }
    }

    @Test
    fun `collision creates crater, crust heat, and merges mass`() {
        val engine = PhysicsEngine()
        val p1 = CelestialBody(
            id = 1L, name = "Target", type = BodyType.TERRESTRIAL_PLANET,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO,
            massSolar = AstroConstants.EARTH_MASS_SOLAR, radiusKm = 6371.0, temperatureK = 280.0
        )
        val p2 = CelestialBody(
            id = 2L, name = "Impactor", type = BodyType.ASTEROID,
            pos = Vec3d(0.01, 0.0, 0.0), vel = Vec3d(-2.0, 0.0, 0.0),
            massSolar = AstroConstants.EARTH_MASS_SOLAR * 0.02, radiusKm = 1500.0, temperatureK = 200.0
        )
        engine.loadSnapshot(SimulationSnapshot("Test", "test", 0.0, listOf(p1, p2), SimulationConfig(timeScaleYearsPerSec = 0.05)))
        repeat(15) { engine.step(0.02) }
        val remaining = engine.getBodiesSnapshot()
        assertEquals(1, remaining.size)
        val survivor = remaining.first()
        assertTrue(survivor.craters.isNotEmpty())
        assertTrue(survivor.crustHeat > 0.1f)
        assertTrue(survivor.massSolar > AstroConstants.EARTH_MASS_SOLAR)
    }

    @Test
    fun `black hole tidal disruption shreds victim and grows black hole mass`() {
        val engine = PhysicsEngine()
        val bh = CelestialBody(
            id = 1L, name = "BH", type = BodyType.BLACK_HOLE,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO,
            massSolar = 100.0, radiusKm = 295.0, temperatureK = 0.0
        )
        val victim = CelestialBody(
            id = 2L, name = "VictimStar", type = BodyType.STAR,
            pos = Vec3d(0.08, 0.0, 0.0), vel = Vec3d(0.0, 0.0, 1.0),
            massSolar = 1.0, radiusKm = 696340.0, temperatureK = 5778.0
        )
        engine.loadSnapshot(SimulationSnapshot("TDE", "tde", 0.0, listOf(bh, victim), SimulationConfig(timeScaleYearsPerSec = 0.2)))
        repeat(40) { engine.step(0.03) }
        val bhAfter = engine.getBodiesSnapshot().first { it.type == BodyType.BLACK_HOLE }
        assertTrue("Black hole should gain mass via tidal disruption/absorption", bhAfter.massSolar > 100.0)
    }

    @Test
    fun `stellar evolution transitions Sun to Red Giant and White Dwarf`() {
        val engine = PhysicsEngine()
        engine.loadSnapshot(SolarSystemPresets.buildPreset(SystemPreset.SUN_RED_GIANT))
        engine.updateConfig { it.copy(stellarEvolutionEnabled = true, stellarEvolutionRateGyrPerSec = 2.0) }
        repeat(20) { engine.step(0.05) } // +2.0 Gyr -> 11.8 Gyr
        val sun = engine.getBodiesSnapshot().first()
        assertTrue(
            "Sun should evolve past Main Sequence to Red Giant or White Dwarf, got ${sun.type}",
            sun.type == BodyType.RED_GIANT || sun.type == BodyType.WHITE_DWARF
        )
    }

    @Test
    fun `snapshot JSON serialization and SaveLoadRepository round-trip`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val repo = SaveLoadRepository(context.filesDir)
        val snap = SolarSystemPresets.buildPreset(SystemPreset.TRAPPIST_1)
        val saved = repo.saveSnapshot(snap)
        val loaded = repo.loadSave(saved.fileName)
        assertNotNull(loaded)
        assertEquals(snap.bodies.size, loaded!!.bodies.size)
        assertEquals(snap.presetId, loaded.presetId)
        assertEquals(snap.bodies.first().name, loaded.bodies.first().name)
        repo.deleteSave(saved.fileName)
    }

    @Test
    fun `slingshot trajectory prediction returns future orbital points`() {
        val engine = PhysicsEngine()
        val pts = engine.predictTrajectory(Vec3d(1.5, 0.0, 0.0), Vec3d(0.0, 0.0, 5.0), steps = 32)
        assertEquals(32, pts.size)
        assertTrue((pts.last() - pts.first()).length() > 0.1)
    }

    @Test
    fun `black hole tidal disruption produces spiral filaments, spaghettification, and telemetry status`() {
        val engine = PhysicsEngine()
        val bh = CelestialBody(
            id = 1L, name = "Gargantua", type = BodyType.BLACK_HOLE,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO,
            massSolar = 12.0, radiusKm = 35.4, temperatureK = 0.0
        )
        val star = CelestialBody(
            id = 2L, name = "Sun", type = BodyType.STAR,
            pos = Vec3d(0.18, 0.0, 0.0), vel = Vec3d(0.0, 0.0, 8.0),
            massSolar = 1.0, radiusKm = 696340.0, temperatureK = 5778.0, luminositySolar = 1.0
        )
        engine.loadSnapshot(SimulationSnapshot("Spaghettify", "test", 0.0, listOf(bh, star), SimulationConfig(timeScaleYearsPerSec = 0.02)))
        engine.step(0.02)
        val frame = engine.buildRenderFrameState(60f)
        assertTrue("Expected visible tidal stream filaments", frame.tidalFilaments.isNotEmpty())
        val starNow = frame.bodies.first { it.id == 2L }
        assertTrue("Expected star tidalStretchAmount > 0.1, got ${starNow.tidalStretchAmount}", starNow.tidalStretchAmount > 0.1f)
        val tel = engine.computeTelemetry(starNow)
        assertTrue("Expected Spaghettifying in telemetry status: ${tel.statusSummary}", tel.statusSummary.contains("Spaghettifying", ignoreCase = true))
    }

    @Test
    fun `smaller black hole consumes heavier star on direct collision`() {
        val engine = PhysicsEngine()
        val bh = CelestialBody(
            id = 1L, name = "Stellar BH", type = BodyType.BLACK_HOLE,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO,
            massSolar = 12.0, radiusKm = 35.4, temperatureK = 0.0
        )
        val heavyStar = CelestialBody(
            id = 2L, name = "Blue Supergiant", type = BodyType.STAR,
            pos = Vec3d(0.005, 0.0, 0.0), vel = Vec3d(-1.0, 0.0, 0.0),
            massSolar = 25.0, radiusKm = 2_000_000.0, temperatureK = 22000.0, luminositySolar = 5000.0
        )
        engine.loadSnapshot(SimulationSnapshot("BHWin", "test", 0.0, listOf(bh, heavyStar), SimulationConfig(timeScaleYearsPerSec = 0.05)))
        repeat(10) { engine.step(0.02) }
        val bodies = engine.getBodiesSnapshot()
        assertEquals(1, bodies.size)
        val survivor = bodies.first()
        assertEquals(BodyType.BLACK_HOLE, survivor.type)
        assertTrue("Black hole should gain mass and record absorbedMassSolar", survivor.massSolar >= 36.5 && survivor.absorbedMassSolar > 20.0)
    }

    @Test
    fun `snapshot serialization preserves BodyArchetype and absorbedMassSolar`() {
        val snap = SolarSystemPresets.buildPreset(SystemPreset.SOLAR_SYSTEM)
        val bh = CelestialBody(
            id = 999L, name = "Test BH", type = BodyType.BLACK_HOLE,
            archetype = BodyArchetype.GENERIC_ROCK,
            pos = Vec3d(5.0, 0.0, 0.0), vel = Vec3d.ZERO,
            massSolar = 20.0, radiusKm = 59.0, temperatureK = 0.0, absorbedMassSolar = 3.25
        )
        val modified = snap.copy(bodies = snap.bodies + bh)
        val json = SnapshotSerializer.toJsonString(modified)
        val decoded = SnapshotSerializer.fromJsonString(json)
        val decodedJupiter = decoded.bodies.first { it.name == "Jupiter" }
        val decodedBh = decoded.bodies.first { it.id == 999L }
        assertEquals(BodyArchetype.JUPITER, decodedJupiter.archetype)
        assertEquals(3.25, decodedBh.absorbedMassSolar, 1e-4)
    }

    @Test
    fun `coupled star mass increase scales luminosity, temperature, and spectral class`() {
        val sun = CelestialBody(
            id = 1L, name = "Sun", type = BodyType.STAR, spectralClass = SpectralClass.G,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO, massSolar = 1.0, radiusKm = 696_340.0,
            temperatureK = 5778.0, luminositySolar = 1.0
        )
        val (bigger, _) = PhysicsEngine.applyCoupledAstrophysics(sun, sun.copy(massSolar = 5.0))
        assertTrue("Expected luminosity > 100 L_sun for 5 M_sun star, got ${bigger.luminositySolar}", bigger.luminositySolar > 100.0)
        assertTrue("Expected hotter temperature for 5 M_sun star, got ${bigger.temperatureK}", bigger.temperatureK > 10000.0)
        assertEquals(SpectralClass.B, bigger.spectralClass)

        val wd = CelestialBody(
            id = 2L, name = "WD", type = BodyType.WHITE_DWARF,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO, massSolar = 0.85, radiusKm = 7000.0,
            temperatureK = 26000.0, luminositySolar = 0.06
        )
        val (ns, wdMsg) = PhysicsEngine.applyCoupledAstrophysics(wd, wd.copy(massSolar = 1.6))
        assertEquals(BodyType.NEUTRON_STAR, ns.type)
        assertNotNull(wdMsg)

        val (bh, nsMsg) = PhysicsEngine.applyCoupledAstrophysics(ns, ns.copy(massSolar = 3.2))
        assertEquals(BodyType.BLACK_HOLE, bh.type)
        assertNotNull(nsMsg)
    }

    @Test
    fun `tiny asteroid hitting sun produces zero craters and zero explosion particles`() {
        val engine = PhysicsEngine()
        val sun = CelestialBody(
            id = 1L, name = "Sun", type = BodyType.STAR, spectralClass = SpectralClass.G,
            pos = Vec3d.ZERO, vel = Vec3d.ZERO, massSolar = 1.0, radiusKm = 696_340.0,
            temperatureK = 5778.0, luminositySolar = 1.0
        )
        val speck = CelestialBody(
            id = 2L, name = "Tiny Asteroid", type = BodyType.ASTEROID,
            pos = Vec3d(0.01, 0.0, 0.0), vel = Vec3d(-3.0, 0.0, 0.0),
            massSolar = 1e-10, radiusKm = 450.0, temperatureK = 180.0
        )
        engine.loadSnapshot(SimulationSnapshot("SunImpact", "test", 0.0, listOf(sun, speck), SimulationConfig(timeScaleYearsPerSec = 0.05)))
        repeat(10) { engine.step(0.02) }
        val frame = engine.buildRenderFrameState(60f)
        assertEquals(1, frame.bodies.size)
        val survivor = frame.bodies.first()
        assertTrue("Sun must not accumulate craters", survivor.craters.isEmpty())
        assertTrue("Small asteroid hitting Sun should not spray rocky explosion particles", frame.particles.isEmpty())
    }

    @Test
    fun `snapshot serialization preserves showHabitableZone toggle`() {
        val snap = SolarSystemPresets.buildPreset(SystemPreset.SOLAR_SYSTEM).let {
            it.copy(config = it.config.copy(showHabitableZone = true))
        }
        val json = SnapshotSerializer.toJsonString(snap)
        val decoded = SnapshotSerializer.fromJsonString(json)
        assertTrue(decoded.config.showHabitableZone)
    }

    @Test
    fun `live Earth geography map responds to temperature changes`() {
        val baseEarth = SolarSystemPresets.buildPreset(SystemPreset.SOLAR_SYSTEM).bodies.first { it.name == "Earth" }
        val (_, temperate) = EarthGeography.renderLiveEarthMap(baseEarth)
        val (_, frozen) = EarthGeography.renderLiveEarthMap(baseEarth.copy(temperatureK = 175.0))
        val (_, magma) = EarthGeography.renderLiveEarthMap(baseEarth.copy(temperatureK = 1600.0))

        assertTrue("Temperate Earth should have high habitability, got ${temperate.habitabilityPct}", temperate.habitabilityPct >= 70)
        assertTrue("Frozen Earth should have high ice cover, got ${frozen.iceCoverPct}", frozen.iceCoverPct >= 80)
        assertTrue("Hot magma Earth should show molten coverage, got ${magma.moltenPct}", magma.moltenPct > 10)
    }
}
