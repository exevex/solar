package com.example.simulation

import android.graphics.Bitmap
import android.graphics.Color
import java.nio.ByteBuffer
import kotlin.math.*

/**
 * Deterministic 128x64 real-world Earth continent & elevation heightmap + live 2D Equirectangular Map renderer.
 * Shared between the OpenGL ES 3.0 3D planet shader (with 3D fBM shoreline & mountain detail) and the
 * Compose Live Earth Surface Map Preview so both stay 100% geographically synchronized as climate/temp/impacts change.
 */
object EarthGeography {

    const val MAP_W = 512
    const val MAP_H = 256

    // Normalized elevation grid in [0f..1f]: < 0.48 = ocean/shelf, >= 0.48 = land, > 0.72 = mountains
    val elevationGrid: FloatArray by lazy { buildEarthElevationGrid() }

    /**
     * Packs elevationGrid into an unsigned byte buffer [0..255] for GL_TEXTURE_2D (GL_R8).
     */
    fun createGlByteBuffer(): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(MAP_W * MAP_H)
        for (v in elevationGrid) {
            buf.put((v.coerceIn(0f, 1f) * 255f).roundToInt().toByte())
        }
        buf.position(0)
        return buf
    }

    private data class LandBlob(
        val lonDeg: Double,
        val latDeg: Double,
        val rxDeg: Double,
        val ryDeg: Double,
        val rotDeg: Double = 0.0,
        val weight: Double = 0.52,
        val mountainBoost: Double = 0.0
    )

    // Calibrated continental landmasses & mountain chains in (lon [-180..180], lat [-90..90])
    private val landFeatures = listOf(
        // North America
        LandBlob(-115.0, 56.0, 34.0, 14.0, -15.0, 0.56, 0.14),
        LandBlob(-96.0, 39.5, 25.0, 13.5, 8.0, 0.56, 0.08),
        LandBlob(-103.5, 22.5, 8.5, 9.5, -32.0, 0.49, 0.11), // Mexico
        LandBlob(-111.0, 26.0, 2.2, 6.0, -34.0, 0.34, 0.04), // Baja California
        LandBlob(-81.5, 27.5, 2.2, 4.2, -12.0, 0.36, 0.01), // Florida
        LandBlob(-150.0, 63.5, 14.5, 5.8, 8.0, 0.49, 0.12), // Alaska
        LandBlob(-84.0, 11.0, 5.5, 4.2, -42.0, 0.37, 0.05), // Central America bridge
        LandBlob(-114.0, 44.0, 5.5, 16.5, -18.0, 0.25, 0.34), // Rockies ridge
        LandBlob(-69.0, 55.0, 9.0, 7.0, 12.0, 0.46, 0.05),   // Quebec/Labrador
        // Greenland & Iceland
        LandBlob(-42.0, 72.5, 15.5, 8.5, 18.0, 0.55, 0.15),
        LandBlob(-18.5, 65.0, 4.0, 2.2, 10.0, 0.34, 0.08),
        // South America
        LandBlob(-61.0, -4.0, 16.5, 13.5, -12.0, 0.58, 0.08),
        LandBlob(-51.0, -14.0, 13.5, 11.0, 15.0, 0.54, 0.06),
        LandBlob(-65.0, -28.0, 7.8, 13.0, 10.0, 0.50, 0.12),
        LandBlob(-69.0, -45.0, 4.2, 11.0, 5.0, 0.44, 0.18), // Patagonia
        LandBlob(-72.0, -18.0, 3.6, 22.0, -6.0, 0.26, 0.38), // Andes chain
        // Africa
        LandBlob(8.0, 21.0, 23.0, 13.0, 0.0, 0.58, 0.05),   // Sahara / North Africa
        LandBlob(24.0, 6.0, 17.5, 14.0, -5.0, 0.58, 0.08),  // Central Africa
        LandBlob(25.0, -18.0, 11.0, 13.0, 6.0, 0.55, 0.12), // Southern Africa
        LandBlob(44.0, 9.0, 7.0, 6.0, 25.0, 0.46, 0.14),    // Horn of Africa
        LandBlob(46.8, -19.0, 2.8, 6.2, 16.0, 0.40, 0.08),  // Madagascar
        // Europe
        LandBlob(13.0, 49.0, 17.5, 8.2, 12.0, 0.52, 0.12),
        LandBlob(-4.0, 40.2, 5.8, 4.0, 0.0, 0.45, 0.10),    // Iberia
        LandBlob(12.5, 42.5, 2.2, 4.8, -32.0, 0.38, 0.12),  // Italy
        LandBlob(16.0, 62.5, 9.5, 7.5, 32.0, 0.48, 0.15),   // Scandinavia
        LandBlob(-2.5, 54.0, 3.0, 4.2, 5.0, 0.39, 0.05),    // Great Britain
        LandBlob(-8.0, 53.3, 1.8, 2.2, 10.0, 0.33, 0.03),   // Ireland
        // Asia & Middle East
        LandBlob(46.0, 23.5, 10.5, 8.5, -22.0, 0.54, 0.08), // Arabia
        LandBlob(64.0, 56.0, 36.0, 14.5, 4.0, 0.58, 0.08),  // Russia / Siberia west
        LandBlob(112.0, 60.0, 37.0, 12.5, -6.0, 0.56, 0.12),// Siberia east
        LandBlob(160.0, 56.0, 3.5, 6.5, 22.0, 0.36, 0.14),  // Kamchatka
        LandBlob(88.0, 39.0, 28.0, 13.5, 5.0, 0.60, 0.25),  // Central Asia / Tibet
        LandBlob(85.0, 31.0, 14.0, 6.2, -12.0, 0.32, 0.48), // Himalayas peak
        LandBlob(78.0, 20.5, 8.0, 10.5, 0.0, 0.54, 0.06),   // India
        LandBlob(80.7, 7.8, 1.6, 2.2, 0.0, 0.33, 0.04),    // Sri Lanka
        LandBlob(111.0, 31.5, 15.0, 11.5, 10.0, 0.56, 0.08),// East China
        LandBlob(102.5, 15.0, 6.5, 8.2, -15.0, 0.46, 0.06), // SE Asia / Indochina
        LandBlob(138.0, 36.5, 3.4, 6.2, 38.0, 0.39, 0.14),  // Japan
        LandBlob(121.5, 12.5, 2.2, 4.5, -10.0, 0.34, 0.06), // Philippines
        LandBlob(102.0, -0.5, 6.0, 2.4, -38.0, 0.36, 0.08), // Sumatra
        LandBlob(114.5, 0.8, 5.0, 4.2, 0.0, 0.38, 0.06),    // Borneo
        LandBlob(141.0, -5.0, 7.5, 4.2, -18.0, 0.38, 0.10), // New Guinea
        // Australia & NZ
        LandBlob(134.0, -25.0, 17.5, 10.5, -8.0, 0.56, 0.06),
        LandBlob(172.0, -42.0, 2.8, 5.0, 32.0, 0.37, 0.15),
        // Antarctica
        LandBlob(0.0, -83.0, 180.0, 11.0, 0.0, 0.62, 0.22),
        LandBlob(75.0, -73.0, 65.0, 6.2, 0.0, 0.48, 0.14)
    )

    private val oceanCuts = listOf(
        // Mediterranean, Red Sea, Black Sea, Caspian Sea, Persian Gulf, Hudson Bay, Gulf of Mexico, Baltic
        LandBlob(18.0, 35.5, 18.0, 4.3, -8.0, -0.46, 0.0),
        LandBlob(38.0, 20.0, 3.2, 7.8, -28.0, -0.36, 0.0),
        LandBlob(34.0, 43.2, 5.5, 2.2, 0.0, -0.32, 0.0),
        LandBlob(51.0, 41.5, 2.5, 4.8, 0.0, -0.30, 0.0),
        LandBlob(-85.0, 59.0, 9.2, 6.5, 0.0, -0.44, 0.0),
        LandBlob(-90.0, 24.5, 9.5, 5.8, 0.0, -0.44, 0.0),
        LandBlob(51.0, 26.5, 4.2, 2.8, -30.0, -0.28, 0.0),
        LandBlob(19.0, 58.0, 4.5, 4.0, 25.0, -0.28, 0.0)
    )

    private fun buildEarthElevationGrid(): FloatArray {
        val grid = FloatArray(MAP_W * MAP_H)
        for (y in 0 until MAP_H) {
            val lat = 90.0 - ((y + 0.5) / MAP_H) * 180.0
            for (x in 0 until MAP_W) {
                val lon = ((x + 0.5) / MAP_W) * 360.0 - 180.0
                var h = 0.22 // Abyssal ocean baseline
                var mountain = 0.0
                for (b in landFeatures) {
                    val dLon = wrapLonDelta(lon - b.lonDeg)
                    val dLat = lat - b.latDeg
                    val rad = Math.toRadians(b.rotDeg)
                    val cr = cos(rad); val sr = sin(rad)
                    val u = (dLon * cr + dLat * sr) / b.rxDeg
                    val v = (-dLon * sr + dLat * cr) / b.ryDeg
                    val r2 = u * u + v * v
                    if (r2 < 2.6) {
                        val g = exp(-r2 * 1.45)
                        h = max(h, 0.22 + b.weight * g)
                        mountain = max(mountain, b.mountainBoost * g)
                    }
                }
                for (c in oceanCuts) {
                    val dLon = wrapLonDelta(lon - c.lonDeg)
                    val dLat = lat - c.latDeg
                    val rad = Math.toRadians(c.rotDeg)
                    val cr = cos(rad); val sr = sin(rad)
                    val u = (dLon * cr + dLat * sr) / c.rxDeg
                    val v = (-dLon * sr + dLat * cr) / c.ryDeg
                    val r2 = u * u + v * v
                    if (r2 < 2.2) {
                        h += c.weight * exp(-r2 * 1.7)
                    }
                }
                // Deterministic multi-harmonic coastline irregularity
                val lonR = Math.toRadians(lon)
                val latR = Math.toRadians(lat)
                val coastWobble = 0.042 * sin(lonR * 7.0 + latR * 5.0) +
                    0.024 * cos(lonR * 13.0 - latR * 9.0) +
                    0.012 * sin(lonR * 23.0 + latR * 17.0)
                grid[y * MAP_W + x] = (h + mountain + coastWobble).toFloat().coerceIn(0.0f, 1.0f)
            }
        }
        return grid
    }

    private fun wrapLonDelta(d: Double): Double {
        var v = d
        while (v > 180.0) v -= 360.0
        while (v < -180.0) v += 360.0
        return v
    }

    data class EarthSurfaceMetrics(
        val liquidOceanPct: Int,
        val iceCoverPct: Int,
        val moltenPct: Int,
        val habitabilityPct: Int,
        val climateLabel: String
    )

    /**
     * Renders a crisp 2D equirectangular Earth Surface Map Bitmap driven directly by live physical state.
     */
    fun renderLiveEarthMap(
        body: CelestialBody,
        width: Int = 256,
        height: Int = 128
    ): Pair<Bitmap, EarthSurfaceMetrics> {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val pixels = IntArray(width * height)

        val tempK = body.temperatureK
        val atmo = body.atmosphereDensity.toDouble()
        val crustHeat = body.crustHeat.toDouble()
        val tidal = body.tidalStress.toDouble()

        val freezeShift = ((275.0 - tempK) / 85.0).coerceIn(0.0, 1.2)
        val heatDry = ((tempK - 315.0) / 140.0).coerceIn(0.0, 1.0)
        val boilOcean = ((tempK - 373.0) / 110.0).coerceIn(0.0, 1.0)
        val lavaHeat = max(crustHeat, max(tidal * 0.85, ((tempK - 780.0) / 1200.0).coerceIn(0.0, 1.0)))

        var oceanPx = 0
        var icePx = 0
        var moltenPx = 0
        val totalPx = width * height

        for (py in 0 until height) {
            val gy = ((py.toDouble() / height) * (MAP_H - 1)).coerceIn(0.0, (MAP_H - 1).toDouble())
            val y0 = gy.toInt()
            val y1 = min(y0 + 1, MAP_H - 1)
            val fy = gy - y0

            val lat = 90.0 - ((py + 0.5) / height) * 180.0
            val absLatNorm = abs(lat) / 90.0
            val latR = Math.toRadians(lat)
            val cosLat = cos(latR)
            val sinLat = sin(latR)

            for (px in 0 until width) {
                val gx = ((px.toDouble() / width) * (MAP_W - 1)).coerceIn(0.0, (MAP_W - 1).toDouble())
                val x0 = gx.toInt()
                val x1 = (x0 + 1) % MAP_W
                val fx = gx - x0

                val h00 = elevationGrid[y0 * MAP_W + x0]
                val h10 = elevationGrid[y0 * MAP_W + x1]
                val h01 = elevationGrid[y1 * MAP_W + x0]
                val h11 = elevationGrid[y1 * MAP_W + x1]
                val macroH = ((h00 * (1 - fx) + h10 * fx) * (1 - fy) + (h01 * (1 - fx) + h11 * fx) * fy)

                val lonR = Math.toRadians(((px + 0.5) / width) * 360.0 - 180.0)
                val sx = cosLat * cos(lonR)
                val sy = sinLat
                val sz = cosLat * sin(lonR)

                val fineNoise = 0.5 + 0.3 * sin(sx * 11.0 + sy * 7.0) * cos(sz * 13.0) +
                    0.2 * sin(sx * 23.0 - sz * 19.0)
                val elev = macroH + (fineNoise - 0.5) * 0.07

                // Polar & global freeze threshold shifts with planet temperature!
                val iceLatThreshold = (0.76 - freezeShift * 0.72 + (fineNoise - 0.5) * 0.08).coerceIn(-0.1, 1.1)
                val isFrozen = (absLatNorm > iceLatThreshold && lavaHeat < 0.25) || (tempK < 185.0 && lavaHeat < 0.25)

                var r: Double
                var g: Double
                var b: Double

                val landLine = 0.47 + boilOcean * 0.26
                if (elev < landLine && boilOcean < 0.92) {
                    if (isFrozen) {
                        r = 0.80; g = 0.90; b = 0.97
                        icePx++
                    } else {
                        val depth = ((landLine - elev) / 0.28).coerceIn(0.0, 1.0)
                        // Deep ocean -> shallow turquoise -> heated green-teal if boiling
                        r = lerp(0.07, 0.02, depth) + heatDry * 0.14
                        g = lerp(0.42, 0.14, depth) + heatDry * 0.06
                        b = lerp(0.68, 0.36, depth) * (1.0 - boilOcean * 0.55)
                        oceanPx++
                    }
                } else {
                    if (isFrozen) {
                        r = 0.88; g = 0.92; b = 0.96
                        icePx++
                    } else {
                        val hNorm = ((elev - landLine) / 0.45).coerceIn(0.0, 1.0)
                        val aridity = (1.0 - absLatNorm * 1.35).coerceIn(0.0, 1.0) * (0.4 + fineNoise * 0.6)
                        val aridBoost = (aridity + heatDry * 0.85).coerceIn(0.0, 1.0)
                        // Lush green forest -> savanna -> desert tan -> scorched rust -> mountain stone
                        val vegR = lerp(0.11, 0.68, aridBoost)
                        val vegG = lerp(0.36, 0.50, aridBoost) * (1.0 - heatDry * 0.28)
                        val vegB = lerp(0.13, 0.29, aridBoost) * (1.0 - heatDry * 0.35)
                        r = lerp(vegR, 0.48, smoothStep(0.48, 0.85, hNorm))
                        g = lerp(vegG, 0.43, smoothStep(0.48, 0.85, hNorm))
                        b = lerp(vegB, 0.38, smoothStep(0.48, 0.85, hNorm))
                        if (hNorm > 0.78 && tempK < 340.0) {
                            r = 0.90; g = 0.92; b = 0.95
                        }
                    }
                }

                // Impact craters on the map
                for (c in body.craters) {
                    val dot = (sx * c.nx + sy * c.ny + sz * c.nz).coerceIn(-1.0, 1.0)
                    val ang = acos(dot)
                    if (ang < c.radiusRad * 1.2) {
                        val bowl = 1.0 - smoothStep(0.0, c.radiusRad.toDouble(), ang)
                        r = lerp(r, 0.28, bowl * 0.75)
                        g = lerp(g, 0.24, bowl * 0.75)
                        b = lerp(b, 0.22, bowl * 0.75)
                        val ch = bowl * c.heat
                        if (ch > 0.08) {
                            r = (r + ch * 1.2).coerceAtMost(1.0)
                            g = (g + ch * 0.45).coerceAtMost(1.0)
                            moltenPx++
                        }
                    }
                }

                // Molten crust magma fissures
                if (lavaHeat > 0.05) {
                    val crack = smoothStep(0.54, 0.78, fineNoise)
                    val glow = lavaHeat * (0.35 + 0.65 * crack)
                    r = lerp(r, 1.0, glow.coerceIn(0.0, 1.0))
                    g = lerp(g, 0.36 + 0.28 * lavaHeat, glow.coerceIn(0.0, 1.0))
                    b = lerp(b, 0.06, glow.coerceIn(0.0, 1.0))
                    if (glow > 0.28) moltenPx++
                }

                // Atmospheric steam/cloud veil
                if (body.hasClouds && atmo > 0.08) {
                    val cloudWisp = smoothStep(0.55, 0.86, fineNoise + sin(lonR * 4.0 + latR * 3.0) * 0.18)
                    val cloudMix = cloudWisp * (atmo * 0.42).coerceIn(0.0, 0.55)
                    val cr = if (tempK > 360.0) 0.95 else 0.96
                    val cg = if (tempK > 360.0) 0.78 else 0.98
                    val cb = if (tempK > 360.0) 0.55 else 1.0
                    r = lerp(r, cr, cloudMix)
                    g = lerp(g, cg, cloudMix)
                    b = lerp(b, cb, cloudMix)
                }

                // Subtle lat/lon cartographic grid lines every 30 deg
                if (px % (width / 12) == 0 || py % (height / 6) == 0) {
                    r = lerp(r, 0.35, 0.14)
                    g = lerp(g, 0.65, 0.14)
                    b = lerp(b, 0.85, 0.14)
                }

                pixels[py * width + px] = Color.rgb(
                    (r.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
                    (g.coerceIn(0.0, 1.0) * 255.0).roundToInt(),
                    (b.coerceIn(0.0, 1.0) * 255.0).roundToInt()
                )
            }
        }

        bmp.setPixels(pixels, 0, width, 0, 0, width, height)

        val oceanPct = (oceanPx * 100.0 / totalPx).roundToInt().coerceIn(0, 100)
        val icePct = (icePx * 100.0 / totalPx).roundToInt().coerceIn(0, 100)
        val moltenPct = (moltenPx * 100.0 / totalPx).roundToInt().coerceIn(0, 100)

        val tempScore = (1.0 - abs(tempK - 288.0) / 85.0).coerceIn(0.0, 1.0)
        val atmoScore = (1.0 - abs(atmo - 0.65) / 0.85).coerceIn(0.0, 1.0)
        val lavaPenalty = (1.0 - lavaHeat * 2.5).coerceIn(0.0, 1.0)
        val habPct = (tempScore * atmoScore * lavaPenalty * 100.0).roundToInt().coerceIn(0, 100)

        val climate = when {
            lavaHeat > 0.25 || tempK > 780.0 -> "Molten Magma Hell"
            tempK > 390.0 -> "Runaway Steam Greenhouse"
            tempK > 330.0 -> "Arid Scorched World"
            tempK < 210.0 -> "Snowball Ice World"
            tempK < 250.0 -> "Glacial Tundra"
            atmo < 0.08 -> "Vacuum / Stripped Air"
            habPct >= 65 -> "Temperate Biosphere"
            else -> "Marginal Climate"
        }

        return Pair(
            bmp,
            EarthSurfaceMetrics(
                liquidOceanPct = oceanPct,
                iceCoverPct = icePct,
                moltenPct = moltenPct,
                habitabilityPct = habPct,
                climateLabel = climate
            )
        )
    }

    private fun lerp(a: Double, b: Double, t: Double): Double = a + (b - a) * t.coerceIn(0.0, 1.0)

    private fun smoothStep(e0: Double, e1: Double, x: Double): Double {
        val t = ((x - e0) / (e1 - e0).coerceAtLeast(1e-6)).coerceIn(0.0, 1.0)
        return t * t * (3.0 - 2.0 * t)
    }
}
