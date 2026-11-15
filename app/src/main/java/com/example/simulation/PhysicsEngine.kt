package com.example.simulation

import kotlin.math.*
import kotlin.random.Random

/**
 * Immutable frame packet handed atomically from the background simulation thread to the OpenGL ES 3.0 renderer.
 */
data class RenderFrameState(
    val bodies: List<CelestialBody>,
    val shockwaves: List<ShockwaveEvent>,
    val particles: List<DebrisParticle>,
    val gammaBursts: List<GammaBurstBeam>,
    val tidalFilaments: List<TidalStreamFilament>,
    val elapsedYears: Double,
    val config: SimulationConfig,
    val simTps: Float,
    val lastEventMessage: String?,
    val activityFeed: List<String>
)

/**
 * High-precision N-body astrophysical & destruction engine.
 * Supports Velocity Verlet, RK4, adaptive substeps, collisions, tidal disruption,
 * Hawking evaporation, stellar evolution, rewind buffer, and cosmic weapons.
 */
class PhysicsEngine {

    private var bodies = mutableListOf<CelestialBody>()
    private var shockwaves = mutableListOf<ShockwaveEvent>()
    private var particles = mutableListOf<DebrisParticle>()
    private var gammaBursts = mutableListOf<GammaBurstBeam>()
    private var tidalFilaments = mutableListOf<TidalStreamFilament>()

    private var currentPresetId: String = SystemPreset.SOLAR_SYSTEM.id
    private var currentPresetName: String = SystemPreset.SOLAR_SYSTEM.title
    private var elapsedYears: Double = 0.0
    var config: SimulationConfig = SimulationConfig()
        private set

    private val rewindBuffer = ArrayDeque<SimulationSnapshot>(125)
    private var rewindAccumSec = 0.0
    private var nextEntityId = 10_000L
    private val rng = Random(System.currentTimeMillis())

    private var lastEventMessage: String? = null
    private var eventMessageTimerSec = 0f
    private val activityLog = ArrayDeque<String>(6)
    private var lastTidalEventLogMs = 0L

    // Reusable scratch buffers for zero-allocation integration
    private var accX = DoubleArray(0)
    private var accY = DoubleArray(0)
    private var accZ = DoubleArray(0)

    init {
        loadSnapshot(SolarSystemPresets.buildPreset(SystemPreset.SOLAR_SYSTEM))
    }

    @Synchronized
    fun loadSnapshot(snapshot: SimulationSnapshot) {
        bodies = snapshot.bodies.map { it.copy(trail = it.trail.toList(), craters = it.craters.toList()) }.toMutableList()
        shockwaves.clear()
        particles.clear()
        gammaBursts.clear()
        tidalFilaments.clear()
        rewindBuffer.clear()
        activityLog.clear()
        currentPresetId = snapshot.presetId
        currentPresetName = snapshot.name
        elapsedYears = snapshot.elapsedYears
        config = snapshot.config
        val maxId = bodies.maxOfOrNull { it.id } ?: 100L
        nextEntityId = max(nextEntityId, maxId + 100L)
        SolarSystemPresets.resetIdCounter(nextEntityId)
        ensureScratchBuffers(bodies.size)
        pushRewindKeyFrame()
        postEvent("Loaded: ${snapshot.name}")
    }

    @Synchronized
    fun createSnapshot(name: String): SimulationSnapshot {
        return SimulationSnapshot(
            name = name,
            presetId = currentPresetId,
            elapsedYears = elapsedYears,
            bodies = bodies.map { it.copy(trail = it.trail.takeLast(60)) },
            config = config
        )
    }

    @Synchronized
    fun updateConfig(transform: (SimulationConfig) -> SimulationConfig) {
        config = transform(config)
    }

    @Synchronized
    fun getBodiesSnapshot(): List<CelestialBody> = bodies.toList()

    @Synchronized
    fun getBodyById(id: Long): CelestialBody? = bodies.firstOrNull { it.id == id }

    @Synchronized
    fun updateBody(id: Long, transform: (CelestialBody) -> CelestialBody) {
        val idx = bodies.indexOfFirst { it.id == id }
        if (idx >= 0) {
            bodies[idx] = transform(bodies[idx])
        }
    }

    @Synchronized
    fun updateBodyCoupled(id: Long, scaleRadiusWithMass: Boolean = true, transform: (CelestialBody) -> CelestialBody) {
        val idx = bodies.indexOfFirst { it.id == id }
        if (idx < 0) return
        val original = bodies[idx]
        val rawEdited = transform(original)
        val (coupled, eventMsg) = applyCoupledAstrophysics(original, rawEdited, scaleRadiusWithMass)
        bodies[idx] = coupled
        if (eventMsg != null) {
            postEvent(eventMsg)
        }
    }

    @Synchronized
    fun removeBody(id: Long) {
        bodies.removeAll { it.id == id }
    }

    @Synchronized
    fun spawnBody(body: CelestialBody): Long {
        val assignedId = nextEntityId++
        bodies.add(body.copy(id = assignedId))
        ensureScratchBuffers(bodies.size)
        postEvent("Spawned ${body.name}")
        return assignedId
    }

    @Synchronized
    fun buildRenderFrameState(tps: Float): RenderFrameState {
        return RenderFrameState(
            bodies = bodies.map { it.copy() },
            shockwaves = shockwaves.toList(),
            particles = particles.map { it.copy() },
            gammaBursts = gammaBursts.toList(),
            tidalFilaments = tidalFilaments.toList(),
            elapsedYears = elapsedYears,
            config = config,
            simTps = tps,
            lastEventMessage = if (eventMessageTimerSec > 0f) lastEventMessage else null,
            activityFeed = activityLog.toList()
        )
    }

    private fun postEvent(msg: String) {
        lastEventMessage = msg
        eventMessageTimerSec = 3.5f
        if (activityLog.firstOrNull() != msg) {
            if (activityLog.size >= 5) activityLog.removeLast()
            activityLog.addFirst(msg)
        }
    }

    private fun ensureScratchBuffers(n: Int) {
        if (accX.size < n) {
            val cap = max(n + 32, 256)
            accX = DoubleArray(cap)
            accY = DoubleArray(cap)
            accZ = DoubleArray(cap)
        }
    }

    private fun pushRewindKeyFrame() {
        if (rewindBuffer.size >= 120) {
            rewindBuffer.removeFirst()
        }
        rewindBuffer.addLast(
            SimulationSnapshot(
                name = currentPresetName,
                presetId = currentPresetId,
                elapsedYears = elapsedYears,
                bodies = bodies.map { it.copy(trail = it.trail.takeLast(50)) },
                config = config
            )
        )
    }

    /**
     * Advances the physics simulation by real-time frame delta `realDtSec`.
     */
    @Synchronized
    fun step(realDtSec: Double) {
        val dtReal = realDtSec.coerceIn(0.001, 0.05)

        if (eventMessageTimerSec > 0f) {
            eventMessageTimerSec = (eventMessageTimerSec - dtReal.toFloat()).coerceAtLeast(0f)
        }

        // Update transient VFX even when paused
        updateVfx(dtReal.toFloat())

        if (config.isPaused) return

        // Handle Rewind playback from ring buffer
        if (config.isRewinding) {
            rewindAccumSec += dtReal
            if (rewindAccumSec >= 0.06) {
                rewindAccumSec = 0.0
                if (rewindBuffer.size > 1) {
                    val prev = rewindBuffer.removeLast()
                    bodies = prev.bodies.toMutableList()
                    elapsedYears = prev.elapsedYears
                } else {
                    config = config.copy(isRewinding = false, isPaused = true)
                    postEvent("Reached earliest rewind buffer")
                }
            }
            return
        }

        // Record rewind keyframe every 0.35 real seconds
        rewindAccumSec += dtReal
        if (rewindAccumSec >= 0.35) {
            rewindAccumSec = 0.0
            pushRewindKeyFrame()
        }

        val totalSimDt = config.timeScaleYearsPerSec * dtReal
        if (totalSimDt <= 0.0) return

        // Adaptive substep calculation based on time scale and closest dynamical encounter
        val substeps = computeAdaptiveSubsteps(totalSimDt)
        val subDt = totalSimDt / substeps

        tidalFilaments.clear()
        for (stepIdx in 0 until substeps) {
            val isLastSubstep = stepIdx == substeps - 1
            when (config.integrator) {
                IntegratorMode.VELOCITY_VERLET -> integrateVelocityVerlet(subDt)
                IntegratorMode.RK4 -> integrateRK4(subDt)
            }
            processBlackHolePhysicsAndTidalDisruption(subDt, generateFilaments = isLastSubstep)
            processCollisions()
        }

        // Spin rotation, cooling, radiative equilibrium temperature, stellar evolution & orbit trails once per tick
        updateSpinsCoolingAndTrails(totalSimDt, dtReal)
        if (config.stellarEvolutionEnabled || config.stellarEvolutionRateGyrPerSec > 0.0) {
            processStellarEvolution(dtReal, totalSimDt)
        }

        elapsedYears += totalSimDt
    }

