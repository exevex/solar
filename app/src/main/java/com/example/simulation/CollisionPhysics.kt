package com.example.simulation

import kotlin.math.*
import kotlin.random.Random

/**
 * Physically-grounded planetary & substellar collision regimes based on
 * Leinhardt & Stewart (2012) scaling laws:
 * specific impact energy Q_R relative to catastrophic disruption threshold Q*_RD,
 * impact parameter b = sin(theta), mass ratio gamma = M2 / M1, and bulk composition.
 */
enum class CollisionRegime(val label: String) {
    GENTLE_MERGER("Accretion Merger"),
    GAS_GIANT_ABSORPTION("Envelope Absorption"),
    PARTIAL_MANTLE_STRIPPING("Giant Impact & Mantle Strip"),
    GRAZING_HIT_AND_RUN("Grazing Hit-and-Run"),
    CATASTROPHIC_DISRUPTION("Catastrophic Disruption")
}

data class CollisionApproachPlan(
    val bodyA: CelestialBody,
    val bodyB: CelestialBody,
    val expectedEscapeKmS: Double,
    val expectedContactVelKmS: Double,
    val etaTimeYears: Double
)

data class CollisionOutcome(
    val regime: CollisionRegime,
    val primaryUpdated: CelestialBody,
    val secondaryUpdated: CelestialBody?,
    val fragments: List<CelestialBody>,
    val particles: List<DebrisParticle>,
    val shockwave: ShockwaveEvent?,
    val impactPos: Vec3d,
    val impactNormal: Vec3d,
    val impactAngleDeg: Double,
    val vImpOverVEsc: Double,
    val eventMessage: String
)

enum class CollisionScenarioPreset(
    val id: String,
    val title: String,
    val subtitle: String,
    val defaultAngleDeg: Float,
    val defaultSpeedRatio: Float
) {
    EARTH_THEIA("earth_theia", "Earth – Theia", "Oblique 45° Mars-sized giant impact (Moon origin)", 45f, 1.15f),
    ROCKY_HEAD_ON("rocky_head_on", "Twin Terra Head-On", "Hypervelocity 0° disruption into debris disk", 5f, 2.35f),
    GRAZING_WORLDS("grazing_worlds", "Oblique Hit & Run", "66° tidal graze with deformed spinning remnants", 66f, 1.55f),
    GIANT_ABSORPTION("giant_absorption", "Super-Earth vs Jupiter", "Deep atmospheric plunge & envelope heating", 28f, 1.40f)
}

object CollisionPhysics {

