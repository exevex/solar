#!/usr/bin/env python3
"""Offscreen GLES3 regression test for the app's black-hole raymarch shader.

Uses the shared ctypes EGL/GLES harness and actual extracted Kotlin shader source.
Writes cinematic and dry-look previews into build/visuals/.
"""
import importlib.util
import math
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tests" / "render_visuals.py"
spec = importlib.util.spec_from_file_location("render_visuals", HARNESS)
rv = importlib.util.module_from_spec(spec)
spec.loader.exec_module(rv)

LIMIT_FILE = ROOT / "app" / "src" / "main" / "java" / "com" / "example" / "render" / "BlackHoleAppearance.kt"
LIMIT_SOURCE = LIMIT_FILE.read_text()

def kotlin_limit(name, fallback):
    match = re.search(rf"const val {name}\s*=\s*([0-9.]+)", LIMIT_SOURCE)
    assert match, f"missing Kotlin shader limit {name}"
    value = float(match.group(1))
    return int(value) if value.is_integer() else value

R_BOUND = 6.5 / 0.45
R_HORIZON = 1.0
MAX_STEPS = kotlin_limit("MAX_STEPS", 512)
STEP_FRACTION = kotlin_limit("STEP_FRACTION_OF_RADIUS", 0.022)
MIN_STEP = kotlin_limit("MIN_STEP", 0.012)
MAX_STEP = kotlin_limit("MAX_STEP", 0.32)
EXIT_MARGIN = kotlin_limit("BOUND_EXIT_MARGIN", 1.04)


def shader_sources():
    defs = rv.constants()
    fs = defs["FRAGMENT"]
    substitutions = {
        "${BlackHoleRayMarchLimits.MAX_STEPS}": str(MAX_STEPS),
        "${BlackHoleRayMarchLimits.BOUND_EXIT_MARGIN}": str(EXIT_MARGIN),
        "${BlackHoleRayMarchLimits.STEP_FRACTION_OF_RADIUS}": str(STEP_FRACTION),
        "${BlackHoleRayMarchLimits.MIN_STEP}": str(MIN_STEP),
        "${BlackHoleRayMarchLimits.MAX_STEP}": str(MAX_STEP),
    }
    for token, value in substitutions.items():
        fs = fs.replace(token, value)
    unresolved = [token for token in substitutions if token in fs]
    assert not unresolved, f"Kotlin shader substitutions unresolved: {unresolved}"
    return defs["VERTEX"], fs


def probe_ray(impact, max_steps=MAX_STEPS):
    """CPU reference of the shader integrator for deterministic coverage probes."""
    x = -math.sqrt(max(R_BOUND * R_BOUND - impact * impact, 0.0))
    y = impact
    vx, vy = 1.0, 0.0
    min_r = R_BOUND
    for step in range(max_steps):
        r2 = x * x + y * y
        radius = math.sqrt(max(r2, 1e-10))
        min_r = min(min_r, radius)
        if radius <= 1.02:
            return "captured", step, min_r
        if step > 4 and radius > R_BOUND * EXIT_MARGIN and x * vx + y * vy > 0.0:
            return "escaped", step, min_r
        h = x * vy - y * vx
        inv_r5 = 1.0 / max(r2 * r2 * radius, 0.08)
        ax = -1.48 * h * h * inv_r5 * x
        ay = -1.48 * h * h * inv_r5 * y
        accel_len = math.hypot(ax, ay)
        dt = min(MAX_STEP, max(MIN_STEP, STEP_FRACTION * radius))
        dt = min(dt, 0.12 / max(math.sqrt(accel_len), 1e-5))
        n = math.hypot(vx + ax * dt, vy + ay * dt)
        nvx, nvy = (vx + ax * dt) / n, (vy + ay * dt) / n
        x += (vx + nvx) * (0.5 * dt)
        y += (vy + nvy) * (0.5 * dt)
        vx, vy = nvx, nvy
    return "work-cap-shadow", max_steps, min_r


