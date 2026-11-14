package com.example.render

import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import kotlin.math.*
import kotlin.random.Random

class GlMesh(
    val vaoId: Int,
    val vboId: Int,
    val iboId: Int,
    val indexCount: Int,
    val mode: Int = GLES30.GL_TRIANGLES
) {
    fun draw() {
        GLES30.glBindVertexArray(vaoId)
        GLES30.glDrawElements(mode, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vaoId), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(vboId, iboId), 0)
    }
}

object MeshFactory {

    /**
     * Generates a UV sphere mesh with layout: [px, py, pz, nx, ny, nz, u, v] (8 floats per vertex).
     */
    fun createSphere(latBands: Int, lonBands: Int): GlMesh {
        val vertCount = (latBands + 1) * (lonBands + 1)
        val vertices = FloatArray(vertCount * 8)
        var vIdx = 0

        for (lat in 0..latBands) {
            val theta = lat * PI / latBands
            val sinT = sin(theta).toFloat()
            val cosT = cos(theta).toFloat()
            val v = lat.toFloat() / latBands

            for (lon in 0..lonBands) {
                val phi = lon * 2.0 * PI / lonBands
                val sinP = sin(phi).toFloat()
                val cosP = cos(phi).toFloat()
                val u = lon.toFloat() / lonBands

                val x = cosP * sinT
                val y = cosT
                val z = sinP * sinT

                vertices[vIdx++] = x
                vertices[vIdx++] = y
                vertices[vIdx++] = z
                vertices[vIdx++] = x
                vertices[vIdx++] = y
                vertices[vIdx++] = z
                vertices[vIdx++] = u
                vertices[vIdx++] = v
            }
        }

        val indices = ShortArray(latBands * lonBands * 6)
        var iIdx = 0
        for (lat in 0 until latBands) {
            for (lon in 0 until lonBands) {
                val first = (lat * (lonBands + 1) + lon).toShort()
                val second = (first + lonBands + 1).toShort()
                indices[iIdx++] = first
                indices[iIdx++] = second
                indices[iIdx++] = (first + 1).toShort()

                indices[iIdx++] = second
                indices[iIdx++] = (second + 1).toShort()
                indices[iIdx++] = (first + 1).toShort()
            }
        }

        return uploadInterleavedMesh(vertices, indices, hasNormalsAndUv = true)
    }

    /**
     * Generates a displaced irregular faceted rock mesh for instanced asteroid rendering.
     */
    fun createAsteroidRock(): GlMesh {
        val latBands = 7
        val lonBands = 10
        val rng = Random(42)
        val ringSeeds = FloatArray(latBands + 1) { 0.78f + rng.nextFloat() * 0.42f }
        val vertCount = (latBands + 1) * (lonBands + 1)
        val vertices = FloatArray(vertCount * 8)
        var vIdx = 0

        for (lat in 0..latBands) {
            val theta = lat * PI / latBands
            val sinT = sin(theta).toFloat()
            val cosT = cos(theta).toFloat()
            for (lon in 0..lonBands) {
                val phi = lon * 2.0 * PI / lonBands
                val cosP = cos(phi).toFloat()
                val sinP = sin(phi).toFloat()
                val bump = if (lat == 0 || lat == latBands) 0.9f else {
                    val seamLon = if (lon == lonBands) 0 else lon
                    0.75f + 0.35f * sin(seamLon * 1.7f + lat * 2.3f) * ringSeeds[lat]
                }
                val x = cosP * sinT * bump
                val y = cosT * bump * 0.82f
                val z = sinP * sinT * bump * 1.1f
                val len = sqrt(x * x + y * y + z * z).coerceAtLeast(1e-4f)
                vertices[vIdx++] = x
                vertices[vIdx++] = y
                vertices[vIdx++] = z
                vertices[vIdx++] = x / len
                vertices[vIdx++] = y / len
                vertices[vIdx++] = z / len
                vertices[vIdx++] = lon.toFloat() / lonBands
                vertices[vIdx++] = lat.toFloat() / latBands
            }
        }

        val indices = ShortArray(latBands * lonBands * 6)
        var iIdx = 0
        for (lat in 0 until latBands) {
            for (lon in 0 until lonBands) {
                val first = (lat * (lonBands + 1) + lon).toShort()
                val second = (first + lonBands + 1).toShort()
                indices[iIdx++] = first
                indices[iIdx++] = second
                indices[iIdx++] = (first + 1).toShort()
                indices[iIdx++] = second
                indices[iIdx++] = (second + 1).toShort()
                indices[iIdx++] = (first + 1).toShort()
            }
        }
        return uploadInterleavedMesh(vertices, indices, hasNormalsAndUv = true)
    }