    /**
     * Places two bodies at `separationMult * rContact` and solves gravitational-focusing energy & angular
     * momentum equations so pure N-body integration brings them to contact at the requested angle & speed ratio.
     */
    fun computeCollisionApproachState(
        target: CelestialBody,
        impactor: CelestialBody,
        separationMult: Double = 4.4,
        impactAngleDeg: Double = 30.0,
        speedVsEscapeRatio: Double = 1.35,
        trueScaleMode: Boolean = false
    ): CollisionApproachPlan {
        val m1 = target.massSolar.coerceAtLeast(1e-16)
        val m2 = impactor.massSolar.coerceAtLeast(1e-16)
        val totalM = m1 + m2
        val mu = AstroConstants.G_AU * totalM

        val rVis1 = target.getVisualRadiusAu(trueScaleMode)
        val rVis2 = impactor.getVisualRadiusAu(trueScaleMode)
        val rContact = ((rVis1 + rVis2) * 0.88).coerceAtLeast(0.012)
        val dInit = (rContact * separationMult.coerceIn(2.2, 10.0)).coerceAtLeast(rContact * 2.1)

        // Gravitational softening matches PhysicsEngine computeAccelerations softening
        val soft = max((target.physicsRadiusAu + impactor.physicsRadiusAu) * 2.0, 1e-5)
        val potentialContact = mu / sqrt(rContact * rContact + soft * soft)
        val potentialInit = mu / sqrt(dInit * dInit + soft * soft)

        // Effective mutual escape speed at visual contact radius (with sensible floor for sub-Earth pairs)
        val vEscContact = sqrt(2.0 * potentialContact).coerceAtLeast(0.45)
        val vImpDesired = vEscContact * speedVsEscapeRatio.coerceIn(0.65, 5.0)

        // Energy conservation: 0.5*v_init^2 - pot_init = 0.5*v_imp^2 - pot_contact
        val vInitSq = max(0.09 * vImpDesired * vImpDesired, vImpDesired * vImpDesired - 2.0 * potentialContact + 2.0 * potentialInit)
        val vInit = sqrt(vInitSq)

        // Angular momentum h = rContact * vImp * sin(theta)
        val thetaRad = Math.toRadians(impactAngleDeg.coerceIn(0.0, 84.0))
        val hTarget = rContact * vImpDesired * sin(thetaRad)
        val vTanInit = (hTarget / dInit).coerceAtMost(vInit * 0.92)
        val vRadInit = -sqrt(max(0.0, vInit * vInit - vTanInit * vRadInitSafety(vTanInit)))

        // Preserve system center-of-mass position & velocity cleanly
        val comPos = target.pos
        val comVel = (target.vel * m1 + impactor.vel * m2) / totalM

        // Orient approach along X/Z plane so camera views both bodies & ejecta clearly
        val relPosDir = Vec3d(1.0, 0.04, 0.18).normalized()
        val tanDir = Vec3d(-relPosDir.z, 0.0, relPosDir.x).normalized()
        val relPosInit = relPosDir * dInit
        val relVelInit = relPosDir * vRadInit + tanDir * vTanInit

        val bodyA = target.copy(
            pos = comPos - relPosInit * (m2 / totalM),
            vel = comVel - relVelInit * (m2 / totalM),
            trail = emptyList()
        )
        val bodyB = impactor.copy(
            pos = comPos + relPosInit * (m1 / totalM),
            vel = comVel + relVelInit * (m1 / totalM),
            trail = emptyList()
        )

        val avgClosing = ((abs(vRadInit) + vImpDesired * cos(thetaRad)) * 0.5).coerceAtLeast(0.1)
        val etaYears = ((dInit - rContact) / avgClosing).coerceAtLeast(1e-5)

        return CollisionApproachPlan(
            bodyA = bodyA,
            bodyB = bodyB,
            expectedEscapeKmS = vEscContact * AstroConstants.AU_YR_TO_KM_S,
            expectedContactVelKmS = vImpDesired * AstroConstants.AU_YR_TO_KM_S,
            etaTimeYears = etaYears
        )
    }

    private fun vRadInitSafety(vTan: Double): Double = vTan

