package com.example.simulation

import kotlin.math.*

/**
 * 64-bit IEEE-754 3D vector for astronomical N-body physics in AU / Solar Mass / Julian Year units.
 * Using AU, M_sun, and yr keeps G = 4 * PI^2 (~39.4784) and prevents floating-point overflow/underflow.
 */
data class Vec3d(
    val x: Double = 0.0,
    val y: Double = 0.0,
    val z: Double = 0.0
) {
    operator fun plus(other: Vec3d) = Vec3d(x + other.x, y + other.y, z + other.z)
    operator fun minus(other: Vec3d) = Vec3d(x - other.x, y - other.y, z - other.z)
    operator fun times(s: Double) = Vec3d(x * s, y * s, z * s)
    operator fun div(s: Double) = Vec3d(x / s, y / s, z / s)
    operator fun unaryMinus() = Vec3d(-x, -y, -z)

    fun dot(other: Vec3d): Double = x * other.x + y * other.y + z * other.z
    fun cross(other: Vec3d): Vec3d = Vec3d(
        y * other.z - z * other.y,
        z * other.x - x * other.z,
        x * other.y - y * other.x
    )

    fun lengthSq(): Double = x * x + y * y + z * z
    fun length(): Double = sqrt(lengthSq())
    fun normalized(): Vec3d {
        val len = length()
        return if (len > 1e-18) this / len else Vec3d(0.0, 1.0, 0.0)
    }

    companion object {
        val ZERO = Vec3d(0.0, 0.0, 0.0)
    }
}

/**
 * Mutable accumulator vector used inside tight N-body integration loops to avoid GC churn at 60 FPS.
 */
class MutableVec3d(var x: Double = 0.0, var y: Double = 0.0, var z: Double = 0.0) {
    fun set(vx: Double, vy: Double, vz: Double) {
        x = vx; y = vy; z = vz
    }
    fun set(v: Vec3d) {
        x = v.x; y = v.y; z = v.z
    }
    fun addScaled(dx: Double, dy: Double, dz: Double, scale: Double) {
        x += dx * scale
        y += dy * scale
        z += dz * scale
    }
    fun toVec3d() = Vec3d(x, y, z)
}

object AstroConstants {
    /** Gravitational constant in AU^3 / (M_sun * yr^2) */
    const val G_AU: Double = 4.0 * PI * PI

    /** 1 Astronomical Unit in kilometers (IAU 2012 exact) */
    const val AU_KM: Double = 149_597_870.7

    /** 1 Astronomical Unit in meters */
    const val AU_M: Double = 149_597_870_700.0

    /** Solar mass in kg */
    const val SOLAR_MASS_KG: Double = 1.98847e30

    /** Earth mass in Solar masses */
    const val EARTH_MASS_SOLAR: Double = 3.003489596e-6

    /** Jupiter mass in Solar masses */
    const val JUPITER_MASS_SOLAR: Double = 9.547919e-4

    /** Moon mass in Solar masses */
    const val MOON_MASS_SOLAR: Double = 3.6943e-8

    /** Julian year in seconds */
    const val YEAR_SECONDS: Double = 31_557_600.0

    /** Speed of light in AU/year */
    const val C_AU_YR: Double = 63_241.077

    /** 2 * G * M_sun / c^2 in AU (~2.953 km) */
    const val SCHWARZSCHILD_SUN_AU: Double = 2.0 * G_AU / (C_AU_YR * C_AU_YR)

    /** Solar radius in km */
    const val SUN_RADIUS_KM: Double = 696_340.0

    /** Earth radius in km */
    const val EARTH_RADIUS_KM: Double = 6_371.0

    /** Convert AU/year velocity to km/s */
    const val AU_YR_TO_KM_S: Double = AU_KM / YEAR_SECONDS

    /** Newton's G in SI m^3 kg^-1 s^-2 */
    const val G_SI: Double = 6.67430e-11
}

/**
 * Converts Keplerian orbital elements around a central parent body into 3D Cartesian (position, velocity).
 * Note: Our OpenGL world uses Y-up for ecliptic north, so orbital plane is XZ with Y as inclination vertical.
 */
