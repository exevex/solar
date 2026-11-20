# Observation-based body assets

Earth is now a geodetic WGS84 ellipsoid mesh (180×360 cells, 65,341 vertices) with sampled GEBCO land relief in kilometres and no terrain exaggeration. North-up UVs and outward triangle winding are shared by the globe and the 2-D preview. Other named bodies remain mapped UV spheres. True Scale uses physical radii; Sandbox Clear View deliberately enlarges display radii. Other-world textures decode at up to 1024 pixels, while Earth uses up to 4096 pixels and night lights up to 2048, bounded by the GPU's limit.

## Earth geometry and independent raster layers

The exact downloads, source/final SHA-256 values, transformations, credit and epoch are packaged in `app/src/main/assets/earth_sources.json`.

- Surface: NASA Terra MODIS Blue Marble Next Generation, September 2004, cloud-free base map (not the version with baked relief shadows). Reto Stöckli et al.; NASA Earth Observatory. Original 5400×2700 JPEG resized to 4096×2048. [Dataset description and projection](https://assets.science.nasa.gov/content/dam/science/esd/eo/content-feature/bluemarble/bmng.pdf).
- Shape: [NGA WGS84 ellipsoid](https://earth-info.nga.mil/index.php?action=wgs84&dir=wgs84), equatorial semi-axis 6378.137 km, inverse flattening 298.257223563. Vertices use geodetic latitude; east maps to -Z in the app's right-handed Y-up frame.
- Relief: [NASA GEBCO_08 topography visualization](https://science.nasa.gov/earth/earth-observatory/blue-marble-next-generation/topography-bathymetry-maps/), Jesse Allen / NASA Earth Observatory, GEBCO / British Oceanographic Data Centre. Downloaded 5400×2700 visualization is scaled 0–6400 m; resized to 2048×1024 and sampled onto the ellipsoid. This is quantized, clipped globe-scale relief, not a survey-resolution DEM. Orthometric/geoid heights are approximated as radial height; no bathymetric seafloor mesh is included.
- Night lights: NASA VIIRS Black Marble 2016 grayscale composite, resized to 2048×1024; static observed light distribution, not invented city noise or live lights.
- [NASA imagery usage guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/). No NASA endorsement is implied. JPEG decoding/resizing changes pixel values slightly; seasonal imagery and clipped elevation are not a current or perfectly complete reconstruction.
- Clouds remain explicitly procedural weather illustrations. Edited freezing, heating and impacts are simulation overlays and are not observed climate predictions. Ocean/ice percentages are area-weighted image classifications, not authoritative land-cover products.
- The legacy `surface_earth.jpg` checksum below is retained for provenance only; it is no longer bound to Earth. Named Earth and the spawn option `Earth (NASA / WGS84)` both use the same stable asset key, retained across rename and save/load.

## Sources

- Mercury, Venus, Earth, Mars, Phobos, Deimos, Io, Europa, Ganymede, Callisto, Enceladus and Triton: [NASA/JPL Solar System Simulator](https://space.jpl.nasa.gov/tmaps/).
- Sun, Moon, Saturn, Uranus and Neptune: [Solar System Scope](https://www.solarsystemscope.com/textures/), [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). Files are unchanged; some data gaps are filled by the source.
- Jupiter: [Hubble Global Map (2019)](https://science.nasa.gov/asset/hubble/jupiter-global-map-2019/), NASA, ESA, A. Simon (GSFC), M.H. Wong (UC Berkeley).
- Pluto: [New Horizons color mosaic](https://science.nasa.gov/resource/pluto-global-color-map), NASA/JHUAPL/SwRI.
- Titan: [Cassini global map](https://science.nasa.gov/resource/titan-global-map-june-2015/), NASA/JPL-Caltech/SSI.
- Initial heliocentric state vectors: NASA/JPL Horizons DE441 at JD 2451545.0 TDB (J2000.0), https://ssd.jpl.nasa.gov/horizons/

The JPL site notes that giant-planet atmospheric maps are representative rather than suitable for scientific analysis; their clouds evolve rapidly. The physics state is independent of the visual map and uses measured radii/masses plus the DE441 initial vectors.

## Asset checksums

SHA-256 checksums are recorded so downloaded binary assets can be audited or replaced reproducibly.

```text
9659f85b76d5cb90cb709b32f3282f3ca94199be4d8b3b159d7d373a1887551c  surface_callisto.jpg
e8902c5b34e634c5146e5d8d2bf352bb5f45181c715ed15644edb5f009826ed6  surface_deimos.jpg
c88ffd7218202eec1d7f4e57f756b2fb4a9e080ad56f073f0f5495fa69c0ca6a  surface_earth.jpg
224b4510a75dc6554cfb0802507f9f1bb959c52848b8ec9286d4253d417d6dd2  surface_enceladus.jpg
03164d293c46b3eff16678c20dfb73337db8a404e2d83f057bc320b95484b54f  surface_europa.jpg
626302a877528d7eab74f4229f6c7249fffc91be8e28e8edc040433aca98cc6d  surface_ganymede.jpg
71c574f74555552fa4bec70931e954c6139d3489df825c9edf40213d49cba132  surface_io.jpg
0def4093a1d98dfa00fc9c0d0a30207973f9705dd66a6ef00cd70faddb7f7109  surface_jupiter.png
0f9a3faf586ac857623660504e63dd3e2b8358203d8d0c32c27c5fe1585548c6  surface_mars.jpg
4de739ff535e2537a62d4f5a01ac06a0a59b67a85ad8e5910c3357afdf76326f  surface_mercury.jpg
2764ba6535ea0481a062846ee033cc7a909dae05b31a8fd13f3e98f3a7fd92bd  surface_moon.jpg
cb42ea82709741d28b0af44d8b283cbc6dbd0c521a7f0e1e1e010ade00977df6  surface_neptune.jpg
84eb21c08626a0558d1e88ccb666bd1e914606146423f9ebcf6c09953d7543af  surface_phobos.jpg
d63da5ee9eab8d0b98ebef3c0cdc3a92676d7c9b247b2383294fb5da5c67a71a  surface_pluto.jpg
54a900ca9bf7ab62e70f862852759abdf342e6d6436a95a2fe9ebdb6bcd3bbac  surface_saturn.jpg
ff0f076ba65e03b5ab518451bc96699325be38e3ccbdd5869ee1c00f3a0c8816  surface_sun.jpg
7215317ba12db79cd9c249912e42d4664ffe0af0f31b3e53e69b257c112b668a  surface_titan.jpg
45f107996448c2236ede95624bfb672b56f0db843f084ed61a197454050b3214  surface_triton.jpg
d15239d46f82d3ea13d2b260b5b29b2a382f42f2916dae0694d0387b1204a09d  surface_uranus.jpg
096477d63cfd2abf7224f06e6e62f4b56829a25ed917227d14763cad02172625  surface_venus.jpg
```
