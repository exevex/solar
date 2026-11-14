package com.example.render

import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import com.example.simulation.Vec3d
import kotlin.math.abs
import kotlin.math.hypot

enum class InteractionMode {
    ORBIT_SELECT,
    SLINGSHOT_LAUNCH,
    SPAWN_TAP
}

/**
 * Custom GLSurfaceView (OpenGL ES 3.0) with full mobile space gestures:
 * - 1-finger drag: orbit camera
 * - 2-finger pinch: logarithmic zoom
 * - Tap: 3D ray-sphere body selection (or tap-to-spawn in Spawn mode)
 * - Long-press & drag: grab & fling existing celestial bodies
 * - Slingshot drag: aim & launch bodies with live predicted trajectory
 */
class SolarGLSurfaceView(
    context: Context,
    val renderer: SolarGLRenderer,
    private val onTapBody: (Long?) -> Unit,
    private val onTapSpawnWorld: (Vec3d) -> Unit,
    private val onSlingshotAim: (spawnPos: Vec3d, launchVel: Vec3d) -> Unit,
    private val onSlingshotRelease: (spawnPos: Vec3d, launchVel: Vec3d) -> Unit,
    private val onFlingMoveBody: (bodyId: Long, newPos: Vec3d, newVel: Vec3d?) -> Unit
) : GLSurfaceView(context) {

    var interactionMode: InteractionMode = InteractionMode.ORBIT_SELECT

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastX = 0f
    private var lastY = 0f
    private var downX = 0f
    private var downY = 0f
    private var downTimeMs = 0L
    private var isMultiTouch = false

    // Slingshot state
    private var slingshotStartWorld: Vec3d? = null
    private var slingshotVel: Vec3d = Vec3d.ZERO
    private var isSlingshotDragging = false

    // Long-press grab & fling state
    private var grabbedBodyId: Long? = null
    private var lastGrabWorldPos: Vec3d? = null
    private var lastGrabTimeMs = 0L
    private var grabVelocity = Vec3d.ZERO

    private val longPressRunnable = Runnable {
        if (!isMultiTouch && interactionMode == InteractionMode.ORBIT_SELECT) {
            val picked = renderer.pickBodyAt(downX, downY)
            if (picked != null) {
                grabbedBodyId = picked
                lastGrabWorldPos = renderer.screenToEclipticWorldPos(downX, downY)
                lastGrabTimeMs = System.currentTimeMillis()
                grabVelocity = Vec3d.ZERO
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor.coerceIn(0.6f, 1.5f)
                renderer.distanceAu = (renderer.distanceAu / factor).coerceIn(0.014, 180.0)
                return true
            }
        }
    )

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 24, 0)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                lastX = event.x
                lastY = event.y
                downTimeMs = System.currentTimeMillis()
                isMultiTouch = false
                grabbedBodyId = null
                isSlingshotDragging = false

                if (interactionMode == InteractionMode.SLINGSHOT_LAUNCH) {
                    val worldPt = renderer.screenToEclipticWorldPos(event.x, event.y)
                    if (worldPt != null) {
                        slingshotStartWorld = worldPt
                        slingshotVel = Vec3d.ZERO
                        isSlingshotDragging = true
                        onSlingshotAim(worldPt, Vec3d.ZERO)
                    }
                } else if (interactionMode == InteractionMode.ORBIT_SELECT) {
                    mainHandler.postDelayed(longPressRunnable, 420L)
                }
                return true
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                isMultiTouch = true
                mainHandler.removeCallbacks(longPressRunnable)
                isSlingshotDragging = false
                renderer.slingshotPreview = null
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x
                lastY = event.y

                if (hypot((event.x - downX).toDouble(), (event.y - downY).toDouble()) > 18.0) {
                    mainHandler.removeCallbacks(longPressRunnable)
                }

                if (isMultiTouch || event.pointerCount > 1 || scaleDetector.isInProgress) {
                    return true
                }

                val grabbed = grabbedBodyId
                if (grabbed != null) {
                    val worldPt = renderer.screenToEclipticWorldPos(event.x, event.y)
                    if (worldPt != null) {
                        val now = System.currentTimeMillis()
                        val dt = ((now - lastGrabTimeMs) / 1000.0).coerceAtLeast(0.016)
                        lastGrabWorldPos?.let { prev ->
                            grabVelocity = (worldPt - prev) / dt * 0.45
                        }
                        lastGrabWorldPos = worldPt
                        lastGrabTimeMs = now
                        onFlingMoveBody(grabbed, worldPt, null)
                    }
                    return true
                }

                if (isSlingshotDragging && slingshotStartWorld != null) {
                    val dragWorld = renderer.screenToEclipticWorldPos(event.x, event.y)
                    if (dragWorld != null) {
                        val pull = slingshotStartWorld!! - dragWorld
                        slingshotVel = pull * 3.8
                        onSlingshotAim(slingshotStartWorld!!, slingshotVel)
                    }
                    return true
                }

                // 1-finger camera orbit
                renderer.yawDeg = (renderer.yawDeg - dx * 0.28f) % 360f
                renderer.pitchDeg = (renderer.pitchDeg + dy * 0.24f).coerceIn(-84f, 84f)
                return true
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                mainHandler.removeCallbacks(longPressRunnable)

                val grabbed = grabbedBodyId
                if (grabbed != null) {
                    lastGrabWorldPos?.let { pos ->
                        onFlingMoveBody(grabbed, pos, grabVelocity)
                    }
                    grabbedBodyId = null
                    return true
                }

                if (isSlingshotDragging && slingshotStartWorld != null) {
                    onSlingshotRelease(slingshotStartWorld!!, slingshotVel)
                    isSlingshotDragging = false
                    slingshotStartWorld = null
                    return true
                }

                val elapsed = System.currentTimeMillis() - downTimeMs
                val moved = hypot((event.x - downX).toDouble(), (event.y - downY).toDouble())
                if (!isMultiTouch && elapsed < 280L && moved < 22.0) {
                    if (interactionMode == InteractionMode.SPAWN_TAP) {
                        val worldPt = renderer.screenToEclipticWorldPos(event.x, event.y)
                        if (worldPt != null) onTapSpawnWorld(worldPt)
                    } else {
                        val picked = renderer.pickBodyAt(event.x, event.y)
                        onTapBody(picked)
                    }
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
