package com.example.render

import android.app.ActivityManager
import android.content.Context

enum class QualityTier(
    val label: String,
    val renderScale: Float,
    val bloomPasses: Int,
    val gravitationalLensing: Boolean,
    val atmosphereScattering: Boolean,
    val eclipseShadows: Boolean,
    val fbmOctaves: Int
) {
    LOW("Low", 0.70f, 0, false, false, false, 2),
    MEDIUM("Medium", 0.85f, 2, true, true, false, 3),
    HIGH("High", 1.0f, 3, true, true, true, 4),
    ULTRA("Ultra", 1.0f, 5, true, true, true, 5)
}

data class GraphicsSettings(
    val tier: QualityTier = QualityTier.HIGH,
    val batterySaver: Boolean = false,
    val bloomIntensity: Float = 1.0f,
    val autoDetectedTier: QualityTier = QualityTier.HIGH
) {
    val targetFps: Int get() = if (batterySaver) 30 else 60
    val effectiveScale: Float get() = if (batterySaver) minOf(tier.renderScale, 0.80f) else tier.renderScale
}

object DeviceQualityDetector {
    fun detectBestTier(context: Context): QualityTier {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            totalRamGb >= 7.5 && cores >= 8 -> QualityTier.ULTRA
            totalRamGb >= 5.0 && cores >= 6 -> QualityTier.HIGH
            totalRamGb >= 3.0 -> QualityTier.MEDIUM
            else -> QualityTier.LOW
        }
    }
}
