package com.example.render

/** Rendering-only black-hole appearance; this never changes simulated mass or accretion state. */
enum class BlackHoleLook(val label: String, val description: String) {
    INTERSTELLAR_CINEMATIC(
        label = "Interstellar-inspired",
        description = "Artistic luminous disk and lensed arches; no polar jets"
    ),
    PHYSICAL_DISK_ONLY(
        label = "Simulation-driven disk",
        description = "Disk brightness follows simulated accretion; no cinematic disk fallback"
    )
}

/** Shared shader limits, kept explicit so numerical regression tests cover the GLSL work cap. */
internal object BlackHoleRayMarchLimits {
    const val MAX_STEPS = 512
    const val STEP_FRACTION_OF_RADIUS = 0.022f
    const val MAX_STEP = 0.32f
    const val MIN_STEP = 0.012f
    const val BOUND_EXIT_MARGIN = 1.04f
}
