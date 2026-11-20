package com.example.simulation

import kotlin.math.*

/** WGS84 geodetic ellipsoid, with optional sampled land elevation in kilometres (no exaggeration).
 * Coordinates: X = lon 0, Y = north, -Z = lon 90 E, matching the simulation's right-handed frame.
 * Asset UVs: west -180 -> u0, east +180 -> u1; north +90 -> v0. Android bitmap rows upload unchanged.
 */
object EarthGeometry {
    const val EQUATORIAL_RADIUS_KM = 6378.137
    const val INVERSE_FLATTENING = 298.257223563
    const val MEAN_RADIUS_KM = 6371.0
    val POLAR_RADIUS_KM = EQUATORIAL_RADIUS_KM * (1.0 - 1.0 / INVERSE_FLATTENING)
    data class MeshData(val vertices: FloatArray, val indices: ShortArray)

    fun positionKm(latitudeDeg: Double, longitudeDeg: Double, elevationKm: Double = 0.0): Vec3d {
        val lat = Math.toRadians(latitudeDeg)
        val lon = Math.toRadians(longitudeDeg)
        val f = 1.0 / INVERSE_FLATTENING
        val e2 = f * (2.0 - f)
        val n = EQUATORIAL_RADIUS_KM / sqrt(1.0 - e2 * sin(lat).pow(2))
        return Vec3d((n + elevationKm) * cos(lat) * cos(lon),
            (n * (1.0 - e2) + elevationKm) * sin(lat),
            -(n + elevationKm) * cos(lat) * sin(lon))
    }

    fun uv(latitudeDeg: Double, longitudeDeg: Double): Pair<Double, Double> =
        Pair((longitudeDeg + 180.0) / 360.0, (90.0 - latitudeDeg) / 180.0)

    fun mesh(latBands: Int, lonBands: Int, heightAt: (Double, Double) -> Double = { _, _ -> 0.0 }): MeshData {
        require(latBands >= 2 && lonBands >= 3 && (latBands + 1) * (lonBands + 1) <= 65535)
        val vertices = FloatArray((latBands + 1) * (lonBands + 1) * 8)
        var cursor = 0
        for (row in 0..latBands) {
            val latDeg = 90.0 - row * 180.0 / latBands
            val lat = Math.toRadians(latDeg)
            for (col in 0..lonBands) {
                val lonDeg = -180.0 + col * 360.0 / lonBands
                val lon = Math.toRadians(lonDeg)
                val pos = positionKm(latDeg, lonDeg, heightAt(latDeg, lonDeg).coerceIn(0.0, 6.4))
                vertices[cursor++] = (pos.x / MEAN_RADIUS_KM).toFloat()
                vertices[cursor++] = (pos.y / MEAN_RADIUS_KM).toFloat()
                vertices[cursor++] = (pos.z / MEAN_RADIUS_KM).toFloat()
                vertices[cursor++] = (cos(lat) * cos(lon)).toFloat()
                vertices[cursor++] = sin(lat).toFloat()
                vertices[cursor++] = (-cos(lat) * sin(lon)).toFloat()
                vertices[cursor++] = (col.toDouble() / lonBands).toFloat()
                vertices[cursor++] = (row.toDouble() / latBands).toFloat()
            }
        }
        val indices = ShortArray(latBands * lonBands * 6)
        var i = 0
        for (row in 0 until latBands) for (col in 0 until lonBands) {
            val a = row * (lonBands + 1) + col
            val b = a + lonBands + 1
            // lon increases toward -Z, giving outward counter-clockwise faces.
            indices[i++] = a.toShort(); indices[i++] = b.toShort(); indices[i++] = (a + 1).toShort()
            indices[i++] = b.toShort(); indices[i++] = (b + 1).toShort(); indices[i++] = (a + 1).toShort()
        }
        return MeshData(vertices, indices)
    }
}