    private fun computeAdaptiveSubsteps(totalSimDt: Double): Int {
        var minDynTime = Double.MAX_VALUE
        val n = bodies.size
        // Sample major gravitating bodies to estimate shortest dynamical timescale
        for (i in 0 until n) {
            val bi = bodies[i]
            if (bi.isBackgroundCluster && i % 4 != 0) continue
            for (j in i + 1 until n) {
                val bj = bodies[j]
                if (bj.isBackgroundCluster && bj.massSolar < 1e-4) continue
                val dx = bj.pos.x - bi.pos.x
                val dy = bj.pos.y - bi.pos.y
                val dz = bj.pos.z - bi.pos.z
                val r2 = dx * dx + dy * dy + dz * dz + 1e-6
                val mSum = (bi.massSolar + bj.massSolar).coerceAtLeast(1e-9)
                val r = sqrt(r2)
                val tDyn = sqrt(r2 * r / (AstroConstants.G_AU * mSum))
                if (tDyn < minDynTime) minDynTime = tDyn
            }
        }
        val desiredSubDt = (minDynTime * 0.08).coerceAtLeast(1e-5)
        val needed = ceil(totalSimDt / desiredSubDt).toInt()
        return needed.coerceIn(2, 36)
    }

    private fun computeAccelerations(
        posX: DoubleArray,
        posY: DoubleArray,
        posZ: DoubleArray,
        outAx: DoubleArray,
        outAy: DoubleArray,
        outAz: DoubleArray,
        n: Int
    ) {
        for (i in 0 until n) {
            outAx[i] = 0.0
            outAy[i] = 0.0
            outAz[i] = 0.0
        }
        val g = AstroConstants.G_AU
        for (i in 0 until n) {
            val bi = bodies[i]
            val mi = bi.massSolar
            val pix = posX[i]; val piy = posY[i]; val piz = posZ[i]
            val radI = bi.physicsRadiusAu * 2.0

            for (j in i + 1 until n) {
                val bj = bodies[j]
                // Skip asteroid-to-asteroid N^2 force to keep 200+ asteroids ultra-fast (still pulled by Sun & all planets!)
                if (bi.isBackgroundCluster && bj.isBackgroundCluster) continue

                val dx = posX[j] - pix
                val dy = posY[j] - piy
                val dz = posZ[j] - piz
                val radJ = bj.physicsRadiusAu * 2.0
                val soft = max(radI + radJ, 1e-5)
                val r2 = dx * dx + dy * dy + dz * dz + soft * soft
                val invR = 1.0 / sqrt(r2)
                val invR3 = invR * invR * invR
                val gInvR3 = g * invR3

                val fj = bj.massSolar * gInvR3
                val fi = mi * gInvR3

                outAx[i] += dx * fj
                outAy[i] += dy * fj
                outAz[i] += dz * fj

                outAx[j] -= dx * fi
                outAy[j] -= dy * fi
                outAz[j] -= dz * fi
            }
        }
    }

    private fun integrateVelocityVerlet(dt: Double) {
        val n = bodies.size
        if (n == 0) return
        ensureScratchBuffers(n)

        val px = DoubleArray(n) { bodies[it].pos.x }
        val py = DoubleArray(n) { bodies[it].pos.y }
        val pz = DoubleArray(n) { bodies[it].pos.z }
        val vx = DoubleArray(n) { bodies[it].vel.x }
        val vy = DoubleArray(n) { bodies[it].vel.y }
        val vz = DoubleArray(n) { bodies[it].vel.z }

        computeAccelerations(px, py, pz, accX, accY, accZ, n)

        val halfDt = 0.5 * dt
        val halfDt2 = 0.5 * dt * dt
        for (i in 0 until n) {
            px[i] += vx[i] * dt + accX[i] * halfDt2
            py[i] += vy[i] * dt + accY[i] * halfDt2
            pz[i] += vz[i] * dt + accZ[i] * halfDt2
            vx[i] += accX[i] * halfDt
            vy[i] += accY[i] * halfDt
            vz[i] += accZ[i] * halfDt
        }

        computeAccelerations(px, py, pz, accX, accY, accZ, n)

        for (i in 0 until n) {
            vx[i] += accX[i] * halfDt
            vy[i] += accY[i] * halfDt
            vz[i] += accZ[i] * halfDt
            bodies[i] = bodies[i].copy(
                pos = Vec3d(px[i], py[i], pz[i]),
                vel = Vec3d(vx[i], vy[i], vz[i])
            )
        }
    }

    private fun integrateRK4(dt: Double) {
        val n = bodies.size
        if (n == 0) return
        ensureScratchBuffers(n)

        val x0 = DoubleArray(n) { bodies[it].pos.x }
        val y0 = DoubleArray(n) { bodies[it].pos.y }
        val z0 = DoubleArray(n) { bodies[it].pos.z }
        val vx0 = DoubleArray(n) { bodies[it].vel.x }
        val vy0 = DoubleArray(n) { bodies[it].vel.y }
        val vz0 = DoubleArray(n) { bodies[it].vel.z }

        val k1ax = DoubleArray(n); val k1ay = DoubleArray(n); val k1az = DoubleArray(n)
        computeAccelerations(x0, y0, z0, k1ax, k1ay, k1az, n)

        val half = 0.5 * dt
        val tx = DoubleArray(n); val ty = DoubleArray(n); val tz = DoubleArray(n)
        val tvx = DoubleArray(n); val tvy = DoubleArray(n); val tvz = DoubleArray(n)
        for (i in 0 until n) {
            tx[i] = x0[i] + vx0[i] * half; ty[i] = y0[i] + vy0[i] * half; tz[i] = z0[i] + vz0[i] * half
            tvx[i] = vx0[i] + k1ax[i] * half; tvy[i] = vy0[i] + k1ay[i] * half; tvz[i] = vz0[i] + k1az[i] * half
        }
        val k2ax = DoubleArray(n); val k2ay = DoubleArray(n); val k2az = DoubleArray(n)
        computeAccelerations(tx, ty, tz, k2ax, k2ay, k2az, n)

        val vx2 = DoubleArray(n); val vy2 = DoubleArray(n); val vz2 = DoubleArray(n)
        for (i in 0 until n) {
            vx2[i] = vx0[i] + k2ax[i] * half; vy2[i] = vy0[i] + k2ay[i] * half; vz2[i] = vz0[i] + k2az[i] * half
            tx[i] = x0[i] + tvx[i] * half; ty[i] = y0[i] + tvy[i] * half; tz[i] = z0[i] + tvz[i] * half
        }
        val k3ax = DoubleArray(n); val k3ay = DoubleArray(n); val k3az = DoubleArray(n)
        computeAccelerations(tx, ty, tz, k3ax, k3ay, k3az, n)

        for (i in 0 until n) {
            tx[i] = x0[i] + vx2[i] * dt; ty[i] = y0[i] + vy2[i] * dt; tz[i] = z0[i] + vz2[i] * dt
            tvx[i] = vx0[i] + k3ax[i] * dt; tvy[i] = vy0[i] + k3ay[i] * dt; tvz[i] = vz0[i] + k3az[i] * dt
        }
        val k4ax = DoubleArray(n); val k4ay = DoubleArray(n); val k4az = DoubleArray(n)
        computeAccelerations(tx, ty, tz, k4ax, k4ay, k4az, n)

        val sixth = dt / 6.0
        for (i in 0 until n) {
            val nx = x0[i] + sixth * (vx0[i] + 2.0 * tvx[i] + 2.0 * vx2[i] + tvx[i])
            val ny = y0[i] + sixth * (vy0[i] + 2.0 * tvy[i] + 2.0 * vy2[i] + tvy[i])
            val nz = z0[i] + sixth * (vz0[i] + 2.0 * tvz[i] + 2.0 * vz2[i] + tvz[i])
            val nvx = vx0[i] + sixth * (k1ax[i] + 2.0 * k2ax[i] + 2.0 * k3ax[i] + k4ax[i])
            val nvy = vy0[i] + sixth * (k1ay[i] + 2.0 * k2ay[i] + 2.0 * k3ay[i] + k4ay[i])
            val nvz = vz0[i] + sixth * (k1az[i] + 2.0 * k2az[i] + 2.0 * k3az[i] + k4az[i])
            bodies[i] = bodies[i].copy(pos = Vec3d(nx, ny, nz), vel = Vec3d(nvx, nvy, nvz))
        }
    }

