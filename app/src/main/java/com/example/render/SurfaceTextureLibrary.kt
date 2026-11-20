package com.example.render

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES30
import android.opengl.GLUtils
import com.example.R
import com.example.simulation.BodyType
import com.example.simulation.CelestialBody

/**
 * NASA/JPL/Caltech equirectangular surface maps packed as real app assets.
 * The procedural shader remains the fallback for user-created bodies that have
 * no observation-based map.
 */
object SurfaceTextureLibrary {
    private val resourceByKey = mapOf(
        "sun" to R.drawable.surface_sun,
        "mercury" to R.drawable.surface_mercury,
        "venus" to R.drawable.surface_venus,
        "earth" to R.drawable.surface_earth,
        "moon" to R.drawable.surface_moon,
        "mars" to R.drawable.surface_mars,
        "phobos" to R.drawable.surface_phobos,
        "deimos" to R.drawable.surface_deimos,
        "jupiter" to R.drawable.surface_jupiter,
        "io" to R.drawable.surface_io,
        "europa" to R.drawable.surface_europa,
        "ganymede" to R.drawable.surface_ganymede,
        "callisto" to R.drawable.surface_callisto,
        "saturn" to R.drawable.surface_saturn,
        "enceladus" to R.drawable.surface_enceladus,
        "titan" to R.drawable.surface_titan,
        "uranus" to R.drawable.surface_uranus,
        "neptune" to R.drawable.surface_neptune,
        "triton" to R.drawable.surface_triton,
        "pluto" to R.drawable.surface_pluto
    )

    fun keyForBody(body: CelestialBody): String? {
        val name = body.name.lowercase()
        return when {
            resourceByKey.containsKey(name) -> name
            body.type == BodyType.STAR && name == "sun" -> "sun"
            else -> null
        }
    }

    fun load(context: Context): Map<String, Int> {
        val loaded = LinkedHashMap<String, Int>()
        val ids = IntArray(1)
        for ((key, resourceId) in resourceByKey) {
            val bitmap = BitmapFactory.decodeResource(context.resources, resourceId) ?: continue
            GLES30.glGenTextures(1, ids, 0)
            val textureId = ids[0]
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textureId)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
            GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
            bitmap.recycle()
            loaded[key] = textureId
        }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
        return loaded
    }

    fun release(textures: Map<String, Int>) {
        if (textures.isEmpty()) return
        GLES30.glDeleteTextures(textures.size, textures.values.toIntArray(), 0)
    }
}
