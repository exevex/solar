package com.example.render

import android.opengl.GLES30
import android.util.Log

class GlShaderProgram(val programId: Int) {
    private val uniformCache = HashMap<String, Int>()

    fun use() {
        GLES30.glUseProgram(programId)
    }

    fun loc(name: String): Int {
        return uniformCache.getOrPut(name) {
            GLES30.glGetUniformLocation(programId, name)
        }
    }

    fun setInt(name: String, v: Int) {
        val l = loc(name); if (l >= 0) GLES30.glUniform1i(l, v)
    }
    fun setFloat(name: String, v: Float) {
        val l = loc(name); if (l >= 0) GLES30.glUniform1f(l, v)
    }
    fun setVec2(name: String, x: Float, y: Float) {
        val l = loc(name); if (l >= 0) GLES30.glUniform2f(l, x, y)
    }
    fun setVec3(name: String, x: Float, y: Float, z: Float) {
        val l = loc(name); if (l >= 0) GLES30.glUniform3f(l, x, y, z)
    }
    fun setVec4(name: String, x: Float, y: Float, z: Float, w: Float) {
        val l = loc(name); if (l >= 0) GLES30.glUniform4f(l, x, y, z, w)
    }
    fun setMat4(name: String, m: FloatArray) {
        val l = loc(name); if (l >= 0) GLES30.glUniformMatrix4fv(l, 1, false, m, 0)
    }

    fun release() {
        GLES30.glDeleteProgram(programId)
    }

    companion object {
        fun compile(vertexSrc: String, fragmentSrc: String, tag: String): GlShaderProgram {
            val vs = compileStage(GLES30.GL_VERTEX_SHADER, vertexSrc, "$tag-VS")
            val fs = compileStage(GLES30.GL_FRAGMENT_SHADER, fragmentSrc, "$tag-FS")
            val prog = GLES30.glCreateProgram()
            GLES30.glAttachShader(prog, vs)
            GLES30.glAttachShader(prog, fs)
            GLES30.glLinkProgram(prog)
            val status = IntArray(1)
            GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetProgramInfoLog(prog)
                Log.e("ShaderLibrary", "Link failed ($tag): $log")
            }
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return GlShaderProgram(prog)
        }

        private fun compileStage(type: Int, src: String, tag: String): Int {
            val shader = GLES30.glCreateShader(type)
            GLES30.glShaderSource(shader, src)
            GLES30.glCompileShader(shader)
            val status = IntArray(1)
            GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
            if (status[0] == 0) {
                val log = GLES30.glGetShaderInfoLog(shader)
                Log.e("ShaderLibrary", "Compile failed ($tag): $log")
            }
            return shader
        }
    }
}

/**
 * Complete GLSL #version 300 es shader suite for Solar Sandbox:
 * - Logarithmic depth buffer across AU-to-kilometer cosmic scales
 * - Procedural 3D spherical PBR planets (Earth clouds/oceans/city lights, Mars, Gas Giants, Craters, Lava)
 * - Rayleigh/Mie atmospheric scattering, eclipses & ring shadows
 * - HDR Blackbody Stars with granulation, limb darkening & additive plasma corona
 * - Black Hole event horizon, photon ring, Doppler-beamed accretion disk & gravitational lensing
 * - Instanced asteroid/particle shader & multi-pass Gaussian bloom post-processor
 */
object ShaderLibrary {

    private const val NOISE_GLSL = """
        float hash31(vec3 p) {
            p = fract(p * vec3(443.8975, 397.2973, 491.1871));
            p += dot(p, p.yxz + 19.19);
            return fract((p.x + p.y) * p.z);
        }

        float noise3(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float n000 = hash31(i + vec3(0.0, 0.0, 0.0));
            float n100 = hash31(i + vec3(1.0, 0.0, 0.0));
            float n010 = hash31(i + vec3(0.0, 1.0, 0.0));
            float n110 = hash31(i + vec3(1.0, 1.0, 0.0));
            float n001 = hash31(i + vec3(0.0, 0.0, 1.0));
            float n101 = hash31(i + vec3(1.0, 0.0, 1.0));
            float n011 = hash31(i + vec3(0.0, 1.0, 1.0));
            float n111 = hash31(i + vec3(1.0, 1.0, 1.0));
            float nx00 = mix(n000, n100, f.x);
            float nx10 = mix(n010, n110, f.x);
            float nx01 = mix(n001, n101, f.x);
            float nx11 = mix(n011, n111, f.x);
            float nxy0 = mix(nx00, nx10, f.y);
            float nxy1 = mix(nx01, nx11, f.y);
            return mix(nxy0, nxy1, f.z);
        }

        float fbm(vec3 p, int octaves) {
            float val = 0.0;
            float amp = 0.5;
            float freq = 1.0;
            for (int i = 0; i < 5; i++) {
                if (i >= octaves) break;
                val += amp * noise3(p * freq);
                freq *= 2.05;
                amp *= 0.5;
            }
            return val;
        }
    """

    private const val LOG_DEPTH_VERT = """
        uniform float uLogDepthCoef;
        vec4 applyLogDepth(vec4 clipPos) {
            if (uLogDepthCoef > 0.0 && clipPos.w > 0.0) {
                float logZ = log2(max(1e-6, 1.0 + clipPos.w)) * uLogDepthCoef - 1.0;
                clipPos.z = logZ * clipPos.w;
            }
            return clipPos;
        }
    """

    private const val SKYBOX_COLOR_GLSL = """
        vec3 evalSkyboxColor(vec3 d) {
            // Tilted galactic coordinate plane for the Milky Way band
            vec3 galUp = normalize(vec3(0.32, 0.86, -0.39));
            float galLat = dot(d, galUp);
            float band = exp(-galLat * galLat * 18.0);
            float coreGlow = exp(-length(d - normalize(vec3(-0.7, 0.25, -0.65))) * 3.0);

            float neb1 = fbm(d * 4.5 + vec3(1.2), 4);
            float neb2 = fbm(d * 10.0 + vec3(neb1 * 1.4), 4);
            float dustLane = smoothstep(0.38, 0.72, fbm(d * 8.5 + vec3(4.1), 3));

            vec3 bg = vec3(0.008, 0.012, 0.025);
            vec3 milkyColor = mix(vec3(0.08, 0.14, 0.28), vec3(0.42, 0.28, 0.45), neb2);
            milkyColor += vec3(0.55, 0.36, 0.20) * coreGlow * 0.65;
            bg += milkyColor * band * (0.35 + neb1 * 0.85) * (1.0 - dustLane * 0.68);

            // Multi-layer crisp procedural starfield
            vec3 s1 = floor(d * 210.0);
            float r1 = hash31(s1);
            if (r1 > 0.9945) {
                vec3 cellCenter = normalize(s1 + 0.5);
                float dist = length(d - cellCenter) * 210.0;
                float star = exp(-dist * dist * 16.0);
                float temp = hash31(s1 + 7.3);
                vec3 sCol = mix(vec3(1.0, 0.68, 0.45), vec3(0.65, 0.82, 1.0), temp);
                bg += sCol * star * (0.9 + 1.8 * fract(r1 * 91.0));
            }
            vec3 s2 = floor(d * 420.0);
            float r2 = hash31(s2);
            if (r2 > 0.9965) {
                vec3 cellCenter = normalize(s2 + 0.5);
                float dist = length(d - cellCenter) * 420.0;
                float star = exp(-dist * dist * 18.0);
                bg += vec3(0.82, 0.88, 1.0) * star * 0.55;
            }
            return bg;
        }
    """

    // 1. SKYBOX / MILKY WAY + STARFIELD
    const val SKYBOX_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        uniform mat4 uViewRotProj;
        out vec3 vDir;
        void main() {
            vDir = aPos;
            vec4 pos = uViewRotProj * vec4(aPos * 100.0, 1.0);
            gl_Position = pos.xyww;
        }
    """

    const val SKYBOX_FS = """#version 300 es
        precision highp float;
        in vec3 vDir;
        out vec4 fragColor;
        $NOISE_GLSL
        $SKYBOX_COLOR_GLSL

