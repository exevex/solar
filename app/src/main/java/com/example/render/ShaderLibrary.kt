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

        void main() {
            vec3 d = normalize(vDir);
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
            fragColor = vec4(bg, 1.0);
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
        $LOG_DEPTH_VERT

        out vec3 vLocalPos;
        out vec3 vWorldPos;
        out vec3 vWorldNormal;
        out vec2 vUv;

        void main() {
            vLocalPos = aPos;
            vUv = aUv;
            vec4 world = uModel * vec4(aPos, 1.0);
            vWorldPos = world.xyz;
            vWorldNormal = normalize(mat3(uModel) * aNormal);
            gl_Position = applyLogDepth(uViewProj * world);
        }
    """

    const val PLANET_FS = """#version 300 es
        precision highp float;
        in vec3 vLocalPos;
        in vec3 vWorldPos;
        in vec3 vWorldNormal;
        in vec2 vUv;

        uniform vec3 uCamPos;
        uniform vec3 uBodyPos;
        uniform float uBodyRadius;
        uniform sampler2D uSurfaceTex;
        uniform int uUseSurfaceTex;
        uniform int uPlanetStyle; // 0=EarthLike, 1=Terrestrial/Mars/Moon, 2=GasGiant, 3=IceGiant, 4=Comet
        uniform float uSeed;
        uniform float uTime;
        uniform int uFbmOctaves;
        uniform vec3 uAlbedo;
        uniform float uAtmoDensity;
        uniform vec3 uAtmoColor;
        uniform int uHasClouds;
        uniform int uHasCityLights;
        uniform float uCrustHeat;
        uniform int uEnableAtmo;
        uniform int uEnableEclipses;

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

            if (uPlanetStyle == 0) {
                // Earth-like: continents, bathymetry, mountain ridges, polar ice, clouds, city lights
                float cont = fbm(p * 2.6 + vec3(uSeed), uFbmOctaves);
                float detail = fbm(p * 11.5 + vec3(uSeed * 2.1), max(2, uFbmOctaves - 1));
                float lat = abs(p.y);
                float ice = smoothstep(0.76, 0.88, lat + (detail - 0.5) * 0.14);
                if (cont < 0.50) {
                    // Ocean with coastal shelf
                    float depth = smoothstep(0.32, 0.50, cont);
                    baseColor = mix(vec3(0.03, 0.12, 0.34), vec3(0.08, 0.35, 0.58), depth);
                    roughness = 0.18;
                    specStrength = 0.75;
                    height = 0.45;
                } else {
                    float elev = (cont - 0.50) * 2.0;
                    vec3 lowland = mix(vec3(0.11, 0.36, 0.15), vec3(0.56, 0.48, 0.28), smoothstep(0.45, 0.75, detail));
                    vec3 alpine = vec3(0.42, 0.38, 0.35);
                    baseColor = mix(lowland, alpine, smoothstep(0.45, 0.85, elev));
                    baseColor = mix(baseColor, vec3(0.92, 0.95, 0.98), smoothstep(0.75, 0.92, elev));
                    roughness = 0.85;
                    specStrength = 0.04;
                    height = 0.5 + elev * 0.45;
                    if (uHasCityLights == 1 && elev < 0.48 && lat < 0.72) {
                        float urban = noise3(p * 34.0 + vec3(uSeed * 3.0));
                        cityMask = smoothstep(0.73, 0.88, urban) * (1.0 - ice);
                    }
                }
                baseColor = mix(baseColor, vec3(0.92, 0.95, 0.99), ice);
                if (ice > 0.5) { roughness = 0.45; specStrength = 0.25; }

                if (uHasClouds == 1) {
                    vec3 cPos = p * 3.4 + vec3(uTime * 0.03, 0.0, -uTime * 0.02);
                    float cWarp = fbm(cPos + vec3(fbm(cPos * 1.4, 2)), uFbmOctaves);
                    cloudAlpha = smoothstep(0.49, 0.76, cWarp) * 0.82;
                }
            } else if (uPlanetStyle == 1) {
                // Terrestrial (Mercury, Venus, Mars, Moons)
                float terrain = fbm(p * 3.8 + vec3(uSeed), uFbmOctaves);
                float micro = fbm(p * 16.0 + vec3(uSeed * 1.7), max(2, uFbmOctaves - 1));
                float polar = smoothstep(0.84, 0.94, abs(p.y) + (micro - 0.5) * 0.08);
                baseColor = uAlbedo * (0.62 + terrain * 0.65 + (micro - 0.5) * 0.25);
                if (uHasClouds == 1) {
                    // Venus sulfuric cloud bands
                    float swirl = fbm(vec3(p.x * 2.5, p.y * 5.5, p.z * 2.5) + vec3(uTime * 0.05, 0.0, 0.0), uFbmOctaves);
                    baseColor = mix(baseColor, uAtmoColor * (0.75 + 0.4 * swirl), 0.78);
                } else {
                    // Mars-like polar frost subtle tint when atmo > 0.05
                    if (uAtmoDensity > 0.05) {
                        baseColor = mix(baseColor, vec3(0.90, 0.88, 0.86), polar * 0.85);
                    }
                }
                height = terrain * 0.7 + micro * 0.3;
            } else if (uPlanetStyle == 2) {
                // Gas Giant (Jupiter / Saturn): Zonal jet bands + Kelvin-Helmholtz eddies + Great Spot
                float turb = fbm(p * 5.5 + vec3(uSeed, uTime * 0.02, 0.0), uFbmOctaves);
                float latBand = sin(p.y * 24.0 + turb * 4.5 + uSeed) * 0.5 + 0.5;
                float fineBand = sin(p.y * 54.0 - turb * 3.0) * 0.5 + 0.5;
                vec3 c1 = uAlbedo * 1.18;
                vec3 c2 = uAlbedo * vec3(0.68, 0.58, 0.50);
                vec3 c3 = mix(uAlbedo, vec3(0.92, 0.86, 0.74), 0.55);
                baseColor = mix(c2, c1, latBand);
                baseColor = mix(baseColor, c3, fineBand * 0.35);
                // Great Red Spot style anticyclonic vortex
                vec3 spotCenter = normalize(vec3(0.88, -0.32, 0.35));
                vec3 dSpot = p - spotCenter;
                float spotDist = length(vec3(dSpot.x * 0.75, dSpot.y * 1.7, dSpot.z));
                float spotMask = smoothstep(0.26, 0.05, spotDist);
                baseColor = mix(baseColor, uAlbedo * vec3(1.15, 0.52, 0.34), spotMask * 0.78);
                roughness = 0.65;
            } else if (uPlanetStyle == 3) {
                // Ice Giant (Uranus / Neptune): Smooth methane gradient + wispy high-altitude streaks
                float wisp = fbm(vec3(p.x * 2.0 + uTime * 0.03, p.y * 10.0, p.z * 2.0) + vec3(uSeed), uFbmOctaves);
                float band = sin(p.y * 12.0 + wisp * 2.2) * 0.5 + 0.5;
                baseColor = mix(uAlbedo * 0.82, uAlbedo * 1.15, band);
                baseColor = mix(baseColor, vec3(0.85, 0.95, 1.0), smoothstep(0.72, 0.88, wisp) * 0.35);
                roughness = 0.48;
                specStrength = 0.22;
            } else {
                // Comet icy nucleus
                float crag = fbm(p * 8.0 + vec3(uSeed), 3);
                baseColor = mix(vec3(0.28, 0.32, 0.38), vec3(0.75, 0.88, 0.98), smoothstep(0.45, 0.75, crag));
                height = crag;
            }

            // Mission-derived map layers override invented procedural geology wherever an
            // observed planetary map exists; procedural shading remains a no-map fallback.
            if (uUseSurfaceTex == 1) {
                baseColor = texture(uSurfaceTex, vec2(vUv.x, 1.0 - vUv.y)).rgb;
                roughness = (uPlanetStyle == 0) ? 0.42 : 0.64;
                specStrength = (uPlanetStyle == 0) ? 0.24 : 0.08;
            }

            // Apply procedural surface craters + glowing impact craters
            float craterLava = 0.0;
            for (int c = 0; c < 8; c++) {
                if (c >= uCraterCount) break;
                float angDist = acos(clamp(dot(p, normalize(uCraters[c].xyz)), -1.0, 1.0));
                float rad = uCraters[c].w;
                if (angDist < rad * 1.25) {
                    float rim = exp(-pow((angDist - rad) / (rad * 0.16), 2.0));
                    float bowl = smoothstep(rad, 0.0, angDist);
                    baseColor = mix(baseColor, baseColor * 0.52, bowl * 0.7);
                    baseColor += vec3(0.18, 0.16, 0.14) * rim;
                    height -= bowl * 0.35 - rim * 0.2;
                    craterLava = max(craterLava, bowl * uCraterHeat[c]);
                }
            }

            // Surface normal perturbation via screen-space height derivatives for crisp relief
            vec3 dpdx = dFdx(vWorldPos);
            vec3 dpdy = dFdy(vWorldPos);
            float dhdx = dFdx(height);
            float dhdy = dFdy(height);
            vec3 bumpN = normalize(N - (dpdx * dhdx + dpdy * dhdy) * 1.2);
            N = mix(N, bumpN, 0.45);

            // Multi-star PBR lighting + shadows + clouds
            vec3 litColor = baseColor * 0.045; // faint cosmic ambient
            vec3 nightCityAccum = vec3(0.0);
            float dayFactorMax = 0.0;

            for (int s = 0; s < 4; s++) {
                if (s >= uStarCount) break;
                vec3 toStar = uStarPos[s] - vWorldPos;
                float starDist = length(toStar);
                vec3 L = toStar / max(starDist, 1e-5);
                float ndl = max(dot(N, L), 0.0);
                dayFactorMax = max(dayFactorMax, dot(normalize(vWorldNormal), L));

                float shadow = computeEclipseShadow(vWorldPos, L, starDist);
                float atten = uStarIntensity[s] / (1.0 + 0.015 * starDist * starDist);
                vec3 starRad = uStarColor[s] * atten * shadow;

                // Diffuse + Blinn-Phong specular
                vec3 surfAlbedo = mix(baseColor, vec3(0.95, 0.97, 1.0), cloudAlpha);
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
                    vec3 sunsetTint = mix(vec3(1.0, 0.38, 0.14), uAtmoColor, smoothstep(-0.08, 0.38, dot(normalize(vWorldNormal), L)));
                    litColor += sunsetTint * fresnel * terminator * uAtmoDensity * starRad * 1.15;
                }
            }

            // Night-side city lights on Earth-like worlds
            if (cityMask > 0.01) {
                float nightWeight = smoothstep(0.12, -0.18, dayFactorMax);
                litColor += vec3(1.0, 0.78, 0.42) * cityMask * nightWeight * (1.0 - cloudAlpha * 0.7) * 1.35;
            }

            // Molten crust magma cracks & impact glow (HDR overbright for bloom!)
            float lavaPattern = smoothstep(0.54, 0.76, fbm(p * 12.0 + vec3(uSeed), 3));
            float totalHeat = max(uCrustHeat * (0.45 + 0.85 * lavaPattern), craterLava);
            if (totalHeat > 0.01) {
                vec3 lavaCol = mix(vec3(1.3, 0.22, 0.02), vec3(2.4, 0.95, 0.25), totalHeat);
                litColor += lavaCol * totalHeat * 1.6;
            }

            fragColor = vec4(litColor, 1.0);
        }
    """

    // 3. HDR STAR / SUN / WHITE DWARF / NEUTRON STAR SHADER
    const val STAR_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        layout(location = 2) in vec2 aUv;

        uniform mat4 uModel;
        uniform mat4 uViewProj;
        $LOG_DEPTH_VERT

        out vec3 vLocalPos;
        out vec3 vWorldPos;
        out vec3 vWorldNormal;
        out vec2 vUv;

        void main() {
            vLocalPos = aPos;
            vUv = aUv;
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorldPos = w.xyz;
            vWorldNormal = normalize(mat3(uModel) * aNormal);
            gl_Position = applyLogDepth(uViewProj * w);
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
        uniform sampler2D uSurfaceTex;
        uniform int uUseSurfaceTex;
        uniform float uTime;
        uniform float uSeed;
        uniform float uIntensity;

        out vec4 fragColor;
        $NOISE_GLSL

        void main() {
            vec3 p = normalize(vLocalPos);
            vec3 N = normalize(vWorldNormal);
            vec3 V = normalize(uCamPos - vWorldPos);

            float gran = fbm(p * 8.0 + vec3(uTime * 0.18, -uTime * 0.12, uSeed), 4);
            float spots = smoothstep(0.68, 0.82, fbm(p * 3.5 + vec3(uSeed * 2.0, uTime * 0.04, 0.0), 3));
            float limb = pow(max(dot(N, V), 0.05), 0.42);

            vec3 hotCore = uStarColor * (1.25 + gran * 0.75) * uIntensity;
            if (uUseSurfaceTex == 1) {
                hotCore = texture(uSurfaceTex, vec2(vUv.x, 1.0 - vUv.y)).rgb * (1.4 + gran * 0.45) * uIntensity;
            }
            vec3 coolSpot = uStarColor * 0.35;
            vec3 col = mix(hotCore, coolSpot, spots * 0.65);
            col *= (0.48 + 0.65 * limb);
            // Bright white-hot plasma center
            col += vec3(0.45) * gran * limb * uIntensity;
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

    // 5. BLACK HOLE EVENT HORIZON + PHOTON RING + DOPPLER ACCRETION DISK
    const val BLACK_HOLE_CORE_VS = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        layout(location = 1) in vec3 aNormal;
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        $LOG_DEPTH_VERT
        out vec3 vWorldPos;
        out vec3 vWorldNormal;
        void main() {
            vec4 w = uModel * vec4(aPos, 1.0);
            vWorldPos = w.xyz;
            vWorldNormal = normalize(mat3(uModel) * aNormal);
            gl_Position = applyLogDepth(uViewProj * w);
        }
    """

    const val BLACK_HOLE_CORE_FS = """#version 300 es
        precision highp float;
        in vec3 vWorldPos;
        in vec3 vWorldNormal;
        uniform vec3 uCamPos;
        uniform float uAccretionActivity;
        out vec4 fragColor;
        void main() {
            vec3 N = normalize(vWorldNormal);
            vec3 V = normalize(uCamPos - vWorldPos);
            float edge = 1.0 - max(dot(N, V), 0.0);
            // Razor-sharp glowing photon sphere around pitch-black event horizon
            float photonRing = pow(edge, 9.0) * (1.8 + uAccretionActivity * 1.2);
            vec3 ringColor = vec3(1.0, 0.76, 0.38) * photonRing;
            fragColor = vec4(ringColor, 1.0);
        }
    """

    const val ACCRETION_DISK_VS = """#version 300 es
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
        out vec3 vWorldPos;
        void main() {
            vRadNorm = aUv.x;
            float r = mix(uInnerRad, uOuterRad, aUv.x);
            vec3 pos = vec3(aPos.x * r, 0.0, aPos.z * r);
            vLocalPos = pos;
            vec4 w = uModel * vec4(pos, 1.0);
            vWorldPos = w.xyz;
            gl_Position = applyLogDepth(uViewProj * w);
        }
    """

    const val ACCRETION_DISK_FS = """#version 300 es
        precision highp float;
        in float vRadNorm;
        in vec3 vLocalPos;
        in vec3 vWorldPos;
        uniform vec3 uCamPos;
        uniform vec3 uBhPos;
        uniform float uTime;
        uniform float uAccretionActivity;
        out vec4 fragColor;
        $NOISE_GLSL

        void main() {
            float angle = atan(vLocalPos.z, vLocalPos.x);
            // Keplerian differential swirl
            float swirl = angle + (1.0 / (0.18 + vRadNorm)) * 2.2 - uTime * (2.6 - vRadNorm * 1.4);
            float spiral = fbm(vec3(cos(swirl) * 3.0, vRadNorm * 9.0, sin(swirl) * 3.0), 3);
            float bands = 0.55 + 0.45 * sin(vRadNorm * 28.0 - uTime * 4.0 + spiral * 5.0);

            // Relativistic Doppler beaming: approaching tangent side brighter & bluer, receding dimmer & redder
            vec3 tangentDir = normalize(cross(vec3(0.0, 1.0, 0.0), normalize(vWorldPos - uBhPos)));
            vec3 toCam = normalize(uCamPos - vWorldPos);
            float doppler = dot(tangentDir, toCam);
            float beaming = pow(1.0 + 0.42 * doppler, 2.4);

            float fade = smoothstep(0.0, 0.10, vRadNorm) * smoothstep(1.0, 0.25, vRadNorm);
            vec3 hotInner = mix(vec3(1.0, 0.55, 0.18), vec3(0.78, 0.90, 1.0), clamp(doppler * 0.5 + (1.0 - vRadNorm) * 0.6, 0.0, 1.0));
            vec3 coolOuter = vec3(0.65, 0.16, 0.08);
            vec3 col = mix(hotInner, coolOuter, vRadNorm) * bands * (0.7 + 0.9 * spiral) * beaming * (1.2 + uAccretionActivity * 0.9);
            float alpha = fade * (0.45 + 0.55 * spiral) * clamp(0.75 + uAccretionActivity * 0.25, 0.0, 1.0);
            fragColor = vec4(col * alpha, alpha);
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
            float edgeFade = smoothstep(0.0, 0.06, vRadNorm) * smoothstep(1.0, 0.92, vRadNorm);
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

            // Screen-space General Relativistic Gravitational Lensing (Einstein ring deflection)
            if (uLensingEnabled == 1) {
                for (int i = 0; i < 4; i++) {
                    if (i >= uBhCount) break;
                    vec2 bhUv = uBhScreen[i].xy;
                    float einsteinR = uBhScreen[i].z;
                    vec2 delta = uv - bhUv;
                    delta.x *= uAspect;
                    float dist = length(delta);
                    if (dist > 0.0005 && dist < einsteinR * 4.5) {
                        // Gravitational deflection alpha ~ R_E^2 / r
                        float deflect = (einsteinR * einsteinR) / (dist * dist + einsteinR * einsteinR * 0.35);
                        vec2 dir = normalize(delta);
                        vec2 shift = dir * einsteinR * deflect * 0.55;
                        shift.x /= uAspect;
                        uv -= shift;
                        // Subtle Einstein ring brightening near R_E
                        photonBoost += exp(-pow((dist - einsteinR * 0.95) / (einsteinR * 0.22), 2.0)) * 0.35;
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
