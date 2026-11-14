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

### Visuals & OpenGL ES 3.0 Shaders
- **Floating Origin + Logarithmic Depth:** World coordinates are subtracted in 64-bit `Double` precision relative to the camera focus target before uploading `Float` matrices, paired with logarithmic depth encoding to eliminate jitter and z-fighting from planetary surfaces out to 100+ AU.
- **NASA/JPL Body-Accurate Procedural PBR Planets:** Distinct high-resolution procedural GLSL shaders tailored to each world—Earth (continents, ocean specular glint, animated cyclone clouds, night-side city lights), Mars (rust basins, *Valles Marineris* canyon scar, craters, polar caps), Mercury & Moon (dense rayed craters & maria), Venus (thick super-rotating sulfur clouds), Jupiter (turbulent zonal belts & Great Red Spot), Saturn (golden bands & North Polar Hexagon), Uranus & Neptune (methane cyan/cobalt bands, cirrus & Great Dark Spot), Pluto (*Sputnik Planitia* nitrogen heart), Io (volcanic sulfur calderas), Europa (criss-crossing ice lineae), Titan (orange organic haze), and Enceladus (icy tiger stripes)—all dynamically reacting to thermal crust heating and tidal stretching without extra VRAM overhead.
- **Atmosphere, Shadows & Eclipses:** Rayleigh/Mie rim scattering with orange-red sunset terminators, analytical ray-sphere eclipse shadows across planets/moons, and mutual Saturn ring/planet shadows.
- **HDR Stars & Post-Processing:** Planckian blackbody star colors (O, B, A, F, G, K, M), dual-scale animated solar granulation & chromosphere limb glow, limb darkening, additive plasma corona & pulsar beams, multi-pass separable Gaussian **Bloom**, and ACES Filmic tone mapping.
- **Skybox & Trails:** Procedural Milky Way galactic band + multi-spectral starfield, toggleable orbit trails, floating body labels with live status tags (`SPAGHETTIFYING`, `ACCRETING`, `MOLTEN`, `RED GIANT`), live Cosmic Activity Ticker, and **Cinematic Camera Mode**.

### Black Holes & Relativistic Effects
- **Black Holes of Any Mass:** Easily spawn Primordial (3 $M_\odot$), Stellar (12 $M_\odot$), Intermediate (80 $M_\odot$), or Supermassive (350 $M_\odot$) black holes from the top `Black Holes` spawn category, or scale mass to infinity in the Inspector.
- **Gargantua-Style Black Hole Model:** Deep-black event horizon, razor-sharp inner photon ring, equatorial swirling Keplerian accretion disk with **relativistic Doppler beaming**, vertically bent **gravitationally lensed upper/lower Einstein disk halo**, screen-space lensing distortion, and **relativistic bipolar polar jets** that intensify during feeding.
- **Visible Spaghettification & Tidal Disruption (TDE):** Bodies entering the Roche/tidal zone visibly elongate into a teardrop toward the black hole via GPU vertex deformation, heat up with tidal magma glow, shed **orbiting spiral plasma filaments** (`TidalStreamFilament`) and dense hot gas particles spiraling directly into the accretion disk, and transfer mass to grow the black hole's event horizon (`Absorbed Mass` tracked live).
- **Hawking Evaporation & Mergers:** Optional Hawking radiation mass loss culminating in an evaporation flash, plus binary black hole inspirals and gravitational-wave merger ripples.

### Sandbox Tools, Weapons & Presets
- **Live Object Inspector & Infinite Sliders:** Live telemetry (status summary, surface gravity $g$, escape velocity km/s, temperature K/°C, density g/cm³, orbital period, semi-major axis, eccentricity, Schwarzschild radius, absorbed mass) plus **infinite self-recentering sliders** and `÷2`/`×2` quick buttons for Radius, Mass, Density, Luminosity, Temperature, Spin Rate, and Atmosphere—drag to max, release to re-center at the new anchor value, and keep scaling upward indefinitely.
- **Wide Multi-Item Spawn Tray:** Full-width horizontal bottom catalog showing 4–5 objects at once (`Black Holes`, `Planets`, `Stars`, `Exotics`, `Small Bodies`) with `Orbit Spawn`, Play/Pause, and tap-in-space placement.
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
- **True Scale vs. Sandbox Clear View:** At exact 1:1 km/AU scale, terrestrial planets are sub-pixel dots at solar-system zoom levels. The default *Sandbox Clear View* applies type-based visual scaling while retaining exact NASA/JPL values for all physics telemetry; you can toggle *True Astronomical Scale* in Settings.
- **N-Body Particle Scaling:** Asteroid-to-asteroid mutual $N^2$ forces are skipped for background belt clusters to maintain 60 FPS on mid-range mobile CPUs (they still experience full gravity from the Sun and all major planets/black holes).
- **Gravitational Lensing:** Uses a fast screen-space Einstein-ring shader pass rather than multi-bounce 3D Kerr geodesic raytracing to stay within mid-range mobile GPU thermal budgets.

## Build an APK

Use a desktop Android SDK with API 36.1 and Build-Tools 36.0.0, then run the included, checksum-pinned Gradle 9.3.1 wrapper:

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

No custom keystore or API keys are needed for debug builds. GitHub Actions runs the same checks on a clean checkout and uploads the verified APK and reports. See [BUILD_INSTRUCTIONS.md](BUILD_INSTRUCTIONS.md) for downloads, stable debug signing, release signing, and phone/Termux guidance.