        void main() {
            fragColor = vec4(evalSkyboxColor(normalize(vDir)), 1.0);
        }
    """

    // 2. PBR PLANET / MOON / COMET SHADER
    const val PLANET_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aUv;

        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform vec3 uBodyPos;
        uniform float uBodyRadius;
        uniform vec3 uTidalDir;
        uniform float uTidalStretch;
        uniform vec3 uImpactDeformDir;
        uniform float uImpactDeformAmount;
        uniform vec3 uPrimaryStarPos;
        $LOG_DEPTH_VERT

        out vec3 vLocalPos;
        out vec3 vWorldPos;
        out vec3 vWorldNormal;
        out vec3 vLocalLightDir;
        out vec2 vUv;

        void main() {
            vLocalPos = aPos;
            vUv = aUv;
            vec4 world = uModel * vec4(aPos, 1.0);
            vec3 wPos = world.xyz;
            vec3 wNorm = normalize(mat3(uModel) * aNormal);

            // Mechanical collision compression & lateral crustal bulge on impact hemisphere
            if (uImpactDeformAmount > 0.001) {
                vec3 iDir = normalize(uImpactDeformDir);
                vec3 relI = wPos - uBodyPos;
                float iAlong = dot(relI, iDir);
                vec3 iPerp = relI - iDir * iAlong;
                float iNorm = iAlong / max(uBodyRadius, 1e-5);
                float frontCup = smoothstep(-0.15, 0.95, iNorm);
                float cupFactor = 1.0 - uImpactDeformAmount * 0.36 * frontCup;
                float rimBulge = 1.0 + uImpactDeformAmount * 0.24 * exp(-pow((iNorm - 0.15) * 2.2, 2.0));
                wPos = uBodyPos + iDir * (iAlong * cupFactor) + iPerp * rimBulge;
                float niPar = dot(wNorm, iDir);
                vec3 niPerp = wNorm - iDir * niPar;
                wNorm = normalize(iDir * (niPar / max(cupFactor, 0.35)) + niPerp / max(rimBulge, 0.35));
            }

            if (uTidalStretch > 0.001) {
                vec3 dir = normalize(uTidalDir);
                vec3 rel = wPos - uBodyPos;
                float along = dot(rel, dir);
                vec3 perp = rel - dir * along;
                float alongNorm = along / max(uBodyRadius, 1e-5);
                // Smooth C1 Roche teardrop spaghettification toward L1 point (no equatorial kink)
                float lobe = smoothstep(-0.35, 0.85, alongNorm);
                float stretchFactor = 1.0 + uTidalStretch * (0.42 + 0.88 * lobe);
                float pinchFactor = 1.0 / sqrt(1.0 + uTidalStretch * (0.50 + 0.85 * lobe));
                wPos = uBodyPos + dir * (along * stretchFactor) + perp * pinchFactor;
                float nPar = dot(wNorm, dir);
                vec3 nPerp = wNorm - dir * nPar;
                wNorm = normalize(dir * (nPar / max(stretchFactor, 0.25)) + nPerp / max(pinchFactor, 0.25));
            }

            vec3 wToStar = normalize(uPrimaryStarPos - wPos);
            vLocalLightDir = normalize(transpose(mat3(uModel)) * wToStar);
            vWorldPos = wPos;
            vWorldNormal = wNorm;
            gl_Position = applyLogDepth(uViewProj * vec4(wPos, 1.0));
        }
    """