fun orbitalElementsToState(
    centralMassSolar: Double,
    bodyMassSolar: Double,
    semiMajorAxisAu: Double,
    eccentricity: Double,
    inclinationDeg: Double,
    ascNodeDeg: Double,
    argPeriapsisDeg: Double,
    meanAnomalyDeg: Double,
    parentPos: Vec3d = Vec3d.ZERO,
    parentVel: Vec3d = Vec3d.ZERO
): Pair<Vec3d, Vec3d> {
    val mu = AstroConstants.G_AU * (centralMassSolar + bodyMassSolar).coerceAtLeast(1e-15)
    val mRad = Math.toRadians(meanAnomalyDeg)
    // Solve Kepler's equation M = E - e*sin(E) via Newton-Raphson
    var eccentricAnomaly = mRad
    for (iter in 0 until 8) {
        val f = eccentricAnomaly - eccentricity * sin(eccentricAnomaly) - mRad
        val fp = 1.0 - eccentricity * cos(eccentricAnomaly)
        eccentricAnomaly -= f / fp.coerceAtLeast(1e-8)
    }

    val cosE = cos(eccentricAnomaly)
    val sinE = sin(eccentricAnomaly)
    val sqrtOneMinusE2 = sqrt((1.0 - eccentricity * eccentricity).coerceAtLeast(1e-6))

    // Position and velocity in orbital perifocal frame
    val xOrb = semiMajorAxisAu * (cosE - eccentricity)
    val zOrb = semiMajorAxisAu * sqrtOneMinusE2 * sinE
    val r = semiMajorAxisAu * (1.0 - eccentricity * cosE)
    val vFactor = sqrt(mu * semiMajorAxisAu) / r.coerceAtLeast(1e-12)
    val vxOrb = -vFactor * sinE
    val vzOrb = vFactor * sqrtOneMinusE2 * cosE

    val i = Math.toRadians(inclinationDeg)
    val omega = Math.toRadians(argPeriapsisDeg)
    val bigOmega = Math.toRadians(ascNodeDeg)

    val cosO = cos(bigOmega); val sinO = sin(bigOmega)
    val cosw = cos(omega); val sinw = sin(omega)
    val cosi = cos(i); val sini = sin(i)

    // Rotate perifocal (xOrb, 0, zOrb) to ecliptic XZ plane (Y-up)
    fun rotateVec(px: Double, pz: Double): Vec3d {
        // Standard ecliptic coordinates (X, Y_ecl, Z_ecl) -> map to (X, Z_ecl, -Y_ecl) for Y-up right-handed
        val xe = (cosO * cosw - sinO * sinw * cosi) * px + (-cosO * sinw - sinO * cosw * cosi) * pz
        val ye = (sinO * cosw + cosO * sinw * cosi) * px + (-sinO * sinw + cosO * cosw * cosi) * pz
        val ze = (sinw * sini) * px + (cosw * sini) * pz
        return Vec3d(xe, ze, -ye)
    }

    val relPos = rotateVec(xOrb, zOrb)
    val relVel = rotateVec(vxOrb, vzOrb)
    return Pair(parentPos + relPos, parentVel + relVel)
}

/**
 * Planckian blackbody temperature (Kelvin) to linear RGB approximation.
 */
fun kelvinToRgb(tempK: Double): Triple<Float, Float, Float> {
    val t = tempK.coerceIn(1000.0, 40000.0) / 100.0
    val r = if (t <= 66.0) {
        255.0
    } else {
        329.698727446 * (t - 60.0).pow(-0.1332047592)
    }
    val g = if (t <= 66.0) {
        99.4708025861 * ln(t) - 161.1195681661
    } else {
        288.1221695283 * (t - 60.0).pow(-0.0755148492)
    }
    val b = when {
        t >= 66.0 -> 255.0
        t <= 19.0 -> 0.0
        else -> 138.5177312231 * ln(t - 10.0) - 305.0447927307
    }
    return Triple(
        (r / 255.0).coerceIn(0.0, 1.0).toFloat(),
        (g / 255.0).coerceIn(0.0, 1.0).toFloat(),
        (b / 255.0).coerceIn(0.0, 1.0).toFloat()
    )
}