def check_coverage():
    renderer_source = (ROOT / "app/src/main/java/com/example/render/SolarGLRenderer.kt").read_text()
    assert "GlShaderProgram.compile(BlackHoleShaders.VERTEX, BlackHoleShaders.FRAGMENT" in renderer_source
    assert 'setInt("uCinematicLook"' in renderer_source
    jet_comment = renderer_source.index("// Additive Relativistic Polar Jets")
    jet_guard = renderer_source.index("if (blackHoleLook != BlackHoleLook.INTERSTELLAR_CINEMATIC)", jet_comment)
    jet_loop = renderer_source.index("for (bh in blackHoles)", jet_guard)
    corona_loop = renderer_source.index("for (st in coronasToDraw)", jet_loop)
    assert jet_guard < jet_loop < corona_loop
    old_distance_cap = 44 * 0.34
    bound_diameter = 2 * R_BOUND
    assert old_distance_cap < bound_diameter, (old_distance_cap, bound_diameter)
    assert MAX_STEPS * MAX_STEP > bound_diameter
    probes = [0.0, 1.0, 2.0, 2.4, 2.48, 2.50, 2.52, 2.522, 2.523, 2.53, 2.54,
              2.6, 2.7, 3.0, 4.0, 6.0, 10.0, 13.0, 14.0]
    results = {b: probe_ray(b) for b in probes}
    assert all(result[0] in ("captured", "escaped", "work-cap-shadow") for result in results.values()), results
    assert all(result[1] <= MAX_STEPS for result in results.values()), results
    # Near-critical rays can orbit for hundreds of integration steps. At the work cap,
    # the shader intentionally treats an unresolved path as black shadow, never as an unbent sky leak.
    assert results[2.523][0] == "work-cap-shadow" and results[2.523][1] == MAX_STEPS, results[2.523]
    assert results[2.53][0] == "escaped" and results[2.53][1] > 400, results[2.53]
    return {"bound_radius_in_rs": R_BOUND, "bound_diameter_in_rs": bound_diameter,
            "legacy_44_step_cap_in_rs": old_distance_cap,
            "new_max_linear_step_budget_in_rs": MAX_STEPS * MAX_STEP,
            "rays": {str(k): {"status": v[0], "steps": v[1], "minimum_radius_rs": round(v[2], 5)}
                     for k, v in results.items()}}


def sphere_mesh(lat_steps=64, lon_steps=96):
    vertices = []
    for i in range(lat_steps + 1):
        theta = math.pi * i / lat_steps
        for j in range(lon_steps):
            phi = 2 * math.pi * j / lon_steps
            x = math.sin(theta) * math.cos(phi)
            y = math.cos(theta)
            z = math.sin(theta) * math.sin(phi)
            vertices.extend((x, y, z, 0.0, 0.0, 0.0, 0.0, 0.0))
    indices = []
    for i in range(lat_steps):
        for j in range(lon_steps):
            a = i * lon_steps + j
            b = i * lon_steps + (j + 1) % lon_steps
            c = (i + 1) * lon_steps + j
            d = (i + 1) * lon_steps + (j + 1) % lon_steps
            # Outward winding (the production mesh fix uses this same convention).
            indices.extend((a, b, c, b, d, c))
    return vertices, indices


