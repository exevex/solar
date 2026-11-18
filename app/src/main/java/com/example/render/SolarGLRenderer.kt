package com.example.render

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.example.simulation.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

data class ScreenBodyLabel(
    val id: Long,
    val name: String,
    val type: BodyType,
    val xPx: Float,
    val yPx: Float,
    val isSelected: Boolean,
    val statusTag: String? = null
)

data class SlingshotPreview(
    val spawnPos: Vec3d,
    val launchVel: Vec3d,
    val trajectoryPoints: List<Vec3d>
)

class SolarGLRenderer(
    private val frameStateRef: AtomicReference<RenderFrameState?>,
    private val onLabelsUpdated: (List<ScreenBodyLabel>) -> Unit,
    private val onFpsUpdated: (Float) -> Unit
) : GLSurfaceView.Renderer {

    // Camera state (thread-safe volatile / synchronized updates from touch thread)
    @Volatile var yawDeg: Float = 28f
    @Volatile var pitchDeg: Float = 32f
    @Volatile var distanceAu: Double = 4.6
    @Volatile var focusedBodyId: Long? = 1L
    @Volatile var selectedBodyId: Long? = 1L
    @Volatile var manualPanOffset: Vec3d = Vec3d.ZERO
    @Volatile var cinematicMode: Boolean = false

    @Volatile var graphicsSettings: GraphicsSettings = GraphicsSettings()
    @Volatile var slingshotPreview: SlingshotPreview? = null
    @Volatile var spawnPreviewPos: Vec3d? = null
    @Volatile var spawnPreviewRadiusAu: Float = 0.025f
    @Volatile var spawnPreviewTrajectory: List<Vec3d> = emptyList()

    private var cinematicLookAtWorld: Vec3d? = null

    private var viewportW = 1280
    private var viewportH = 720
    private var fboW = 1280
    private var fboH = 720

    // Matrices & Floating origin snapshot for ray picking on UI thread
    private val projMatrix = FloatArray(16)
    private val viewMatrix = FloatArray(16)
    private val viewProjMatrix = FloatArray(16)
    private val viewRotProjMatrix = FloatArray(16)
    private val modelMatrix = FloatArray(16)
    private val tempMat = FloatArray(16)

    @Volatile private var lastCameraOrigin = Vec3d.ZERO
    @Volatile private var lastCamEyeRel = FloatArray(3)
    @Volatile private var lastViewProjSnapshot = FloatArray(16)
    @Volatile private var lastBodiesForPick: List<CelestialBody> = emptyList()
    @Volatile private var lastTrueScale: Boolean = false

    // Shader programs
    private lateinit var skyboxProg: GlShaderProgram
    private lateinit var planetProg: GlShaderProgram
    private lateinit var starProg: GlShaderProgram
    private lateinit var coronaProg: GlShaderProgram
    private lateinit var bhRelativisticProg: GlShaderProgram
    private lateinit var habitableZoneProg: GlShaderProgram
    private lateinit var bhJetProg: GlShaderProgram
    private lateinit var ringProg: GlShaderProgram
    private lateinit var instancedRockProg: GlShaderProgram
    private lateinit var lineProg: GlShaderProgram
    private lateinit var particleProg: GlShaderProgram
    private lateinit var brightPassProg: GlShaderProgram
    private lateinit var blurProg: GlShaderProgram
    private lateinit var compositeProg: GlShaderProgram

    private var earthTexId = 0

    // Meshes
    private lateinit var sphereLod0: GlMesh
    private lateinit var sphereLod1: GlMesh
    private lateinit var sphereLod2: GlMesh
    private lateinit var asteroidMesh: GlMesh
    private lateinit var annulusMesh: GlMesh
    private lateinit var quadMesh: GlMesh
    private lateinit var skyboxCube: GlMesh

    // Dynamic buffers (instancing, lines, particles)
    private var instPosVbo = 0
    private var instColVbo = 0
    private var asteroidVao = 0
    private var lineVao = 0
    private var lineVbo = 0
    private var particleVao = 0
    private var particleVbo = 0

    // HDR + Ping-pong Bloom FBOs
    private var sceneFbo = 0
    private var sceneColorTex = 0
    private var sceneDepthRbo = 0
    private val bloomFbo = IntArray(2)
    private val bloomTex = IntArray(2)

    // Timing & FPS
    private var animTimeSec = 0f
    private var lastFrameNs = System.nanoTime()
    private var fpsFrames = 0
    private var fpsAccumSec = 0f

    // Reusable float buffers
    private val maxInstances = 512
    private val instPosBuf: FloatBuffer = ByteBuffer.allocateDirect(maxInstances * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val instColBuf: FloatBuffer = ByteBuffer.allocateDirect(maxInstances * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val maxLineVerts = 8192
    private val lineBuf: FloatBuffer = ByteBuffer.allocateDirect(maxLineVerts * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val maxParticles = 1024
    private val particleBuf: FloatBuffer = ByteBuffer.allocateDirect(maxParticles * 7 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0.01f, 0.015f, 0.03f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)

        skyboxProg = GlShaderProgram.compile(ShaderLibrary.SKYBOX_VS, ShaderLibrary.SKYBOX_FS, "Skybox")
        planetProg = GlShaderProgram.compile(ShaderLibrary.PLANET_VS, ShaderLibrary.PLANET_FS, "Planet")
        starProg = GlShaderProgram.compile(ShaderLibrary.STAR_VS, ShaderLibrary.STAR_FS, "Star")
        coronaProg = GlShaderProgram.compile(ShaderLibrary.CORONA_VS, ShaderLibrary.CORONA_FS, "Corona")
        bhRelativisticProg = GlShaderProgram.compile(ShaderLibrary.BH_RELATIVISTIC_VS, ShaderLibrary.BH_RELATIVISTIC_FS, "BHRel")
        habitableZoneProg = GlShaderProgram.compile(ShaderLibrary.HABITABLE_ZONE_VS, ShaderLibrary.HABITABLE_ZONE_FS, "HabZone")
        bhJetProg = GlShaderProgram.compile(ShaderLibrary.CORONA_VS, ShaderLibrary.BH_JET_FS, "BHJet")
        ringProg = GlShaderProgram.compile(ShaderLibrary.RING_VS, ShaderLibrary.RING_FS, "Ring")
        instancedRockProg = GlShaderProgram.compile(ShaderLibrary.INSTANCED_ROCK_VS, ShaderLibrary.INSTANCED_ROCK_FS, "InstRock")
        lineProg = GlShaderProgram.compile(ShaderLibrary.LINE_VS, ShaderLibrary.LINE_FS, "Line")
        particleProg = GlShaderProgram.compile(ShaderLibrary.PARTICLE_VS, ShaderLibrary.PARTICLE_FS, "Particle")
        brightPassProg = GlShaderProgram.compile(ShaderLibrary.QUAD_VS, ShaderLibrary.BRIGHT_PASS_FS, "Bright")
        blurProg = GlShaderProgram.compile(ShaderLibrary.QUAD_VS, ShaderLibrary.BLUR_FS, "Blur")
        compositeProg = GlShaderProgram.compile(ShaderLibrary.QUAD_VS, ShaderLibrary.COMPOSITE_FS, "Composite")

        val texArr = IntArray(1)
        GLES30.glGenTextures(1, texArr, 0)
        earthTexId = texArr[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, earthTexId)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D,
            0,
            GLES30.GL_R8,
            EarthGeography.MAP_W,
            EarthGeography.MAP_H,
            0,
            GLES30.GL_RED,
            GLES30.GL_UNSIGNED_BYTE,
            EarthGeography.createGlByteBuffer()
        )
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)

        sphereLod0 = MeshFactory.createSphere(46, 68)
        sphereLod1 = MeshFactory.createSphere(24, 34)
        sphereLod2 = MeshFactory.createSphere(12, 16)
        asteroidMesh = MeshFactory.createAsteroidRock()
        annulusMesh = MeshFactory.createAnnulus(96)
        quadMesh = MeshFactory.createQuad()
        skyboxCube = createSkyboxCube()

        setupInstancedAsteroidVao()
        setupDynamicBuffers()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportW = max(1, width)
        viewportH = max(1, height)
        rebuildFbos()
    }

    private fun rebuildFbos() {
        val scale = graphicsSettings.effectiveScale.coerceIn(0.55f, 1.0f)
        fboW = max(320, (viewportW * scale).toInt())
        fboH = max(200, (viewportH * scale).toInt())

        if (sceneFbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(sceneFbo), 0)
            GLES30.glDeleteTextures(1, intArrayOf(sceneColorTex), 0)
            GLES30.glDeleteRenderbuffers(1, intArrayOf(sceneDepthRbo), 0)
            GLES30.glDeleteFramebuffers(2, bloomFbo, 0)
            GLES30.glDeleteTextures(2, bloomTex, 0)
        }

        val fboArr = IntArray(1)
        val texArr = IntArray(1)
        val rboArr = IntArray(1)

        // Try HDR RGBA16F first, fallback to RGBA8 if unsupported on GPU
        if (!createSceneFbo(fboArr, texArr, rboArr, GLES30.GL_RGBA16F, GLES30.GL_HALF_FLOAT)) {
            createSceneFbo(fboArr, texArr, rboArr, GLES30.GL_RGBA8, GLES30.GL_UNSIGNED_BYTE)
        }
        sceneFbo = fboArr[0]
        sceneColorTex = texArr[0]
        sceneDepthRbo = rboArr[0]

        val bw = max(160, fboW / 2)
        val bh = max(100, fboH / 2)
        GLES30.glGenFramebuffers(2, bloomFbo, 0)
        GLES30.glGenTextures(2, bloomTex, 0)
        for (i in 0..1) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[i])
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, bw, bh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[i])
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bloomTex[i], 0)
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    private fun createSceneFbo(
        fboArr: IntArray,
        texArr: IntArray,
        rboArr: IntArray,
        internalFmt: Int,
        pixelType: Int
    ): Boolean {
        GLES30.glGenFramebuffers(1, fboArr, 0)
        GLES30.glGenTextures(1, texArr, 0)
        GLES30.glGenRenderbuffers(1, rboArr, 0)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texArr[0])
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internalFmt, fboW, fboH, 0, GLES30.GL_RGBA, pixelType, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)

        GLES30.glBindRenderbuffer(GLES30.GL_RENDERBUFFER, rboArr[0])
        GLES30.glRenderbufferStorage(GLES30.GL_RENDERBUFFER, GLES30.GL_DEPTH_COMPONENT24, fboW, fboH)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboArr[0])
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texArr[0], 0)
        GLES30.glFramebufferRenderbuffer(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_RENDERBUFFER, rboArr[0])

        val status = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (status != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            GLES30.glDeleteFramebuffers(1, fboArr, 0)
            GLES30.glDeleteTextures(1, texArr, 0)
            GLES30.glDeleteRenderbuffers(1, rboArr, 0)
            return false
        }
        return true
    }

    override fun onDrawFrame(gl: GL10?) {
        val nowNs = System.nanoTime()
        val dtSec = ((nowNs - lastFrameNs) / 1e9).toFloat().coerceIn(0.001f, 0.1f)
        lastFrameNs = nowNs
        animTimeSec += dtSec

        fpsFrames++
        fpsAccumSec += dtSec
        if (fpsAccumSec >= 0.5f) {
            onFpsUpdated(fpsFrames / fpsAccumSec)
            fpsFrames = 0
            fpsAccumSec = 0f
        }

        // Check if render scale changed
        val desiredW = max(320, (viewportW * graphicsSettings.effectiveScale).toInt())
        if (abs(desiredW - fboW) > 16) {
            rebuildFbos()
        }

        val frame = frameStateRef.get() ?: return
        val bodies = frame.bodies
        val trueScale = frame.config.trueScaleMode
        val quality = graphicsSettings.tier

        val cinState = frame.cinematicState
        if (cinState.isActive && cinState.cameraGoal != null) {
            val goal = cinState.cameraGoal
            val lerpRate = (dtSec * 4.5f).coerceIn(0.02f, 0.35f)
            // Shortest-angle yaw interpolation
            var dy = ((goal.yawDeg - yawDeg + 540f) % 360f) - 180f
            yawDeg = (yawDeg + dy * lerpRate + 360f) % 360f
            pitchDeg = pitchDeg + (goal.pitchDeg - pitchDeg) * lerpRate
            distanceAu = distanceAu + (goal.distanceAu - distanceAu) * lerpRate.toDouble()
            val curLook = cinematicLookAtWorld ?: goal.lookAtPos
            cinematicLookAtWorld = curLook + (goal.lookAtPos - curLook) * lerpRate.toDouble()
        } else {
            cinematicLookAtWorld = null
            if (cinematicMode) {
                yawDeg = (yawDeg + dtSec * 11f) % 360f
                pitchDeg = 18f + 10f * sin(animTimeSec * 0.35f)
            }
        }

        // Floating origin: subtract active look-at or focused body position in 64-bit Double before OpenGL Float matrices
        val focusBody = focusedBodyId?.let { id -> bodies.firstOrNull { it.id == id } }
        val cameraOrigin = if (cinState.isActive && cinematicLookAtWorld != null) {
            cinematicLookAtWorld!!
        } else {
            (focusBody?.pos ?: Vec3d.ZERO) + manualPanOffset
        }
        lastCameraOrigin = cameraOrigin
        lastBodiesForPick = bodies
        lastTrueScale = trueScale

        val yawRad = Math.toRadians(yawDeg.toDouble())
        val pitchRad = Math.toRadians(pitchDeg.toDouble().coerceIn(-86.0, 86.0))
        val dist = distanceAu.coerceIn(0.012, 220.0)
        val eyeX = (dist * cos(pitchRad) * sin(yawRad)).toFloat()
        val eyeY = (dist * sin(pitchRad)).toFloat()
        val eyeZ = (dist * cos(pitchRad) * cos(yawRad)).toFloat()
        lastCamEyeRel = floatArrayOf(eyeX, eyeY, eyeZ)

        val aspect = viewportW.toFloat() / viewportH.toFloat()
        val nearPlane = (dist * 0.0015).toFloat().coerceIn(0.0005f, 0.08f)
        val farPlane = 1200f
        val logDepthCoef = (2.0 / ln(farPlane + 1.0) * ln(2.0)).toFloat()

        Matrix.perspectiveM(projMatrix, 0, 50f, aspect, nearPlane, farPlane)
        Matrix.setLookAtM(viewMatrix, 0, eyeX, eyeY, eyeZ, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.multiplyMM(viewProjMatrix, 0, projMatrix, 0, viewMatrix, 0)
        lastViewProjSnapshot = viewProjMatrix.copyOf()

        // Rotation-only view matrix for skybox
        val viewRot = viewMatrix.copyOf()
        viewRot[12] = 0f; viewRot[13] = 0f; viewRot[14] = 0f
        Matrix.multiplyMM(viewRotProjMatrix, 0, projMatrix, 0, viewRot, 0)

        // Bind HDR Scene FBO
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, sceneFbo)
        GLES30.glViewport(0, 0, fboW, fboH)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // 1. Skybox Milky Way + Starfield
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        skyboxProg.use()
        skyboxProg.setMat4("uViewRotProj", viewRotProjMatrix)
        skyboxCube.draw()
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)

        // Collect star lights & eclipse occluders
        val stars = bodies.filter { it.type.isLuminous && !it.isBackgroundCluster }.take(4)
        val primaryStarPos = stars.firstOrNull()?.let { it.pos - cameraOrigin } ?: Vec3d(0.0, 5.0, 0.0)
        val primaryStarCol = stars.firstOrNull()?.albedoColor ?: Triple(1f, 0.95f, 0.88f)

        val occluders = bodies.filter { !it.type.isLuminous && !it.isBackgroundCluster && it.type != BodyType.BLACK_HOLE }

        // 2. Orbit Trails, Slingshot & Spawn Ghost Trajectory
        if (frame.config.showOrbits || slingshotPreview != null || (spawnPreviewPos != null && spawnPreviewTrajectory.size >= 2)) {
            drawOrbitTrailsAndSlingshot(bodies, cameraOrigin, frame.config.showOrbits, logDepthCoef)
        }

        // 3. Instanced Asteroids & Background Star Clusters (Single GPU instanced draw call!)
        val clusterBodies = bodies.filter { it.isBackgroundCluster }
        if (clusterBodies.isNotEmpty()) {
            drawInstancedClusters(clusterBodies, cameraOrigin, primaryStarPos, trueScale, logDepthCoef)
        }

        // 4. Opaque Celestial Bodies (Planets, Moons, Stars, Black Hole Cores)
        val majorBodies = bodies.filter { !it.isBackgroundCluster }
        val ringsToDraw = mutableListOf<CelestialBody>()
        val coronasToDraw = mutableListOf<CelestialBody>()
        val blackHoles = mutableListOf<CelestialBody>()
        val screenLabels = ArrayList<ScreenBodyLabel>(majorBodies.size)

        for (b in majorBodies) {
            val rel = b.pos - cameraOrigin
            val rx = rel.x.toFloat(); val ry = rel.y.toFloat(); val rz = rel.z.toFloat()
            val rad = b.getVisualRadiusAu(trueScale).toFloat()

            // Screen-space projection for Compose labels & gravitational lensing
            val screenPt = projectToScreen(rx, ry, rz)
            if (screenPt != null) {
                val tag = when {
                    b.tidalStretchAmount > 0.16f || b.tidalStress > 0.12f -> "SPAGHETTIFYING"
                    b.type == BodyType.BLACK_HOLE && b.accretionActivity > 0.55f -> "ACCRETING"
                    b.type == BodyType.RED_GIANT -> "RED GIANT"
                    b.crustHeat > 0.35f -> "MOLTEN"
                    else -> null
                }
                screenLabels.add(
                    ScreenBodyLabel(
                        id = b.id,
                        name = b.name,
                        type = b.type,
                        xPx = screenPt.first,
                        yPx = screenPt.second,
                        isSelected = b.id == selectedBodyId,
                        statusTag = tag
                    )
                )
            }

            // Pick sphere LOD by angular size
            val camDist = sqrt((rx - eyeX) * (rx - eyeX) + (ry - eyeY) * (ry - eyeY) + (rz - eyeZ) * (rz - eyeZ)).coerceAtLeast(1e-4f)
            val angSize = rad / camDist
            val mesh = when {
                angSize > 0.045f -> sphereLod0
                angSize > 0.012f -> sphereLod1
                else -> sphereLod2
            }

            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, rx, ry, rz)
            Matrix.rotateM(modelMatrix, 0, b.axialTiltDeg.toFloat(), 0f, 0f, 1f)
            Matrix.rotateM(modelMatrix, 0, b.spinAngleDeg.toFloat(), 0f, 1f, 0f)
            Matrix.scaleM(modelMatrix, 0, rad, rad, rad)

            when {
                b.type == BodyType.BLACK_HOLE -> {
                    blackHoles.add(b)
                }
                b.type.isLuminous -> {
                    coronasToDraw.add(b)
                    starProg.use()
                    starProg.setMat4("uModel", modelMatrix)
                    starProg.setMat4("uViewProj", viewProjMatrix)
                    starProg.setFloat("uLogDepthCoef", logDepthCoef)
                    starProg.setVec3("uBodyPos", rx, ry, rz)
                    starProg.setFloat("uBodyRadius", rad)
                    starProg.setVec3("uTidalDir", b.tidalStretchDir.x.toFloat(), b.tidalStretchDir.y.toFloat(), b.tidalStretchDir.z.toFloat())
                    starProg.setFloat("uTidalStretch", b.tidalStretchAmount)
                    starProg.setVec3("uCamPos", eyeX, eyeY, eyeZ)
                    starProg.setVec3("uStarColor", b.albedoColor.first, b.albedoColor.second, b.albedoColor.third)
                    starProg.setFloat("uTime", animTimeSec)
                    starProg.setFloat("uSeed", b.seed)
                    val hdrBoost = when (b.type) {
                        BodyType.NEUTRON_STAR -> 2.8f
                        BodyType.WHITE_DWARF -> 2.2f
                        BodyType.RED_GIANT -> 1.6f
                        else -> 1.85f
                    }
                    starProg.setFloat("uIntensity", hdrBoost)
                    mesh.draw()
                }
                else -> {
                    if (b.ringSystem != null) ringsToDraw.add(b)
                    if (b.type == BodyType.COMET) coronasToDraw.add(b)

                    planetProg.use()
                    GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, earthTexId)
                    planetProg.setInt("uEarthTex", 0)
                    planetProg.setMat4("uModel", modelMatrix)
                    planetProg.setMat4("uViewProj", viewProjMatrix)
                    planetProg.setFloat("uLogDepthCoef", logDepthCoef)
                    planetProg.setVec3("uCamPos", eyeX, eyeY, eyeZ)
                    planetProg.setVec3("uBodyPos", rx, ry, rz)
                    planetProg.setFloat("uBodyRadius", rad)
                    planetProg.setVec3("uTidalDir", b.tidalStretchDir.x.toFloat(), b.tidalStretchDir.y.toFloat(), b.tidalStretchDir.z.toFloat())
                    planetProg.setFloat("uTidalStretch", b.tidalStretchAmount)
                    planetProg.setVec3("uImpactDeformDir", b.impactDeformDir.x.toFloat(), b.impactDeformDir.y.toFloat(), b.impactDeformDir.z.toFloat())
                    planetProg.setFloat("uImpactDeformAmount", b.impactDeformAmount)
                    planetProg.setVec3("uImpactShockDir", b.impactShockDir.x.toFloat(), b.impactShockDir.y.toFloat(), b.impactShockDir.z.toFloat())
                    planetProg.setFloat("uImpactShockAge", b.impactShockAge)
                    planetProg.setVec3("uPrimaryStarPos", primaryStarPos.x.toFloat(), primaryStarPos.y.toFloat(), primaryStarPos.z.toFloat())

                    val style = when (b.type) {
                        BodyType.EARTH_LIKE -> 0
                        BodyType.GAS_GIANT -> 2
                        BodyType.ICE_GIANT -> 3
                        BodyType.COMET -> 4
                        else -> 1
                    }
                    planetProg.setInt("uPlanetStyle", style)
                    planetProg.setInt("uBodyArchetype", b.archetype.shaderId)
                    planetProg.setFloat("uSeed", b.seed)
                    planetProg.setFloat("uTime", animTimeSec)
                    planetProg.setFloat("uTempK", b.temperatureK.toFloat())
                    planetProg.setInt("uFbmOctaves", quality.fbmOctaves)
                    planetProg.setVec3("uAlbedo", b.albedoColor.first, b.albedoColor.second, b.albedoColor.third)
                    planetProg.setFloat("uAtmoDensity", b.atmosphereDensity)
                    planetProg.setVec3("uAtmoColor", b.atmosphereColor.first, b.atmosphereColor.second, b.atmosphereColor.third)
                    planetProg.setInt("uHasClouds", if (b.hasClouds) 1 else 0)
                    planetProg.setInt("uHasCityLights", if (b.hasCityLights) 1 else 0)
                    planetProg.setFloat("uCrustHeat", b.crustHeat)
                    planetProg.setFloat("uTidalStress", b.tidalStress)
                    planetProg.setInt("uEnableAtmo", if (quality.atmosphereScattering) 1 else 0)
                    planetProg.setInt("uEnableEclipses", if (quality.eclipseShadows) 1 else 0)

                    // Craters
                    val craterCount = b.craters.size.coerceAtMost(8)
                    planetProg.setInt("uCraterCount", craterCount)
                    for (c in 0 until craterCount) {
                        val cr = b.craters[c]
                        planetProg.setVec4("uCraters[$c]", cr.nx, cr.ny, cr.nz, cr.radiusRad)
                        planetProg.setFloat("uCraterHeat[$c]", cr.heat)
                    }

                    // Multi-star lighting uniforms
                    val sCount = stars.size.coerceAtLeast(1)
                    planetProg.setInt("uStarCount", sCount)
                    if (stars.isEmpty()) {
                        planetProg.setVec3("uStarPos[0]", 0f, 8f, 0f)
                        planetProg.setVec3("uStarColor[0]", 1f, 0.96f, 0.9f)
                        planetProg.setFloat("uStarIntensity[0]", 1.6f)
                    } else {
                        for (s in stars.indices) {
                            val st = stars[s]
                            val sRel = st.pos - cameraOrigin
                            planetProg.setVec3("uStarPos[$s]", sRel.x.toFloat(), sRel.y.toFloat(), sRel.z.toFloat())
                            planetProg.setVec3("uStarColor[$s]", st.albedoColor.first, st.albedoColor.second, st.albedoColor.third)
                            val lumBoost = (1.35f + 0.35f * ln(1.0 + st.luminositySolar).toFloat()).coerceIn(1.1f, 3.2f)
                            planetProg.setFloat("uStarIntensity[$s]", lumBoost)
                        }
                    }

                    // Eclipse occluders (nearest 6 bodies excluding self)
                    val nearestOcc = occluders
                        .filter { it.id != b.id }
                        .sortedBy { (it.pos - b.pos).lengthSq() }
                        .take(6)
                    planetProg.setInt("uOccluderCount", nearestOcc.size)
                    for (o in nearestOcc.indices) {
                        val oc = nearestOcc[o]
                        val oRel = oc.pos - cameraOrigin
                        planetProg.setVec4("uOccluders[$o]", oRel.x.toFloat(), oRel.y.toFloat(), oRel.z.toFloat(), oc.getVisualRadiusAu(trueScale).toFloat())
                    }

                    // Ring shadow on parent planet
                    val ring = b.ringSystem
                    if (ring != null) {
                        planetProg.setInt("uHasRingShadow", 1)
                        val tiltRad = Math.toRadians(b.axialTiltDeg)
                        planetProg.setVec3("uRingNormal", -sin(tiltRad).toFloat(), cos(tiltRad).toFloat(), 0f)
                        planetProg.setFloat("uRingInner", ring.innerRadiusRatio)
                        planetProg.setFloat("uRingOuter", ring.outerRadiusRatio)
                    } else {
                        planetProg.setInt("uHasRingShadow", 0)
                    }

                    mesh.draw()
                }
            }
        }

        // 5. Transparent Pass: Planetary Rings, Accretion Disks, Star Coronas, Comet Tails, Shockwaves, Particles & GRB Beams
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        // Planetary Rings (Alpha blend)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        for (b in ringsToDraw) {
            val ring = b.ringSystem ?: continue
            val rel = b.pos - cameraOrigin
            val rad = b.getVisualRadiusAu(trueScale).toFloat()
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
            Matrix.rotateM(modelMatrix, 0, b.axialTiltDeg.toFloat(), 0f, 0f, 1f)
            ringProg.use()
            ringProg.setMat4("uModel", modelMatrix)
            ringProg.setMat4("uViewProj", viewProjMatrix)
            ringProg.setFloat("uLogDepthCoef", logDepthCoef)
            ringProg.setFloat("uInnerRad", rad * ring.innerRadiusRatio)
            ringProg.setFloat("uOuterRad", rad * ring.outerRadiusRatio)
            ringProg.setVec3("uRingColor", ring.colorR, ring.colorG, ring.colorB)
            ringProg.setFloat("uOpacity", ring.opacity)
            ringProg.setInt("uHasCassiniGap", if (ring.hasCassiniGap) 1 else 0)
            ringProg.setVec3("uPlanetPos", rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
            ringProg.setFloat("uPlanetRadius", rad)
            ringProg.setVec3("uStarPos", primaryStarPos.x.toFloat(), primaryStarPos.y.toFloat(), primaryStarPos.z.toFloat())
            ringProg.setVec3("uStarColor", primaryStarCol.first, primaryStarCol.second, primaryStarCol.third)
            annulusMesh.draw()
        }

        // Expanded Red/Green/Blue Circumstellar Zones (0.28..4.20 * sqrt(L) AU)
        if (frame.config.showHabitableZone) {
            for (st in stars) {
                if (st.luminositySolar <= 0.0001) continue
                val sqrtL = sqrt(st.luminositySolar).toFloat().coerceIn(0.05f, 400f)
                val innerAu = 0.28f * sqrtL
                val outerAu = 4.20f * sqrtL
                val rel = st.pos - cameraOrigin
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
                habitableZoneProg.use()
                habitableZoneProg.setMat4("uModel", modelMatrix)
                habitableZoneProg.setMat4("uViewProj", viewProjMatrix)
                habitableZoneProg.setFloat("uLogDepthCoef", logDepthCoef)
                habitableZoneProg.setFloat("uInnerRad", innerAu)
                habitableZoneProg.setFloat("uOuterRad", outerAu)
                habitableZoneProg.setFloat("uTime", animTimeSec)
                annulusMesh.draw()
            }
        }

        // Spawn Target Placement Reticle + Translucent Preview Sphere
        val spPreview = spawnPreviewPos
        if (spPreview != null) {
            drawSpawnTargetReticle(spPreview, cameraOrigin, dist, logDepthCoef)
            val spRel = spPreview - cameraOrigin
            val ghostRad = spawnPreviewRadiusAu.coerceAtLeast(0.015f)
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, spRel.x.toFloat(), spRel.y.toFloat(), spRel.z.toFloat())
            Matrix.scaleM(modelMatrix, 0, ghostRad, ghostRad, ghostRad)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            starProg.use()
            starProg.setMat4("uModel", modelMatrix)
            starProg.setMat4("uViewProj", viewProjMatrix)
            starProg.setFloat("uLogDepthCoef", logDepthCoef)
            starProg.setVec3("uBodyPos", spRel.x.toFloat(), spRel.y.toFloat(), spRel.z.toFloat())
            starProg.setFloat("uBodyRadius", ghostRad)
            starProg.setVec3("uTidalDir", 0f, 1f, 0f)
            starProg.setFloat("uTidalStretch", 0f)
            starProg.setVec3("uCamPos", eyeX, eyeY, eyeZ)
            starProg.setVec3("uStarColor", 0.18f, 0.92f, 0.85f)
            starProg.setFloat("uTime", animTimeSec)
            starProg.setFloat("uSeed", 7f)
            starProg.setFloat("uIntensity", 0.45f)
            sphereLod1.draw()
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }

        // 3D Raymarched Schwarzschild Black Holes (with photon ring, background skybox lensing & 8-bin Doppler disk)
        if (blackHoles.isNotEmpty()) {
            GLES30.glEnable(GLES30.GL_CULL_FACE)
            GLES30.glCullFace(GLES30.GL_FRONT)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)

            for (bh in blackHoles) {
                val rel = bh.pos - cameraOrigin
                val rad = bh.getVisualRadiusAu(trueScale).toFloat()
                val horizonRad = rad * 0.45f
                val boundRad = rad * 6.5f
                val bins = bh.diskProfile
                val b0 = bins.getOrElse(0) { 0f }; val b1 = bins.getOrElse(1) { 0f }
                val b2 = bins.getOrElse(2) { 0f }; val b3 = bins.getOrElse(3) { 0f }
                val b4 = bins.getOrElse(4) { 0f }; val b5 = bins.getOrElse(5) { 0f }
                val b6 = bins.getOrElse(6) { 0f }; val b7 = bins.getOrElse(7) { 0f }
                val diskStrength = bh.accretionActivity.coerceIn(0f, 2.5f)

                val normD = if (bh.diskPlaneNormal.lengthSq() > 1e-6) bh.diskPlaneNormal.normalized() else Vec3d(0.0, 1.0, 0.0)
                val ref = if (abs(normD.y) < 0.92) Vec3d(0.0, 1.0, 0.0) else Vec3d(1.0, 0.0, 0.0)
                val rightD = ref.cross(normD).normalized()
                val fwdD = normD.cross(rightD).normalized()

                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
                Matrix.scaleM(modelMatrix, 0, boundRad, boundRad, boundRad)

                bhRelativisticProg.use()
                bhRelativisticProg.setMat4("uModel", modelMatrix)
                bhRelativisticProg.setMat4("uViewProj", viewProjMatrix)
                bhRelativisticProg.setFloat("uLogDepthCoef", logDepthCoef)
                bhRelativisticProg.setVec3("uCamPos", eyeX, eyeY, eyeZ)
                bhRelativisticProg.setVec3("uBhPos", rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
                bhRelativisticProg.setFloat("uHorizonRadius", horizonRad)
                bhRelativisticProg.setFloat("uBoundRadius", boundRad)
                bhRelativisticProg.setFloat("uTime", animTimeSec)
                bhRelativisticProg.setFloat("uDiskStrength", diskStrength)
                bhRelativisticProg.setVec4("uDiskBins0", b0, b1, b2, b3)
                bhRelativisticProg.setVec4("uDiskBins1", b4, b5, b6, b7)
                bhRelativisticProg.setVec3("uDiskNormal", normD.x.toFloat(), normD.y.toFloat(), normD.z.toFloat())
                bhRelativisticProg.setVec3("uDiskRight", rightD.x.toFloat(), rightD.y.toFloat(), rightD.z.toFloat())
                bhRelativisticProg.setVec3("uDiskForward", fwdD.x.toFloat(), fwdD.y.toFloat(), fwdD.z.toFloat())
                sphereLod1.draw()
            }

            GLES30.glCullFace(GLES30.GL_BACK)
            GLES30.glDisable(GLES30.GL_CULL_FACE)
        }

        // Additive Relativistic Polar Jets, Star Coronas, Tidal Filaments & Debris Particles
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        for (bh in blackHoles) {
            val diskStrength = bh.accretionActivity.coerceIn(0f, 2.5f)
            if (diskStrength > 0.45f) {
                val rel = bh.pos - cameraOrigin
                val rad = bh.getVisualRadiusAu(trueScale).toFloat()
                val vx = eyeX - rel.x.toFloat()
                val vz = eyeZ - rel.z.toFloat()
                val camYawDeg = Math.toDegrees(atan2(vx.toDouble(), vz.toDouble())).toFloat()
                val jetLen = rad * (5.2f + diskStrength * 3.8f)
                Matrix.setIdentityM(modelMatrix, 0)
                Matrix.translateM(modelMatrix, 0, rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
                modelMatrix[0] = cos(Math.toRadians(camYawDeg.toDouble())).toFloat() * jetLen
                modelMatrix[2] = -sin(Math.toRadians(camYawDeg.toDouble())).toFloat() * jetLen
                modelMatrix[5] = jetLen
                bhJetProg.use()
                bhJetProg.setMat4("uModel", modelMatrix)
                bhJetProg.setMat4("uViewProj", viewProjMatrix)
                bhJetProg.setFloat("uLogDepthCoef", logDepthCoef)
                bhJetProg.setFloat("uTime", animTimeSec)
                bhJetProg.setFloat("uAccretionActivity", diskStrength)
                quadMesh.draw()
            }
        }

        for (st in coronasToDraw) {
            val rel = st.pos - cameraOrigin
            val rad = st.getVisualRadiusAu(trueScale).toFloat()
            val scale = if (st.type == BodyType.COMET) rad * 4.5f else rad * 3.4f
            // Billboard matrix aligned to camera
            Matrix.setIdentityM(modelMatrix, 0)
            Matrix.translateM(modelMatrix, 0, rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat())
            modelMatrix[0] = viewMatrix[0] * scale; modelMatrix[1] = viewMatrix[4] * scale; modelMatrix[2] = viewMatrix[8] * scale
            modelMatrix[4] = viewMatrix[1] * scale; modelMatrix[5] = viewMatrix[5] * scale; modelMatrix[6] = viewMatrix[9] * scale
            modelMatrix[8] = viewMatrix[2] * scale; modelMatrix[9] = viewMatrix[6] * scale; modelMatrix[10] = viewMatrix[10] * scale

            coronaProg.use()
            coronaProg.setMat4("uModel", modelMatrix)
            coronaProg.setMat4("uViewProj", viewProjMatrix)
            coronaProg.setFloat("uLogDepthCoef", logDepthCoef)
            coronaProg.setVec3("uColor", st.albedoColor.first, st.albedoColor.second, st.albedoColor.third)
            coronaProg.setFloat("uTime", animTimeSec)
            coronaProg.setFloat("uIntensity", if (st.type == BodyType.COMET) 0.9f else 1.45f)
            coronaProg.setInt("uIsPulsar", if (st.type == BodyType.NEUTRON_STAR) 1 else 0)
            quadMesh.draw()
        }

        // Comet solar-wind tails, shockwaves, Gamma-Ray Bursts & Debris Particles
        drawVfxAndParticles(frame, majorBodies, cameraOrigin, primaryStarPos, trueScale, logDepthCoef)

        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)

        // 6. Bloom & Screen-Space Gravitational Lensing Composite
        runPostProcess(blackHoles, cameraOrigin, trueScale)

        onLabelsUpdated(screenLabels)
    }

    private fun drawOrbitTrailsAndSlingshot(
        bodies: List<CelestialBody>,
        cameraOrigin: Vec3d,
        showOrbits: Boolean,
        logDepthCoef: Float
    ) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        lineProg.use()
        lineProg.setMat4("uViewProj", viewProjMatrix)
        lineProg.setFloat("uLogDepthCoef", logDepthCoef)

        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)

        if (showOrbits) {
            for (b in bodies) {
                if (b.isBackgroundCluster || b.trail.size < 2) continue
                val pts = b.trail
                val count = pts.size.coerceAtMost(maxLineVerts)
                lineBuf.clear()
                val cr = b.albedoColor.first.coerceAtLeast(0.25f)
                val cg = b.albedoColor.second.coerceAtLeast(0.35f)
                val cb = b.albedoColor.third.coerceAtLeast(0.45f)
                for (i in 0 until count) {
                    val rel = pts[i] - cameraOrigin
                    val alpha = (i.toFloat() / count) * 0.55f
                    lineBuf.put(rel.x.toFloat()); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat())
                    lineBuf.put(cr); lineBuf.put(cg); lineBuf.put(cb); lineBuf.put(alpha)
                }
                lineBuf.position(0)
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 7 * 4, lineBuf)
                GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, count)
            }
        }

        // Slingshot predicted trajectory curve
        val preview = slingshotPreview
        if (preview != null && preview.trajectoryPoints.size >= 2) {
            val pts = preview.trajectoryPoints
            val count = pts.size.coerceAtMost(maxLineVerts)
            lineBuf.clear()
            for (i in 0 until count) {
                val rel = pts[i] - cameraOrigin
                val alpha = (1.0f - i.toFloat() / count) * 0.95f
                lineBuf.put(rel.x.toFloat()); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat())
                lineBuf.put(0.22f); lineBuf.put(0.85f); lineBuf.put(1.0f); lineBuf.put(alpha)
            }
            lineBuf.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 7 * 4, lineBuf)
            GLES30.glLineWidth(4.0f)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, count)
            GLES30.glLineWidth(1.5f)
        }

        // Aim & Place ghost orbital preview path
        val spTraj = spawnPreviewTrajectory
        if (spawnPreviewPos != null && spTraj.size >= 2) {
            val count = spTraj.size.coerceAtMost(maxLineVerts)
            lineBuf.clear()
            for (i in 0 until count) {
                val rel = spTraj[i] - cameraOrigin
                val alpha = (1.0f - i.toFloat() / count) * 0.85f
                lineBuf.put(rel.x.toFloat()); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat())
                lineBuf.put(0.18f); lineBuf.put(0.95f); lineBuf.put(0.72f); lineBuf.put(alpha)
            }
            lineBuf.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 7 * 4, lineBuf)
            GLES30.glLineWidth(3.0f)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, count)
            GLES30.glLineWidth(1.5f)
        }

        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawInstancedClusters(
        clusters: List<CelestialBody>,
        cameraOrigin: Vec3d,
        primaryStarPos: Vec3d,
        trueScale: Boolean,
        logDepthCoef: Float
    ) {
        val count = clusters.size.coerceAtMost(maxInstances)
        instPosBuf.clear()
        instColBuf.clear()
        for (i in 0 until count) {
            val b = clusters[i]
            val rel = b.pos - cameraOrigin
            instPosBuf.put(rel.x.toFloat()); instPosBuf.put(rel.y.toFloat()); instPosBuf.put(rel.z.toFloat())
            instPosBuf.put(b.getVisualRadiusAu(trueScale).toFloat())

            instColBuf.put(b.albedoColor.first); instColBuf.put(b.albedoColor.second); instColBuf.put(b.albedoColor.third)
            instColBuf.put(if (b.type.isLuminous) 1.7f else 0f)
        }
        instPosBuf.position(0)
        instColBuf.position(0)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instPosVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 4 * 4, instPosBuf)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instColVbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 4 * 4, instColBuf)

        instancedRockProg.use()
        instancedRockProg.setMat4("uViewProj", viewProjMatrix)
        instancedRockProg.setFloat("uLogDepthCoef", logDepthCoef)
        instancedRockProg.setVec3("uStarPos", primaryStarPos.x.toFloat(), primaryStarPos.y.toFloat(), primaryStarPos.z.toFloat())

        GLES30.glBindVertexArray(asteroidVao)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, asteroidMesh.indexCount, GLES30.GL_UNSIGNED_SHORT, 0, count)
        GLES30.glBindVertexArray(0)
    }

    private fun drawVfxAndParticles(
        frame: RenderFrameState,
        majorBodies: List<CelestialBody>,
        cameraOrigin: Vec3d,
        primaryStarPos: Vec3d,
        trueScale: Boolean,
        logDepthCoef: Float
    ) {
        // Shockwave rings, Gamma-Ray Bursts & Comet Tails
        lineProg.use()
        lineProg.setMat4("uViewProj", viewProjMatrix)
        lineProg.setFloat("uLogDepthCoef", logDepthCoef)
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)

        // Comet anti-solar ion & dust tails
        for (b in majorBodies) {
            if (b.type != BodyType.COMET) continue
            val rel = b.pos - cameraOrigin
            val away = (rel - primaryStarPos).normalized()
            val tailLen = 0.45 + 0.3 / rel.length().coerceAtLeast(0.35)
            val end = rel + away * tailLen
            lineBuf.clear()
            lineBuf.put(rel.x.toFloat()); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat())
            lineBuf.put(0.45f); lineBuf.put(0.9f); lineBuf.put(1.0f); lineBuf.put(0.95f)
            lineBuf.put(end.x.toFloat()); lineBuf.put(end.y.toFloat()); lineBuf.put(end.z.toFloat())
            lineBuf.put(0.15f); lineBuf.put(0.45f); lineBuf.put(0.95f); lineBuf.put(0.0f)
            lineBuf.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 2 * 7 * 4, lineBuf)
            GLES30.glLineWidth(4.0f)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
        }

        // Curved Tidal Spaghettification Plasma Filaments pulling from stars/planets into Black Holes
        for (fil in frame.tidalFilaments) {
            val pts = fil.points
            if (pts.size < 2) continue
            val count = pts.size.coerceAtMost(maxLineVerts)
            lineBuf.clear()
            for (i in 0 until count) {
                val rel = pts[i] - cameraOrigin
                val t = i.toFloat() / (count - 1).coerceAtLeast(1)
                val alpha = (1.0f - t * 0.45f) * fil.intensity.coerceIn(0.2f, 1.3f)
                // Taper from victim color into blazing white-gold accretion stream
                val cr = fil.color.first * (1.25f + t * 0.4f)
                val cg = fil.color.second * (1.05f + t * 0.3f)
                val cb = fil.color.third + t * 0.35f
                lineBuf.put(rel.x.toFloat()); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat())
                lineBuf.put(cr); lineBuf.put(cg); lineBuf.put(cb); lineBuf.put(alpha.coerceIn(0f, 1f))
            }
            lineBuf.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 7 * 4, lineBuf)
            GLES30.glLineWidth(6.5f)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, count)
        }

        // Expanding Shockwaves & Gravitational Wave Ripples
        for (sw in frame.shockwaves) {
            val t = (sw.ageSec / sw.durationSec).coerceIn(0f, 1f)
            val rad = (sw.maxRadiusAu * t.toDouble().pow(0.65)).toFloat()
            val alpha = (1f - t) * (1f - t)
            val rel = sw.pos - cameraOrigin
            val segs = 48
            lineBuf.clear()
            for (i in 0..segs) {
                val a = (i.toFloat() / segs) * (2f * PI.toFloat())
                val x = rel.x.toFloat() + cos(a) * rad
                val y = rel.y.toFloat()
                val z = rel.z.toFloat() + sin(a) * rad
                lineBuf.put(x); lineBuf.put(y); lineBuf.put(z)
                lineBuf.put(sw.color.first * 1.4f); lineBuf.put(sw.color.second * 1.4f); lineBuf.put(sw.color.third * 1.4f); lineBuf.put(alpha)
            }
            lineBuf.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, (segs + 1) * 7 * 4, lineBuf)
            GLES30.glLineWidth(if (sw.isGravitationalWave) 5.0f else 3.5f)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, segs + 1)
        }

        // Gamma-Ray Bursts (bipolar hyper-luminous beams)
        for (grb in frame.gammaBursts) {
            val t = (grb.ageSec / grb.durationSec).coerceIn(0f, 1f)
            val alpha = (1f - t)
            val rel = grb.origin - cameraOrigin
            val p1 = rel - grb.dir * 35.0
            val p2 = rel + grb.dir * 35.0
            lineBuf.clear()
            lineBuf.put(p1.x.toFloat()); lineBuf.put(p1.y.toFloat()); lineBuf.put(p1.z.toFloat())
            lineBuf.put(grb.color.first * 2f); lineBuf.put(grb.color.second * 2f); lineBuf.put(grb.color.third * 2f); lineBuf.put(alpha)
            lineBuf.put(p2.x.toFloat()); lineBuf.put(p2.y.toFloat()); lineBuf.put(p2.z.toFloat())
            lineBuf.put(grb.color.first * 2f); lineBuf.put(grb.color.second * 2f); lineBuf.put(grb.color.third * 2f); lineBuf.put(alpha)
            lineBuf.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 2 * 7 * 4, lineBuf)
            GLES30.glLineWidth(9.0f)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, 2)
        }
        GLES30.glLineWidth(1.5f)
        GLES30.glBindVertexArray(0)

        // Debris, Ejecta & Orbiting Black Hole Accretion Parcels
        val pts = frame.particles
        val parcelsCount = majorBodies.sumOf { if (it.type == BodyType.BLACK_HOLE) it.accretionParcels.size else 0 }
        if (pts.isNotEmpty() || parcelsCount > 0) {
            particleBuf.clear()
            var count = 0
            val ptsCount = pts.size.coerceAtMost(maxParticles)
            for (i in 0 until ptsCount) {
                val p = pts[i]
                val fade = (1f - p.life / p.maxLife).coerceIn(0f, 1f)
                particleBuf.put((p.x - cameraOrigin.x).toFloat())
                particleBuf.put((p.y - cameraOrigin.y).toFloat())
                particleBuf.put((p.z - cameraOrigin.z).toFloat())
                particleBuf.put(p.r * fade * 1.5f)
                particleBuf.put(p.g * fade * 1.5f)
                particleBuf.put(p.b * fade * 1.5f)
                particleBuf.put(p.size)
                count++
            }
            for (b in majorBodies) {
                if (b.type != BodyType.BLACK_HOLE || b.accretionParcels.isEmpty()) continue
                for (ap in b.accretionParcels) {
                    if (count >= maxParticles) break
                    val worldP = b.pos + ap.relPos
                    val rgb = kelvinToRgb(ap.temperatureK.coerceIn(2200.0, 45000.0))
                    val heatBoost = (1.1f + (ap.temperatureK / 18000.0).toFloat()).coerceIn(1.1f, 2.2f)
                    particleBuf.put((worldP.x - cameraOrigin.x).toFloat())
                    particleBuf.put((worldP.y - cameraOrigin.y).toFloat())
                    particleBuf.put((worldP.z - cameraOrigin.z).toFloat())
                    particleBuf.put(rgb.first * heatBoost)
                    particleBuf.put(rgb.second * heatBoost)
                    particleBuf.put(rgb.third * heatBoost)
                    particleBuf.put(2.6f)
                    count++
                }
            }
            if (count > 0) {
                particleBuf.position(0)
                particleProg.use()
                particleProg.setMat4("uViewProj", viewProjMatrix)
                particleProg.setFloat("uLogDepthCoef", logDepthCoef)
                particleProg.setFloat("uPointScale", viewportH * 0.018f)
                GLES30.glBindVertexArray(particleVao)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, particleVbo)
                GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count * 7 * 4, particleBuf)
                GLES30.glDrawArrays(GLES30.GL_POINTS, 0, count)
                GLES30.glBindVertexArray(0)
            }
        }
    }

    private fun runPostProcess(blackHoles: List<CelestialBody>, cameraOrigin: Vec3d, trueScale: Boolean) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)

        val passes = graphicsSettings.tier.bloomPasses
        val bw = max(160, fboW / 2)
        val bh = max(100, fboH / 2)

        if (passes > 0) {
            // Bright pass into bloomFbo[0]
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[0])
            GLES30.glViewport(0, 0, bw, bh)
            brightPassProg.use()
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColorTex)
            brightPassProg.setInt("uSceneTex", 0)
            quadMesh.draw()

            // Separable Gaussian blur ping-pong
            var horizontal = true
            blurProg.use()
            blurProg.setInt("uTex", 0)
            for (p in 0 until passes * 2) {
                val targetIdx = if (horizontal) 1 else 0
                val srcIdx = if (horizontal) 0 else 1
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, bloomFbo[targetIdx])
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[srcIdx])
                if (horizontal) {
                    blurProg.setVec2("uDir", 1.5f / bw, 0f)
                } else {
                    blurProg.setVec2("uDir", 0f, 1.5f / bh)
                }
                quadMesh.draw()
                horizontal = !horizontal
            }
        }

        // Composite to default screen framebuffer + gravitational lensing
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewportW, viewportH)
        compositeProg.use()

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneColorTex)
        compositeProg.setInt("uSceneTex", 0)

        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bloomTex[0])
        compositeProg.setInt("uBloomTex", 1)

        compositeProg.setFloat("uBloomStrength", if (passes > 0) graphicsSettings.bloomIntensity * 0.72f else 0f)
        compositeProg.setFloat("uAspect", viewportW.toFloat() / viewportH.toFloat())
        compositeProg.setInt("uLensingEnabled", if (graphicsSettings.tier.gravitationalLensing) 1 else 0)

        var bhVisible = 0
        for (bh in blackHoles) {
            if (bhVisible >= 4) break
            val rel = bh.pos - cameraOrigin
            val clip = floatArrayOf(rel.x.toFloat(), rel.y.toFloat(), rel.z.toFloat(), 1f)
            val out = FloatArray(4)
            Matrix.multiplyMV(out, 0, viewProjMatrix, 0, clip, 0)
            if (out[3] > 0.05f) {
                val u = (out[0] / out[3]) * 0.5f + 0.5f
                val v = (out[1] / out[3]) * 0.5f + 0.5f
                val rad = bh.getVisualRadiusAu(trueScale).toFloat()
                val einsteinUv = ((rad * 3.2f) / out[3]).coerceIn(0.01f, 0.28f)
                compositeProg.setVec4("uBhScreen[$bhVisible]", u, v, einsteinUv, einsteinUv * 0.35f)
                bhVisible++
            }
        }
        compositeProg.setInt("uBhCount", bhVisible)

        quadMesh.draw()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun projectToScreen(x: Float, y: Float, z: Float): Pair<Float, Float>? {
        val out = FloatArray(4)
        Matrix.multiplyMV(out, 0, viewProjMatrix, 0, floatArrayOf(x, y, z, 1f), 0)
        if (out[3] <= 0.001f) return null
        val ndcX = out[0] / out[3]
        val ndcY = out[1] / out[3]
        if (ndcX < -1.1f || ndcX > 1.1f || ndcY < -1.1f || ndcY > 1.1f) return null
        val px = (ndcX * 0.5f + 0.5f) * viewportW
        val py = (1f - (ndcY * 0.5f + 0.5f)) * viewportH
        return Pair(px, py)
    }

    /**
     * Unprojects a screen tap (px, py) into a 3D world ray and picks the closest intersecting body.
     */
    fun pickBodyAt(screenX: Float, screenY: Float): Long? {
        val bodies = lastBodiesForPick
        if (bodies.isEmpty() || viewportW <= 0 || viewportH <= 0) return null

        val invVp = FloatArray(16)
        if (!Matrix.invertM(invVp, 0, lastViewProjSnapshot, 0)) return null

        val ndcX = (screenX / viewportW) * 2f - 1f
        val ndcY = 1f - (screenY / viewportH) * 2f
        val nearWorld = FloatArray(4)
        val farWorld = FloatArray(4)
        Matrix.multiplyMV(nearWorld, 0, invVp, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0)
        Matrix.multiplyMV(farWorld, 0, invVp, 0, floatArrayOf(ndcX, ndcY, 1f, 1f), 0)
        if (abs(nearWorld[3]) < 1e-6f || abs(farWorld[3]) < 1e-6f) return null

        val ox = nearWorld[0] / nearWorld[3]
        val oy = nearWorld[1] / nearWorld[3]
        val oz = nearWorld[2] / nearWorld[3]
        val fx = farWorld[0] / farWorld[3]
        val fy = farWorld[1] / farWorld[3]
        val fz = farWorld[2] / farWorld[3]

        val dir = Vec3d((fx - ox).toDouble(), (fy - oy).toDouble(), (fz - oz).toDouble()).normalized()
        val origin = Vec3d(ox.toDouble(), oy.toDouble(), oz.toDouble())
        val camOrigin = lastCameraOrigin

        var bestId: Long? = null
        var bestT = Double.MAX_VALUE
        val minPickRadius = (distanceAu * 0.038).coerceAtLeast(0.015)

        for (b in bodies) {
            if (b.isBackgroundCluster) continue
            val center = b.pos - camOrigin
            val r = max(b.getVisualRadiusAu(lastTrueScale) * 1.45, minPickRadius)
            val oc = origin - center
            val proj = oc.dot(dir)
            val c = oc.dot(oc) - r * r
            val disc = proj * proj - c
            if (disc >= 0.0) {
                val t = -proj - sqrt(disc)
                if (t > 0.0 && t < bestT) {
                    bestT = t
                    bestId = b.id
                }
            }
        }
        return bestId
    }

    private fun drawSpawnTargetReticle(spawnPos: Vec3d, cameraOrigin: Vec3d, camDistAu: Double, logDepthCoef: Float) {
        val rel = spawnPos - cameraOrigin
        val reticleRad = (camDistAu * 0.045).coerceIn(0.03, 0.75).toFloat()
        val segs = 36
        lineProg.use()
        lineProg.setMat4("uViewProj", viewProjMatrix)
        lineProg.setFloat("uLogDepthCoef", logDepthCoef)
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)

        // Outer ring
        lineBuf.clear()
        for (i in 0..segs) {
            val a = (i.toFloat() / segs) * (2f * PI.toFloat())
            lineBuf.put(rel.x.toFloat() + cos(a) * reticleRad)
            lineBuf.put(rel.y.toFloat())
            lineBuf.put(rel.z.toFloat() + sin(a) * reticleRad)
            lineBuf.put(0.18f); lineBuf.put(0.95f); lineBuf.put(0.85f); lineBuf.put(0.9f)
        }
        lineBuf.position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, (segs + 1) * 7 * 4, lineBuf)
        GLES30.glLineWidth(3.5f)
        GLES30.glDrawArrays(GLES30.GL_LINE_STRIP, 0, segs + 1)

        // Crosshair ticks
        val arm = reticleRad * 1.45f
        lineBuf.clear()
        lineBuf.put(rel.x.toFloat() - arm); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat())
        lineBuf.put(0.18f); lineBuf.put(0.95f); lineBuf.put(0.85f); lineBuf.put(0.75f)
        lineBuf.put(rel.x.toFloat() + arm); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat())
        lineBuf.put(0.18f); lineBuf.put(0.95f); lineBuf.put(0.85f); lineBuf.put(0.75f)
        lineBuf.put(rel.x.toFloat()); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat() - arm)
        lineBuf.put(0.18f); lineBuf.put(0.95f); lineBuf.put(0.85f); lineBuf.put(0.75f)
        lineBuf.put(rel.x.toFloat()); lineBuf.put(rel.y.toFloat()); lineBuf.put(rel.z.toFloat() + arm)
        lineBuf.put(0.18f); lineBuf.put(0.95f); lineBuf.put(0.85f); lineBuf.put(0.75f)
        lineBuf.position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 4 * 7 * 4, lineBuf)
        GLES30.glLineWidth(2.5f)
        GLES30.glDrawArrays(GLES30.GL_LINES, 0, 4)

        GLES30.glLineWidth(1.5f)
        GLES30.glBindVertexArray(0)
    }

    /**
     * Maps a screen coordinate (px, py) onto the orbital XZ plane (Y=0 relative to camera origin)
     * for slingshot launching, tap-to-spawn placement, and dragging—including shallow horizon rays.
     */
    fun screenToEclipticWorldPos(screenX: Float, screenY: Float): Vec3d? {
        if (viewportW <= 0 || viewportH <= 0) return null
        val invVp = FloatArray(16)
        if (!Matrix.invertM(invVp, 0, lastViewProjSnapshot, 0)) return null

        val ndcX = (screenX / viewportW) * 2f - 1f
        val ndcY = 1f - (screenY / viewportH) * 2f
        val nearW = FloatArray(4)
        val farW = FloatArray(4)
        Matrix.multiplyMV(nearW, 0, invVp, 0, floatArrayOf(ndcX, ndcY, -1f, 1f), 0)
        Matrix.multiplyMV(farW, 0, invVp, 0, floatArrayOf(ndcX, ndcY, 1f, 1f), 0)
        if (abs(nearW[3]) < 1e-6f || abs(farW[3]) < 1e-6f) return null

        val ox = (nearW[0] / nearW[3]).toDouble()
        val oy = (nearW[1] / nearW[3]).toDouble()
        val oz = (nearW[2] / nearW[3]).toDouble()
        val dx = (farW[0] / farW[3]).toDouble() - ox
        val dy = (farW[1] / farW[3]).toDouble() - oy
        val dz = (farW[2] / farW[3]).toDouble() - oz

        val horiz = hypot(dx, dz).coerceAtLeast(1e-6)
        if (abs(dy) >= 0.04 * horiz) {
            val t = -oy / dy
            if (t > 0.0) {
                val maxRange = (distanceAu * 8.0).coerceIn(2.0, 220.0)
                val px = ox + dx * t
                val pz = oz + dz * t
                val r = hypot(px, pz)
                return if (r <= maxRange) {
                    lastCameraOrigin + Vec3d(px, 0.0, pz)
                } else {
                    lastCameraOrigin + Vec3d(px * (maxRange / r), 0.0, pz * (maxRange / r))
                }
            }
        }
        // Fallback when ray is parallel to ecliptic or pointing above horizon: project forward onto ecliptic disk
        val dirXz = Vec3d(dx / horiz, 0.0, dz / horiz)
        val projDist = (distanceAu * 1.15).coerceIn(0.25, 45.0)
        val eyeXz = Vec3d(lastCamEyeRel[0].toDouble(), 0.0, lastCamEyeRel[2].toDouble())
        return lastCameraOrigin + eyeXz + dirXz * projDist
    }

    private fun setupInstancedAsteroidVao() {
        val vao = IntArray(1)
        val vbos = IntArray(2)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(2, vbos, 0)
        asteroidVao = vao[0]
        instPosVbo = vbos[0]
        instColVbo = vbos[1]

        GLES30.glBindVertexArray(asteroidVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, asteroidMesh.vboId)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, asteroidMesh.iboId)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 8 * 4, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 8 * 4, 3 * 4)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instPosVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxInstances * 4 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribDivisor(3, 1)

        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instColVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxInstances * 4 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 16, 0)
        GLES30.glVertexAttribDivisor(4, 1)

        GLES30.glBindVertexArray(0)
    }

    private fun setupDynamicBuffers() {
        val vaos = IntArray(2)
        val vbos = IntArray(2)
        GLES30.glGenVertexArrays(2, vaos, 0)
        GLES30.glGenBuffers(2, vbos, 0)

        lineVao = vaos[0]; lineVbo = vbos[0]
        GLES30.glBindVertexArray(lineVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, lineVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxLineVerts * 7 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 7 * 4, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 7 * 4, 3 * 4)

        particleVao = vaos[1]; particleVbo = vbos[1]
        GLES30.glBindVertexArray(particleVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, particleVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, maxParticles * 7 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 7 * 4, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 4, GLES30.GL_FLOAT, false, 7 * 4, 3 * 4)

        GLES30.glBindVertexArray(0)
    }

    private fun createSkyboxCube(): GlMesh {
        val v = floatArrayOf(
            -1f,-1f,-1f, 0f,0f,0f, 0f,0f,
             1f,-1f,-1f, 0f,0f,0f, 0f,0f,
             1f, 1f,-1f, 0f,0f,0f, 0f,0f,
            -1f, 1f,-1f, 0f,0f,0f, 0f,0f,
            -1f,-1f, 1f, 0f,0f,0f, 0f,0f,
             1f,-1f, 1f, 0f,0f,0f, 0f,0f,
             1f, 1f, 1f, 0f,0f,0f, 0f,0f,
            -1f, 1f, 1f, 0f,0f,0f, 0f,0f
        )
        val idx = shortArrayOf(
            0,1,2, 0,2,3,
            4,6,5, 4,7,6,
            0,4,5, 0,5,1,
            3,2,6, 3,6,7,
            1,5,6, 1,6,2,
            0,3,7, 0,7,4
        )
        val vao = IntArray(1); val vbo = IntArray(1); val ibo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glGenBuffers(1, ibo, 0)
        val vb = ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply { put(v); position(0) }
        val ib = ByteBuffer.allocateDirect(idx.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().apply { put(idx); position(0) }
        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 2, ib, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 8 * 4, 0)
        GLES30.glBindVertexArray(0)
        return GlMesh(vao[0], vbo[0], ibo[0], idx.size)
    }
}