    const val PLANET_FS = """#version 300 es
        precision highp float;
        in vec3 vLocalPos;
        in vec3 vWorldPos;
        in vec3 vWorldNormal;
        in vec3 vLocalLightDir;
        in vec2 vUv;

        uniform sampler2D uEarthTex;
        uniform sampler2D uEarthNightTex;
        uniform sampler2D uObservedSurfaceTex;
        uniform int uUseObservedSurface;
        uniform vec3 uCamPos;
        uniform vec3 uBodyPos;
        uniform float uBodyRadius;
        uniform int uPlanetStyle; // 0=EarthLike, 1=Terrestrial/Mars/Moon, 2=GasGiant, 3=IceGiant, 4=Comet
        uniform int uBodyArchetype; // 0..18 NASA-accurate BodyArchetype IDs
        uniform float uSeed;
        uniform float uTime;
        uniform int uFbmOctaves;
        uniform vec3 uAlbedo;
        uniform float uTempK;
        uniform float uAtmoDensity;
        uniform vec3 uAtmoColor;
        uniform int uHasClouds;
        uniform int uHasCityLights;
        uniform float uCrustHeat;
        uniform float uTidalStress;
        uniform int uEnableAtmo;
        uniform int uEnableEclipses;
        uniform vec3 uImpactShockDir;
        uniform float uImpactShockAge;

        // Craters (local sphere dir + angular radius)
        uniform int uCraterCount;
        uniform vec4 uCraters[8];
        uniform float uCraterHeat[8];

        // Multi-star lighting
        uniform int uStarCount;
        uniform vec3 uStarPos[4];
        uniform vec3 uStarColor[4];
        uniform float uStarIntensity[4];

        // Eclipse occluders
        uniform int uOccluderCount;
        uniform vec4 uOccluders[6]; // xyz = pos, w = radius

        // Ring shadow on planet
        uniform int uHasRingShadow;
        uniform vec3 uRingNormal;
        uniform float uRingInner;
        uniform float uRingOuter;

        out vec4 fragColor;
        $NOISE_GLSL

        // Fast procedural impact crater field on unit sphere
        float craterField(vec3 p, float scale, float seedOff) {
            vec3 sp = p * scale + vec3(seedOff);
            vec3 cell = floor(sp);
            vec3 f = fract(sp) - 0.5;
            float rnd = hash31(cell);
            if (rnd < 0.42) return 0.0;
            vec3 jit = vec3(hash31(cell + 1.1), hash31(cell + 2.3), hash31(cell + 3.7)) - 0.5;
            float d = length(f - jit * 0.45);
            float rad = 0.16 + 0.24 * fract(rnd * 17.3);
            float rim = exp(-pow((d - rad) / (rad * 0.22), 2.0)) * 0.45;
            float bowl = -(1.0 - smoothstep(0.0, rad, d)) * 0.55;
            return bowl + rim;
        }

        // Scale-invariant height evaluator in local sphere space for crisp 3D bump normals
        float evalTerrainHeight(vec3 q) {
            if (uBodyArchetype == 5) {
                float eu = atan(-q.z, q.x) / 6.2831853 + 0.5;
                float ev = 0.5 - asin(clamp(q.y, -1.0, 1.0)) / 3.14159265;
                // Elevation is independent of visible brightness: snow is not a mountain.
                return texture(uEarthTex, vec2(eu, ev)).r;
            } else if (uBodyArchetype == 6) {
                float hi = fbm(q * 3.4 + vec3(7.21), 3);
                float c = craterField(q, 9.0, 6.0);
                float canyon = exp(-pow(q.y * 12.0 + sin(q.x * 4.0) * 0.4, 2.0)) * smoothstep(0.2, 0.8, q.x);
                return hi * 0.65 + c * 0.28 - canyon * 0.35;
            } else if (uBodyArchetype == 7 || uBodyArchetype == 9) {
                return fbm(q * 4.0 + vec3(uSeed), 3) * 0.45 + craterField(q, 8.0, uSeed) * 0.65 + craterField(q, 16.0, uSeed + 4.1) * 0.35;
            } else if (uPlanetStyle == 2 || uPlanetStyle == 3 || uBodyArchetype == 8 || uBodyArchetype == 17) {
                return 0.5 + fbm(vec3(q.x * 4.0, q.y * 11.0, q.z * 4.0) + vec3(uSeed), 2) * 0.12;
            } else {
                return fbm(q * 3.8 + vec3(uSeed), 3) * 0.7 + craterField(q, 10.0, uSeed) * 0.3;
            }
        }

        float computeEclipseShadow(vec3 fragPos, vec3 toLightDir, float lightDist) {
            if (uEnableEclipses == 0) return 1.0;
            float shadow = 1.0;
            for (int i = 0; i < 6; i++) {
                if (i >= uOccluderCount) break;
                vec3 occPos = uOccluders[i].xyz;
                float occRad = uOccluders[i].w;
                vec3 toOcc = occPos - fragPos;
                float proj = dot(toOcc, toLightDir);
                if (proj > 0.0001 && proj < lightDist) {
                    float perp = length(toOcc - toLightDir * proj);
                    float penumbra = occRad * 0.25 + 0.002;
                    shadow *= smoothstep(occRad - penumbra, occRad + penumbra, perp);
                }
            }
            if (uHasRingShadow == 1) {
                float denom = dot(toLightDir, uRingNormal);
                if (abs(denom) > 0.001) {
                    float t = dot(uBodyPos - fragPos, uRingNormal) / denom;
                    if (t > 0.001) {
                        vec3 hit = fragPos + toLightDir * t - uBodyPos;
                        float r = length(hit) / max(uBodyRadius, 1e-5);
                        if (r > uRingInner && r < uRingOuter) {
                            float cassini = smoothstep(0.02, 0.06, abs((r - uRingInner) / (uRingOuter - uRingInner) - 0.62));
                            shadow *= mix(0.85, 0.32, cassini);
                        }
                    }
                }
            }
            return shadow;
        }

        void main() {
            vec3 p = normalize(vLocalPos);
            vec3 N = normalize(vWorldNormal);
            vec3 V = normalize(uCamPos - vWorldPos);

            vec3 baseColor = uAlbedo;
            float roughness = 0.78;
            float specStrength = 0.05;
            float height = 0.5;
            float cityMask = 0.0;
            float cloudAlpha = 0.0;

            float freezeShift = clamp((274.0 - uTempK) / 85.0, 0.0, 1.25);
            float heatDry = clamp((uTempK - 315.0) / 145.0, 0.0, 1.0);
            float boilOcean = clamp((uTempK - 373.0) / 110.0, 0.0, 1.0);
            float gasThermal = clamp((uTempK - 550.0) / 1200.0, 0.0, 1.0);
            float cloudShadow = 1.0;

            if (uBodyArchetype == 5 && uUseObservedSurface == 1) {
                // Direct NASA Blue Marble. UVs are geodetic, north-first and shared
                // with the measured WGS84/GEBCO mesh and the 2-D surface preview.
                baseColor = texture(uObservedSurfaceTex, vUv).rgb;
                float ocean = (baseColor.b > baseColor.r * 1.25 && baseColor.b > baseColor.g * 1.08) ? 1.0 : 0.0;
                float snow = freezeShift * smoothstep(0.05, 0.65, abs(p.y) + freezeShift * 0.65);
                baseColor = mix(baseColor, vec3(0.88, 0.93, 0.97), snow);
                baseColor = mix(baseColor, vec3(0.42, 0.22, 0.13), heatDry * 0.65);
                roughness = mix(0.86, 0.22, ocean * (1.0 - snow));
                specStrength = mix(0.025, 0.42, ocean * (1.0 - snow));
                if (uHasCityLights == 1) {
                    cityMask = texture(uEarthNightTex, vUv).r *
                        (1.0 - snow) * (1.0 - clamp(uCrustHeat * 3.0, 0.0, 1.0));
                    cityMask *= 1.0 - smoothstep(335.0, 390.0, uTempK);
                }
                if (uHasClouds == 1 && uAtmoDensity > 0.05) {
                    vec3 cPos = p * 4.4 + vec3(uTime * 0.012, 0.0, -uTime * 0.008);
                    float weather = fbm(cPos + vec3(fbm(cPos * 1.6, 2)), uFbmOctaves);
                    cloudAlpha = smoothstep(0.48, 0.70, weather) * min(0.78, uAtmoDensity);
                    cloudShadow = 1.0 - cloudAlpha * 0.24;
                }
            } else if (uPlanetStyle == 0) {
                // Fictional, user-created oceanic exoplanets only; never alter Earth's coastlines.
                float detail = fbm(p * 13.0 + vec3(uSeed * 2.1), max(2, uFbmOctaves - 1));
                float ridge = 1.0 - abs(noise3(p * 18.0 + vec3(uSeed)) * 2.0 - 1.0);
                float lat = abs(p.y);

                float cont = fbm(p * 2.65 + vec3(uSeed), uFbmOctaves) + (detail - 0.5) * 0.08;
                float landThresh = 0.49 + boilOcean * 0.24;

                // Dynamic polar & global snowball freeze threshold driven by uTempK!
                float iceThresh = 0.77 - freezeShift * 0.74;
                float ice = (uTempK < 185.0) ? 1.0 : smoothstep(iceThresh, iceThresh + 0.11, lat + (detail - 0.5) * 0.12);
                ice *= (1.0 - clamp(uCrustHeat * 2.0, 0.0, 1.0));

                if (cont < landThresh && boilOcean < 0.92) {
                    float shelf = smoothstep(max(0.0, landThresh - 0.14), landThresh, cont);
                    float abyss = smoothstep(0.18, max(0.20, landThresh - 0.06), cont);
                    vec3 deepSea = mix(vec3(0.015, 0.055, 0.22), vec3(0.16, 0.22, 0.20), boilOcean);
                    vec3 midSea = mix(vec3(0.035, 0.21, 0.46), vec3(0.24, 0.36, 0.28), boilOcean);
                    vec3 coastSea = mix(vec3(0.06, 0.44, 0.60), vec3(0.35, 0.46, 0.32), boilOcean);
                    baseColor = mix(deepSea, midSea, abyss);
                    baseColor = mix(baseColor, coastSea, shelf * 0.72);
                    roughness = mix(0.22, 0.40, boilOcean);
                    specStrength = mix(0.65, 0.30, boilOcean);
                    height = 0.38;
                } else {
                    float elev = clamp((cont - landThresh) / max(0.08, 1.0 - landThresh), 0.0, 1.0);
                    float aridity = clamp(smoothstep(0.15, 0.45, 1.0 - lat) * smoothstep(0.42, 0.70, detail) + heatDry * 0.85, 0.0, 1.0);
                    vec3 forest = mix(vec3(0.08, 0.27, 0.10), vec3(0.34, 0.25, 0.14), heatDry);
                    vec3 savanna = mix(vec3(0.38, 0.41, 0.19), vec3(0.55, 0.37, 0.18), heatDry);
                    vec3 desert = mix(vec3(0.66, 0.54, 0.35), vec3(0.54, 0.30, 0.17), boilOcean);
                    vec3 lowland = mix(mix(forest, savanna, detail), desert, aridity * 0.88);
                    vec3 mountain = vec3(0.37, 0.33, 0.29);
                    baseColor = mix(lowland, mountain, smoothstep(0.38, 0.78, elev + ridge * 0.16));
                    float snowCap = smoothstep(0.72, 0.92, elev + ridge * 0.18) * clamp((330.0 - uTempK) / 60.0, 0.0, 1.0);
                    baseColor = mix(baseColor, vec3(0.90, 0.93, 0.96), snowCap);
                    roughness = 0.90;
                    specStrength = 0.015;
                    height = 0.50 + elev * 0.44 + ridge * 0.08;

                    if (uHasCityLights == 1 && elev < 0.45 && lat < 0.72 && uTempK > 235.0 && uTempK < 345.0) {
                        float urban = noise3(p * 38.0 + vec3(uSeed * 3.0)) * smoothstep(0.0, 0.24, elev);
                        cityMask = smoothstep(0.65, 0.83, urban) * (1.0 - ice) * (1.0 - clamp(uCrustHeat * 3.0, 0.0, 1.0));
                    }
                }
                baseColor = mix(baseColor, vec3(0.86, 0.91, 0.96), ice);
                if (ice > 0.5) { roughness = 0.48; specStrength = 0.24; }

                if (uHasClouds == 1 && uAtmoDensity > 0.05) {
                    vec3 windOff = vec3(uTime * 0.032, 0.0, -uTime * 0.020);
                    vec3 cPos = p * 3.8 + windOff;
                    float cWarp = fbm(cPos + vec3(fbm(cPos * 1.6, 2) * 0.8), uFbmOctaves);
                    float steamDense = clamp(uAtmoDensity * (0.85 + boilOcean * 0.65), 0.15, 1.25);
                    float cLow = max(0.34, 0.49 - boilOcean * 0.12);
                    cloudAlpha = smoothstep(cLow, 0.76, cWarp) * clamp(0.78 * steamDense, 0.0, 0.95);

                    // Offset sample toward local sunlight direction so dense clouds cast ground shadows
                    vec3 shadowP = normalize(p + vLocalLightDir * 0.028);
                    vec3 csPos = shadowP * 3.8 + windOff;
                    float csWarp = fbm(csPos + vec3(fbm(csPos * 1.6, 2) * 0.8), 2);
                    float shadowCov = smoothstep(cLow + 0.02, 0.78, csWarp);
                    cloudShadow = 1.0 - shadowCov * clamp(0.42 * steamDense, 0.0, 0.52);
                }
            } else if (uBodyArchetype == 6) {
                // NASA MARS: Iron-oxide highlands, Syrtis Major dark basalt, Valles Marineris rift, CO2 polar ice caps
                float highlands = fbm(p * 3.4 + vec3(7.21), uFbmOctaves);
                float micro = fbm(p * 19.0 + vec3(14.4), max(2, uFbmOctaves - 1));
                float basalt = smoothstep(0.45, 0.68, fbm(p * 2.4 + vec3(1.8), 3));
                float craters = craterField(p, 9.0, 6.0) + craterField(p, 18.0, 12.0) * 0.5;
                float canyonMask = exp(-pow(p.y * 12.0 + sin(p.x * 4.0) * 0.4, 2.0)) * smoothstep(0.2, 0.8, p.x);
                vec3 dust = mix(vec3(0.70, 0.33, 0.19), vec3(0.82, 0.46, 0.28), highlands);
                vec3 darkBasalt = vec3(0.30, 0.19, 0.14);
                baseColor = mix(dust, darkBasalt, basalt * 0.68);
                baseColor = mix(baseColor, vec3(0.20, 0.11, 0.08), canyonMask * 0.65);
                baseColor *= (0.84 + 0.30 * micro + craters * 0.34);
                float polarLat = clamp(0.84 - freezeShift * 0.45, 0.1, 0.96);
                float polar = smoothstep(polarLat, polarLat + 0.08, abs(p.y) + (micro - 0.5) * 0.07);
                baseColor = mix(baseColor, vec3(0.91, 0.89, 0.87), polar);
                height = highlands * 0.65 + craters * 0.28 - canyonMask * 0.32;
                roughness = 0.92;
                specStrength = 0.015;
            } else if (uBodyArchetype == 7 || uBodyArchetype == 9) {
                // MERCURY (7) & LUNA (9): Mare basalt basins vs heavily cratered anorthositic highlands
                float maria = smoothstep(0.44, 0.66, fbm(p * 2.6 + vec3(uSeed), uFbmOctaves));
                float regolith = fbm(p * 18.0 + vec3(uSeed * 1.4), max(2, uFbmOctaves - 1));
                float cLarge = craterField(p, 7.5, uSeed);
                float cMed = craterField(p, 15.0, uSeed + 4.1) * 0.7;
                float cFine = craterField(p, 28.0, uSeed + 9.3) * 0.4;
                vec3 highlandCol = (uBodyArchetype == 9) ? vec3(0.72, 0.71, 0.68) : vec3(0.58, 0.54, 0.50);
                vec3 mariaCol = (uBodyArchetype == 9) ? vec3(0.29, 0.30, 0.32) : vec3(0.34, 0.32, 0.30);
                baseColor = mix(highlandCol, mariaCol, maria * (uBodyArchetype == 9 ? 0.88 : 0.55));
                baseColor *= (0.78 + regolith * 0.36 + (cLarge + cMed + cFine) * 0.45);
                height = 0.5 + (cLarge + cMed + cFine) * 0.45 + regolith * 0.15;
                roughness = 0.95;
                specStrength = 0.01;
            } else if (uBodyArchetype == 8) {
                // NASA VENUS: Pale cream & soft straw sulfuric acid cloud chevrons
                float chevron = sin(abs(p.y) * 9.0 - atan(p.z, p.x) * 1.5 + uTime * 0.06) * 0.14;
                float yWave = fbm(vec3(p.x * 2.4 - uTime * 0.055, p.y * 5.8, p.z * 2.4), uFbmOctaves);
                vec3 cream = vec3(0.92, 0.86, 0.68);
                vec3 paleOchre = vec3(0.78, 0.64, 0.42);
                baseColor = mix(paleOchre, cream, smoothstep(0.24, 0.78, yWave + chevron));
                roughness = 0.68;
                specStrength = 0.08;
            } else if (uBodyArchetype == 10 || uPlanetStyle == 2) {
                // NASA JUPITER (10) & GAS GIANT (2): Turbulent belts/zones, festoons, Great Red Spot collar
                float shear = fbm(vec3(p.x * 5.5 - uTime * 0.025, p.y * 14.0, p.z * 5.5) + vec3(uSeed), uFbmOctaves);
                float eddies = fbm(p * 14.5 + vec3(uSeed, uTime * 0.02, shear * 2.1), max(2, uFbmOctaves - 1));
                float beltPhase = p.y * 22.0 + shear * 2.8 + eddies * 1.1 + uSeed * 0.2;
                float band1 = sin(beltPhase) * 0.5 + 0.5;
                float band2 = sin(beltPhase * 2.35 + 1.1) * 0.5 + 0.5;

                if (uBodyArchetype == 10) {
                    vec3 zoneCream = vec3(0.90, 0.85, 0.76);
                    vec3 eqBeltRust = vec3(0.64, 0.36, 0.22);
                    vec3 ochre = vec3(0.80, 0.64, 0.45);
                    vec3 polarSlate = vec3(0.44, 0.42, 0.46);
                    baseColor = mix(eqBeltRust, zoneCream, band1);
                    baseColor = mix(baseColor, ochre, band2 * 0.44);
                    // Blue-grey festoon plumes along equatorial belt boundary
                    float festoon = smoothstep(0.66, 0.86, eddies) * exp(-pow((p.y - 0.14) * 14.0, 2.0));
                    baseColor = mix(baseColor, vec3(0.36, 0.42, 0.52), festoon * 0.65);
                    baseColor = mix(baseColor, polarSlate, smoothstep(0.68, 0.94, abs(p.y)) * 0.74);

                    vec3 grsCenter = normalize(vec3(0.86, -0.37, 0.34));
                    vec3 dSpot = p - grsCenter;
                    float spotR = length(vec3(dSpot.x * 0.70, dSpot.y * 1.85, dSpot.z));
                    float collar = exp(-pow((spotR - 0.20) / 0.045, 2.0));
                    baseColor = mix(baseColor, vec3(0.93, 0.90, 0.84), collar * 0.55);
                    float grsMask = 1.0 - smoothstep(0.04, 0.21, spotR);
                    float grsSwirl = sin(spotR * 38.0 - uTime * 0.75) * 0.5 + 0.5;
                    vec3 grsCol = mix(vec3(0.78, 0.26, 0.14), vec3(0.90, 0.46, 0.27), grsSwirl);
                    baseColor = mix(baseColor, grsCol, grsMask * 0.90);
                } else {
                    vec3 c1 = uAlbedo * 1.15;
                    vec3 c2 = uAlbedo * vec3(0.66, 0.56, 0.48);
                    vec3 c3 = mix(uAlbedo, vec3(0.92, 0.86, 0.74), 0.52);
                    baseColor = mix(c2, c1, band1);
                    baseColor = mix(baseColor, c3, band2 * 0.38);
                }
                float coldGas = clamp((95.0 - uTempK) / 55.0, 0.0, 0.65);
                baseColor = mix(baseColor, vec3(0.84, 0.88, 0.96), coldGas);
                baseColor = mix(baseColor, vec3(0.14, 0.08, 0.07), gasThermal * 0.82);
                roughness = 0.64;
                specStrength = 0.04;
            } else if (uBodyArchetype == 11) {
                // NASA SATURN: Muted golden-butterscotch bands + north polar teal hexagon zone
                float turb = fbm(vec3(p.x * 3.2, p.y * 11.0, p.z * 3.2) + vec3(uSeed), uFbmOctaves);
                float band = sin(p.y * 24.0 + turb * 1.5) * 0.5 + 0.5;
                vec3 cream = vec3(0.91, 0.85, 0.70);
                vec3 butter = vec3(0.76, 0.66, 0.46);
                baseColor = mix(butter, cream, band);
                float hexWave = cos(atan(p.z, p.x) * 6.0) * 0.02;
                baseColor = mix(baseColor, vec3(0.42, 0.55, 0.54), smoothstep(0.78, 0.94, p.y + hexWave) * 0.68);
                baseColor = mix(baseColor, vec3(0.16, 0.09, 0.07), gasThermal * 0.8);
                roughness = 0.64;
                specStrength = 0.03;
            } else if (uBodyArchetype == 12 || uBodyArchetype == 13 || uPlanetStyle == 3) {
                // URANUS (12), NEPTUNE (13), ICE GIANT (3)
                float wisp = fbm(vec3(p.x * 2.5 + uTime * 0.035, p.y * 11.5, p.z * 2.5) + vec3(uSeed), uFbmOctaves);
                float band = sin(p.y * 13.0 + wisp * 2.0) * 0.5 + 0.5;
                if (uBodyArchetype == 12) {
                    baseColor = mix(vec3(0.49, 0.79, 0.84), vec3(0.61, 0.87, 0.90), band);
                    baseColor = mix(baseColor, vec3(0.76, 0.91, 0.93), smoothstep(0.65, 0.95, abs(p.y)) * 0.32);
                    roughness = 0.54;
                    specStrength = 0.12;
                } else {
                    baseColor = mix(vec3(0.16, 0.34, 0.74), vec3(0.25, 0.50, 0.89), band);
                    float cirrus = smoothstep(0.69, 0.87, wisp) * (1.0 - abs(p.y) * 0.6);
                    baseColor = mix(baseColor, vec3(0.86, 0.93, 0.99), cirrus * 0.58);
                    if (uBodyArchetype == 13) {
                        vec3 gds = normalize(vec3(0.75, -0.32, 0.58));
                        float d = length((p - gds) * vec3(0.8, 1.6, 1.0));
                        baseColor = mix(baseColor, vec3(0.08, 0.16, 0.44), (1.0 - smoothstep(0.05, 0.20, d)) * 0.75);
                    }
                    roughness = 0.50;
                    specStrength = 0.16;
                }
                baseColor = mix(baseColor, vec3(0.18, 0.10, 0.12), gasThermal * 0.8);
            } else if (uBodyArchetype == 14) {
                // NASA PLUTO: Bright nitrogen-ice Sputnik Planitia heart vs dark reddish tholin Cthulhu Macula
                float terrain = fbm(p * 4.2 + vec3(uSeed), uFbmOctaves);
                float heart = 1.0 - smoothstep(0.12, 0.36, length((p - normalize(vec3(0.85, 0.08, 0.52))) * vec3(1.0, 1.25, 0.85)));
                float tholin = smoothstep(0.42, 0.70, fbm(p * 3.0 + vec3(2.2), 3)) * exp(-pow(p.y * 2.5, 2.0));
                vec3 nitrogenIce = vec3(0.90, 0.86, 0.80);
                vec3 crust = mix(vec3(0.66, 0.56, 0.48), vec3(0.34, 0.18, 0.14), tholin);
                baseColor = mix(crust * (0.78 + terrain * 0.4), nitrogenIce, heart * 0.92);
                height = terrain * 0.7;
                specStrength = 0.02;
            } else if (uBodyArchetype == 15) {
                // NASA IO: Sulfur yellow/orange/red with dark volcanic calderas
                float sulfur = fbm(p * 5.2 + vec3(uSeed), uFbmOctaves);
                float vents = smoothstep(0.72, 0.88, fbm(p * 12.0 + vec3(3.1), 3));
                baseColor = mix(vec3(0.88, 0.74, 0.25), vec3(0.80, 0.40, 0.15), sulfur);
                baseColor = mix(baseColor, vec3(0.14, 0.10, 0.08), vents);
                height = sulfur * 0.6;
                specStrength = 0.02;
            } else if (uBodyArchetype == 16 || uBodyArchetype == 18) {
                // NASA EUROPA (16) & ENCELADUS (18): Bright water-ice shell with reddish tectonic lineae / blue tiger stripes
                float iceVar = fbm(p * 4.5 + vec3(uSeed), uFbmOctaves);
                float crackA = 1.0 - abs(noise3(p * 9.5 + vec3(uSeed)) * 2.0 - 1.0);
                float crackB = 1.0 - abs(noise3(p * 15.0 + vec3(uSeed + 5.2)) * 2.0 - 1.0);
                float lineae = smoothstep(0.84, 0.98, max(crackA, crackB));
                if (uBodyArchetype == 16) {
                    baseColor = mix(vec3(0.82, 0.80, 0.74), vec3(0.92, 0.91, 0.87), iceVar);
                    baseColor = mix(baseColor, vec3(0.54, 0.33, 0.23), lineae * 0.75);
                } else {
                    baseColor = mix(vec3(0.86, 0.91, 0.96), vec3(0.96, 0.98, 0.99), iceVar);
                    float southTiger = lineae * (1.0 - smoothstep(-0.85, -0.35, p.y));
                    baseColor = mix(baseColor, vec3(0.32, 0.64, 0.90), southTiger * 0.82);
                }
                roughness = 0.42;
                specStrength = 0.30;
                height = 0.6 - lineae * 0.25;
            } else if (uBodyArchetype == 17) {
                // NASA TITAN: Dense orange-amber nitrogen-methane haze
                float haze = fbm(vec3(p.x * 2.5, p.y * 6.0, p.z * 2.5) + vec3(uSeed, uTime * 0.02, 0.0), uFbmOctaves);
                baseColor = mix(vec3(0.74, 0.49, 0.21), vec3(0.88, 0.68, 0.35), haze);
                roughness = 0.62;
                specStrength = 0.04;
            } else if (uPlanetStyle == 4 || uBodyArchetype == 4) {
                float crag = fbm(p * 8.0 + vec3(uSeed), 3);
                baseColor = mix(vec3(0.28, 0.32, 0.38), vec3(0.75, 0.88, 0.98), smoothstep(0.45, 0.75, crag));
                height = crag;
                specStrength = 0.04;
            } else {
                // Generic Terrestrial / Moon / Rocky / Exoplanets with freeze glaze when icy cold
                float terrain = fbm(p * 3.8 + vec3(uSeed), uFbmOctaves);
                float micro = fbm(p * 16.0 + vec3(uSeed * 1.7), max(2, uFbmOctaves - 1));
                float craters = craterField(p, 10.0, uSeed);
                float polar = smoothstep(0.82, 0.94, abs(p.y) + (micro - 0.5) * 0.08);
                baseColor = uAlbedo * (0.64 + terrain * 0.62 + (micro - 0.5) * 0.25 + craters * 0.3);
                if (uHasClouds == 1) {
                    float swirl = fbm(vec3(p.x * 2.5, p.y * 5.5, p.z * 2.5) + vec3(uTime * 0.05, 0.0, 0.0), uFbmOctaves);
                    baseColor = mix(baseColor, uAtmoColor * (0.75 + 0.4 * swirl), 0.78);
                } else if (uAtmoDensity > 0.05 || uTempK < 200.0) {
                    float iceCover = (uTempK < 165.0) ? 0.85 : polar * 0.85;
                    baseColor = mix(baseColor, vec3(0.88, 0.92, 0.97), iceCover);
                }
                height = terrain * 0.65 + micro * 0.25 + craters * 0.25;
                specStrength = 0.015;
            }

            // Replace invented layouts with the packaged observation-based maps for named bodies.
            if (uUseObservedSurface == 1 && uBodyArchetype != 5) {
                // Android bitmaps upload north-first; flipping v inverted hemispheres.
                vec3 observed = texture(uObservedSurfaceTex, vUv).rgb;
                float mapWeight = (uBodyArchetype == 5)
                    ? mix(0.48, 0.94, smoothstep(180.0, 270.0, uTempK))
                    : 0.94;
                baseColor = mix(baseColor, observed, mapWeight);
                roughness = (uBodyArchetype == 5) ? 0.68 : 0.86;
                specStrength = (uBodyArchetype == 5) ? 0.13 : 0.035;
            }

            // Apply procedural impact craters + glowing impact craters
            float craterLava = 0.0;
            for (int c = 0; c < 8; c++) {
                if (c >= uCraterCount) break;
                float angDist = acos(clamp(dot(p, normalize(uCraters[c].xyz)), -1.0, 1.0));
                float rad = uCraters[c].w;
                if (angDist < rad * 1.25) {
                    float rim = exp(-pow((angDist - rad) / (rad * 0.16), 2.0));
                    float bowl = 1.0 - smoothstep(0.0, rad, angDist);
                    baseColor = mix(baseColor, baseColor * 0.52, bowl * 0.7);
                    baseColor += vec3(0.18, 0.16, 0.14) * rim;
                    height -= bowl * 0.35 - rim * 0.2;
                    craterLava = max(craterLava, bowl * uCraterHeat[c]);
                }
            }

            // Scale-invariant 3D finite-difference tangent bump normal (crisp relief regardless of AU planet scale!)
            float eps = 0.014;
            vec3 upVec = abs(p.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
            vec3 T_loc = normalize(cross(upVec, p));
            vec3 B_loc = cross(p, T_loc);
            float h0 = evalTerrainHeight(p);
            float hx = evalTerrainHeight(normalize(p + T_loc * eps));
            float hy = evalTerrainHeight(normalize(p + B_loc * eps));
            float bumpScale = (uBodyArchetype == 5) ? 0.08 : ((uPlanetStyle == 2 || uPlanetStyle == 3) ? 0.30 : 2.15);
            vec3 wUp = abs(N.y) < 0.9 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0);
            vec3 T_w = normalize(cross(wUp, N));
            vec3 B_w = cross(N, T_w);
            N = normalize(N - (T_w * ((hx - h0) * bumpScale) + B_w * ((hy - h0) * bumpScale)));

            // Multi-star PBR lighting + shadows + clouds + cloud ground shadows
            vec3 litColor = baseColor * 0.045;
            float dayFactorMax = 0.0;

            for (int s = 0; s < 4; s++) {
                if (s >= uStarCount) break;
                vec3 toStar = uStarPos[s] - vWorldPos;
                float starDist = length(toStar);
                vec3 L = toStar / max(starDist, 1e-5);
                float ndlRaw = max(dot(N, L), 0.0);
                // Lommel-Seeliger-inspired softer terminator shading for dusty matte regoliths
                float ndl = (uPlanetStyle == 1 && uAtmoDensity < 0.1) ? pow(ndlRaw, 0.80) : ndlRaw;
                dayFactorMax = max(dayFactorMax, dot(normalize(vWorldNormal), L));

                float shadow = computeEclipseShadow(vWorldPos, L, starDist);
                float atten = uStarIntensity[s] / (1.0 + 0.015 * starDist * starDist);
                vec3 starRad = uStarColor[s] * atten * shadow;

                vec3 steamCloudTint = mix(vec3(0.95, 0.97, 1.0), vec3(1.0, 0.72, 0.45), heatDry * 0.85);
                vec3 shadowedGround = baseColor * cloudShadow;
                vec3 surfAlbedo = mix(shadowedGround, steamCloudTint, cloudAlpha);
                litColor += surfAlbedo * starRad * ndl;

                if (specStrength > 0.01 && cloudAlpha < 0.4) {
                    vec3 H = normalize(L + V);
                    float specPow = mix(128.0, 16.0, roughness);
                    float spec = pow(max(dot(N, H), 0.0), specPow) * specStrength * (1.0 - cloudAlpha);
                    litColor += starRad * spec;
                }

                // Rayleigh / Mie atmosphere scattering on illuminated limb & terminator
                if (uEnableAtmo == 1 && uAtmoDensity > 0.01) {
                    float fresnel = pow(1.0 - max(dot(normalize(vWorldNormal), V), 0.0), 3.0);
                    float terminator = smoothstep(-0.28, 0.35, dot(normalize(vWorldNormal), L));
                    vec3 hotAtmo = mix(uAtmoColor, vec3(1.0, 0.45, 0.16), clamp(gasThermal + boilOcean * 0.7, 0.0, 1.0));
                    vec3 sunsetTint = mix(vec3(1.0, 0.38, 0.14), hotAtmo, smoothstep(-0.08, 0.38, dot(normalize(vWorldNormal), L)));
                    litColor += sunsetTint * fresnel * terminator * uAtmoDensity * starRad * 1.15;
                }
            }

            // Night-side city lights on Earth-like worlds
            if (cityMask > 0.01) {
                float nightWeight = 1.0 - smoothstep(-0.18, 0.12, dayFactorMax);
                litColor += vec3(1.0, 0.78, 0.42) * cityMask * nightWeight * (1.0 - cloudAlpha * 0.7) * 1.35;
            }

            // Hot-Jupiter / Hot-Gas radiant thermal cracks & glowing interior heat
            if ((uPlanetStyle == 2 || uPlanetStyle == 3) && gasThermal > 0.04) {
                float rift = smoothstep(0.55, 0.82, sin(p.y * 24.0 + fbm(p * 8.0, 2) * 3.5) * 0.5 + 0.5);
                litColor += mix(vec3(1.4, 0.25, 0.03), vec3(2.4, 0.75, 0.15), gasThermal) * rift * gasThermal * 1.5;
            }

            // Expanding planetary seismic & thermal shockwave front after giant impacts
            if (uImpactShockAge > 0.001 && uImpactShockAge < 3.5) {
                float shockRad = uImpactShockAge * 1.15;
                float ang = acos(clamp(dot(p, normalize(uImpactShockDir)), -1.0, 1.0));
                float bandW = 0.075 + uImpactShockAge * 0.04;
                float ringWave = exp(-pow((ang - shockRad) / bandW, 2.0));
                float fadeShock = clamp(1.0 - uImpactShockAge / 3.2, 0.0, 1.0);
                float crackGlow = smoothstep(0.55, 0.82, fbm(p * 16.0 + vec3(uSeed), 2)) *
                    (1.0 - smoothstep(max(0.0, shockRad - 0.25), shockRad + 0.05, ang));
                vec3 shockCol = mix(vec3(1.5, 0.35, 0.04), vec3(2.8, 1.45, 0.55), ringWave);
                litColor += shockCol * (ringWave * 2.2 + crackGlow * 0.85) * fadeShock;
            }

            // Molten crust magma cracks & tidal shear heating glow (HDR overbright for bloom!)
            float lavaPattern = smoothstep(0.50, 0.74, fbm(p * 12.0 + vec3(uSeed), 3));
            float tempMelt = (uPlanetStyle == 2 || uPlanetStyle == 3) ? 0.0 : clamp((uTempK - 950.0) / 1400.0, 0.0, 1.0);
            float heatDriver = max(max(uCrustHeat, uTidalStress * 0.95), tempMelt);
            float totalHeat = max(heatDriver * (0.48 + 0.88 * lavaPattern), craterLava);
            if (totalHeat > 0.01) {
                vec3 lavaCol = mix(vec3(1.35, 0.24, 0.02), vec3(2.5, 1.0, 0.28), totalHeat);
                litColor += lavaCol * totalHeat * 1.65;
            }

            fragColor = vec4(litColor, 1.0);
        }
    """

