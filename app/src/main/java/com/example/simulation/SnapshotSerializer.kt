package com.example.simulation

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class SavedSlotInfo(
    val fileName: String,
    val title: String,
    val presetId: String,
    val bodyCount: Int,
    val elapsedYears: Double,
    val savedAtEpochMs: Long
)

/**
 * Zero-dependency JSON serializer for complete SimulationSnapshot persistence in app internal storage.
 */
object SnapshotSerializer {

    fun toJsonString(snapshot: SimulationSnapshot): String {
        val root = JSONObject()
        root.put("name", snapshot.name)
        root.put("presetId", snapshot.presetId)
        root.put("elapsedYears", snapshot.elapsedYears)
        root.put("savedAtEpochMs", snapshot.savedAtEpochMs)

        val cfg = JSONObject().apply {
            put("isPaused", snapshot.config.isPaused)
            put("timeScaleYearsPerSec", snapshot.config.timeScaleYearsPerSec)
            put("integrator", snapshot.config.integrator.name)
            put("hawkingEvaporation", snapshot.config.hawkingEvaporation)
            put("stellarEvolutionEnabled", snapshot.config.stellarEvolutionEnabled)
            put("stellarEvolutionRateGyrPerSec", snapshot.config.stellarEvolutionRateGyrPerSec)
            put("trueScaleMode", snapshot.config.trueScaleMode)
            put("showOrbits", snapshot.config.showOrbits)
            put("showLabels", snapshot.config.showLabels)
            put("showHabitableZone", snapshot.config.showHabitableZone)
            put("showEventText", snapshot.config.showEventText)
        }
        root.put("config", cfg)

        val bodiesArr = JSONArray()
        for (b in snapshot.bodies) {
            val bj = JSONObject().apply {
                put("id", b.id)
                put("name", b.name)
                put("type", b.type.name)
                put("archetype", b.archetype.name)
                b.surfaceAssetKey?.let { put("surfaceAssetKey", it) }
                put("spectralClass", b.spectralClass.name)
                put("px", b.pos.x); put("py", b.pos.y); put("pz", b.pos.z)
                put("vx", b.vel.x); put("vy", b.vel.y); put("vz", b.vel.z)
                put("massSolar", b.massSolar)
                put("radiusKm", b.radiusKm)
                put("temperatureK", b.temperatureK)
                put("luminositySolar", b.luminositySolar)
                put("rotationPeriodHours", b.rotationPeriodHours)
                put("axialTiltDeg", b.axialTiltDeg)
                put("spinAngleDeg", b.spinAngleDeg)
                put("atmosphereDensity", b.atmosphereDensity.toDouble())
                put("atmoR", b.atmosphereColor.first.toDouble())
                put("atmoG", b.atmosphereColor.second.toDouble())
                put("atmoB", b.atmosphereColor.third.toDouble())
                put("albR", b.albedoColor.first.toDouble())
                put("albG", b.albedoColor.second.toDouble())
                put("albB", b.albedoColor.third.toDouble())
                put("seed", b.seed.toDouble())
                put("hasClouds", b.hasClouds)
                put("hasCityLights", b.hasCityLights)
                put("crustHeat", b.crustHeat.toDouble())
                put("ageGyr", b.ageGyr)
                put("initialMassSolar", b.initialMassSolar)
                put("evolutionStage", b.evolutionStage.name)
                put("accretionActivity", b.accretionActivity.toDouble())
                put("absorbedMassSolar", b.absorbedMassSolar)
                put("diskNormalX", b.diskPlaneNormal.x)
                put("diskNormalY", b.diskPlaneNormal.y)
                put("diskNormalZ", b.diskPlaneNormal.z)
                val dpArr = JSONArray()
                for (bin in b.diskProfile) dpArr.put(bin.toDouble())
                put("diskProfile", dpArr)
                put("isBackgroundCluster", b.isBackgroundCluster)
                if (b.parentBodyId != null) put("parentBodyId", b.parentBodyId)

                b.ringSystem?.let { r ->
                    put("ring", JSONObject().apply {
                        put("inner", r.innerRadiusRatio.toDouble())
                        put("outer", r.outerRadiusRatio.toDouble())
                        put("r", r.colorR.toDouble())
                        put("g", r.colorG.toDouble())
                        put("b", r.colorB.toDouble())
                        put("opacity", r.opacity.toDouble())
                        put("cassini", r.hasCassiniGap)
                    })
                }

                if (b.craters.isNotEmpty()) {
                    val ca = JSONArray()
                    for (c in b.craters) {
                        ca.put(JSONObject().apply {
                            put("nx", c.nx.toDouble()); put("ny", c.ny.toDouble()); put("nz", c.nz.toDouble())
                            put("rad", c.radiusRad.toDouble()); put("heat", c.heat.toDouble())
                        })
                    }
                    put("craters", ca)
                }
            }
            bodiesArr.put(bj)
        }
        root.put("bodies", bodiesArr)
        return root.toString()
    }