    /**
     * Generates a flat unit annulus in XZ plane with radial U coordinate in [0..1] (mapped via uInner/uOuter).
     */
    fun createAnnulus(segments: Int = 96): GlMesh {
        val vertices = FloatArray((segments + 1) * 2 * 8)
        var vIdx = 0
        for (i in 0..segments) {
            val angle = (i.toDouble() / segments) * 2.0 * PI
            val c = cos(angle).toFloat()
            val s = sin(angle).toFloat()
            val v = i.toFloat() / segments

            // Inner ring vertex (encoded with u = 0.0)
            vertices[vIdx++] = c; vertices[vIdx++] = 0f; vertices[vIdx++] = s
            vertices[vIdx++] = 0f; vertices[vIdx++] = 1f; vertices[vIdx++] = 0f
            vertices[vIdx++] = 0f; vertices[vIdx++] = v

            // Outer ring vertex (encoded with u = 1.0)
            vertices[vIdx++] = c; vertices[vIdx++] = 0f; vertices[vIdx++] = s
            vertices[vIdx++] = 0f; vertices[vIdx++] = 1f; vertices[vIdx++] = 0f
            vertices[vIdx++] = 1f; vertices[vIdx++] = v
        }

        val indices = ShortArray(segments * 6)
        var iIdx = 0
        for (i in 0 until segments) {
            val base = (i * 2).toShort()
            indices[iIdx++] = base
            indices[iIdx++] = (base + 1).toShort()
            indices[iIdx++] = (base + 2).toShort()

            indices[iIdx++] = (base + 1).toShort()
            indices[iIdx++] = (base + 3).toShort()
            indices[iIdx++] = (base + 2).toShort()
        }
        return uploadInterleavedMesh(vertices, indices, hasNormalsAndUv = true)
    }

    /**
     * Unit quad [-1..1] in XY with UVs [0..1] for screen-space post-processing and star corona billboards.
     */
    fun createQuad(): GlMesh {
        val vertices = floatArrayOf(
            -1f, -1f, 0f, 0f, 0f, 1f, 0f, 0f,
             1f, -1f, 0f, 0f, 0f, 1f, 1f, 0f,
             1f,  1f, 0f, 0f, 0f, 1f, 1f, 1f,
            -1f,  1f, 0f, 0f, 0f, 1f, 0f, 1f
        )
        val indices = shortArrayOf(0, 1, 2, 0, 2, 3)
        return uploadInterleavedMesh(vertices, indices, hasNormalsAndUv = true)
    }

    private fun uploadInterleavedMesh(
        vertices: FloatArray,
        indices: ShortArray,
        hasNormalsAndUv: Boolean
    ): GlMesh {
        val vao = IntArray(1)
        val vbo = IntArray(1)
        val ibo = IntArray(1)
        GLES30.glGenVertexArrays(1, vao, 0)
        GLES30.glGenBuffers(1, vbo, 0)
        GLES30.glGenBuffers(1, ibo, 0)

        val vBuf: FloatBuffer = ByteBuffer.allocateDirect(vertices.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(vertices)
                position(0)
            }
        val iBuf: ShortBuffer = ByteBuffer.allocateDirect(indices.size * 2)
            .order(ByteOrder.nativeOrder()).asShortBuffer().apply {
                put(indices)
                position(0)
            }

        GLES30.glBindVertexArray(vao[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo[0])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vBuf, GLES30.GL_STATIC_DRAW)

        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo[0])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 2, iBuf, GLES30.GL_STATIC_DRAW)

        val stride = if (hasNormalsAndUv) 8 * 4 else 3 * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        if (hasNormalsAndUv) {
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 3 * 4)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 6 * 4)
        }
        GLES30.glBindVertexArray(0)

        return GlMesh(vao[0], vbo[0], ibo[0], indices.size)
    }
}