    // 3. HDR STAR / SUN / WHITE DWARF / NEUTRON STAR SHADER (with smooth C1 tidal spaghettification stretch!)
    const val STAR_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aUv;

        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform vec3 uBodyPos;
        uniform float uBodyRadius;
        uniform vec3 uTidalDir;
        uniform float uTidalStretch;
        $LOG_DEPTH_VERT

        out vec3 vLocalPos;
        out vec3 vWorldPos;
        out vec3 vWorldNormal;
        out vec2 vUv;

        void main() {
            vLocalPos = aPos;
            vUv = aUv;
            vec4 w = uModel * vec4(aPos, 1.0);
            vec3 wPos = w.xyz;
            vec3 wNorm = normalize(mat3(uModel) * aNormal);

            if (uTidalStretch > 0.001) {
                vec3 dir = normalize(uTidalDir);
                vec3 rel = wPos - uBodyPos;
                float along = dot(rel, dir);
                vec3 perp = rel - dir * along;
                float alongNorm = along / max(uBodyRadius, 1e-5);
                float lobe = smoothstep(-0.35, 0.85, alongNorm);
                float stretchFactor = 1.0 + uTidalStretch * (0.45 + 0.95 * lobe);
                float pinchFactor = 1.0 / sqrt(1.0 + uTidalStretch * (0.55 + 0.92 * lobe));
                wPos = uBodyPos + dir * (along * stretchFactor) + perp * pinchFactor;
                float nPar = dot(wNorm, dir);
                vec3 nPerp = wNorm - dir * nPar;
                wNorm = normalize(dir * (nPar / max(stretchFactor, 0.25)) + nPerp / max(pinchFactor, 0.25));
            }

            vWorldPos = wPos;
            vWorldNormal = wNorm;
            gl_Position = applyLogDepth(uViewProj * vec4(wPos, 1.0));
        }
    """

    const val STAR_FS = """#version 300 es
        precision highp float;
        in vec3 vLocalPos;
        in vec3 vWorldPos;
        in vec3 vWorldNormal;
        in vec2 vUv;

