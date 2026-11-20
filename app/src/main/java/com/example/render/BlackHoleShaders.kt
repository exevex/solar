package com.example.render

/**
 * Procedural Schwarzschild-inspired light bending for the black-hole volume renderer.
 * This is an approximate optical model, not full Schwarzschild or Kerr ray tracing.
 */
object BlackHoleShaders {
    const val VERTEX = """#version 300 es
        precision highp float;
        layout(location = 0) in vec3 aPos;
        uniform mat4 uModel;
        uniform mat4 uViewProj;
        uniform float uLogDepthCoef;
        out vec3 vWorldPos;
        vec4 applyLogDepth(vec4 clipPos) {
            if (uLogDepthCoef > 0.0 && clipPos.w > 0.0) {
                float logZ = log2(max(1e-6, 1.0 + clipPos.w)) * uLogDepthCoef - 1.0;
                clipPos.z = logZ * clipPos.w;
            }
            return clipPos;
        }
        void main() {
            vec4 world = uModel * vec4(aPos, 1.0);
            vWorldPos = world.xyz;
            gl_Position = applyLogDepth(uViewProj * world);
        }
    """

    const val FRAGMENT = """#version 300 es
        precision highp float;
        in vec3 vWorldPos;
        uniform vec3 uCamPos;
        uniform vec3 uBhPos;
        uniform float uHorizonRadius;
        uniform float uBoundRadius;
        uniform float uTime;
        uniform float uDiskStrength;
        uniform vec4 uDiskBins0;
        uniform vec4 uDiskBins1;
        uniform vec3 uDiskNormal;
        uniform vec3 uDiskRight;
        uniform vec3 uDiskForward;
        uniform int uCinematicLook;
        out vec4 fragColor;

        float hash31(vec3 p) {
            p = fract(p * vec3(443.8975, 397.2973, 491.1871));
            p += dot(p, p.yxz + 19.19);
            return fract((p.x + p.y) * p.z);
        }
        float noise3(vec3 p) {
            vec3 i = floor(p);
            vec3 f = fract(p);
            f = f * f * (3.0 - 2.0 * f);
            float a = hash31(i + vec3(0.0, 0.0, 0.0));
            float b = hash31(i + vec3(1.0, 0.0, 0.0));
            float c = hash31(i + vec3(0.0, 1.0, 0.0));
            float d = hash31(i + vec3(1.0, 1.0, 0.0));
            float e = hash31(i + vec3(0.0, 0.0, 1.0));
            float f1 = hash31(i + vec3(1.0, 0.0, 1.0));
            float g = hash31(i + vec3(0.0, 1.0, 1.0));
            float h = hash31(i + vec3(1.0, 1.0, 1.0));
            return mix(mix(mix(a, b, f.x), mix(c, d, f.x), f.y),
                       mix(mix(e, f1, f.x), mix(g, h, f.x), f.y), f.z);
        }
        float fbm(vec3 p) {
            float value = 0.0;
            float amplitude = 0.5;
            for (int i = 0; i < 3; ++i) {
                value += amplitude * noise3(p);
                p = p * 2.03 + vec3(7.1, 3.7, 5.3);
                amplitude *= 0.5;
            }
            return value;
        }
        vec3 evalSky(vec3 d) {
            vec3 galacticUp = normalize(vec3(0.32, 0.86, -0.39));
            float band = exp(-pow(dot(d, galacticUp) * 5.0, 2.0));
            float clouds = fbm(d * 7.0 + vec3(1.2));
            float dust = smoothstep(0.48, 0.76, fbm(d * 13.0 + vec3(4.1)));
            vec3 color = vec3(0.004, 0.007, 0.016);
            color += mix(vec3(0.035, 0.07, 0.16), vec3(0.24, 0.15, 0.21), clouds)
                     * band * (0.3 + clouds) * (1.0 - dust * 0.65);
            vec3 cell = floor(d * 250.0);
            float starHash = hash31(cell);
            if (starHash > 0.994) {
                float starDist = length(d - normalize(cell + 0.5)) * 250.0;
                float star = exp(-starDist * starDist * 18.0);
                color += mix(vec3(1.0, 0.67, 0.42), vec3(0.62, 0.79, 1.0), hash31(cell + 7.3))
                         * star * (0.8 + fract(starHash * 91.0) * 1.8);
            }
            return color;
        }
        float sampleDiskProfile(float u) {
            float idx = clamp(u, 0.0, 0.999) * 7.0;
            int i0 = int(floor(idx));
            float f = fract(idx);
            float vals[8];
            vals[0] = uDiskBins0.x; vals[1] = uDiskBins0.y; vals[2] = uDiskBins0.z; vals[3] = uDiskBins0.w;
            vals[4] = uDiskBins1.x; vals[5] = uDiskBins1.y; vals[6] = uDiskBins1.z; vals[7] = uDiskBins1.w;
            return mix(vals[clamp(i0, 0, 7)], vals[clamp(i0 + 1, 0, 7)], f);
        }

        void main() {
            float rs = max(uHorizonRadius, 1e-12);
            vec3 ro = (uCamPos - uBhPos) / rs;
            vec3 rd = normalize(vWorldPos - uCamPos);
            float rBound = max(uBoundRadius / rs, 1.05);

            // Robust ray/sphere entry. The old 44 x 0.34 Rs maximum path was less than
            // this volume's 2*rBound (about 28.9 Rs in Clear View), so rays often ended inside it.
            float b = dot(ro, rd);
            float c = dot(ro, ro) - rBound * rBound;
            float discriminant = b * b - c;
            if (discriminant < 0.0) discard;
            float tEnter = max(0.0, -b - sqrt(discriminant));
            vec3 pos = ro + rd * tEnter;
            vec3 vel = rd;

            vec3 accumGlow = vec3(0.0);
            float accumAlpha = 0.0;
            float minR = 100.0;
            bool captured = false;
            bool escaped = false;
            float peakBin = max(max(max(uDiskBins0.x, uDiskBins0.y), max(uDiskBins0.z, uDiskBins0.w)),
                                max(max(uDiskBins1.x, uDiskBins1.y), max(uDiskBins1.z, uDiskBins1.w)));
            float cinematic = float(uCinematicLook);
            float diskVisible = mix(smoothstep(0.015, 0.18, max(uDiskStrength, peakBin)), 1.0, cinematic);
            float diskInner = 3.0;
            float diskOuter = 6.2;
            float prevPlane = dot(pos, uDiskNormal);

            // Dimensionless Schwarzschild-inspired integration. Radius-scaled steps
            // cover the full bound volume, while small steps near the photon sphere resolve bends.
            for (int i = 0; i < ${BlackHoleRayMarchLimits.MAX_STEPS}; ++i) {
                float r2 = dot(pos, pos);
                float r = sqrt(max(r2, 1e-10));
                minR = min(minR, r);
                if (r <= 1.02) {
                    captured = true;
                    break;
                }
                if (i > 4 && r > rBound * ${BlackHoleRayMarchLimits.BOUND_EXIT_MARGIN} && dot(pos, vel) > 0.0) {
                    escaped = true;
                    break;
                }

                // Approximate null-ray bending in a Schwarzschild field; not full Kerr geodesics.
                vec3 h = cross(pos, vel);
                float h2 = dot(h, h);
                float invR5 = 1.0 / max(r2 * r2 * r, 0.08);
                vec3 accel = -1.48 * h2 * invR5 * pos;
                float dt = clamp(${BlackHoleRayMarchLimits.STEP_FRACTION_OF_RADIUS} * r,
                                 ${BlackHoleRayMarchLimits.MIN_STEP}, ${BlackHoleRayMarchLimits.MAX_STEP});
                dt = min(dt, 0.12 / max(sqrt(length(accel)), 1e-5));
                vec3 nextVel = normalize(vel + accel * dt);
                vec3 nextPos = pos + (vel + nextVel) * (0.5 * dt);

                float currPlane = dot(nextPos, uDiskNormal);
                bool crossedPlane = (prevPlane > 0.0 && currPlane <= 0.0) ||
                                    (prevPlane < 0.0 && currPlane >= 0.0);
                // A strictly zero-thickness crossing test makes an edge-on disk
                // disappear. Resolve its finite photosphere as a narrow slab.
                float diskHalfThickness = mix(0.035, 0.09, cinematic);
                bool insidePhotosphere = min(abs(prevPlane), abs(currPlane)) < diskHalfThickness;
                if (diskVisible > 0.005 && (crossedPlane || insidePhotosphere)) {
                    float frac = crossedPlane ? abs(prevPlane) / max(abs(prevPlane) + abs(currPlane), 1e-6) : 0.5;
                    vec3 hit = mix(pos, nextPos, frac);
                    float rDisk = length(hit);
                    if (rDisk > diskInner && rDisk < diskOuter) {
                        float u = (rDisk - diskInner) / (diskOuter - diskInner);
                        float physicalBin = sampleDiskProfile(u);
                        float cinematicProfile = 0.72 + 0.28 * exp(-pow((u - 0.12) / 0.34, 2.0));
                        float binWeight = mix(physicalBin, max(physicalBin, cinematicProfile), cinematic);
                        float physicalDensity = smoothstep(0.015, 0.14, physicalBin);
                        float localDensity = mix(physicalDensity, cinematicProfile, cinematic);
                        if (localDensity > 0.005) {
                            float phi = atan(dot(hit, uDiskForward), dot(hit, uDiskRight));
                            float omega = inversesqrt(max(rDisk * rDisk * rDisk, 1e-5));
                            float swirl = phi + omega * 6.5 - uTime * (3.1 - u * 1.7);
                            float mri = fbm(vec3(cos(swirl) * 3.8, u * 12.5, sin(swirl) * 3.8));
                            float rings = 0.58 + 0.42 * sin(u * 46.0 - uTime * 4.2 + mri * 6.0);
                            float clumps = smoothstep(0.24, 0.78, mri);

                            vec3 orbTan = normalize(cross(uDiskNormal, hit));
                            float doppler = dot(orbTan, -vel);
                            float gravRedshift = sqrt(clamp(1.0 - 1.0 / max(rDisk, 1.001), 0.0, 1.0));
                            float gRel = clamp(1.0 + 0.46 * doppler, 0.42, 1.68) * gravRedshift;
                            // Cinema keeps both disk limbs readable; physical mode
                            // retains the stronger approximate Doppler contrast.
                            float beam = mix(pow(gRel, 2.5), 1.0 + 0.15 * doppler, cinematic);
                            float fade = smoothstep(0.0, 0.055, u) * (1.0 - smoothstep(0.80, 1.0, u));
                            float innerHeat = exp(-pow((u - 0.10) / 0.075, 2.0));
                            vec3 warm = mix(vec3(0.72, 0.12, 0.035), vec3(1.0, 0.50, 0.14),
                                            clamp(gRel * 0.58 + (1.0 - u) * 0.45, 0.0, 1.0));
                            vec3 hot = mix(warm, vec3(1.0, 0.91, 0.72), clamp(innerHeat * (0.55 + gRel * 0.30), 0.0, 1.0));
                            vec3 diskColor = mix(warm, hot, cinematic);
                            float energy = mix((0.48 + binWeight * 1.15) * diskVisible,
                                               (1.10 + 0.70 * innerHeat) * diskVisible, cinematic);
                            float dAlpha = fade * (0.45 + 0.55 * clumps)
                                         * mix(clamp(0.35 + binWeight * 0.55, 0.0, 0.90), 0.78, cinematic)
                                         * diskVisible * localDensity;
                            if (!crossedPlane) dAlpha *= clamp(dt / (2.0 * diskHalfThickness) * 0.35, 0.0, 1.0);
                            vec3 dCol = diskColor * (rings * (0.58 + 0.88 * clumps) + innerHeat * 1.2)
                                      * beam * energy;
                            accumGlow += dCol * dAlpha * (1.0 - accumAlpha);
                            accumAlpha = clamp(accumAlpha + dAlpha * (1.0 - accumAlpha), 0.0, 0.96);
                        }
                    }
                }
                prevPlane = currPlane;
                pos = nextPos;
                vel = nextVel;
            }

            // A ray still trapped after the bounded integration is classified as shadow,
            // rather than leaking an unbent sky sample at the work cap.
            if (!escaped && !captured) captured = true;
            float photonRing = exp(-pow((minR - 1.50) / 0.075, 2.0));
            float ringLevel = photonRing * mix(0.16 + diskVisible * 0.20, 0.90, cinematic);
            vec3 ringColor = mix(vec3(0.72, 0.84, 1.0), vec3(1.0, 0.74, 0.39), cinematic);

            if (captured) {
                // The horizon absorbs background light, not disk light already
                // emitted in front of it along this ray.
                fragColor = vec4(accumGlow + ringColor * ringLevel * (1.0 - accumAlpha), 1.0);
            } else {
                vec3 lensedBg = evalSky(normalize(vel));
                float deflection = acos(clamp(dot(normalize(vel), rd), -1.0, 1.0));
                float deflectFade = smoothstep(0.015, 0.14, deflection);
                float bgWeight = (1.0 - accumAlpha) * deflectFade;
                float ringAlpha = clamp(ringLevel * 0.55, 0.0, 0.55);
                accumGlow += ringColor * ringLevel * (1.0 - accumAlpha);
                vec3 rgbPremultiplied = accumGlow + lensedBg * (1.0 + 0.35 * deflectFade) * bgWeight;
                float outAlpha = clamp(max(accumAlpha + ringAlpha * (1.0 - accumAlpha), deflectFade * 0.90), 0.0, 0.98);
                if (outAlpha < 0.008) discard;
                // The caller uses SRC_ALPHA blending; un-premultiply the integrated radiance here.
                fragColor = vec4(rgbPremultiplied / max(outAlpha, 0.02), outAlpha);
            }
        }
    """
}