    fun resolveCollision(
        primary: CelestialBody,
        secondary: CelestialBody,
        trueScaleMode: Boolean,
        currentBodyCount: Int,
        maxTotalBodies: Int,
        nextIdAllocator: () -> Long,
        rng: Random
    ): CollisionOutcome {
        val m1 = primary.massSolar.coerceAtLeast(1e-18)
        val m2 = secondary.massSolar.coerceAtLeast(1e-18)
        val totalMass = m1 + m2
        val gamma = (m2 / m1).coerceIn(0.0, 1.0)
        val muRed = (m1 * m2) / totalMass
        val comVel = (primary.vel * m1 + secondary.vel * m2) / totalMass

        val relPos = secondary.pos - primary.pos
        val dist = relPos.length().coerceAtLeast(1e-7)
        val impactNormal = relPos / dist

        val relVel = secondary.vel - primary.vel
        val relSpeed = relVel.length().coerceAtLeast(1e-5)

        val rVis1 = primary.getVisualRadiusAu(trueScaleMode)
        val rVis2 = secondary.getVisualRadiusAu(trueScaleMode)
        val rContact = (rVis1 + rVis2) * 0.88
        val soft = max((primary.physicsRadiusAu + secondary.physicsRadiusAu) * 2.0, 1e-5)

        // Impact parameter b = sin(theta) using transverse velocity fraction
        val crossVec = impactNormal.cross(relVel)
        val bParam = (crossVec.length() / relSpeed).coerceIn(0.0, 0.999)
        val impactAngleDeg = Math.toDegrees(asin(bParam))

        // Mutual escape speed at collision separation
        val mu = AstroConstants.G_AU * totalMass
        val vEsc = sqrt(2.0 * mu / sqrt(rContact * rContact + soft * soft)).coerceAtLeast(0.35)
        val vRatio = relSpeed / vEsc

        val primaryIsStar = primary.type.isLuminous
        val primaryIsFluidGiant = primary.type == BodyType.GAS_GIANT || primary.type == BodyType.ICE_GIANT
        val isTinyImpactor = m2 < m1 * 0.04

        // Leinhardt & Stewart (2012) dimensionless disruption ratio Q_R / Q_RD*:
        // Interacting fraction drops obliquely; equal-mass head-on disruption occurs ~ 1.85 v_esc.
        val obliqueFactor = (1.0 - bParam * 0.55).coerceIn(0.35, 1.0)
        val massSymmetry = (4.0 * m1 * m2) / (totalMass * totalMass) // 1.0 for equal mass, drops for tiny impactor
        val qrOverQrdStar = (vRatio * vRatio) * (0.28 + 0.72 * massSymmetry) * obliqueFactor

        val regime = when {
            primaryIsStar || (primaryIsFluidGiant && isTinyImpactor) -> CollisionRegime.GAS_GIANT_ABSORPTION
            bParam > 0.50 && vRatio > 0.92 && gamma > 0.06 && !primaryIsStar -> CollisionRegime.GRAZING_HIT_AND_RUN
            !primaryIsFluidGiant && gamma > 0.05 && qrOverQrdStar >= 1.25 -> CollisionRegime.CATASTROPHIC_DISRUPTION
            !primaryIsFluidGiant && gamma > 0.04 && (qrOverQrdStar >= 0.45 || vRatio >= 1.18) -> CollisionRegime.PARTIAL_MANTLE_STRIPPING
            else -> CollisionRegime.GENTLE_MERGER
        }

        val impactPos = primary.pos + impactNormal * rVis1
        val newRadiusKm = primary.radiusKm * cbrt(totalMass / m1)
        val ejectaBaseColor = when {
            primary.type.isLuminous -> primary.albedoColor
            primary.type == BodyType.ICE_GIANT || secondary.type == BodyType.COMET -> Triple(0.68f, 0.88f, 1.0f)
            else -> Triple(1.0f, 0.54f, 0.18f)
        }

        val spinTorqueKick = crossVec.y * (18.0 * gamma)

        // 1. Suppress tiny pebble explosions against stars/giants cleanly
        if (regime == CollisionRegime.GAS_GIANT_ABSORPTION && isTinyImpactor) {
            val updated = primary.copy(
                massSolar = totalMass,
                radiusKm = if (primaryIsStar) primary.radiusKm else newRadiusKm,
                vel = comVel
            )
            return CollisionOutcome(
                regime = regime,
                primaryUpdated = updated,
                secondaryUpdated = null,
                fragments = emptyList(),
                particles = emptyList(),
                shockwave = null,
                impactPos = impactPos,
                impactNormal = impactNormal,
                impactAngleDeg = impactAngleDeg,
                vImpOverVEsc = vRatio,
                eventMessage = "${secondary.name} vaporized inside ${primary.name}"
            )
        }

        // Compute ejecta particle plume tangent plane
        val upRef = if (abs(impactNormal.y) < 0.85) Vec3d(0.0, 1.0, 0.0) else Vec3d(1.0, 0.0, 0.0)
        val tangU = impactNormal.cross(upRef).normalized()
        val tangV = impactNormal.cross(tangU).normalized()

        fun buildEjectaParticles(count: Int, speedScale: Double, isIcy: Boolean): List<DebrisParticle> {
            val list = ArrayList<DebrisParticle>(count)
            for (k in 0 until count) {
                val phi = rng.nextDouble() * PI * 2.0
                val coneAngle = (0.32 + rng.nextDouble() * 0.85) // 45 deg ejecta cone curtain
                val radialTerm = cos(coneAngle)
                val tanTerm = sin(coneAngle)
                val dir = (impactNormal * radialTerm + (tangU * cos(phi) + tangV * sin(phi)) * tanTerm).normalized()
                val speed = (0.38 + rng.nextDouble() * 0.95) * speedScale
                val pVel = comVel + dir * speed
                val glowRand = rng.nextFloat()
                val colR = if (isIcy) 0.72f + glowRand * 0.25f else (0.95f + glowRand * 0.25f).coerceAtMost(1.2f)
                val colG = if (isIcy) 0.88f + glowRand * 0.12f else 0.34f + glowRand * 0.48f
                val colB = if (isIcy) 1.0f else 0.06f + glowRand * 0.16f
                list.add(
                    DebrisParticle(
                        x = impactPos.x + dir.x * (rVis1 * 0.15),
                        y = impactPos.y + dir.y * (rVis1 * 0.15),
                        z = impactPos.z + dir.z * (rVis1 * 0.15),
                        vx = pVel.x * 0.14,
                        vy = pVel.y * 0.14,
                        vz = pVel.z * 0.14,
                        life = 0f,
                        maxLife = 2.6f + rng.nextFloat() * 2.2f,
                        size = 1.5f + rng.nextFloat() * 2.4f,
                        r = colR, g = colG, b = colB,
                        targetBlackHoleId = -1L,
                        affectedByGravity = true
                    )
                )
            }
            return list
        }

        // Crater creation helper for solid worlds
        val updatedCratersPrimary = if (!primaryIsStar && !primaryIsFluidGiant) {
            val cRad = (0.18f + (secondary.radiusKm / primary.radiusKm).toFloat() * 0.55f).coerceIn(0.14f, 0.72f)
            (primary.craters + CraterMark(impactNormal.x.toFloat(), impactNormal.y.toFloat(), impactNormal.z.toFloat(), cRad, 1.0f)).takeLast(8)
        } else primary.craters

        // Orbit tangent direction around primary COM for realistic circumplanetary debris
        val orbSpinAxis = if (crossVec.lengthSq() > 1e-8) crossVec.normalized() else Vec3d(0.0, 1.0, 0.0)

        when (regime) {
            CollisionRegime.GRAZING_HIT_AND_RUN -> {
                // Separate & reflect relative velocity partially along normal (restitution ~ 0.65, tangential ~ 0.78)
                val vRadRel = relVel.dot(impactNormal)
                val vTanRel = relVel - impactNormal * vRadRel
                val postRelVel = -impactNormal * abs(vRadRel) * 0.58 + vTanRel * 0.78

                val v1Post = comVel - postRelVel * (m2 / totalMass)
                val v2Post = comVel + postRelVel * (m1 / totalMass)

                // Strip small tidal bridge fraction into 3 orbiting fragments
                val availSlots = (maxTotalBodies - currentBodyCount).coerceAtLeast(0)
                val fragCount = min(3, availSlots)
                val stripped1 = m1 * 0.04
                val stripped2 = m2 * 0.14

                val pushSep = impactNormal * ((rVis1 + rVis2) * 0.22 + 0.008)
                val heatPrimary = (0.45f + gamma.toFloat() * 0.8f).coerceIn(0.35f, 0.95f)
                val heatSecondary = (0.65f + vRatio.toFloat() * 0.2f).coerceIn(0.55f, 1.0f)

                val primOut = primary.copy(
                    pos = primary.pos - pushSep * 0.4,
                    vel = v1Post,
                    massSolar = (m1 - stripped1).coerceAtLeast(1e-16),
                    radiusKm = primary.radiusKm * 0.985,
                    temperatureK = (primary.temperatureK + 1450.0 * heatPrimary).coerceAtMost(8500.0),
                    crustHeat = if (primaryIsFluidGiant) 0f else (primary.crustHeat + heatPrimary).coerceAtMost(1.0f),
                    craters = updatedCratersPrimary,
                    impactDeformDir = impactNormal,
                    impactDeformAmount = 0.24f,
                    impactShockDir = impactNormal,
                    impactShockAge = 0.0f,
                    rotationPeriodHours = adjustSpin(primary.rotationPeriodHours, spinTorqueKick)
                )

                val secNormal = -impactNormal
                val secCraters = if (secondary.type != BodyType.GAS_GIANT && secondary.type != BodyType.ICE_GIANT) {
                    (secondary.craters + CraterMark(secNormal.x.toFloat(), secNormal.y.toFloat(), secNormal.z.toFloat(), 0.42f, 1.0f)).takeLast(8)
                } else secondary.craters

                val secOut = secondary.copy(
                    pos = secondary.pos + pushSep,
                    vel = v2Post,
                    massSolar = (m2 - stripped2).coerceAtLeast(1e-16),
                    radiusKm = secondary.radiusKm * 0.95,
                    temperatureK = (secondary.temperatureK + 2100.0 * heatSecondary).coerceAtMost(9500.0),
                    crustHeat = if (secondary.type == BodyType.GAS_GIANT || secondary.type == BodyType.ICE_GIANT) 0f else 0.95f,
                    craters = secCraters,
                    impactDeformDir = secNormal,
                    impactDeformAmount = 0.34f,
                    impactShockDir = secNormal,
                    impactShockAge = 0.0f,
                    rotationPeriodHours = adjustSpin(secondary.rotationPeriodHours, -spinTorqueKick * 2.2)
                )

                val frags = ArrayList<CelestialBody>(fragCount)
                val fragMass = ((stripped1 + stripped2) / max(1, fragCount)).coerceAtLeast(1e-12)
                for (f in 0 until fragCount) {
                    val phase = (f.toDouble() / fragCount) * PI * 0.65 - 0.3
                    val dir = (impactNormal * cos(phase) + vTanRel.normalized() * sin(phase)).normalized()
                    val pos = impactPos + dir * (rVis1 * 0.55 + rVis2 * 0.45)
                    val vel = comVel + dir * (vEsc * 0.62) + vTanRel * 0.35
                    frags.add(
                        CelestialBody(
                            id = nextIdAllocator(),
                            name = "${secondary.name} Bridge ${f + 1}",
                            type = BodyType.DEBRIS,
                            pos = pos,
                            vel = vel,
                            massSolar = fragMass,
                            radiusKm = (secondary.radiusKm * 0.24).coerceAtLeast(160.0),
                            temperatureK = 2400.0,
                            crustHeat = 1.0f,
                            albedoColor = Triple(0.75f, 0.36f, 0.20f),
                            seed = rng.nextFloat() * 100f
                        )
                    )
                }

                val shock = ShockwaveEvent(
                    id = nextIdAllocator(), pos = impactPos,
                    maxRadiusAu = (rVis1 + rVis2) * 4.8,
                    ageSec = 0f, durationSec = 1.6f, color = ejectaBaseColor
                )

                return CollisionOutcome(
                    regime = regime,
                    primaryUpdated = primOut,
                    secondaryUpdated = secOut,
                    fragments = frags,
                    particles = buildEjectaParticles(55, vEsc * 1.4, isIcy = secondary.type == BodyType.ICE_GIANT),
                    shockwave = shock,
                    impactPos = impactPos,
                    impactNormal = impactNormal,
                    impactAngleDeg = impactAngleDeg,
                    vImpOverVEsc = vRatio,
                    eventMessage = "Grazing Hit & Run (${impactAngleDeg.toInt()}°): ${secondary.name} tore across ${primary.name}!"
                )
            }

            CollisionRegime.CATASTROPHIC_DISRUPTION -> {
                val retainedFrac = (0.58 / (qrOverQrdStar.coerceIn(1.0, 4.0)).pow(0.45)).coerceIn(0.32, 0.72)
                val coreMass = totalMass * retainedFrac
                val debrisMassTotal = (totalMass - coreMass).coerceAtLeast(1e-12)
                val availSlots = (maxTotalBodies - currentBodyCount).coerceAtLeast(0)
                val fragCount = min(8, availSlots).coerceAtLeast(2)

                val primOut = primary.copy(
                    vel = comVel,
                    massSolar = coreMass,
                    radiusKm = primary.radiusKm * cbrt(coreMass / m1),
                    temperatureK = (primary.temperatureK + 3600.0).coerceAtMost(12000.0),
                    crustHeat = 1.0f,
                    atmosphereDensity = (primary.atmosphereDensity * 0.12f),
                    hasCityLights = false,
                    craters = updatedCratersPrimary,
                    impactDeformDir = impactNormal,
                    impactDeformAmount = 0.42f,
                    impactShockDir = impactNormal,
                    impactShockAge = 0.0f,
                    rotationPeriodHours = adjustSpin(primary.rotationPeriodHours, spinTorqueKick)
                )

                val frags = ArrayList<CelestialBody>(fragCount)
                val fragMass = debrisMassTotal / fragCount
                for (f in 0 until fragCount) {
                    val az = (f.toDouble() / fragCount) * PI * 2.0 + rng.nextDouble() * 0.35
                    val tangent = impactNormal.cross(orbSpinAxis).let { if (it.lengthSq() < 1e-6) tangU else it.normalized() }
                    val radialOut = (impactNormal * cos(az) + tangent * sin(az) + Vec3d(0.0, (rng.nextDouble() - 0.5) * 0.32, 0.0)).normalized()
                    val spawnR = (rVis1 * 1.45 + rVis2 * 0.65) * (1.0 + 0.25 * (f % 3))
                    val tangVelDir = orbSpinAxis.cross(radialOut).normalized()
                    // Mix of escaping & bound debris fragments obeying conservation of angular momentum
                    val vCirc = sqrt(AstroConstants.G_AU * coreMass / spawnR).coerceAtLeast(0.25)
                    val radialKick = vEsc * (0.45 + rng.nextDouble() * 0.55)
                    val ejectVel = comVel + radialOut * radialKick + tangVelDir * (vCirc * 0.95)
                    frags.add(
                        CelestialBody(
                            id = nextIdAllocator(),
                            name = "${primary.name} Fragment ${f + 1}",
                            type = BodyType.DEBRIS,
                            pos = primary.pos + radialOut * spawnR,
                            vel = ejectVel,
                            massSolar = fragMass,
                            radiusKm = (secondary.radiusKm * 0.38).coerceAtLeast(180.0),
                            temperatureK = 2850.0,
                            crustHeat = 1.0f,
                            albedoColor = Triple(0.55f, 0.30f, 0.24f),
                            seed = rng.nextFloat() * 100f
                        )
                    )
                }

                val shock = ShockwaveEvent(
                    id = nextIdAllocator(), pos = impactPos,
                    maxRadiusAu = (rVis1 + rVis2) * 8.5,
                    ageSec = 0f, durationSec = 2.0f, color = ejectaBaseColor
                )

                return CollisionOutcome(
                    regime = regime,
                    primaryUpdated = primOut,
                    secondaryUpdated = null,
                    fragments = frags,
                    particles = buildEjectaParticles(95, vEsc * 2.1, isIcy = false),
                    shockwave = shock,
                    impactPos = impactPos,
                    impactNormal = impactNormal,
                    impactAngleDeg = impactAngleDeg,
                    vImpOverVEsc = vRatio,
                    eventMessage = "Catastrophic Disruption (${String.format("%.1f", vRatio)}× v_esc): ${primary.name} & ${secondary.name} shattered!"
                )
            }

            CollisionRegime.PARTIAL_MANTLE_STRIPPING -> {
                val diskFrac = (0.04 + 0.14 * bParam).coerceIn(0.03, 0.22)
                val remMass = totalMass * (1.0 - diskFrac)
                val fragCount = min(5, (maxTotalBodies - currentBodyCount).coerceAtLeast(0))

                val primOut = primary.copy(
                    vel = comVel,
                    massSolar = remMass,
                    radiusKm = primary.radiusKm * cbrt(remMass / m1),
                    temperatureK = (primary.temperatureK + 2600.0).coerceAtMost(10500.0),
                    crustHeat = 1.0f,
                    atmosphereDensity = (primary.atmosphereDensity * 0.45f),
                    hasCityLights = false,
                    craters = updatedCratersPrimary,
                    impactDeformDir = impactNormal,
                    impactDeformAmount = 0.32f,
                    impactShockDir = impactNormal,
                    impactShockAge = 0.0f,
                    rotationPeriodHours = adjustSpin(primary.rotationPeriodHours, spinTorqueKick)
                )

                val frags = ArrayList<CelestialBody>(fragCount)
                val fragMass = if (fragCount > 0) (totalMass * diskFrac) / fragCount else 0.0
                for (f in 0 until fragCount) {
                    val az = (f.toDouble() / fragCount) * PI * 2.0
                    val radDir = (tangU * cos(az) + tangV * sin(az)).normalized()
                    val orbR = (rVis1 * 1.9 + rVis2 * 0.7) * (1.0 + f * 0.14)
                    val orbSpeed = sqrt(AstroConstants.G_AU * remMass / orbR).coerceAtLeast(0.25)
                    val orbTan = orbSpinAxis.cross(radDir).let { if (it.lengthSq() < 1e-6) tangV else it.normalized() }
                    frags.add(
                        CelestialBody(
                            id = nextIdAllocator(),
                            name = "${primary.name} Moonlet ${f + 1}",
                            type = BodyType.DEBRIS,
                            pos = primary.pos + radDir * orbR,
                            vel = comVel + orbTan * orbSpeed + radDir * (vEsc * 0.22),
                            massSolar = fragMass,
                            radiusKm = (secondary.radiusKm * 0.28).coerceAtLeast(150.0),
                            temperatureK = 2250.0,
                            crustHeat = 0.92f,
                            albedoColor = Triple(0.68f, 0.38f, 0.26f),
                            seed = rng.nextFloat() * 100f
                        )
                    )
                }

                val shock = ShockwaveEvent(
                    id = nextIdAllocator(), pos = impactPos,
                    maxRadiusAu = (rVis1 + rVis2) * 6.0,
                    ageSec = 0f, durationSec = 1.65f, color = ejectaBaseColor
                )

                return CollisionOutcome(
                    regime = regime,
                    primaryUpdated = primOut,
                    secondaryUpdated = null,
                    fragments = frags,
                    particles = buildEjectaParticles(70, vEsc * 1.65, isIcy = false),
                    shockwave = shock,
                    impactPos = impactPos,
                    impactNormal = impactNormal,
                    impactAngleDeg = impactAngleDeg,
                    vImpOverVEsc = vRatio,
                    eventMessage = "Giant Impact (${impactAngleDeg.toInt()}°): ${secondary.name} stripped mantle & formed debris disk around ${primary.name}!"
                )
            }

            CollisionRegime.GENTLE_MERGER, CollisionRegime.GAS_GIANT_ABSORPTION -> {
                val heatBump = (0.35f + gamma.toFloat() * 1.8f).coerceIn(0.25f, 1.0f)
                val primOut = primary.copy(
                    massSolar = totalMass,
                    radiusKm = newRadiusKm,
                    vel = comVel,
                    temperatureK = (primary.temperatureK + 1500.0 * heatBump).coerceAtMost(10000.0),
                    crustHeat = if (primaryIsFluidGiant) 0f else (primary.crustHeat + heatBump).coerceAtMost(1.0f),
                    craters = updatedCratersPrimary,
                    impactDeformDir = impactNormal,
                    impactDeformAmount = if (primaryIsFluidGiant) 0.14f else 0.22f,
                    impactShockDir = impactNormal,
                    impactShockAge = 0.0f,
                    rotationPeriodHours = adjustSpin(primary.rotationPeriodHours, spinTorqueKick)
                )

                val shock = ShockwaveEvent(
                    id = nextIdAllocator(), pos = impactPos,
                    maxRadiusAu = (rVis1 + rVis2) * 4.2,
                    ageSec = 0f, durationSec = 1.4f, color = ejectaBaseColor
                )

                return CollisionOutcome(
                    regime = regime,
                    primaryUpdated = primOut,
                    secondaryUpdated = null,
                    fragments = emptyList(),
                    particles = buildEjectaParticles(36, vEsc * 1.15, isIcy = primaryIsFluidGiant),
                    shockwave = shock,
                    impactPos = impactPos,
                    impactNormal = impactNormal,
                    impactAngleDeg = impactAngleDeg,
                    vImpOverVEsc = vRatio,
                    eventMessage = "${secondary.name} merged with ${primary.name} (${impactAngleDeg.toInt()}° impact)"
                )
            }
        }
    }

    private fun adjustSpin(periodHours: Double, torqueKick: Double): Double {
        val omega0 = if (abs(periodHours) > 1e-3) 24.0 / periodHours else 0.0
        val omegaNew = (omega0 + torqueKick * 0.35).coerceIn(-12.0, 12.0)
        return if (abs(omegaNew) < 0.04) 240.0 else 24.0 / omegaNew
    }
}