        uniform vec3 uCamPos;
        uniform vec3 uStarColor;
        uniform sampler2D uStarSurfaceTex;
        uniform int uUseStarSurface;
        uniform float uTime;
        uniform float uSeed;
        uniform float uIntensity;

        out vec4 fragColor;
        $NOISE_GLSL

        void main() {
            vec3 p = normalize(vLocalPos);
            vec3 N = normalize(vWorldNormal);
            vec3 V = normalize(uCamPos - vWorldPos);

            float superGran = fbm(p * 6.5 + vec3(uTime * 0.14, -uTime * 0.10, uSeed), 4);
            float fineCell = fbm(p * 19.0 + vec3(-uTime * 0.22, uTime * 0.16, uSeed * 1.9), 3);
            float gran = mix(superGran, fineCell, 0.42);
            float spots = smoothstep(0.70, 0.85, fbm(p * 4.2 + vec3(uSeed * 2.0, uTime * 0.03, 0.0), 3));
            float ndv = max(dot(N, V), 0.04);
            float limb = pow(ndv, 0.46);
            float chromosphere = pow(1.0 - ndv, 3.2) * 0.9;

            vec3 hotCore = uStarColor * (1.30 + gran * 0.85) * uIntensity;
            if (uUseStarSurface == 1) {
                vec3 observed = texture(uStarSurfaceTex, vec2(vUv.x, 1.0 - vUv.y)).rgb;
                hotCore = observed * (1.35 + gran * 0.35) * uIntensity;
            }
            vec3 coolSpot = uStarColor * 0.28;
            vec3 col = mix(hotCore, coolSpot, spots * 0.70);
            col *= (0.45 + 0.70 * limb);
            col += vec3(0.55) * pow(gran, 1.6) * limb * uIntensity;
            col += mix(uStarColor, vec3(1.0, 0.55, 0.22), 0.45) * chromosphere * uIntensity;
            fragColor = vec4(col, 1.0);
        }
    """

    // 4. STAR CORONA & FLARE BILLBOARD SHADER
    const val CORONA_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 2) in vec2 aUv;
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        $LOG_DEPTH_VERT
        out vec2 vUv;
        void main() {
            vUv = aUv * 2.0 - 1.0;
            gl_Position = applyLogDepth(uViewProj * uModel * vec4(aPos, 1.0));
        }
    """

