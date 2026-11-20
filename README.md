# Solar Sandbox

**Solar Sandbox** is a native Android 3D astrophysics & cosmic destruction game combining the tactile planet-smashing chaos of *Solar Smash* with the real-time N-body orbital simulation of *Universe Sandbox*, built in **Kotlin**, **Jetpack Compose**, and **OpenGL ES 3.0 (GLSL `#version 300 es`)**.

> **Runtime Note:** This project is built natively for Android using Gradle, Kotlin Coroutines, Jetpack Compose, and custom OpenGL ES 3.0 shaders (rather than Unity/Godot) so it compiles directly as a self-contained Android Studio / Gradle APK with minimal binary footprint and 60 FPS hardware-accelerated rendering on mid-range phones.

---

## Implemented Features

### Core N-Body Simulation
- **True N-Body Gravity:** Symplectic **Velocity Verlet** and **Runge-Kutta 4 (RK4)** integrators with **adaptive substeps** (2–36 substeps per frame based on shortest pairwise dynamical crossing time). No fake rails or scripted orbits.
- **Real NASA/JPL Solar System:** Sun (G2V), Mercury, Venus, Earth & Moon, Mars (Phobos & Deimos), 180 Main Asteroid Belt rocks, Jupiter (Io, Europa, Ganymede, Callisto), Saturn (Cassini-division rings, Enceladus, Titan), Uranus (97.77° axial tilt & faint rings), Neptune (Triton), Pluto, and Comet Halley-X.
- **Time Control, Rewind & Save/Load:**
  - Pause, Play, Reverse/Rewind from a 120-keyframe ring buffer, and logarithmic speed control from real-time up to millions of times faster (`1d/s`, `1mo/s`, `1yr/s`, `10M×`).
  - Full JSON simulation state **Save & Load** slots stored locally in app storage.
- **Collisions & Destruction:**
  - Momentum-conserving merging with volume growth, shockwaves, surface thermal heating (glowing lava crust melting with radiative cooling), and persistent **3D impact craters**.
  - Hypervelocity catastrophic fragmentation spawning glowing molten debris chunks and particle bursts.

### Real observation assets & physics provenance
- **Earth geometry and imagery:** Earth uses a WGS84 oblate ellipsoid mesh with GEBCO-derived land-height samples, NASA MODIS September 2004 Blue Marble surface imagery, and independent VIIRS 2016 night lights. Globe and 2-D map share north-up geography, not synthetic continent blobs. Other named worlds remain mapped spheres; source epochs, projection, hashes and resolution limits are in [REAL_DATA_SOURCES.md](REAL_DATA_SOURCES.md).
- **JPL initial conditions:** The Solar System preset loads DE441/JPL Horizons J2000 state vectors for the Sun, planets and tracked moons, then evolves them with the selected N-body integrator.
- **Physical collision model:** Gravity softening, contact radii, impact energy and Roche limits use physical radii rather than sandbox display radii. Swept contacts prevent fast objects tunneling through a body between simulation samples.
- **Event text toggle:** Physics events continue to happen when the HUD event-text switch is off; only the explanatory toast is hidden.
- **Science Copilot:** Optional Gemini 2.5 Flash explainer with up to eight prior turns and a read-only snapshot of the selected body's live simulation values. Paste a key per session; the app does not persist it or bundle it into the APK. Sending a question shares it, the displayed simulation context, and recent turns with Google. Requires internet access and a Gemini API key.

### Visuals & OpenGL ES 3.0 Shaders
- **Floating Origin + Logarithmic Depth:** World coordinates are subtracted in 64-bit `Double` precision relative to the camera focus target before uploading `Float` matrices, paired with logarithmic depth encoding to eliminate jitter and z-fighting from planetary surfaces out to 100+ AU.
- **Earth mesh and mapped worlds:** Earth's 65,341-vertex mesh samples clipped, quantized 0–6400 m GEBCO land elevation without vertical exaggeration. This is globe-scale relief, not a survey-resolution terrain model. Other worlds retain mapped UV spheres. Clouds and edited-climate effects are illustrative overlays, not observed weather or climate forecasts.
- **Habitable Zone Overlay:** Toggleable **Habitable Zone** Goldilocks view rendering dynamic liquid-water circumstellar rings (`0.85√L`–`1.78√L` AU) around every luminous star as you edit stellar mass or luminosity.
- **Atmosphere, Shadows & Eclipses:** Rayleigh/Mie rim scattering with orange-red sunset terminators, analytical ray-sphere eclipse shadows across planets/moons, and mutual Saturn ring/planet shadows.
- **HDR Stars & Post-Processing:** Planckian blackbody star colors (O, B, A, F, G, K, M), dual-scale animated solar granulation & chromosphere limb glow, limb darkening, additive plasma corona & pulsar beams, multi-pass separable Gaussian **Bloom**, and ACES Filmic tone mapping.
- **Skybox & Trails:** Procedural Milky Way galactic band + multi-spectral starfield, toggleable orbit trails, floating body labels with live status tags (`SPAGHETTIFYING`, `ACCRETING`, `MOLTEN`, `RED GIANT`), live Cosmic Activity Ticker, and **Cinematic Camera Mode**.