    fun fromJsonString(json: String): SimulationSnapshot {
        val root = JSONObject(json)
        val cfgObj = root.optJSONObject("config")
        val config = if (cfgObj != null) {
            SimulationConfig(
                isPaused = cfgObj.optBoolean("isPaused", false),
                timeScaleYearsPerSec = cfgObj.optDouble("timeScaleYearsPerSec", 0.04),
                integrator = runCatching { IntegratorMode.valueOf(cfgObj.optString("integrator")) }
                    .getOrDefault(IntegratorMode.VELOCITY_VERLET),
                hawkingEvaporation = cfgObj.optBoolean("hawkingEvaporation", false),
                stellarEvolutionEnabled = cfgObj.optBoolean("stellarEvolutionEnabled", false),
                stellarEvolutionRateGyrPerSec = cfgObj.optDouble("stellarEvolutionRateGyrPerSec", 0.0),
                trueScaleMode = cfgObj.optBoolean("trueScaleMode", false),
                showOrbits = cfgObj.optBoolean("showOrbits", true),
                showLabels = cfgObj.optBoolean("showLabels", true),
                showHabitableZone = cfgObj.optBoolean("showHabitableZone", false),
                showEventText = cfgObj.optBoolean("showEventText", true)
            )
        } else SimulationConfig()

        val bodiesArr = root.getJSONArray("bodies")
        val bodies = ArrayList<CelestialBody>(bodiesArr.length())
        for (i in 0 until bodiesArr.length()) {
            val bj = bodiesArr.getJSONObject(i)
            val ringObj = bj.optJSONObject("ring")
            val ring = ringObj?.let {
                RingSystem(
                    innerRadiusRatio = it.optDouble("inner", 1.35).toFloat(),
                    outerRadiusRatio = it.optDouble("outer", 2.4).toFloat(),
                    colorR = it.optDouble("r", 0.85).toFloat(),
                    colorG = it.optDouble("g", 0.78).toFloat(),
                    colorB = it.optDouble("b", 0.65).toFloat(),
                    opacity = it.optDouble("opacity", 0.85).toFloat(),
                    hasCassiniGap = it.optBoolean("cassini", true)
                )
            }
            val cratersArr = bj.optJSONArray("craters")
            val craters = if (cratersArr != null) {
                List(cratersArr.length()) { idx ->
                    val cj = cratersArr.getJSONObject(idx)
                    CraterMark(
                        nx = cj.getDouble("nx").toFloat(),
                        ny = cj.getDouble("ny").toFloat(),
                        nz = cj.getDouble("nz").toFloat(),
                        radiusRad = cj.getDouble("rad").toFloat(),
                        heat = cj.getDouble("heat").toFloat()
                    )
                }
            } else emptyList()

            val bodyType = BodyType.valueOf(bj.getString("type"))
            val defaultArchetype = when (bodyType) {
                BodyType.EARTH_LIKE -> BodyArchetype.GENERIC_EARTH
                BodyType.GAS_GIANT -> BodyArchetype.GENERIC_GAS
                BodyType.ICE_GIANT -> BodyArchetype.GENERIC_ICE
                BodyType.COMET -> BodyArchetype.COMET
                else -> BodyArchetype.GENERIC_ROCK
            }
            bodies.add(
                CelestialBody(
                    id = bj.getLong("id"),
                    name = bj.getString("name"),
                    type = bodyType,
                    archetype = runCatching { BodyArchetype.valueOf(bj.optString("archetype")) }
                        .getOrDefault(defaultArchetype),
                    spectralClass = runCatching { SpectralClass.valueOf(bj.optString("spectralClass")) }
                        .getOrDefault(SpectralClass.NONE),
                    pos = Vec3d(bj.getDouble("px"), bj.getDouble("py"), bj.getDouble("pz")),
                    vel = Vec3d(bj.getDouble("vx"), bj.getDouble("vy"), bj.getDouble("vz")),
                    massSolar = bj.getDouble("massSolar"),
                    radiusKm = bj.getDouble("radiusKm"),
                    temperatureK = bj.getDouble("temperatureK"),
                    luminositySolar = bj.optDouble("luminositySolar", 0.0),
                    rotationPeriodHours = bj.optDouble("rotationPeriodHours", 24.0),
                    axialTiltDeg = bj.optDouble("axialTiltDeg", 0.0),
                    spinAngleDeg = bj.optDouble("spinAngleDeg", 0.0),
                    atmosphereDensity = bj.optDouble("atmosphereDensity", 0.0).toFloat(),
                    atmosphereColor = Triple(
                        bj.optDouble("atmoR", 0.35).toFloat(),
                        bj.optDouble("atmoG", 0.65).toFloat(),
                        bj.optDouble("atmoB", 1.0).toFloat()
                    ),
                    albedoColor = Triple(
                        bj.optDouble("albR", 0.7).toFloat(),
                        bj.optDouble("albG", 0.7).toFloat(),
                        bj.optDouble("albB", 0.7).toFloat()
                    ),
                    seed = bj.optDouble("seed", 1.0).toFloat(),
                    hasClouds = bj.optBoolean("hasClouds", false),
                    hasCityLights = bj.optBoolean("hasCityLights", false),
                    ringSystem = ring,
                    craters = craters,
                    crustHeat = bj.optDouble("crustHeat", 0.0).toFloat(),
                    ageGyr = bj.optDouble("ageGyr", 4.6),
                    initialMassSolar = bj.optDouble("initialMassSolar", bj.getDouble("massSolar")),
                    evolutionStage = runCatching { EvolutionStage.valueOf(bj.optString("evolutionStage")) }
                        .getOrDefault(EvolutionStage.NONE),
                    parentBodyId = if (bj.has("parentBodyId")) bj.getLong("parentBodyId") else null,
                    accretionActivity = bj.optDouble("accretionActivity", 0.0).toFloat(),
                    absorbedMassSolar = bj.optDouble("absorbedMassSolar", 0.0),
                    diskPlaneNormal = Vec3d(
                        bj.optDouble("diskNormalX", 0.24),
                        bj.optDouble("diskNormalY", 0.97),
                        bj.optDouble("diskNormalZ", 0.0)
                    ).normalized(),
                    diskProfile = bj.optJSONArray("diskProfile")?.let { arr ->
                        List(8) { idx ->
                            if (idx < arr.length()) arr.optDouble(idx, 0.0).toFloat() else 0f
                        }
                    } ?: listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
                    isBackgroundCluster = bj.optBoolean("isBackgroundCluster", false),
                    surfaceAssetKey = if (bj.has("surfaceAssetKey") && !bj.isNull("surfaceAssetKey")) {
                        bj.getString("surfaceAssetKey")
                    } else if (bj.optString("archetype") == "EARTH" || bj.getString("name").equals("Earth", true)) "earth" else null
                )
            )
        }

        return SimulationSnapshot(
            name = root.getString("name"),
            presetId = root.optString("presetId", "custom"),
            elapsedYears = root.optDouble("elapsedYears", 0.0),
            bodies = bodies,
            config = config,
            savedAtEpochMs = root.optLong("savedAtEpochMs", System.currentTimeMillis())
        )
    }
}