    const val CORONA_FS = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform vec3 uColor;
        uniform float uTime;
        uniform float uIntensity;
        uniform int uIsPulsar;
        out vec4 fragColor;

        void main() {
            float r = length(vUv);
            if (r > 1.0) discard;
            float angle = atan(vUv.y, vUv.x);
            float rays = 0.78 + 0.22 * sin(angle * 7.0 + uTime * 1.4) * cos(angle * 11.0 - uTime * 0.9);
            float glow = exp(-r * 4.2) * (1.0 - r) * rays;
            if (uIsPulsar == 1) {
                float beam = pow(abs(cos(angle + uTime * 7.5)), 24.0) * (1.0 - r);
                glow += beam * 1.6;
            }
            fragColor = vec4(uColor * glow * uIntensity, glow);
        }
    """

    // 5. RELATIVISTIC 3D RAYMARCHED BLACK HOLE SHADER (Schwarzschild Geodesics + Photon Ring + Doppler Disk)
    const val BH_RELATIVISTIC_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        $LOG_DEPTH_VERT
        out vec3 vWorldPos;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorldPos = w.xyz;
            gl_Position = applyLogDepth(uViewProj * w);
        }
    """

    const val BH_RELATIVISTIC_FS = """#version 300 es
        precision highp float;
        in vec3 vWorldPos;

        uniform vec3 uCamPos;
        uniform vec3 uBhPos;
        uniform float uHorizonRadius;
        uniform float uBoundRadius;
        uniform float uTime;
        uniform float uDiskStrength; // Total integrated activity (0 = dry gravitational lens only)
        uniform vec4 uDiskBins0;     // Normalized radial profile bins 0..3
        uniform vec4 uDiskBins1;     // Normalized radial profile bins 4..7
        uniform vec3 uDiskNormal;
        uniform vec3 uDiskRight;
        uniform vec3 uDiskForward;

        out vec4 fragColor;
        $NOISE_GLSL
        $SKYBOX_COLOR_GLSL

        float sampleDiskProfile(float u) {
            float idx = clamp(u, 0.0, 0.999) * 7.0;
            int i0 = int(floor(idx));
            float f = fract(idx);
            float vals[8];
            vals[0] = uDiskBins0.x; vals[1] = uDiskBins0.y; vals[2] = uDiskBins0.z; vals[3] = uDiskBins0.w;
            vals[4] = uDiskBins1.x; vals[5] = uDiskBins1.y; vals[6] = uDiskBins1.z; vals[7] = uDiskBins1.w;
            float v0 = vals[clamp(i0, 0, 7)];
            float v1 = vals[clamp(i0 + 1, 0, 7)];
            return mix(v0, v1, f);
        }

        void main() {
            // Normalize coordinates so horizon capture radius rs_vis = 1.0
            float rs = max(uHorizonRadius, 1e-5);
            vec3 ro = (uCamPos - uBhPos) / rs;
            vec3 rd = normalize(vWorldPos - uCamPos);
            float rBound = uBoundRadius / rs;

            // Advance ray start to bounding sphere front intersection if camera is outside
            float b = dot(ro, rd);
            float c = dot(ro, ro) - rBound * rBound;
            float disc = max(b * b - c, 0.0);
            float tEnter = max(0.0, -b - sqrt(disc));
            vec3 pos = ro + rd * tEnter;
            vec3 vel = rd;

            vec3 accumGlow = vec3(0.0);
            float accumAlpha = 0.0;
            bool captured = false;
            float minR = 100.0;

            float peakBin = max(max(max(uDiskBins0.x, uDiskBins0.y), max(uDiskBins0.z, uDiskBins0.w)),
                                max(max(uDiskBins1.x, uDiskBins1.y), max(uDiskBins1.z, uDiskBins1.w)));
            float diskVisible = smoothstep(0.015, 0.18, max(uDiskStrength, peakBin));
            // Schwarzschild ISCO for a non-spinning black hole is r = 3 Rs.
            float diskInner = 3.0;
            float diskOuter = 6.20;
            float prevPlane = dot(pos, uDiskNormal);

            // 44-step Schwarzschild null-geodesic integration: bends background sky & far accretion disk around shadow
            for (int i = 0; i < 44; i++) {
                float r2 = dot(pos, pos);
                float r = sqrt(r2);
                minR = min(minR, r);

                if (r <= 1.02) {
                    captured = true;
                    break;
                }
                if (i > 4 && r > rBound * 1.08 && dot(pos, vel) > 0.0) {
                    break;
                }

                // GR light deflection acceleration: a = -1.5 * |x x v|^2 * x / r^5
                vec3 h = cross(pos, vel);
                float h2 = dot(h, h);
                float invR5 = 1.0 / max(r2 * r2 * r, 0.08);
                vec3 accel = -1.48 * h2 * invR5 * pos;

                float dt = clamp(0.085 * r, 0.032, 0.34);
                vec3 nextVel = normalize(vel + accel * dt);
                vec3 nextPos = pos + (vel + nextVel) * (0.5 * dt);

                // Equatorial accretion disk crossing (strictly scaled by real simulated radial disk bins)
                if (diskVisible > 0.005) {
                    float currPlane = dot(nextPos, uDiskNormal);
                    if ((prevPlane > 0.0 && currPlane <= 0.0) || (prevPlane < 0.0 && currPlane >= 0.0)) {
                        float frac = abs(prevPlane) / max(abs(prevPlane) + abs(currPlane), 1e-5);
                        vec3 hit = mix(pos, nextPos, frac);
                        float rDisk = length(hit);
                        if (rDisk > diskInner && rDisk < diskOuter) {
                            float u = (rDisk - diskInner) / (diskOuter - diskInner);
                            float binWeight = sampleDiskProfile(u);
                            float localDensity = smoothstep(0.015, 0.14, binWeight);
                            if (localDensity > 0.005) {
                                float phi = atan(dot(hit, uDiskForward), dot(hit, uDiskRight));
                                float omega = 1.0 / pow(max(rDisk, 3.0), 1.5);
                                float swirl = phi + omega * 6.5 - uTime * (3.1 - u * 1.7);
                                float mri = fbm(vec3(cos(swirl) * 3.8, u * 12.5, sin(swirl) * 3.8), 3);
                                float rings = 0.55 + 0.45 * sin(u * 46.0 - uTime * 4.2 + mri * 6.5);
                                float clumps = smoothstep(0.24, 0.78, mri);

                                // Relativistic Doppler beaming + gravitational redshift factor g_rel
                                vec3 orbTan = normalize(cross(uDiskNormal, hit));
                                float doppler = dot(orbTan, -vel);
                                float gravRedshift = sqrt(clamp(1.0 - 1.0 / max(rDisk, 1.001), 0.0, 1.0));
                                float gRel = clamp(1.0 + 0.46 * doppler, 0.42, 1.68) * gravRedshift;
                                float beam = pow(gRel, 2.5);

                                float fade = smoothstep(0.0, 0.06, u) * (1.0 - smoothstep(0.24, 1.0, u));
                                float isco = exp(-pow((u - 0.09) / 0.07, 2.0)) * 0.90;

                                // Relativistic blackbody temperature shift: blue-white approaching, deep crimson receding
                                vec3 hotBlueWhite = vec3(0.92, 0.96, 1.05);
                                vec3 warmGold = vec3(1.0, 0.74, 0.36);
                                vec3 coolCrimson = vec3(0.68, 0.16, 0.04);
                                vec3 baseHue = mix(coolCrimson, warmGold, clamp(gRel * 0.75 + (1.0 - u) * 0.45, 0.0, 1.0));
                                vec3 dCol = mix(baseHue, hotBlueWhite, clamp((gRel - 0.85) * 1.15, 0.0, 1.0));

                                float energy = (0.48 + binWeight * 1.15) * diskVisible * localDensity;
                                dCol *= (rings * (0.55 + 0.95 * clumps) + isco) * beam * energy;
                                float dAlpha = fade * (0.45 + 0.55 * clumps) * clamp(0.35 + binWeight * 0.55, 0.0, 0.96) * diskVisible * localDensity;

                                accumGlow += dCol * dAlpha * (1.0 - accumAlpha);
                                accumAlpha = clamp(accumAlpha + dAlpha * (1.0 - accumAlpha), 0.0, 0.98);
                            }
                        }
                    }
                    prevPlane = currPlane;
                }

                pos = nextPos;
                vel = nextVel;
            }

            // Razor-sharp Schwarzschild photon sphere ring at r ~ 1.16 (subtle lensing glimmer even when empty)
            float photonDist = abs(minR - 1.5);
            float photonRing = exp(-pow(photonDist / 0.07, 2.0)) * (0.28 + diskVisible * (0.45 + uDiskStrength * 1.25));
            vec3 pCol = mix(vec3(0.76, 0.86, 1.0), vec3(1.0, 0.84, 0.52), diskVisible);
            accumGlow += pCol * photonRing * (1.0 - accumAlpha * 0.65);

            if (captured) {
                // Opaque dark event horizon shadow core behind any foreground disk emission
                fragColor = vec4(accumGlow, 1.0);
            } else {
                // Escaped rays sample gravitationally deflected background Milky Way & starfield
                float totalDeflection = acos(clamp(dot(normalize(vel), rd), -1.0, 1.0));
                vec3 lensedBg = evalSkyboxColor(normalize(vel));
                float einsteinMag = 1.0 + smoothstep(0.10, 1.35, totalDeflection) * 0.40;
                vec3 bgContrib = lensedBg * einsteinMag * (1.0 - accumAlpha);
                float deflectFade = smoothstep(0.015, 0.14, totalDeflection);
                float outAlpha = clamp(max(accumAlpha + photonRing * 0.60, deflectFade * 0.96), 0.0, 1.0);
                if (outAlpha < 0.01) discard;
                fragColor = vec4(accumGlow + bgContrib * deflectFade, outAlpha);
            }
        }
    """