### Black Holes & Relativistic Effects
- **Black Holes of Any Mass:** Easily spawn Primordial (3 $M_\odot$), Stellar (12 $M_\odot$), Intermediate (80 $M_\odot$), or Supermassive (350 $M_\odot$) black holes from the `Black Holes` spawn category, or scale mass to infinity in the Inspector.
- **Interstellar-inspired black-hole look:** A default, explicitly labeled cinematic option shows a luminous disk and lensed upper/lower arches around a dark shadow, with polar jets suppressed. Switch it off in Graphics & Physics Settings to make disk brightness depend on simulated accretion. Appearance never adds physical mass or changes gravity. This is an artistic, approximate optical renderer—not the movie's production model or full Kerr ray tracing.
- **Smooth Tidal Spaghettification & Disruption (TDE):** Bodies entering the Roche zone elongate into a smooth $C^1$ teardrop toward the black hole, heat up with tidal shear glow, shed L1-anchored **orbiting spiral plasma filaments** (`TidalStreamFilament`) into the black hole, and transfer mass (`Absorbed Mass` tracked live). Tiny rocky specks hitting stars or gas giants vaporize cleanly without spurious debris/craters.
- **Hawking Evaporation & Mergers:** Optional Hawking radiation mass loss culminating in an evaporation flash, plus binary black hole inspirals and gravitational-wave merger ripples.

### Sandbox Tools, Weapons & Live Coupling
- **Live Property Coupling & Earth Surface Map:** Editing any property triggers coupled astrophysics: Star Mass ↔ Luminosity ($M^{3.5}$), Radius ($M^{0.8}$), Temperature, and Spectral Class (`O..M`); White Dwarf (`>1.44 M☉`) → Neutron Star (`>2.8 M☉`) → Black Hole; Planet Mass (`>15 M⊕`) → Gas Giant (`>0.08 M☉`) → Red Dwarf Star; Atmosphere → greenhouse warming. Includes a **Live Earth 2D Surface Map Preview** in both the Inspector and a toggleable HUD window showing real-time oceans, ice sheets, magma, craters, and habitability %.
- **Wide Multi-Item Spawn Tray & Precise Placement:** Full-width horizontal bottom catalog with `Circular Orbit` / `Drop Still` velocity toggle, cyan **target placement reticle**, horizon-safe tap-to-place anywhere in space, and `Quick Place`.
- **Weapons & Cosmic Events:** Asteroid Strike, Thermonuclear Strike, Planet Splitter, Trigger Supernova, Gamma-Ray Burst, and Rogue Star Flyby.
- **7 System Presets:** Solar System, Alpha Centauri, Binary Star System, TRAPPIST-1, Galaxy Collision, Sun → Red Giant Evolution, and Binary Black Holes.
- **Stellar Evolution:** Main Sequence → Red Giant expansion → Supernova (Neutron Star / Black Hole) or Planetary Nebula → White Dwarf.

### Mobile UX & Optimization
- **Touch Controls:**
  - **Orbit tool (default):** 1-finger drag orbits camera, 2-finger pinch zooms logarithmically, tap selects, long-press + drag grabs/flings any body
  - **Drag tool:** Dedicated left-rail **Drag** mode so 1-finger touch immediately grabs and flings planets, stars, or black holes without waiting for a long-press
  - **Slingshot mode:** Touch and drag back to aim launch velocity with a real-time **predicted trajectory arc**
- **Performance:** Dedicated background physics thread, upgraded high-detail sphere mesh LODs (`46×68` / `24×34`), GPU-instanced (`glDrawElementsInstanced`) asteroid belt & star clusters, Quality Settings (`Low`, `Medium`, `High`, `Ultra`), **Auto-Detect Device**, and **Battery Saver (30 FPS)** mode.

---

## Known Limits
- **True Scale vs. Sandbox Clear View:** At exact 1:1 km/AU scale, terrestrial planets are tiny dots at solar-system zoom levels. The default *Sandbox Clear View* applies enlarged planetary/solar visual scaling while keeping orbital physics at true AU radii; toggle *True Astronomical Scale* in Settings.
- **N-Body Particle Scaling:** Asteroid-to-asteroid mutual $N^2$ forces are skipped for background belt clusters to maintain 60 FPS on mid-range mobile CPUs (they still experience full gravity from the Sun and all major planets/black holes).
- **Relativistic optics approximation:** The radius-scaled marcher has a 512-step work cap. Unresolved near-critical rays are treated as shadow to avoid background leakage. Clear View enlarges the visible horizon; True Scale uses the physical Schwarzschild radius. Cinematic disk emission is artistic; neither look is full Kerr ray tracing.
- **AI and scientific limits:** The copilot is a language-model explainer, not a numerical solver or live JPL lookup. Its request sends the question, displayed simulation context, and up to eight prior turns to Google's Gemini API; confirm important facts independently.

## Build an APK

Use a desktop Android SDK with API 36.1 and Build-Tools 36.0.0, then run the included, checksum-pinned Gradle 9.3.1 wrapper:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

No custom keystore or API keys are needed for debug builds. GitHub Actions runs the same checks on a clean checkout and uploads the verified APK and reports. See [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md) for downloads, stable debug signing, release signing, and phone/Termux guidance.

CI also renders actual GLSL on a surfaceless EGL/GLES3 context with the production Earth mesh exported by tests. It verifies map landmark colors and context recreation, checks captured/escaping black-hole paths and bright upper/lower arches, and uploads PNGs plus JSON under `Solar-Sandbox-visual-verification`. These are rendering checks, not proof of scientific completeness, performance on every phone, or film equivalence.