class SaveLoadRepository(private val filesDir: File) {
    private val savesDir = File(filesDir, "solar_saves").apply { mkdirs() }

    fun saveSnapshot(snapshot: SimulationSnapshot): SavedSlotInfo {
        val safeName = snapshot.name.replace(Regex("[^A-Za-z0-9_\\-]"), "_").take(28).ifEmpty { "sim" }
        val fileName = "${safeName}_${System.currentTimeMillis()}.json"
        val file = File(savesDir, fileName)
        file.writeText(SnapshotSerializer.toJsonString(snapshot))
        return SavedSlotInfo(
            fileName = fileName,
            title = snapshot.name,
            presetId = snapshot.presetId,
            bodyCount = snapshot.bodies.size,
            elapsedYears = snapshot.elapsedYears,
            savedAtEpochMs = snapshot.savedAtEpochMs
        )
    }

    fun listSaves(): List<SavedSlotInfo> {
        val files = savesDir.listFiles { f -> f.extension == "json" } ?: return emptyList()
        return files.mapNotNull { file ->
            runCatching {
                val snap = SnapshotSerializer.fromJsonString(file.readText())
                SavedSlotInfo(
                    fileName = file.name,
                    title = snap.name,
                    presetId = snap.presetId,
                    bodyCount = snap.bodies.size,
                    elapsedYears = snap.elapsedYears,
                    savedAtEpochMs = snap.savedAtEpochMs
                )
            }.getOrNull()
        }.sortedByDescending { it.savedAtEpochMs }
    }

    fun loadSave(fileName: String): SimulationSnapshot? {
        val file = File(savesDir, fileName)
        if (!file.exists()) return null
        return runCatching { SnapshotSerializer.fromJsonString(file.readText()) }.getOrNull()
    }

    fun deleteSave(fileName: String) {
        File(savesDir, fileName).delete()
    }
}
