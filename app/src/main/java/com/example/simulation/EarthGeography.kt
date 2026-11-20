package com.example.simulation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import com.example.R
import java.nio.ByteBuffer
import kotlin.math.*

/** Shared NASA raster data for the WGS84 globe, land relief, and 2-D preview.
 * No drawn continent blobs. All rasters use lon -180..180 and north-first rows.
 * Clouds and edited-climate overlays are illustrations, not observed weather.
 */
object EarthGeography {
    const val MAP_W = 2048
    const val MAP_H = 1024
    private data class Raster(val colors: IntArray, val heights: ByteArray)
    @Volatile private var raster: Raster? = null

    @Synchronized
    fun initialize(context: Context) {
        if (raster != null) return
        val day = BitmapFactory.decodeResource(context.resources, R.drawable.earth_blue_marble,
            BitmapFactory.Options().apply { inScaled = false; inSampleSize = 2 })
            ?: error("NASA Blue Marble asset could not be decoded")
        val scaled = if (day.width == MAP_W && day.height == MAP_H) day
            else Bitmap.createScaledBitmap(day, MAP_W, MAP_H, true)
        val colors = IntArray(MAP_W * MAP_H)
        scaled.getPixels(colors, 0, MAP_W, 0, 0, MAP_W, MAP_H)
        if (scaled !== day) scaled.recycle()
        day.recycle()
        val height = BitmapFactory.decodeResource(context.resources, R.drawable.earth_elevation,
            BitmapFactory.Options().apply { inScaled = false })
            ?: error("NASA GEBCO elevation asset could not be decoded")
        require(height.width == MAP_W && height.height == MAP_H)
        val hp = IntArray(MAP_W * MAP_H)
        height.getPixels(hp, 0, MAP_W, 0, 0, MAP_W, MAP_H)
        val heights = ByteArray(hp.size) { (Color.red(hp[it])).toByte() }
        height.recycle()
        raster = Raster(colors, heights)
    }

    private fun data(): Raster = checkNotNull(raster) { "Initialize EarthGeography with application context before rendering" }
    private fun index(latitudeDeg: Double, longitudeDeg: Double): Int {
        val lon = ((longitudeDeg + 180.0) % 360.0 + 360.0) % 360.0
        val x = (lon / 360.0 * MAP_W).toInt().coerceIn(0, MAP_W - 1)
        val y = ((90.0 - latitudeDeg) / 180.0 * MAP_H).toInt().coerceIn(0, MAP_H - 1)
        return y * MAP_W + x
    }

    fun colorAt(latitudeDeg: Double, longitudeDeg: Double): Int = data().colors[index(latitudeDeg, longitudeDeg)]
    fun elevationKmAt(latitudeDeg: Double, longitudeDeg: Double): Double =
        (data().heights[index(latitudeDeg, longitudeDeg)].toInt() and 255) / 255.0 * 6.4

    fun createGlByteBuffer(): ByteBuffer = ByteBuffer.allocateDirect(MAP_W * MAP_H).apply {
        put(data().heights); position(0)
    }

    data class EarthSurfaceMetrics(
        val liquidOceanPct: Int,
        val iceCoverPct: Int,
        val moltenPct: Int,
        val habitabilityPct: Int,
        val climateLabel: String
    )

    fun renderLiveEarthMap(body: CelestialBody, width: Int = 256, height: Int = 128): Pair<Bitmap, EarthSurfaceMetrics> {
        require(width > 0 && height > 0)
        val actualEarth = body.surfaceAssetKey == "earth" || body.archetype == BodyArchetype.EARTH
        val pixels = IntArray(width * height)
        val temp = body.temperatureK
        val freeze = ((274.0 - temp) / 85.0).coerceIn(0.0, 1.0)
        val heat = max(body.crustHeat.toDouble(), max(body.tidalStress.toDouble(), ((temp - 950.0) / 1400.0).coerceIn(0.0, 1.0)))
        val dry = ((temp - 330.0) / 160.0).coerceIn(0.0, 1.0)
        var oceanArea = 0.0; var iceArea = 0.0; var moltenArea = 0.0; var totalArea = 0.0
        for (y in 0 until height) {
            val lat = 90.0 - (y + 0.5) / height * 180.0
            val area = cos(Math.toRadians(lat))
            for (x in 0 until width) {
                val lon = (x + 0.5) / width * 360.0 - 180.0
                val c = if (actualEarth) colorAt(lat, lon) else {
                    val terrain = sin(Math.toRadians(lon * 2.8) + body.seed) * cos(Math.toRadians(lat * 3.7))
                    if (terrain > 0.15) Color.rgb(67, 105, 41) else Color.rgb(6, 42, 78)
                }
                var r = Color.red(c) / 255.0; var g = Color.green(c) / 255.0; var b = Color.blue(c) / 255.0
                val ocean = b > r * 1.25 && b > g * 1.08
                val ice = freeze * smoothStep(0.05, 0.65, abs(sin(Math.toRadians(lat))) + freeze * 0.65)
                r = lerp(r, 0.88, ice); g = lerp(g, 0.93, ice); b = lerp(b, 0.97, ice)
                r = lerp(r, if (ocean) 0.17 else 0.42, dry * 0.7)
                g = lerp(g, 0.22, dry * 0.7); b = lerp(b, 0.13, dry * 0.7)
                val lonR = Math.toRadians(lon); val latR = Math.toRadians(lat)
                val sx = cos(latR) * cos(lonR); val sy = sin(latR); val sz = -cos(latR) * sin(lonR)
                var craterHeat = 0.0
                for (crater in body.craters) {
                    val ang = acos((sx * crater.nx + sy * crater.ny + sz * crater.nz).coerceIn(-1.0, 1.0))
                    val bowl = 1.0 - smoothStep(0.0, crater.radiusRad.toDouble(), ang)
                    r *= 1.0 - bowl * 0.55; g *= 1.0 - bowl * 0.55; b *= 1.0 - bowl * 0.55
                    craterHeat = max(craterHeat, bowl * crater.heat)
                }
                val molten = max(heat, craterHeat).coerceIn(0.0, 1.0)
                r = lerp(r, 1.0, molten); g = lerp(g, 0.34, molten); b = lerp(b, 0.035, molten)
                pixels[y * width + x] = Color.rgb((r * 255).roundToInt(), (g * 255).roundToInt(), (b * 255).roundToInt())
                totalArea += area
                if (molten > 0.25) moltenArea += area
                else if (ice > 0.4 || (r > 0.75 && g > 0.75 && b > 0.75)) iceArea += area
                else if (ocean && dry < 0.9) oceanArea += area
            }
        }
        val habitability = ((1.0 - abs(temp - 288.0) / 85.0).coerceIn(0.0, 1.0) *
            (1.0 - abs(body.atmosphereDensity - 0.65) / 0.85).coerceIn(0.0, 1.0) *
            (1.0 - heat * 2.5).coerceIn(0.0, 1.0) * 100.0).roundToInt()
        val climate = when {
            heat > 0.25 -> "Molten overlay (approximate)"
            temp < 210 -> "Snowball overlay (approximate)"
            temp > 390 -> "Runaway greenhouse (approximate)"
            temp > 330 -> "Arid overlay (approximate)"
            else -> if (actualEarth) "NASA September 2004 surface" else "Procedural exoplanet"
        }
        val bmp = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        return bmp to EarthSurfaceMetrics((100 * oceanArea / totalArea).roundToInt(),
            (100 * iceArea / totalArea).roundToInt(), (100 * moltenArea / totalArea).roundToInt(), habitability, climate)
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)
    private fun smoothStep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