    private fun processBlackHolePhysicsAndTidalDisruption(subDt: Double, generateFilaments: Boolean) {
        val bhIndices = bodies.indices.filter { bodies[it].type == BodyType.BLACK_HOLE }
        if (bhIndices.isEmpty()) {
            // Ensure no stale tidal stretch lingers if all black holes were removed
            for (i in bodies.indices) {
                val b = bodies[i]
                if (b.tidalStretchAmount > 0f || b.tidalStress > 0f || b.tidalTargetBhId != null) {
                    bodies[i] = b.copy(tidalStress = 0f, tidalStretchAmount = 0f, tidalTargetBhId = null)
                }
            }
            return
        }

        val toRemove = HashSet<Int>()

        // Binary black hole gravitational-wave inspiral damping when close
        if (bhIndices.size >= 2) {
            for (a in bhIndices.indices) {
                for (b in a + 1 until bhIndices.size) {
                    val i = bhIndices[a]; val j = bhIndices[b]
                    val bi = bodies[i]; val bj = bodies[j]
                    val d = (bj.pos - bi.pos).length()
                    if (d < 2.2) {
                        val damp = (1.0 - 0.14 * subDt).coerceIn(0.95, 1.0)
                        bodies[i] = bi.copy(vel = bi.vel * damp)
                        bodies[j] = bj.copy(vel = bj.vel * damp)
                    }
                }
            }
        }

        val stretchedByBhThisStep = HashSet<Int>()

        for (bhIdx in bhIndices) {
            var bh = bodies[bhIdx]
            val bhRad = bh.getVisualRadiusAu(config.trueScaleMode)
            // Wide visible Roche/tidal zone so stars & planets visibly spaghettify & stream into an accretion disk
            val massScale = (1.0 + ln(max(1.0, bh.massSolar)) * 0.25).coerceIn(1.0, 2.8)
            val tidalLimit = bhRad * (5.8 + massScale)

            for (j in bodies.indices) {
                if (j == bhIdx || j in toRemove) continue
                val victim = bodies[j]
                if (victim.type == BodyType.BLACK_HOLE || victim.isBackgroundCluster) continue

                val toBh = bh.pos - victim.pos
                val dist = toBh.length()
                val victimVisRad = victim.getVisualRadiusAu(config.trueScaleMode)
                val effectiveLimit = tidalLimit + victimVisRad * 2.5

                if (dist < effectiveLimit) {
                    stretchedByBhThisStep.add(j)
                    val stretchDir = toBh.normalized()
                    val stress = ((effectiveLimit - dist) / effectiveLimit).toFloat().coerceIn(0f, 1f)
                    // Smooth Roche elongation capped at 1.85 so geometry stays fluid instead of rigid cigar needles
                    val stretchAmount = (stress.pow(1.15f) * 1.85f).coerceIn(0.05f, 1.85f)

                    // Start accreting mass strongly once inside inner 78% of tidal zone
                    val shredWeight = ((stress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    val shredFraction = (1.45 * shredWeight * subDt).coerceAtMost(0.42)
                    val absorbedMass = victim.massSolar * shredFraction
                    val remainingMass = victim.massSolar - absorbedMass

                    val earnedFloor = ((bh.absorbedMassSolar + absorbedMass) * 0.14).toFloat().coerceIn(0f, 0.95f)
                    bh = bh.copy(
                        massSolar = bh.massSolar + absorbedMass,
                        absorbedMassSolar = bh.absorbedMassSolar + absorbedMass,
                        radiusKm = 2.95325 * (bh.massSolar + absorbedMass),
                        accretionActivity = max(
                            earnedFloor,
                            (bh.accretionActivity + stress * 0.85f * subDt.toFloat() * 10f + shredWeight * 0.08f).coerceAtMost(2.5f)
                        )
                    )

                    // Choose authentic plasma stream color (golden-white for stars, molten orange for planets)
                    val streamColor = if (victim.type.isLuminous) {
                        val hot = kelvinToRgb(max(victim.temperatureK, 4800.0))
                        Triple(
                            (hot.first * 1.15f).coerceIn(0f, 1.4f),
                            (hot.second * 1.0f).coerceIn(0f, 1.2f),
                            (hot.third * 0.85f).coerceIn(0f, 1.1f)
                        )
                    } else {
                        Triple(1.0f, 0.56f + stress * 0.25f, 0.22f)
                    }

                    // Build curved spiral tidal stream ribbons anchored directly at the L1 facing limb of the victim
                    if (generateFilaments && tidalFilaments.size < 20 && stress > 0.08f) {
                        val spinUp = Vec3d(0.18, 0.96, 0.18).normalized()
                        val vRel = victim.vel - bh.vel
                        val angMom = (victim.pos - bh.pos).cross(vRel)
                        val orbitNormal = if (angMom.lengthSq() > 1e-8) angMom.normalized() else spinUp

                        val l1Tip = victim.pos + stretchDir * (victimVisRad * (0.85 + stretchAmount * 0.45))
                        val tipFromBh = l1Tip - bh.pos
                        val startRadius = tipFromBh.length().coerceAtLeast(bhRad * 1.30)
                        val baseAngle = atan2(tipFromBh.z, tipFromBh.x)
                        val yStart = tipFromBh.y

                        for (arm in 0 until 2) {
                            val pts = ArrayList<Vec3d>(20)
                            val armSign = if (arm == 0) 1.0 else -1.0
                            val targetRingR = bhRad * (1.75 + arm * 0.65)

                            for (s in 0..19) {
                                val t = s / 19.0
                                val r = startRadius * (1.0 - t).pow(1.25) + targetRingR * t
                                val swirlAngle = baseAngle + armSign * (0.04 * arm) + t * (PI * (1.45 + stress * 1.35))
                                val planeDir = Vec3d(cos(swirlAngle), 0.0, sin(swirlAngle))
                                val yOff = yStart * (1.0 - t) + sin(t * PI * 2.0) * bhRad * 0.14 * orbitNormal.y
                                val point = bh.pos + planeDir * r + Vec3d(0.0, yOff, 0.0)
                                pts.add(point)
                            }
                            tidalFilaments.add(
                                TidalStreamFilament(
                                    points = pts,
                                    color = streamColor,
                                    intensity = (0.55f + stress * 0.85f) * (if (arm == 0) 1.0f else 0.72f)
                                )
                            )
                        }
                    }

                    // Spawn glowing plasma stream nodes only for substantial stars/planets (no tiny pebble dust spam)
                    if (victim.massSolar > 1e-8 && particles.size < 680 && rng.nextFloat() < (0.45f + stress * 0.40f)) {
                        val radial = -stretchDir
                        val tangent = radial.cross(Vec3d(0.0, 1.0, 0.0)).let {
                            if (it.lengthSq() < 1e-6) Vec3d(1.0, 0.0, 0.0) else it.normalized()
                        }
                        val spawnPt = victim.pos + stretchDir * (victimVisRad * (0.8 + rng.nextDouble() * 0.6))
                        val orbitalSpeed = sqrt(AstroConstants.G_AU * bh.massSolar / max(dist, bhRad)).coerceIn(1.5, 16.0)
                        val streamVel = bh.vel + tangent * (orbitalSpeed * 0.88) + stretchDir * (orbitalSpeed * 0.35)
                        particles.add(
                            DebrisParticle(
                                x = spawnPt.x + (rng.nextDouble() - 0.5) * bhRad * 0.18,
                                y = spawnPt.y + (rng.nextDouble() - 0.5) * bhRad * 0.10,
                                z = spawnPt.z + (rng.nextDouble() - 0.5) * bhRad * 0.18,
                                vx = streamVel.x, vy = streamVel.y, vz = streamVel.z,
                                life = 0f, maxLife = 1.6f + rng.nextFloat() * 1.2f,
                                size = 1.4f + stress * 2.0f,
                                r = streamColor.first.coerceIn(0f, 1f),
                                g = streamColor.second.coerceIn(0f, 1f),
                                b = streamColor.third.coerceIn(0f, 1f),
                                targetBlackHoleId = bh.id
                            )
                        )
                    }

                    // Relativistic polar jet particles when accretion is high
                    if (bh.accretionActivity > 0.65f && particles.size < 680 && rng.nextFloat() < 0.32f) {
                        val sign = if (rng.nextBoolean()) 1.0 else -1.0
                        particles.add(
                            DebrisParticle(
                                x = bh.pos.x, y = bh.pos.y + sign * bhRad * 0.45, z = bh.pos.z,
                                vx = (rng.nextDouble() - 0.5) * 0.35,
                                vy = sign * (9.0 + rng.nextDouble() * 7.0),
                                vz = (rng.nextDouble() - 0.5) * 0.35,
                                life = 0f, maxLife = 1.1f, size = 1.8f,
                                r = 0.55f, g = 0.85f, b = 1.0f
                            )
                        )
                    }

                    val nowMs = System.currentTimeMillis()
                    if (nowMs - lastTidalEventLogMs > 2800L && stress > 0.3f) {
                        lastTidalEventLogMs = nowMs
                        postEvent("${victim.name} being spaghettified & accreted by ${bh.name}")
                    }

                    if (remainingMass <= victim.initialMassSolar * 0.08 || dist < bhRad * 1.12) {
                        toRemove.add(j)
                        val finalBhMass = bh.massSolar + remainingMass
                        bh = bh.copy(
                            massSolar = finalBhMass,
                            absorbedMassSolar = bh.absorbedMassSolar + remainingMass,
                            radiusKm = 2.95325 * finalBhMass,
                            accretionActivity = (bh.accretionActivity + 0.85f).coerceAtMost(2.5f)
                        )
                        postEvent("${victim.name} tidally shredded into ${bh.name}'s disk")
                    } else {
                        val scaleFactor = cbrt(remainingMass / victim.massSolar)
                        bodies[j] = victim.copy(
                            massSolar = remainingMass,
                            radiusKm = (victim.radiusKm * scaleFactor).coerceAtLeast(100.0),
                            crustHeat = (victim.crustHeat + stress * 0.45f).coerceAtMost(1f),
                            temperatureK = (victim.temperatureK + stress * 2200.0).coerceAtMost(14000.0),
                            tidalStress = stress,
                            tidalStretchDir = stretchDir,
                            tidalStretchAmount = stretchAmount,
                            tidalTargetBhId = bh.id
                        )
                    }
                }
            }

            // Hawking radiation evaporation if enabled
            if (config.hawkingEvaporation) {
                val evapRate = (0.35 / (bh.massSolar.coerceAtLeast(0.05).pow(1.4))) * subDt
                val newMass = bh.massSolar - evapRate
                if (newMass <= 0.05) {
                    toRemove.add(bhIdx)
                    shockwaves.add(
                        ShockwaveEvent(
                            id = nextEntityId++, pos = bh.pos, maxRadiusAu = 1.8,
                            ageSec = 0f, durationSec = 2.4f, color = Triple(0.75f, 0.92f, 1.0f)
                        )
                    )
                    spawnBurst(bh.pos, 70, Triple(0.8f, 0.95f, 1.0f), 4.5)
                    postEvent("${bh.name} evaporated via Hawking radiation!")
                    continue
                } else {
                    bh = bh.copy(massSolar = newMass, radiusKm = 2.95325 * newMass)
                }
            }

            bodies[bhIdx] = bh
        }

        // Smoothly relax tidal stretch for bodies outside any black hole's tidal zone
        for (i in bodies.indices) {
            if (i in toRemove || i in stretchedByBhThisStep) continue
            val b = bodies[i]
            if (b.tidalStretchAmount > 0f || b.tidalStress > 0f || b.tidalTargetBhId != null) {
                bodies[i] = b.copy(
                    tidalStress = (b.tidalStress - 0.06f).coerceAtLeast(0f),
                    tidalStretchAmount = (b.tidalStretchAmount - 0.12f).coerceAtLeast(0f),
                    tidalTargetBhId = if (b.tidalStretchAmount <= 0.12f) null else b.tidalTargetBhId
                )
            }
        }

        if (toRemove.isNotEmpty()) {
            val sorted = toRemove.sortedDescending()
            for (idx in sorted) bodies.removeAt(idx)
        }
    }

    private fun processCollisions() {
        val n = bodies.size
        if (n < 2) return
        val removed = BooleanArray(n)
        val newlySpawned = mutableListOf<CelestialBody>()

        for (i in 0 until n) {
            if (removed[i]) continue
            var bi = bodies[i]
            val ri = bi.getVisualRadiusAu(config.trueScaleMode)

            for (j in i + 1 until n) {
                if (removed[j]) continue
                val bj = bodies[j]
                if (bi.isBackgroundCluster && bj.isBackgroundCluster) continue
                val rj = bj.getVisualRadiusAu(config.trueScaleMode)

                val diff = bj.pos - bi.pos
                val distSq = diff.lengthSq()
                val minDist = (ri + rj) * 0.88
                if (distSq < minDist * minDist) {
                    val primaryIsI = bi.massSolar >= bj.massSolar
                    val primary = if (primaryIsI) bi else bj
                    val secondary = if (primaryIsI) bj else bi
                    val primaryIdx = if (primaryIsI) i else j
                    val secondaryIdx = if (primaryIsI) j else i

                    val totalMass = (primary.massSolar + secondary.massSolar).coerceAtLeast(1e-18)
                    val comVel = (primary.vel * primary.massSolar + secondary.vel * secondary.massSolar) / totalMass
                    val relVel = (secondary.vel - primary.vel)
                    val relSpeed = relVel.length()

                    // Black Hole merger or absorption (Black Hole always consumes non-BH regardless of mass!)
                    if (bi.type == BodyType.BLACK_HOLE || bj.type == BodyType.BLACK_HOLE) {
                        val bhIsI = when {
                            bi.type == BodyType.BLACK_HOLE && bj.type == BodyType.BLACK_HOLE -> bi.massSolar >= bj.massSolar
                            bi.type == BodyType.BLACK_HOLE -> true
                            else -> false
                        }
                        val bhBody = if (bhIsI) bi else bj
                        val otherBody = if (bhIsI) bj else bi
                        val bhSlot = if (bhIsI) i else j
                        val otherSlot = if (bhIsI) j else i

                        removed[otherSlot] = true
                        val isMerger = otherBody.type == BodyType.BLACK_HOLE
                        val gainActivity = if (isMerger) 1.25f else (0.35f + (otherBody.massSolar / bhBody.massSolar.coerceAtLeast(0.1)).toFloat() * 0.8f).coerceIn(0.25f, 1.4f)
                        val updated = bhBody.copy(
                            massSolar = totalMass,
                            absorbedMassSolar = bhBody.absorbedMassSolar + otherBody.massSolar,
                            radiusKm = 2.95325 * totalMass,
                            vel = comVel,
                            accretionActivity = (bhBody.accretionActivity + gainActivity).coerceAtMost(2.5f)
                        )
                        bodies[bhSlot] = updated
                        if (bhIsI) bi = updated
                        if (isMerger || otherBody.massSolar >= 0.05) {
                            shockwaves.add(
                                ShockwaveEvent(
                                    id = nextEntityId++,
                                    pos = bhBody.pos,
                                    maxRadiusAu = if (isMerger) 3.5 else 1.4,
                                    ageSec = 0f,
                                    durationSec = if (isMerger) 3.0f else 1.4f,
                                    color = if (isMerger) Triple(0.70f, 0.45f, 1.0f) else Triple(1.0f, 0.72f, 0.35f),
                                    isGravitationalWave = isMerger
                                )
                            )
                        }
                        if (isMerger) {
                            spawnBurst(bhBody.pos, 50, Triple(0.75f, 0.55f, 1.0f), 3.0)
                        }
                        postEvent(if (isMerger) "Black Hole Merger! Gravitational Waves Emitted" else "${bhBody.name} absorbed ${otherBody.name}")
                        continue
                    }

                    // Normal collision: check kinetic vs binding energy for fragmentation vs merge
                    val reducedMass = (primary.massSolar * secondary.massSolar) / totalMass
                    val kineticEnergy = 0.5 * reducedMass * relSpeed * relSpeed
                    val bindingEnergy = AstroConstants.G_AU * primary.massSolar * secondary.massSolar / max(ri + rj, 1e-6)
                    val primaryIsFluidOrStar = primary.type.isLuminous || primary.type == BodyType.GAS_GIANT || primary.type == BodyType.ICE_GIANT
                    val isTinyImpactor = secondary.massSolar < primary.massSolar * 0.05
                    val isCatastrophic = kineticEnergy > bindingEnergy * 2.2 && secondary.massSolar > primary.massSolar * 0.08 && !primary.type.isLuminous

                    // Only solid terrestrial/rocky bodies retain impact craters!
                    val impactDir = (secondary.pos - primary.pos).normalized()
                    val updatedCraters = if (!primaryIsFluidOrStar) {
                        val craterRadius = (0.18f + (secondary.radiusKm / primary.radiusKm).toFloat() * 0.45f).coerceIn(0.10f, 0.55f)
                        val newCrater = CraterMark(
                            nx = impactDir.x.toFloat(),
                            ny = impactDir.y.toFloat(),
                            nz = impactDir.z.toFloat(),
                            radiusRad = craterRadius,
                            heat = 1.0f
                        )
                        (primary.craters + newCrater).takeLast(8)
                    } else primary.craters

                    val massRatio = (secondary.massSolar / primary.massSolar).toFloat()
                    val heatBump = if (primaryIsFluidOrStar && isTinyImpactor) {
                        0.0f
                    } else {
                        (0.35f + massRatio * 2.5f).coerceIn(0.25f, 1.0f)
                    }
                    val newTemp = if (primaryIsFluidOrStar && isTinyImpactor) {
                        primary.temperatureK
                    } else {
                        (primary.temperatureK + 1800.0 * heatBump).coerceAtMost(12000.0)
                    }
                    val newRadiusKm = primary.radiusKm * cbrt(totalMass / primary.massSolar)

                    removed[secondaryIdx] = true
                    val mergedPrimary = primary.copy(
                        massSolar = if (isCatastrophic) primary.massSolar * 0.85 else totalMass,
                        radiusKm = if (isCatastrophic) primary.radiusKm * 0.94 else newRadiusKm,
                        vel = comVel,
                        temperatureK = newTemp,
                        crustHeat = if (primaryIsFluidOrStar) primary.crustHeat else (primary.crustHeat + heatBump).coerceAtMost(1.0f),
                        craters = updatedCraters
                    )
                    bodies[primaryIdx] = mergedPrimary
                    if (primaryIsI) bi = mergedPrimary

                    val impactPos = primary.pos + impactDir * primary.getVisualRadiusAu(config.trueScaleMode)

                    // Suppress useless rocky explosion/debris particles when tiny impactors vaporize in stars or gas/ice giants!
                    val shouldEmitParticles = !(primaryIsFluidOrStar && isTinyImpactor)
                    if (shouldEmitParticles) {
                        val burstColor = if (primary.type.isLuminous) primary.albedoColor else Triple(1.0f, 0.48f, 0.15f)
                        spawnBurst(impactPos, if (isCatastrophic) 70 else 28, burstColor, relSpeed * 0.40 + 0.7)
                        shockwaves.add(
                            ShockwaveEvent(
                                id = nextEntityId++,
                                pos = impactPos,
                                maxRadiusAu = (ri + rj) * (if (isCatastrophic) 8.0 else 3.8),
                                ageSec = 0f,
                                durationSec = 1.3f,
                                color = burstColor
                            )
                        )
                    }

                    if (isCatastrophic && bodies.size + newlySpawned.size < 280) {
                        val fragCount = 4
                        val fragMass = (primary.massSolar * 0.15 + secondary.massSolar * 0.5) / fragCount
                        for (f in 0 until fragCount) {
                            val dir = Vec3d(
                                rng.nextDouble() - 0.5,
                                (rng.nextDouble() - 0.5) * 0.6,
                                rng.nextDouble() - 0.5
                            ).normalized()
                            val offset = dir * (ri * 1.6)
                            val ejectVel = comVel + dir * (relSpeed * 0.45 + 0.35)
                            newlySpawned.add(
                                CelestialBody(
                                    id = nextEntityId++,
                                    name = "${secondary.name} Fragment ${f + 1}",
                                    type = BodyType.DEBRIS,
                                    pos = primary.pos + offset,
                                    vel = ejectVel,
                                    massSolar = fragMass,
                                    radiusKm = (secondary.radiusKm * 0.42).coerceAtLeast(180.0),
                                    temperatureK = 2600.0,
                                    crustHeat = 1.0f,
                                    albedoColor = Triple(0.45f, 0.32f, 0.28f),
                                    seed = rng.nextFloat() * 100f
                                )
                            )
                        }
                        postEvent("Catastrophic impact: ${secondary.name} shattered against ${primary.name}!")
                    } else if (!primary.isBackgroundCluster && !secondary.isBackgroundCluster && (!primaryIsFluidOrStar || !isTinyImpactor)) {
                        postEvent("${secondary.name} impacted ${primary.name}")
                    }
                }
            }
        }

        if (removed.any { it }) {
            val filtered = mutableListOf<CelestialBody>()
            for (idx in bodies.indices) {
                if (!removed[idx]) filtered.add(bodies[idx])
            }
            filtered.addAll(newlySpawned)
            bodies = filtered
            ensureScratchBuffers(bodies.size)
        } else if (newlySpawned.isNotEmpty()) {
            bodies.addAll(newlySpawned)
            ensureScratchBuffers(bodies.size)
        }
    }

    private fun updateSpinsCoolingAndTrails(simDtYears: Double, realDtSec: Double) {
        val dtHours = simDtYears * 8766.0
        val stars = bodies.filter { it.type.isLuminous && it.luminositySolar > 0.0 }

        for (i in bodies.indices) {
            val b = bodies[i]
            val deltaDeg = if (abs(b.rotationPeriodHours) > 1e-3) {
                (dtHours / b.rotationPeriodHours) * 360.0
            } else 0.0
            val newSpin = (b.spinAngleDeg + deltaDeg) % 360.0

            // Radiative cooling of impact melt crust
            val coolRate = 0.08f * realDtSec.toFloat()
            val newCrustHeat = (b.crustHeat - coolRate).coerceAtLeast(0f)
            val newCraters = if (b.craters.isNotEmpty()) {
                b.craters.map { it.copy(heat = (it.heat - coolRate * 0.8f).coerceAtLeast(0f)) }
            } else b.craters

            // Radiative equilibrium temperature from active stars + atmospheric greenhouse warming
            var eqTemp = b.temperatureK
            if (!b.type.isLuminous && b.type != BodyType.BLACK_HOLE && stars.isNotEmpty()) {
                var fluxSum = 0.0
                for (s in stars) {
                    val d2 = (b.pos - s.pos).lengthSq().coerceAtLeast(1e-4)
                    fluxSum += s.luminositySolar / d2
                }
                val greenhouse = 1.0 + b.atmosphereDensity.toDouble().coerceAtLeast(0.0) * 0.22
                val targetEq = (278.0 * greenhouse * fluxSum.pow(0.25)).coerceIn(30.0, 5200.0)
                val targetWithLava = targetEq + newCrustHeat * 1800.0
                eqTemp = b.temperatureK + (targetWithLava - b.temperatureK) * (realDtSec * 0.45).coerceIn(0.0, 0.25)
            }

            // Retain persistent accretion disk proportional to absorbed matter; freshly spawned isolated BH stays disk-free at 0f
            val newAccretion = if (b.type == BodyType.BLACK_HOLE) {
                val earnedFloor = (b.absorbedMassSolar * 0.14).toFloat().coerceIn(0f, 0.95f)
                (b.accretionActivity - 0.06f * realDtSec.toFloat()).coerceAtLeast(earnedFloor)
            } else 0f

            // Record orbit trail point if moved meaningfully
            val newTrail = if (!b.isBackgroundCluster && config.showOrbits) {
                val lastPt = b.trail.lastOrNull()
                if (lastPt == null || (b.pos - lastPt).lengthSq() > 0.00025) {
                    (b.trail + b.pos).takeLast(75)
                } else b.trail
            } else b.trail

            bodies[i] = b.copy(
                spinAngleDeg = newSpin,
                crustHeat = newCrustHeat,
                craters = newCraters,
                temperatureK = eqTemp,
                accretionActivity = newAccretion,
                trail = newTrail
            )
        }
    }

    private fun processStellarEvolution(realDtSec: Double, simDtYears: Double) {
        val gyrStep = if (config.stellarEvolutionRateGyrPerSec > 0.0) {
            config.stellarEvolutionRateGyrPerSec * realDtSec
        } else {
            simDtYears / 1_000_000_000.0
        }
        if (gyrStep <= 0.0) return

        for (i in bodies.indices) {
            val s = bodies[i]
            if (s.type != BodyType.STAR && s.type != BodyType.RED_GIANT) continue
            val newAge = s.ageGyr + gyrStep
            // Main sequence lifetime ~ 10 * (M / M_sun)^(-2.5) Gyr
            val msLife = (10.0 * s.initialMassSolar.coerceAtLeast(0.2).pow(-2.5)).coerceIn(0.02, 80.0)

            when {
                newAge < msLife -> {
                    bodies[i] = s.copy(ageGyr = newAge, evolutionStage = EvolutionStage.MAIN_SEQUENCE)
                }
                newAge < msLife * 1.18 -> {
                    // Red Giant expansion phase!
                    val prog = ((newAge - msLife) / (msLife * 0.18)).coerceIn(0.0, 1.0)
                    val targetRadius = 696_340.0 * (4.0 + prog * 28.0)
                    val targetTemp = (5778.0 - prog * 2700.0).coerceIn(2800.0, 6000.0)
                    val targetLum = (1.0 + prog * 450.0)
                    val wasMs = s.type == BodyType.STAR
                    bodies[i] = s.copy(
                        type = BodyType.RED_GIANT,
                        ageGyr = newAge,
                        radiusKm = targetRadius,
                        temperatureK = targetTemp,
                        luminositySolar = targetLum,
                        albedoColor = kelvinToRgb(targetTemp),
                        evolutionStage = EvolutionStage.RED_GIANT
                    )
                    if (wasMs) postEvent("${s.name} expanded into a Red Giant!")
                }
                else -> {
                    // End of stellar life: Supernova for massive stars, White Dwarf + Planetary Nebula for solar-mass
                    if (s.initialMassSolar >= 8.0) {
                        detonateStarAtIndex(i)
                    } else {
                        bodies[i] = s.copy(
                            type = BodyType.WHITE_DWARF,
                            ageGyr = newAge,
                            massSolar = (s.initialMassSolar * 0.55).coerceAtLeast(0.45),
                            radiusKm = 7000.0,
                            temperatureK = 28000.0,
                            luminositySolar = 0.08,
                            albedoColor = kelvinToRgb(28000.0),
                            evolutionStage = EvolutionStage.WHITE_DWARF
                        )
                        shockwaves.add(
                            ShockwaveEvent(
                                id = nextEntityId++, pos = s.pos, maxRadiusAu = 3.2,
                                ageSec = 0f, durationSec = 4.0f, color = Triple(0.35f, 0.85f, 1.0f)
                            )
                        )
                        spawnBurst(s.pos, 90, Triple(0.4f, 0.9f, 1.0f), 2.8)
                        postEvent("${s.name} shed a Planetary Nebula → White Dwarf")
                    }
                }
            }
        }
    }

    private fun detonateStarAtIndex(idx: Int) {
        val s = bodies[idx]
        val becomeBlackHole = s.initialMassSolar >= 20.0
        val remnantType = if (becomeBlackHole) BodyType.BLACK_HOLE else BodyType.NEUTRON_STAR
        val remnantMass = if (becomeBlackHole) s.initialMassSolar * 0.45 else 1.65
        bodies[idx] = s.copy(
            name = "${s.name} Remnant",
            type = remnantType,
            massSolar = remnantMass,
            radiusKm = if (becomeBlackHole) 2.95 * remnantMass else 12.0,
            temperatureK = if (becomeBlackHole) 0.0 else 180_000.0,
            luminositySolar = if (becomeBlackHole) 0.0 else 0.5,
            albedoColor = if (becomeBlackHole) Triple(0f, 0f, 0f) else Triple(0.65f, 0.85f, 1.0f),
            accretionActivity = if (becomeBlackHole) 1.5f else 0f,
            evolutionStage = if (becomeBlackHole) EvolutionStage.BLACK_HOLE else EvolutionStage.NEUTRON_STAR
        )
        shockwaves.add(
            ShockwaveEvent(
                id = nextEntityId++, pos = s.pos, maxRadiusAu = 9.0,
                ageSec = 0f, durationSec = 3.8f, color = Triple(0.65f, 0.85f, 1.0f)
            )
        )
        spawnBurst(s.pos, 160, Triple(1.0f, 0.72f, 0.35f), 8.5)

        // Blast wave pushes and superheats nearby planets
        for (j in bodies.indices) {
            if (j == idx) continue
            val b = bodies[j]
            val d = b.pos - s.pos
            val dist = d.length().coerceAtLeast(0.1)
            if (dist < 12.0) {
                val impulse = d.normalized() * (6.5 / dist)
                bodies[j] = b.copy(
                    vel = b.vel + impulse,
                    crustHeat = 1.0f,
                    temperatureK = (b.temperatureK + 3500.0 / dist).coerceAtMost(9000.0)
                )
            }
        }
        postEvent("SUPERNOVA! ${s.name} collapsed into a ${remnantType.label}")
    }

    // ==================== COSMIC WEAPONS & EVENTS ====================

    @Synchronized
    fun fireAsteroidStrike(targetId: Long) {
        val target = getBodyById(targetId) ?: return
        val dir = Vec3d(rng.nextDouble() - 0.5, (rng.nextDouble() - 0.5) * 0.3, rng.nextDouble() - 0.5).normalized()
        val rVis = target.getVisualRadiusAu(config.trueScaleMode)
        val spawnPos = target.pos + dir * (rVis * 6.5 + 0.12)
        val strikeVel = target.vel - dir * 18.0
        spawnBody(
            CelestialBody(
                id = 0L,
                name = "Hyper-Impactor",
                type = BodyType.ASTEROID,
                pos = spawnPos,
                vel = strikeVel,
                massSolar = (target.massSolar * 0.08).coerceIn(1e-9, 5e-4),
                radiusKm = (target.radiusKm * 0.28).coerceIn(500.0, 4500.0),
                temperatureK = 1400.0,
                crustHeat = 0.85f,
                albedoColor = Triple(0.85f, 0.42f, 0.22f),
                seed = rng.nextFloat() * 100f
            )
        )
        postEvent("Asteroid strike inbound to ${target.name}!")
    }

    @Synchronized
    fun fireNuke(targetId: Long) {
        val idx = bodies.indexOfFirst { it.id == targetId }
        if (idx < 0) return
        val t = bodies[idx]
        val nDir = Vec3d(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5, rng.nextDouble() - 0.5).normalized()
        val newCrater = CraterMark(nDir.x.toFloat(), nDir.y.toFloat(), nDir.z.toFloat(), 0.32f, 1.0f)
        bodies[idx] = t.copy(
            crustHeat = (t.crustHeat + 0.75f).coerceAtMost(1.0f),
            temperatureK = (t.temperatureK + 2200.0).coerceAtMost(7500.0),
            craters = (t.craters + newCrater).takeLast(8)
        )
        val blastPos = t.pos + nDir * t.getVisualRadiusAu(config.trueScaleMode)
        shockwaves.add(
            ShockwaveEvent(
                id = nextEntityId++, pos = blastPos, maxRadiusAu = t.getVisualRadiusAu(config.trueScaleMode) * 4.5,
                ageSec = 0f, durationSec = 1.6f, color = Triple(1.0f, 0.65f, 0.15f)
            )
        )
        spawnBurst(blastPos, 55, Triple(1.0f, 0.75f, 0.2f), 1.6)
        postEvent("Thermonuclear detonation on ${t.name}!")
    }

    @Synchronized
    fun triggerSupernova(targetId: Long) {
        val idx = bodies.indexOfFirst { it.id == targetId }
        if (idx >= 0) {
            detonateStarAtIndex(idx)
        }
    }

    @Synchronized
    fun fireGammaRayBurst(originId: Long?) {
        val originBody = originId?.let { getBodyById(it) } ?: bodies.firstOrNull() ?: return
        val beamDir = Vec3d(rng.nextDouble() - 0.5, (rng.nextDouble() - 0.5) * 0.25, rng.nextDouble() - 0.5).normalized()
        gammaBursts.add(
            GammaBurstBeam(
                id = nextEntityId++, origin = originBody.pos, dir = beamDir,
                ageSec = 0f, durationSec = 2.6f, color = Triple(0.45f, 0.85f, 1.0f)
            )
        )
        // Superheat and push any bodies near the collimated bipolar beam axis
        for (i in bodies.indices) {
            val b = bodies[i]
            if (b.id == originBody.id) continue
            val rel = b.pos - originBody.pos
            val proj = rel.dot(beamDir)
            val perpDist = (rel - beamDir * proj).length()
            if (perpDist < 0.85) {
                bodies[i] = b.copy(
                    temperatureK = (b.temperatureK + 4200.0).coerceAtMost(15000.0),
                    crustHeat = 1.0f,
                    atmosphereDensity = (b.atmosphereDensity * 0.4f),
                    vel = b.vel + rel.normalized() * 1.4
                )
            }
        }
        postEvent("Relativistic Gamma-Ray Burst fired from ${originBody.name}!")
    }

    @Synchronized
    fun splitPlanet(targetId: Long) {
        val idx = bodies.indexOfFirst { it.id == targetId }
        if (idx < 0) return
        val b = bodies[idx]
        if (b.type == BodyType.BLACK_HOLE) {
            postEvent("Cannot split a Black Hole singularity")
            return
        }
        val sepDir = Vec3d(rng.nextDouble() - 0.5, 0.15, rng.nextDouble() - 0.5).normalized()
        val rVis = b.getVisualRadiusAu(config.trueScaleMode)
        val halfMass = b.massSolar * 0.46
        val halfRadius = b.radiusKm * 0.78
        val kick = sepDir * 1.4

        bodies[idx] = b.copy(
            name = "${b.name} Alpha",
            pos = b.pos + sepDir * (rVis * 1.15),
            vel = b.vel + kick,
            massSolar = halfMass,
            radiusKm = halfRadius,
            crustHeat = 1.0f,
            temperatureK = (b.temperatureK + 2400.0).coerceAtMost(7000.0)
        )
        bodies.add(
            b.copy(
                id = nextEntityId++,
                name = "${b.name} Beta",
                pos = b.pos - sepDir * (rVis * 1.15),
                vel = b.vel - kick,
                massSolar = halfMass,
                radiusKm = halfRadius,
                crustHeat = 1.0f,
                temperatureK = (b.temperatureK + 2400.0).coerceAtMost(7000.0),
                trail = emptyList()
            )
        )
        shockwaves.add(
            ShockwaveEvent(
                id = nextEntityId++, pos = b.pos, maxRadiusAu = rVis * 7.0,
                ageSec = 0f, durationSec = 2.0f, color = Triple(1.0f, 0.42f, 0.25f)
            )
        )
        spawnBurst(b.pos, 95, Triple(1.0f, 0.55f, 0.2f), 2.5)
        ensureScratchBuffers(bodies.size)
        postEvent("${b.name} cleaved in two!")
    }

    @Synchronized
    fun launchRogueStar() {
        val entryAngle = rng.nextDouble() * PI * 2.0
        val startPos = Vec3d(cos(entryAngle) * 14.0, (rng.nextDouble() - 0.5) * 1.5, sin(entryAngle) * 14.0)
        val targetOffset = Vec3d((rng.nextDouble() - 0.5) * 1.5, 0.0, (rng.nextDouble() - 0.5) * 1.5)
        val vel = (targetOffset - startPos).normalized() * 14.5
        spawnBody(
            CelestialBody(
                id = 0L,
                name = "Nemesis Rogue Star",
                type = BodyType.STAR,
                spectralClass = SpectralClass.K,
                pos = startPos,
                vel = vel,
                massSolar = 0.85,
                radiusKm = 560_000.0,
                temperatureK = 4300.0,
                luminositySolar = 0.45,
                albedoColor = kelvinToRgb(4300.0),
                seed = rng.nextFloat() * 100f
            )
        )
        postEvent("Rogue K-type star entering the system!")
    }

    /**
     * Runs a lightweight forward symplectic forecast to draw the Slingshot predicted trajectory curve.
     */
    @Synchronized
    fun predictTrajectory(startPos: Vec3d, startVel: Vec3d, steps: Int = 72, stepDt: Double = 0.014): List<Vec3d> {
        val attractors = bodies.filter { !it.isBackgroundCluster && it.massSolar > 1e-8 }
        val result = ArrayList<Vec3d>(steps)
        var px = startPos.x; var py = startPos.y; var pz = startPos.z
        var vx = startVel.x; var vy = startVel.y; var vz = startVel.z
        val g = AstroConstants.G_AU

        for (s in 0 until steps) {
            result.add(Vec3d(px, py, pz))
            var ax = 0.0; var ay = 0.0; var az = 0.0
            for (a in attractors) {
                val dx = a.pos.x - px
                val dy = a.pos.y - py
                val dz = a.pos.z - pz
                val r2 = dx * dx + dy * dy + dz * dz + 0.0025
                val invR3 = 1.0 / (r2 * sqrt(r2))
                val f = g * a.massSolar * invR3
                ax += dx * f; ay += dy * f; az += dz * f
            }
            vx += ax * stepDt; vy += ay * stepDt; vz += az * stepDt
            px += vx * stepDt; py += vy * stepDt; pz += vz * stepDt
        }
        return result
    }

    @Synchronized
    fun computeTelemetry(body: CelestialBody): BodyTelemetry {
        val speedKmS = body.vel.length() * AstroConstants.AU_YR_TO_KM_S
        val gMs2 = body.surfaceGravityMs2()
        val gEarth = gMs2 / 9.80665
        val vEsc = body.escapeVelocityKmS()
        val density = body.densityGCm3()

        // Find dominant gravitational attractor for orbital elements calculation
        val dominant = bodies
            .filter { it.id != body.id && it.massSolar > body.massSolar * 1.1 }
            .maxByOrNull { it.massSolar / (it.pos - body.pos).lengthSq().coerceAtLeast(1e-9) }

        var periodDays: Double? = null
        var semiMajorAu: Double? = null
        var ecc: Double? = null
        var rocheAu: Double? = null

        if (dominant != null) {
            val rVec = body.pos - dominant.pos
            val vVec = body.vel - dominant.vel
            val r = rVec.length().coerceAtLeast(1e-9)
            val v2 = vVec.lengthSq()
            val mu = AstroConstants.G_AU * (dominant.massSolar + body.massSolar)
            val specificEnergy = 0.5 * v2 - mu / r
            if (specificEnergy < -1e-9) {
                val a = -mu / (2.0 * specificEnergy)
                val h = rVec.cross(vVec)
                val eVec = (vVec.cross(h) / mu) - (rVec / r)
                semiMajorAu = a
                ecc = eVec.length().coerceIn(0.0, 0.9999)
                val periodYr = 2.0 * PI * sqrt(a * a * a / mu)
                periodDays = periodYr * 365.25
            }
            val rhoRatio = (dominant.densityGCm3() / density.coerceAtLeast(0.05)).coerceAtLeast(0.1)
            rocheAu = 2.44 * dominant.physicsRadiusAu * cbrt(rhoRatio)
        }

        val status = when {
            body.tidalStretchAmount > 0.18f || body.tidalStress > 0.12f ->
                "Spaghettifying into Black Hole (${(body.tidalStress * 100).toInt()}% stress)"
            body.type == BodyType.BLACK_HOLE && body.accretionActivity > 0.7f ->
                "Active Quasar Accretion • Polar Jets"
            body.type == BodyType.BLACK_HOLE && body.absorbedMassSolar > 1e-4 ->
                "Accreting Matter (+${String.format("%.2f", body.absorbedMassSolar)} M☉)"
            body.type == BodyType.BLACK_HOLE ->
                "Event Horizon • Gravitational Lens"
            body.type == BodyType.RED_GIANT || body.evolutionStage == EvolutionStage.RED_GIANT ->
                "Red Giant Envelope Expansion"
            body.type == BodyType.WHITE_DWARF ->
                "Degenerate White Dwarf Remnant"
            body.type == BodyType.NEUTRON_STAR ->
                "Rapidly Spinning Pulsar Remnant"
            body.type == BodyType.STAR && body.temperatureK > 15000 ->
                "Hot Luminous Star (${body.temperatureK.toInt()} K)"
            body.type == BodyType.STAR ->
                "Main Sequence Fusion (${body.temperatureK.toInt()} K)"
            body.crustHeat > 0.25f || (!body.type.isLuminous && body.temperatureK > 900.0) ->
                "Molten Crust (${body.temperatureK.toInt()} K)"
            dominant != null && semiMajorAu == null ->
                "Hyperbolic Escape Trajectory"
            body.type == BodyType.EARTH_LIKE && body.temperatureK in 240.0..325.0 ->
                "Habitable Zone • Liquid Water"
            else -> "Stable Keplerian Orbit"
        }

        return BodyTelemetry(
            body = body,
            speedKmS = speedKmS,
            surfaceGravityG = gEarth,
            escapeVelocityKmS = vEsc,
            densityGCm3 = density,
            orbitalPeriodDays = periodDays,
            semiMajorAxisAu = semiMajorAu,
            eccentricity = ecc,
            rocheLimitAu = rocheAu,
            schwarzschildKm = body.schwarzschildRadiusKm(),
            statusSummary = status
        )
    }

    private fun spawnBurst(origin: Vec3d, count: Int, color: Triple<Float, Float, Float>, speedScale: Double) {
        val room = (700 - particles.size).coerceAtLeast(0)
        val n = min(count, room)
        for (i in 0 until n) {
            val dir = Vec3d(rng.nextDouble() - 0.5, rng.nextDouble() - 0.5, rng.nextDouble() - 0.5).normalized()
            val sp = (0.3 + rng.nextDouble() * 0.9) * speedScale
            particles.add(
                DebrisParticle(
                    x = origin.x, y = origin.y, z = origin.z,
                    vx = dir.x * sp, vy = dir.y * sp, vz = dir.z * sp,
                    life = 0f, maxLife = 1.0f + rng.nextFloat() * 1.6f,
                    size = 1.2f + rng.nextFloat() * 2.2f,
                    r = (color.first * (0.85f + rng.nextFloat() * 0.3f)).coerceIn(0f, 1f),
                    g = (color.second * (0.85f + rng.nextFloat() * 0.3f)).coerceIn(0f, 1f),
                    b = (color.third * (0.85f + rng.nextFloat() * 0.3f)).coerceIn(0f, 1f)
                )
            )
        }
    }

    private fun updateVfx(dtSec: Float) {
        shockwaves.replaceAll { it.copy(ageSec = it.ageSec + dtSec) }
        shockwaves.removeAll { it.ageSec >= it.durationSec }

        gammaBursts.replaceAll { it.copy(ageSec = it.ageSec + dtSec) }
        gammaBursts.removeAll { it.ageSec >= it.durationSec }

        val iter = particles.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.life += dtSec
            if (p.life >= p.maxLife) {
                iter.remove()
                continue
            }
            // If spiraling into a black hole, apply inward centripetal pull
            if (p.targetBlackHoleId != -1L) {
                val bh = bodies.firstOrNull { it.id == p.targetBlackHoleId }
                if (bh != null) {
                    val dx = bh.pos.x - p.x
                    val dy = bh.pos.y - p.y
                    val dz = bh.pos.z - p.z
                    val d = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(0.01)
                    val pull = 12.0 / (d + 0.08)
                    p.vx += (dx / d) * pull * dtSec
                    p.vy += (dy / d) * pull * dtSec
                    p.vz += (dz / d) * pull * dtSec
                }
            }
            p.x += p.vx * dtSec
            p.y += p.vy * dtSec
            p.z += p.vz * dtSec
        }
    }

    companion object {
        fun spectralClassForTemp(tempK: Double): SpectralClass = when {
            tempK >= 25000.0 -> SpectralClass.O
            tempK >= 10000.0 -> SpectralClass.B
            tempK >= 7500.0 -> SpectralClass.A
            tempK >= 6000.0 -> SpectralClass.F
            tempK >= 5200.0 -> SpectralClass.G
            tempK >= 3700.0 -> SpectralClass.K
            else -> SpectralClass.M
        }

        /**
         * Couples live property edits to real astrophysical scaling laws and phase transitions:
         * - Star Mass <-> Luminosity (M^3.5), Radius (M^0.8), Temperature (Stefan-Boltzmann), Spectral Class, & Red Giant / BH collapse
         * - White Dwarf (>1.44 M_sun Chandrasekhar) -> Neutron Star; Neutron Star (>2.8 M_sun TOV) -> Black Hole
         * - Planet Mass (>15 M_earth -> Gas Giant; >0.08 M_sun -> M-Dwarf Star ignition)
         * - Atmosphere <-> Greenhouse temperature boost; Temperature <-> Magma crust / Ice freeze states
         */
        fun applyCoupledAstrophysics(
            original: CelestialBody,
            edited: CelestialBody,
            scaleRadiusWithMass: Boolean = true
        ): Pair<CelestialBody, String?> {
            if (original.type == BodyType.BLACK_HOLE) {
                val m = edited.massSolar.coerceAtLeast(0.05)
                return edited.copy(
                    massSolar = m,
                    radiusKm = 2.95325 * m,
                    temperatureK = 0.0,
                    luminositySolar = 0.0
                ) to null
            }

            val massChanged = abs(edited.massSolar - original.massSolar) > max(1e-18, original.massSolar * 1e-4)
            val radiusChanged = abs(edited.radiusKm - original.radiusKm) > max(1e-3, original.radiusKm * 1e-4)
            val tempChanged = abs(edited.temperatureK - original.temperatureK) > 0.5
            val lumChanged = abs(edited.luminositySolar - original.luminositySolar) > max(1e-5, original.luminositySolar * 1e-4)
            val atmoChanged = abs(edited.atmosphereDensity - original.atmosphereDensity) > 0.005f

            if (original.type.isLuminous) {
                val m = edited.massSolar.coerceAtLeast(0.08)

                // Compact remnant & hypermassive collapse thresholds
                if (original.type == BodyType.WHITE_DWARF && m > 1.44) {
                    return edited.copy(
                        type = BodyType.NEUTRON_STAR,
                        spectralClass = SpectralClass.NONE,
                        massSolar = m,
                        radiusKm = 12.0,
                        temperatureK = 180_000.0,
                        luminositySolar = 0.5,
                        albedoColor = Triple(0.62f, 0.85f, 1.0f),
                        evolutionStage = EvolutionStage.NEUTRON_STAR
                    ) to "${original.name} crossed Chandrasekhar limit (1.44 M☉) → Neutron Star!"
                }
                if (original.type == BodyType.NEUTRON_STAR && m > 2.8) {
                    return edited.copy(
                        type = BodyType.BLACK_HOLE,
                        spectralClass = SpectralClass.NONE,
                        massSolar = m,
                        radiusKm = 2.95325 * m,
                        temperatureK = 0.0,
                        luminositySolar = 0.0,
                        accretionActivity = 0.45f,
                        evolutionStage = EvolutionStage.BLACK_HOLE
                    ) to "${original.name} crossed TOV limit (2.8 M☉) → Black Hole!"
                }
                if ((original.type == BodyType.STAR || original.type == BodyType.RED_GIANT) && m >= 120.0) {
                    return edited.copy(
                        type = BodyType.BLACK_HOLE,
                        spectralClass = SpectralClass.NONE,
                        massSolar = m,
                        radiusKm = 2.95325 * m,
                        temperatureK = 0.0,
                        luminositySolar = 0.0,
                        accretionActivity = 0.55f,
                        evolutionStage = EvolutionStage.BLACK_HOLE
                    ) to "${original.name} exceeded Eddington mass (120 M☉) → Black Hole!"
                }

                // Main Sequence / Red Giant coupled scaling
                if (massChanged && (original.type == BodyType.STAR || original.type == BodyType.RED_GIANT)) {
                    val newLum = if (original.type == BodyType.RED_GIANT) {
                        (m.pow(3.5) * 35.0).coerceIn(10.0, 2_500_000.0)
                    } else {
                        m.pow(3.5).coerceIn(1e-4, 2_000_000.0)
                    }
                    val newRadKm = if (scaleRadiusWithMass) {
                        val baseRad = 696_340.0 * m.pow(0.8)
                        if (original.type == BodyType.RED_GIANT) (baseRad * 12.0).coerceAtLeast(3_500_000.0)
                        else baseRad.coerceIn(30_000.0, 45_000_000.0)
                    } else edited.radiusKm
                    val rSolar = (newRadKm / 696_340.0).coerceAtLeast(0.05)
                    val rawTemp = 5778.0 * (newLum / (rSolar * rSolar)).pow(0.25)
                    val newTemp = rawTemp.coerceIn(2400.0, 52000.0)
                    val spec = spectralClassForTemp(newTemp)
                    val msg = if (spec != original.spectralClass) "${original.name} is now ${spec.label} (${newTemp.toInt()} K)" else null
                    return edited.copy(
                        massSolar = m,
                        radiusKm = newRadKm,
                        luminositySolar = newLum,
                        temperatureK = newTemp,
                        spectralClass = spec,
                        albedoColor = kelvinToRgb(newTemp),
                        initialMassSolar = max(original.initialMassSolar, m)
                    ) to msg
                }

                if (lumChanged) {
                    val newLum = edited.luminositySolar.coerceIn(1e-4, 5_000_000.0)
                    val rSolar0 = (edited.radiusKm / 696_340.0).coerceAtLeast(0.01)
                    val ratio = (newLum / original.luminositySolar.coerceAtLeast(1e-4)).coerceIn(1e-4, 1e6)
                    val newTemp = (original.temperatureK * ratio.pow(0.18)).coerceIn(2400.0, 52000.0)
                    val newRadKm = (edited.radiusKm * ratio.pow(0.14)).coerceIn(7000.0, 60_000_000.0)
                    val becomeGiant = original.type == BodyType.STAR && newRadKm > 3_500_000.0 && newTemp < 4200.0
                    val spec = spectralClassForTemp(newTemp)
                    return edited.copy(
                        type = if (becomeGiant) BodyType.RED_GIANT else edited.type,
                        luminositySolar = newLum,
                        temperatureK = newTemp,
                        radiusKm = newRadKm,
                        spectralClass = spec,
                        albedoColor = kelvinToRgb(newTemp)
                    ) to (if (becomeGiant) "${original.name} puffed into a Red Giant!" else null)
                }

                if (tempChanged) {
                    val newTemp = edited.temperatureK.coerceIn(1800.0, 250_000.0)
                    val rSolar = (edited.radiusKm / 696_340.0).coerceAtLeast(1e-4)
                    val newLum = (rSolar * rSolar * (newTemp / 5778.0).pow(4.0)).coerceIn(1e-5, 5_000_000.0)
                    val spec = if (edited.type == BodyType.STAR || edited.type == BodyType.RED_GIANT) spectralClassForTemp(newTemp) else edited.spectralClass
                    return edited.copy(
                        temperatureK = newTemp,
                        luminositySolar = newLum,
                        spectralClass = spec,
                        albedoColor = kelvinToRgb(newTemp)
                    ) to null
                }

                if (radiusChanged) {
                    val newRadKm = edited.radiusKm.coerceAtLeast(5.0)
                    val becomeGiant = original.type == BodyType.STAR && newRadKm > 3_200_000.0 && original.massSolar < 8.0
                    val rSolar = (newRadKm / 696_340.0).coerceAtLeast(1e-4)
                    val newTemp = if (becomeGiant) 3400.0 else edited.temperatureK
                    val newLum = (rSolar * rSolar * (newTemp / 5778.0).pow(4.0)).coerceIn(1e-5, 5_000_000.0)
                    return edited.copy(
                        type = if (becomeGiant) BodyType.RED_GIANT else edited.type,
                        radiusKm = newRadKm,
                        temperatureK = newTemp,
                        luminositySolar = newLum,
                        albedoColor = kelvinToRgb(newTemp),
                        evolutionStage = if (becomeGiant) EvolutionStage.RED_GIANT else edited.evolutionStage
                    ) to (if (becomeGiant) "${original.name} expanded into a Red Giant!" else null)
                }

                return edited to null
            }

            // Non-luminous bodies (Planets, Moons, Dwarf Planets, Asteroids)
            if (massChanged) {
                val m = edited.massSolar.coerceAtLeast(1e-16)
                // Brown dwarf -> Red Dwarf hydrogen ignition threshold at 0.08 M_sun!
                if (m >= 0.08) {
                    val newLum = m.pow(3.5).coerceAtLeast(0.005)
                    val newRadKm = 696_340.0 * m.pow(0.8)
                    return edited.copy(
                        type = BodyType.STAR,
                        spectralClass = SpectralClass.M,
                        massSolar = m,
                        radiusKm = newRadKm,
                        temperatureK = 3100.0,
                        luminositySolar = newLum,
                        albedoColor = kelvinToRgb(3100.0),
                        crustHeat = 0f,
                        atmosphereDensity = 0f,
                        hasClouds = false,
                        hasCityLights = false,
                        evolutionStage = EvolutionStage.MAIN_SEQUENCE
                    ) to "${original.name} ignited hydrogen fusion → Red Dwarf Star!"
                }

                val isRocky = original.type == BodyType.TERRESTRIAL_PLANET ||
                    original.type == BodyType.EARTH_LIKE ||
                    original.type == BodyType.DWARF_PLANET ||
                    original.type == BodyType.MOON ||
                    original.type == BodyType.ASTEROID

                val newRadKm = if (scaleRadiusWithMass && !radiusChanged) {
                    (original.radiusKm * cbrt((m / original.massSolar.coerceAtLeast(1e-16)).coerceIn(1e-6, 1e6))).coerceIn(10.0, 220_000.0)
                } else edited.radiusKm

                if (isRocky && m > 15.0 * AstroConstants.EARTH_MASS_SOLAR) {
                    return edited.copy(
                        type = BodyType.GAS_GIANT,
                        archetype = BodyArchetype.GENERIC_GAS,
                        massSolar = m,
                        radiusKm = max(newRadKm, 46_000.0),
                        atmosphereDensity = max(edited.atmosphereDensity, 0.55f),
                        albedoColor = Triple(0.82f, 0.66f, 0.48f),
                        hasCityLights = false
                    ) to "${original.name} grew past 15 M⊕ and accreted H/He → Gas Giant!"
                }

                return edited.copy(massSolar = m, radiusKm = newRadKm) to null
            }

            if (atmoChanged && !tempChanged) {
                val deltaAtmo = edited.atmosphereDensity - original.atmosphereDensity
                val newTemp = (edited.temperatureK + deltaAtmo * 95.0).coerceIn(25.0, 5000.0)
                return edited.copy(temperatureK = newTemp) to null
            }

            if (tempChanged) {
                val t = edited.temperatureK.coerceAtLeast(1.0)
                val nextCrustHeat = if (t > 1050.0) {
                    ((t - 1050.0) / 2400.0).toFloat().coerceIn(0f, 1f)
                } else {
                    0f
                }
                return edited.copy(temperatureK = t, crustHeat = nextCrustHeat) to null
            }

            return edited to null
        }
    }
}
