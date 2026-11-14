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
- **Procedural 3D Spherical PBR Planets:** Zero-seam 3D FBM terrain, normal relief perturbation, ocean specular glint, polar ice caps, Earth night-side city lights, animated swirling cloud layers, Jupiter/Saturn zonal jet bands & Great Red Spot, and Mars/Mercury craters.
- **Atmosphere, Shadows & Eclipses:** Rayleigh/Mie rim scattering with orange-red sunset terminators, analytical ray-sphere eclipse shadows across planets/moons, and mutual Saturn ring/planet shadows.
- **HDR Stars & Post-Processing:** Planckian blackbody star colors (O, B, A, F, G, K, M), animated solar granulation & sunspots, limb darkening, additive plasma corona & pulsar beams, multi-pass separable Gaussian **Bloom**, and ACES Filmic tone mapping.
- **Skybox & Trails:** Procedural Milky Way galactic band + multi-spectral starfield, toggleable orbit trails, floating body labels, and **Cinematic Camera Mode**.

### Black Holes & Relativistic Effects
- **Black Holes of Any Mass:** Spawn Stellar (12 $M_\odot$), Intermediate (80 $M_\odot$), or Supermassive (300+ $M_\odot$) black holes, or edit mass live.
- **Gravitational Lensing Shader:** Screen-space Einstein-ring deflection warping background stars and planets around every visible black hole.
- **Event Horizon, Photon Ring & Doppler Accretion Disk:** Pitch-black horizon core, HDR photon sphere, and swirling Keplerian accretion disk with **relativistic Doppler beaming** (blue-bright approaching limb, dim redshifted receding limb).
- **Tidal Disruption Events (TDE):** Bodies entering the tidal limit are shredded into spiraling plasma streams, trigger relativistic polar jets, and grow the black hole's mass and Schwarzschild radius.
- **Hawking Evaporation & Mergers:** Optional Hawking radiation mass loss culminating in an evaporation flash, plus binary black hole inspirals and gravitational-wave merger ripples.

### Sandbox Tools, Weapons & Presets
- **Live Object Inspector & Editor:** Live telemetry (surface gravity $g$, escape velocity km/s, temperature K/°C, density g/cm³, orbital period, semi-major axis, eccentricity, Schwarzschild radius) plus live sliders for Radius, Mass, Density, Luminosity, Temperature, Spin, Axial Tilt, Atmosphere, Orbit Speed ±15%, and Surface Tint.
- **Spawn Catalog:** Planets, Moons, Stars (O/B/A/F/G/K/M + Red Giant), White Dwarfs, Neutron Stars (Pulsars), Comets, Asteroids, and Black Holes.
- **Weapons & Cosmic Events:** Asteroid Strike, Thermonuclear Strike, Planet Splitter, Trigger Supernova, Gamma-Ray Burst, and Rogue Star Flyby.
- **7 System Presets:** Solar System, Alpha Centauri, Binary Star System, TRAPPIST-1, Galaxy Collision, Sun → Red Giant Evolution, and Binary Black Holes.
- **Stellar Evolution:** Main Sequence → Red Giant expansion → Supernova (Neutron Star / Black Hole) or Planetary Nebula → White Dwarf.

### Mobile UX & Optimization
- **Touch Controls:**
  - **1-finger drag:** Orbit camera around selected focus body
  - **2-finger pinch:** Logarithmic zoom from planetary crust out to 180 AU
  - **Tap:** 3D ray-sphere body selection (or tap-to-place in Spawn mode)
  - **Long-press + drag:** Grab and fling existing bodies
  - **Slingshot mode:** Touch and drag back to aim launch velocity with a real-time **predicted trajectory arc**
- **Performance:** Dedicated background physics thread, 3-tier sphere mesh LODs, GPU-instanced (`glDrawElementsInstanced`) asteroid belt & star clusters, Quality Settings (`Low`, `Medium`, `High`, `Ultra`), **Auto-Detect Device**, and **Battery Saver (30 FPS)** mode.

---

## Known Limits
- **True Scale vs. Sandbox Clear View:** At exact 1:1 km/AU scale, terrestrial planets are sub-pixel dots at solar-system zoom levels. The default *Sandbox Clear View* applies type-based visual scaling while retaining exact NASA/JPL values for all physics telemetry; you can toggle *True Astronomical Scale* in Settings.
- **N-Body Particle Scaling:** Asteroid-to-asteroid mutual $N^2$ forces are skipped for background belt clusters to maintain 60 FPS on mid-range mobile CPUs (they still experience full gravity from the Sun and all major planets/black holes).
- **Gravitational Lensing:** Uses a fast screen-space Einstein-ring shader pass rather than multi-bounce 3D Kerr geodesic raytracing to stay within mid-range mobile GPU thermal budgets.