    // 5B. EXPANDED HABITABLE ZONE OVERLAY SHADER (Hot Red 0.28..0.90√L | Habitable Green 0.90..1.85√L | Cold Blue 1.85..4.20√L)
    const val HABITABLE_ZONE_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 2) in vec2 aUv;
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform float uInnerRad;
        uniform float uOuterRad;
        $LOG_DEPTH_VERT
        out float vRadNorm;
        out vec3 vLocalPos;
        void main() {
            vRadNorm = aUv.x;
            float r = mix(uInnerRad, uOuterRad, aUv.x);
            vec3 pos = vec3(aPos.x * r, 0.0, aPos.z * r);
            vLocalPos = pos;
            gl_Position = applyLogDepth(uViewProj * uModel * vec4(pos, 1.0));
        }
    """

    const val HABITABLE_ZONE_FS = """#version 300 es
        precision highp float;
        in float vRadNorm;
        in vec3 vLocalPos;
        uniform float uTime;
        out vec4 fragColor;
        void main() {
            float angle = atan(vLocalPos.z, vLocalPos.x);
            float dash = 0.68 + 0.32 * sin(angle * 48.0 + uTime * 0.35);
            // Red zone: [0.0..0.16] (0.28..0.90√L)
            // Green habitable zone: [0.16..0.40] (0.90..1.85√L)
            // Blue cold/frost zone: [0.40..1.0] (1.85..4.20√L)
            float redZone = 1.0 - smoothstep(0.145, 0.175, vRadNorm);
            float greenZone = smoothstep(0.145, 0.175, vRadNorm) * (1.0 - smoothstep(0.385, 0.415, vRadNorm));
            float blueZone = smoothstep(0.385, 0.415, vRadNorm);

            vec3 redCol = vec3(0.95, 0.24, 0.20);
            vec3 greenCol = vec3(0.12, 0.90, 0.48);
            vec3 blueCol = vec3(0.24, 0.62, 0.98);
            vec3 fillCol = redCol * redZone + greenCol * greenZone + blueCol * blueZone;

            float edgeFade = smoothstep(0.0, 0.035, vRadNorm) * (1.0 - smoothstep(0.88, 1.0, vRadNorm));
            float innerBound = exp(-pow((vRadNorm - 0.16) / 0.014, 2.0)) * dash;
            float outerBound = exp(-pow((vRadNorm - 0.40) / 0.016, 2.0)) * dash;
            float baseOpacity = greenZone * 0.26 + redZone * 0.18 + blueZone * 0.14;
            float alpha = clamp(edgeFade * baseOpacity + (innerBound + outerBound) * 0.46, 0.0, 0.70);
            if (alpha < 0.01) discard;
            fragColor = vec4(fillCol, alpha);
        }
    """

    // Relativistic Polar Jet billboard shader for active accreting Black Holes
    const val BH_JET_FS = """#version 300 es
        precision highp float;
        in vec2 vUv; // [-1..1]
        uniform float uTime;
        uniform float uAccretionActivity;
        out vec4 fragColor;

        void main() {
            float axial = abs(vUv.y);
            float beamWidth = 0.045 + axial * 0.11;
            float radial = abs(vUv.x) / beamWidth;
            if (radial > 1.0 || axial < 0.06) discard;

            float core = exp(-radial * radial * 3.8);
            float pulse = 0.72 + 0.28 * sin(axial * 24.0 - uTime * 11.0);
            float lengthFade = smoothstep(0.06, 0.22, axial) * pow(1.0 - axial, 1.45);
            float strength = clamp(uAccretionActivity * 0.85, 0.0, 1.8) * core * pulse * lengthFade;
            vec3 col = mix(vec3(0.32, 0.68, 1.0), vec3(0.82, 0.92, 1.0), core);
            fragColor = vec4(col * strength, strength);
        }
    """

    // 6. PLANETARY RING SHADER (Saturn / Uranus with Cassini division & planet shadow)
    const val RING_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 2) in vec2 aUv;
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform float uInnerRad;
        uniform float uOuterRad;
        $LOG_DEPTH_VERT
        out float vRadNorm;
        out vec3 vWorldPos;
        void main() {
            vRadNorm = aUv.x;
            float r = mix(uInnerRad, uOuterRad, aUv.x);
            vec3 pos = vec3(aPos.x * r, 0.0, aPos.z * r);
            vec4 w = uModel * vec4(pos, 1.0);
            vWorldPos = w.xyz;
            gl_Position = applyLogDepth(uViewProj * w);
        }
    """

    const val RING_FS = """#version 300 es
        precision highp float;
        in float vRadNorm;
        in vec3 vWorldPos;
        uniform vec3 uRingColor;
        uniform float uOpacity;
        uniform int uHasCassiniGap;
        uniform vec3 uPlanetPos;
        uniform float uPlanetRadius;
        uniform vec3 uStarPos;
        uniform vec3 uStarColor;
        out vec4 fragColor;

        void main() {
            // Concentric ice/rock ringlet bands
            float ringlets = 0.65 + 0.35 * sin(vRadNorm * 95.0) * cos(vRadNorm * 37.0);
            float edgeFade = smoothstep(0.0, 0.06, vRadNorm) * (1.0 - smoothstep(0.92, 1.0, vRadNorm));
            float gap = 1.0;
            if (uHasCassiniGap == 1) {
                gap *= smoothstep(0.018, 0.048, abs(vRadNorm - 0.62)); // Cassini division
                gap *= smoothstep(0.008, 0.024, abs(vRadNorm - 0.84)); // Encke gap
            }

            // Analytical shadow cast by parent planet onto its rings
            vec3 toStar = normalize(uStarPos - vWorldPos);
            vec3 toPlanet = uPlanetPos - vWorldPos;
            float proj = dot(toPlanet, toStar);
            float shadow = 1.0;
            if (proj > 0.0) {
                float perp = length(toPlanet - toStar * proj);
                shadow = smoothstep(uPlanetRadius * 0.94, uPlanetRadius * 1.06, perp);
            }

            vec3 col = uRingColor * uStarColor * (0.06 + 0.94 * shadow) * ringlets;
            float alpha = uOpacity * edgeFade * gap * (0.55 + 0.45 * ringlets);
            if (alpha < 0.02) discard;
            fragColor = vec4(col, alpha);
        }
    """

    // 7. GPU-INSTANCED ASTEROIDS & BACKGROUND STAR CLUSTERS
    const val INSTANCED_ROCK_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 3) in vec4 aInstPosScale; // xyz = relPos, w = radius
        layout(location = 4) in vec4 aInstColorGlow; // rgb = color, w = emissive (0=rock, >0=star)

        uniform mat4 uViewProj;
        uniform vec3 uStarPos;
        $LOG_DEPTH_VERT

        out vec3 vNormal;
        out vec3 vWorldPos;
        out vec4 vColorGlow;

        void main() {
            vec3 world = aPos * aInstPosScale.w + aInstPosScale.xyz;
            vWorldPos = world;
            vNormal = aNormal;
            vColorGlow = aInstColorGlow;
            gl_Position = applyLogDepth(uViewProj * vec4(world, 1.0));
        }
    """

    const val INSTANCED_ROCK_FS = """#version 300 es
        precision mediump float;
        in vec3 vNormal;
        in vec3 vWorldPos;
        in vec4 vColorGlow;
        uniform vec3 uStarPos;
        out vec4 fragColor;
        void main() {
            if (vColorGlow.w > 0.5) {
                fragColor = vec4(vColorGlow.rgb * vColorGlow.w, 1.0);
                return;
            }
            vec3 L = normalize(uStarPos - vWorldPos);
            float diff = max(dot(normalize(vNormal), L), 0.08);
            fragColor = vec4(vColorGlow.rgb * diff, 1.0);
        }
    """

    // 8. SIMPLE COLORED LINE / PARTICLE / SHOCKWAVE SHADERS
    const val LINE_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec4 aColor;
        uniform mat4 uViewProj;
        $LOG_DEPTH_VERT
        out vec4 vColor;
        void main() {
            vColor = aColor;
            gl_Position = applyLogDepth(uViewProj * vec4(aPos, 1.0));
        }
    """

    const val LINE_FS = """#version 300 es
        precision mediump float;
        in vec4 vColor;
        out vec4 fragColor;
        void main() {
            fragColor = vColor;
        }
    """

    const val PARTICLE_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec4 aColorSize; // rgb, w=pointSize
        uniform mat4 uViewProj;
        uniform float uPointScale;
        $LOG_DEPTH_VERT
        out vec3 vColor;
        void main() {
            vColor = aColorSize.rgb;
            vec4 cp = applyLogDepth(uViewProj * vec4(aPos, 1.0));
            gl_Position = cp;
            gl_PointSize = clamp((aColorSize.w * uPointScale) / max(cp.w, 0.08), 2.0, 42.0);
        }
    """

    const val PARTICLE_FS = """#version 300 es
        precision mediump float;
        in vec3 vColor;
        out vec4 fragColor;
        void main() {
            vec2 d = gl_PointCoord * 2.0 - 1.0;
            float r2 = dot(d, d);
            if (r2 > 1.0) discard;
            float falloff = exp(-r2 * 3.5);
            fragColor = vec4(vColor * falloff * 1.4, falloff);
        }
    """

    // 9. POST-PROCESSING: BRIGHT EXTRACT + GAUSSIAN BLUR + GRAVITATIONAL LENSING COMPOSITE
    const val QUAD_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 2) in vec2 aUv;
        out vec2 vUv;
        void main() {
            vUv = aUv;
            gl_Position = vec4(aPos.xy, 0.0, 1.0);
        }
    """

    const val BRIGHT_PASS_FS = """#version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform sampler2D uSceneTex;
        out vec4 fragColor;
        void main() {
            vec3 c = texture(uSceneTex, vUv).rgb;
            float lum = dot(c, vec3(0.2126, 0.7152, 0.0722));
            vec3 bright = max(c - vec3(0.85), vec3(0.0));
            fragColor = vec4(bright * smoothstep(0.8, 1.4, lum), 1.0);
        }
    """

    const val BLUR_FS = """#version 300 es
        precision mediump float;
        in vec2 vUv;
        uniform sampler2D uTex;
        uniform vec2 uDir;
        out vec4 fragColor;
        void main() {
            float weights[5] = float[](0.227027, 0.1945946, 0.1216216, 0.054054, 0.016216);
            vec3 result = texture(uTex, vUv).rgb * weights[0];
            for (int i = 1; i < 5; i++) {
                vec2 off = uDir * float(i);
                result += texture(uTex, vUv + off).rgb * weights[i];
                result += texture(uTex, vUv - off).rgb * weights[i];
            }
            fragColor = vec4(result, 1.0);
        }
    """

    const val COMPOSITE_FS = """#version 300 es
        precision highp float;
        in vec2 vUv;
        uniform sampler2D uSceneTex;
        uniform sampler2D uBloomTex;
        uniform float uBloomStrength;
        uniform float uAspect;
        uniform int uLensingEnabled;
        uniform int uBhCount;
        uniform vec4 uBhScreen[4]; // xy = screen UV, z = Einstein radius UV, w = schwarzschild UV
        out vec4 fragColor;

        vec3 acesToneMap(vec3 x) {
            float a = 2.51;
            float b = 0.03;
            float c = 2.43;
            float d = 0.59;
            float e = 0.14;
            return clamp((x * (a * x + b)) / (x * (c * x + d) + e), 0.0, 1.0);
        }

        void main() {
            vec2 uv = vUv;
            float photonBoost = 0.0;

            // Subtle far-field screen-space lensing (3D geodesic raymarcher handles inner Einstein distortion directly)
            if (uLensingEnabled == 1) {
                for (int i = 0; i < 4; i++) {
                    if (i >= uBhCount) break;
                    vec2 bhUv = uBhScreen[i].xy;
                    float einsteinR = uBhScreen[i].z;
                    vec2 delta = uv - bhUv;
                    delta.x *= uAspect;
                    float dist = length(delta);
                    if (dist > einsteinR * 0.45 && dist < einsteinR * 4.5) {
                        float deflect = (einsteinR * einsteinR) / (dist * dist + einsteinR * einsteinR * 0.65);
                        vec2 dir = normalize(delta);
                        vec2 shift = dir * einsteinR * deflect * 0.16;
                        shift.x /= uAspect;
                        uv -= shift;
                        photonBoost += exp(-pow((dist - einsteinR * 0.95) / (einsteinR * 0.25), 2.0)) * 0.14;
                    }
                }
            }

            vec2 safeUv = clamp(uv, vec2(0.001), vec2(0.999));
            vec3 sceneCol = texture(uSceneTex, safeUv).rgb;
            vec3 bloomCol = texture(uBloomTex, safeUv).rgb;
            vec3 hdr = sceneCol + bloomCol * uBloomStrength + vec3(1.0, 0.82, 0.55) * photonBoost;
            vec3 mapped = acesToneMap(hdr);
            // Gamma correction
            mapped = pow(mapped, vec3(1.0 / 2.2));
            fragColor = vec4(mapped, 1.0);
        }
    """
}
