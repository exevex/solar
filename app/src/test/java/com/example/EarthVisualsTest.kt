package com.example

import android.content.Context
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.example.simulation.*
import com.example.render.SurfaceTextureLibrary
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EarthVisualsTest {
    @Test fun `cinematic disk appearance persists without changing gravity`() {
        for (enabled in listOf(false, true)) {
            val snapshot = SolarSystemPresets.buildPreset(SystemPreset.BINARY_STARS).copy(
                config = SimulationConfig(cinematicBlackHoles = enabled))
            val restored = SnapshotSerializer.fromJsonString(SnapshotSerializer.toJsonString(snapshot))
            assertEquals(enabled, restored.config.cinematicBlackHoles)
        }
        val base = SolarSystemPresets.buildPreset(SystemPreset.BINARY_STARS)
        val a = PhysicsEngine(); val b = PhysicsEngine()
        a.loadSnapshot(base.copy(config = base.config.copy(cinematicBlackHoles = false)))
        b.loadSnapshot(base.copy(config = base.config.copy(cinematicBlackHoles = true)))
        repeat(6) { a.step(0.01); b.step(0.01) }
        assertEquals(a.getBodiesSnapshot().map { it.pos to it.vel }, b.getBodiesSnapshot().map { it.pos to it.vel })
    }

    private fun initialize() = EarthGeography.initialize(ApplicationProvider.getApplicationContext<Context>())

    @Test fun `earth uses WGS84 ellipsoid not a generic sphere`() {
        assertEquals(6378.137, EarthGeometry.positionKm(0.0, 0.0).length(), 1e-6)
        assertEquals(6356.752314245, EarthGeometry.positionKm(90.0, 0.0).length(), 1e-6)
        assertTrue(EarthGeometry.positionKm(0.0, 90.0).z < 0)
        assertEquals(0.0, EarthGeometry.uv(90.0, -180.0).first, 1e-9)
        assertEquals(0.0, EarthGeometry.uv(90.0, -180.0).second, 1e-9)
        assertEquals(1.0, EarthGeometry.uv(-90.0, 180.0).second, 1e-9)
    }

    @Test fun `satellite map has correct land ocean and polar landmarks`() {
        initialize()
        val sahara = EarthGeography.colorAt(22.0, 20.0)
        val amazon = EarthGeography.colorAt(-5.0, -65.0)
        val pacific = EarthGeography.colorAt(0.0, -140.0)
        val greenland = EarthGeography.colorAt(74.0, -40.0)
        val antarctica = EarthGeography.colorAt(-85.0, 0.0)
        assertTrue("Sahara must be tan, not sea", Color.red(sahara) > Color.blue(sahara) * 1.4)
        assertTrue("Amazon must be green, not sea", Color.green(amazon) > Color.blue(amazon) * 1.5)
        assertTrue("Pacific must be ocean", Color.blue(pacific) > Color.red(pacific) * 2)
        assertTrue("Greenland north must stay north", Color.red(greenland) > 220)
        assertTrue("Antarctic ice must stay south", Color.red(antarctica) > 200)
        assertTrue("Land relief must be in km, not brightness-derived mountains", EarthGeography.elevationKmAt(30.0, 90.0) in 2.0..6.4)
        assertTrue("Pacific surface height must be sea level", EarthGeography.elevationKmAt(0.0, -140.0) < 0.1)
    }

    @Test fun `earth mesh is outward and exports exact GPU verification input`() {
        initialize()
        val data = EarthGeometry.mesh(180, 360, EarthGeography::elevationKmAt)
        assertTrue(data.vertices.size / 8 <= 65535)
        var checked = 0
        for (i in data.indices.indices step 3) {
            fun position(index: Int): Vec3d {
                val offset = (data.indices[index].toInt() and 65535) * 8
                return Vec3d(data.vertices[offset].toDouble(), data.vertices[offset + 1].toDouble(), data.vertices[offset + 2].toDouble())
            }
            val a = position(i); val b = position(i + 1); val c = position(i + 2)
            val n = (b - a).cross(c - a)
            if (n.lengthSq() > 1e-16) {
                assertTrue("Every nondegenerate triangle must face outward", n.dot(a) > 0.0)
                checked++
            }
        }
        assertTrue(checked > 100_000)
        val dir = File("build/visual-input").apply { mkdirs() }
        DataOutputStream(File(dir, "earth.vertices").outputStream()).use { out -> data.vertices.forEach { out.writeFloat(it) } }
        DataOutputStream(File(dir, "earth.indices").outputStream()).use { out -> data.indices.forEach { out.writeShort(it.toInt() and 65535) } }
    }

    @Test fun `Earth preview uses same NASA geography instead of made up continents`() {
        initialize()
        val earth = SolarSystemPresets.buildPreset(SystemPreset.SOLAR_SYSTEM).bodies.first { it.name == "Earth" }
        val (map, _) = EarthGeography.renderLiveEarthMap(earth.copy(hasClouds = false), 360, 180)
        val previewSahara = map.getPixel(200, 68)
        assertTrue(Color.red(previewSahara) > Color.blue(previewSahara) * 1.4)
        assertTrue(Color.blue(map.getPixel(40, 90)) > Color.red(map.getPixel(40, 90)) * 2)
        val renamed = earth.copy(name = "Home")
        assertEquals("earth", SurfaceTextureLibrary.keyForBody(renamed))
        val saved = SnapshotSerializer.fromJsonString(SnapshotSerializer.toJsonString(
            SimulationSnapshot("rename", "test", 0.0, listOf(renamed), SimulationConfig())))
        assertEquals("earth", saved.bodies.single().surfaceAssetKey)
        assertEquals("earth", SurfaceTextureLibrary.keyForBody(saved.bodies.single()))
    }
}