def render(look, edge_on=False):
    vs, fs = shader_sources()
    gl = rv.GL(512, 512)
    try:
        vertices, indices = sphere_mesh()
        gl.mesh(vertices, indices)
        assert gl.glGetError() == 0, "mesh upload left GLES error"
        gl.program(vs, fs)
        assert gl.glGetError() == 0, "program setup left GLES error"
        radius = R_BOUND
        model = list(rv.IDENTITY)
        model[0] = model[5] = model[10] = radius
        eye = [0.0, 0.0 if edge_on else 2.5, 40.0]
        vp = rv.matmul(rv.perspective(aspect=1.0, near=0.1, far=120.0), rv.view(eye))
        values = {
            "uModel": model, "uViewProj": vp, "uLogDepthCoef": 0.0,
            "uCamPos": tuple(eye), "uBhPos": (0.0, 0.0, 0.0),
            "uHorizonRadius": R_HORIZON, "uBoundRadius": radius,
            "uTime": 0.42, "uDiskStrength": 0.0,
            "uDiskBins0": (0.0, 0.0, 0.0, 0.0), "uDiskBins1": (0.0, 0.0, 0.0, 0.0),
            "uDiskNormal": (0.0, 1.0, 0.0),
            "uDiskRight": (0.0, 0.0, 1.0), "uDiskForward": (1.0, 0.0, 0.0),
            "uCinematicLook": look,
        }
        for name, value in values.items():
            if isinstance(value, list):
                gl.uniform(name, value)
            elif isinstance(value, tuple):
                gl.uniform(name, *value)
            else:
                gl.uniform(name, value)
            error = gl.glGetError()
            assert error == 0, f"uniform {name} left GLES error {hex(error)}"
        pixels = gl.draw(cull=0x0404)  # Cull the near exterior faces; shade the ray-volume exit.
        image_name = "black-hole-edge-on.png" if edge_on else ("black-hole-cinematic.png" if look else "black-hole-physical-dry.png")
        rv.png(rv.OUT / image_name, gl.w, gl.h, pixels)
        rgb = [pixels[i:i + 3] for i in range(0, len(pixels), 4)]
        lit = [p for p in rgb if max(p) >= 72]
        center = rgb[(gl.h // 2) * gl.w + gl.w // 2]
        upper_arch = lower_arch = 0
        cx, cy = gl.w // 2, gl.h // 2
        for y in range(gl.h):
            dy = y - cy
            if 18 <= abs(dy) <= 140:
                count = sum(1 for x in range(max(0, cx - 160), min(gl.w, cx + 160))
                            if max(rgb[y * gl.w + x]) >= 72)
                if dy < 0:
                    upper_arch += count
                else:
                    lower_arch += count
        wings = {}
        for side, xa, xb in [("left", cx - 95, cx - 42), ("right", cx + 42, cx + 95)]:
            wings[side] = sum(1 for y in range(cy - 10, cy + 15) for x in range(xa, xb)
                              if max(rgb[y * gl.w + x]) >= 55)
        return {"look": "cinematic-edge-on" if edge_on else ("cinematic" if look else "physical-dry"), "lit_pixels": len(lit),
                "horizontal_disk_wing_pixels": wings,
                "upper_arch_pixels": upper_arch, "lower_arch_pixels": lower_arch,
                "peak_rgb": list(max(rgb, key=lambda p: max(p))), "shadow_center_rgb": list(center),
                "image": str(rv.OUT / image_name)}
    finally:
        gl.close()


def main():
    coverage = check_coverage()
    cinematic = render(1)
    physical = render(0)
    edge = render(1, edge_on=True)
    assert cinematic["lit_pixels"] > physical["lit_pixels"] + 20, (cinematic, physical)
    assert cinematic["upper_arch_pixels"] >= 400, cinematic
    assert cinematic["lower_arch_pixels"] >= 400, cinematic
    assert max(cinematic["shadow_center_rgb"]) <= 35, cinematic
    for image in [cinematic, edge]:
        assert min(image["horizontal_disk_wing_pixels"].values()) >= 50, image
    report = {"coverage": coverage, "rendered": [cinematic, physical, edge],
              "shader": "GLES3 extracted from BlackHoleShaders.kt; actual pbuffer draw/readback"}
    out = rv.OUT / "black-hole-verification.json"
    out.write_text(__import__("json").dumps(report, indent=2) + "\n")
    print(__import__("json").dumps(report, indent=2))


if __name__ == "__main__":
    main()
