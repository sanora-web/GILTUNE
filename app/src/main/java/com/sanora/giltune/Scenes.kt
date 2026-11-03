package com.sanora.giltune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/*
 * Scene engine. All thresholds, OSM mapping and scene priority come from
 * assets/scene_rules.json (shared with the GILTUNE web version; schema: docs/scene_rules.schema_ko.md).
 * The functions below mirror web/js/engine.js 1:1 - SceneEngineEquivalenceTest checks it.
 * Author: SANORA WEB
 */

/** Road class names used in scene_rules.json. Labels come from rules.road.labels. */
enum class RoadType {
    MOTORWAY, URBAN_EXPRESSWAY, DOWNTOWN, ALLEY, BRIDGE, TUNNEL, UNKNOWN;
    val label: String get() = SceneRules.currentOrNull()?.roadLabels?.get(name) ?: name
    companion object { fun of(s: String?) = values().firstOrNull { it.name == s } ?: UNKNOWN }
}

/** [source] is a key of rules.road.sourceLabels: overpass / nominatim / speed / demo / none. */
data class RoadInfo(val type: RoadType, val name: String?, val coastal: Boolean, val source: String, val river: Boolean = false,
                    /** OSM class before tunnel/bridge (MOTORWAY...), elevated = "bridge" tag that is not a real crossing. */
                    val cls: String? = null, val elevated: Boolean = false, val sustained: Boolean = false,
                    /** Judgment evidence: raw OSM highway class, OSM way id, built-in coast/river data used. */
                    val highway: String? = null, val wayId: Long? = null, val geoHit: Boolean = false) {
    val sourceLabel: String get() = SceneRules.currentOrNull()?.sourceLabels?.get(source) ?: source
    companion object { val UNKNOWN = RoadInfo(RoadType.UNKNOWN, null, false, "none") }
}

/** Speed band rule (scene_rules road.speedBands). */
data class SpeedBand(val maxMps: Double, val roadType: String)
/** road.speedBands.fastSustain: 60 km/h+ for 30 s -> expressway candidate. */
data class FastSustain(val mps: Double, val sec: Double, val roadType: String)
/** rules.geo (built-in coast / river data). */
data class GeoRules(val cellDeg: Double, val bridgeNeedsWater: List<String>, val bridgeWaterMarginM: Double,
                    val riverRadiusM: Double, val coastRadiusM: Double, val bridgeNamePattern: String)

typealias OsmTags = Map<String, String>

/** A scene as shown to the user (first entry with that id in rules.scenes). */
data class Scene(val id: String, val label: String, val caption: String, val local: String, val keywords: List<String>) {
    val name: String get() = id
    companion object {
        fun all(): List<Scene> = SceneRules.current.sceneList
        fun byId(id: String?): Scene? = SceneRules.current.sceneList.firstOrNull { it.id == id }
        val CRUISE get() = byId("CRUISE") ?: all().last()
        val BRIDGE get() = byId("BRIDGE") ?: CRUISE
        val TUNNEL get() = byId("TUNNEL") ?: CRUISE
    }
}

data class WhenRule(val roadTypes: List<String>?, val flags: Map<String, Boolean>, val minSpeedMps: Double?, val maxSpeedMps: Double?)
data class SceneRule(val scene: Scene, val whenRule: WhenRule)

class SceneRules(
    val version: Int,
    val highwayMap: Map<String, String>,
    val importance: List<String>,
    val slowSpeedMps: Double,
    val roadLabels: Map<String, String>,
    val speedBands: List<SpeedBand>, val fastSustain: FastSustain?, val geo: GeoRules, val holidays: Set<String>, val speedBandStaleMs: Long, val minSpeedForBandMps: Double, val sourceLabels: Map<String, String>,
    val roadRadiusM: Int, val riverRadiusM: Int, val coastRadiusM: Int,
    val osmEndpoints: List<String>, val osmTimeoutMs: Long, val nominatimEndpoint: String, val nominatimZoom: Int, val nominatimMinIntervalMs: Long,
    val osmMinMoveM: Double, val osmMinIntervalMs: Long, val osmMaxAgeMs: Long,
    val cacheGridDeg: Double, val cacheMax: Int, val backoffMs: Long,
    val weatherEndpoint: String, val weatherMinMoveM: Double, val weatherMinIntervalMs: Long, val rainCodes: Set<Int>,
    val highwayFallbackMps: Double, val drivingMps: Double,
    val maxAccuracyM: Double, val maxPlausibleMps: Double, val speedHoldMs: Long,
    val minHoldMs: Long, val confirmCount: Int, val confirmMs: Long, val urgentConfirmMs: Long, val urgentScenes: Set<String>,
    val genres: Map<String, String>, val genreLabels: Map<String, String>,
    val taste: TasteRules, val localTitles: Map<String, String>,
    val rules: List<SceneRule>,
    /** Genre name text for the 0-result fallback search (e.g. 발라드, K-pop). */
    val genreText: Map<String, String> = emptyMap(),
    /** Per-genre sources for the genre pool (playlist queries, keywords, representative artists). */
    val genreSources: Map<String, GenreSource> = emptyMap(),
    val genrePool: GenrePoolConfig = GenrePoolConfig(),
    /** Title words per scene id + night/morning/RAIN (Korean and English) for title matching. */
    val titleWords: Map<String, List<String>> = emptyMap()
) {
    /** Unique scenes in priority order (HIGHWAY appears twice in the rules). */
    val sceneList: List<Scene> = rules.map { it.scene }.distinctBy { it.id }

    companion object {
        const val ASSET = "scene_rules.json"
        @Volatile private var loaded: SceneRules? = null
        val current: SceneRules get() = loaded ?: error("scene_rules.json not loaded")
        fun currentOrNull() = loaded
        fun ensure(context: Context): SceneRules = loaded ?: synchronized(this) {
            loaded ?: parse(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }).also { loaded = it }
        }
        fun install(r: SceneRules) { loaded = r }

        private fun JSONObject.strMap(): Map<String, String> = keys().asSequence().associateWith { getString(it) }
        internal fun JSONObject.strMapPublic(): Map<String, String> = strMap()
        internal fun JSONArray.strings() = (0 until length()).map { getString(it) }

        fun parse(text: String): SceneRules {
            val j = JSONObject(text)
            val road = j.getJSONObject("road"); val prox = j.getJSONObject("proximity"); val osm = j.getJSONObject("osm")
            val w = j.getJSONObject("weather"); val sp = j.getJSONObject("speed")
            val st = j.getJSONObject("stabilizer"); val arr = j.getJSONArray("scenes")
            val rules = (0 until arr.length()).map { i ->
                val s = arr.getJSONObject(i); val wh = s.optJSONObject("when") ?: JSONObject()
                val flags = listOf("rain", "coastal", "river").filter { wh.has(it) }.associateWith { wh.getBoolean(it) }
                SceneRule(Scene(s.getString("id"), s.getString("label"), s.optString("caption"), s.getString("local"), s.getJSONArray("keywords").strings()),
                    WhenRule(wh.optJSONArray("roadTypes")?.strings(), flags,
                        if (wh.has("minSpeedMps")) wh.getDouble("minSpeedMps") else null,
                        if (wh.has("maxSpeedMps")) wh.getDouble("maxSpeedMps") else null))
            }
            val rc = w.getJSONArray("rainCodes")
            return SceneRules(j.getInt("version"), road.getJSONObject("highwayMap").strMap(), road.getJSONArray("importance").strings(),
                road.getDouble("slowSpeedMps"), road.getJSONObject("labels").strMap(),
                road.getJSONObject("speedBands").getJSONArray("bands").let { b -> (0 until b.length()).map { SpeedBand(b.getJSONObject(it).getDouble("maxMps"), b.getJSONObject(it).getString("roadType")) } },
                road.getJSONObject("speedBands").optJSONObject("fastSustain")?.let { FastSustain(it.getDouble("mps"), it.getDouble("sec"), it.getString("roadType")) },
                j.optJSONObject("geo")?.let { g -> GeoRules(g.optDouble("cellDeg", 0.02), g.optJSONArray("bridgeNeedsWater")?.strings() ?: emptyList(),
                    g.optDouble("bridgeWaterMarginM", 0.0), g.optDouble("riverRadiusM", 0.0), g.optDouble("coastRadiusM", 0.0), g.optString("bridgeNamePattern", "")) }
                    ?: GeoRules(0.02, emptyList(), 0.0, 0.0, 0.0, ""),
                j.optJSONObject("holidays")?.optJSONArray("dates")?.strings()?.toSet() ?: emptySet(),
                road.getJSONObject("speedBands").getLong("staleMs"), road.getJSONObject("speedBands").getDouble("minSpeedForBandMps"),
                road.getJSONObject("sourceLabels").strMap(),
                prox.getInt("roadRadiusM"), prox.getInt("riverRadiusM"), prox.getInt("coastRadiusM"),
                osm.getJSONArray("endpoints").strings(), osm.getLong("timeoutMs"),
                osm.getJSONObject("nominatim").getString("endpoint"), osm.getJSONObject("nominatim").getInt("zoom"), osm.getJSONObject("nominatim").getLong("minIntervalMs"),
                osm.getDouble("minMoveM"), osm.getLong("minIntervalMs"), osm.getLong("maxAgeMs"),
                osm.getDouble("cacheGridDeg"), osm.getInt("cacheMax"), osm.getLong("backoffMs"),
                w.getString("endpoint"), w.getDouble("minMoveM"), w.getLong("minIntervalMs"), (0 until rc.length()).map { rc.getInt(it) }.toSet(),
                sp.getDouble("highwayFallbackMps"), sp.getDouble("drivingMps"),
                sp.getDouble("maxAccuracyM"), sp.getDouble("maxPlausibleMps"), sp.getLong("holdMs"),
                st.getLong("minHoldMs"), st.getInt("confirmCount"), st.optLong("confirmMs", 0), st.optLong("urgentConfirmMs", 0),
                st.getJSONArray("urgentScenes").strings().toSet(), j.getJSONObject("genres").strMap(), j.getJSONObject("genreLabels").strMap(),
                TasteRules.parse(j.getJSONObject("taste")), j.optJSONObject("localTitles")?.strMap() ?: emptyMap(), rules,
                j.optJSONObject("genreText")?.strMap() ?: emptyMap(),
                j.optJSONObject("genreSources")?.let { gs -> gs.keys().asSequence().associateWith { GenreSource.parse(gs.getJSONObject(it)) } } ?: emptyMap(),
                j.optJSONObject("genrePool")?.let { GenrePoolConfig.parse(it) } ?: GenrePoolConfig(),
                j.optJSONObject("titleWords")?.let { tw -> tw.keys().asSequence().associateWith { k -> tw.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) } } } } ?: emptyMap())
        }
    }
}

/** Mirrors engine.js classifyOsm. */
object RoadClassifier {
    private fun yes(v: String?) = v != null && v != "no"
    fun classify(elements: List<OsmTags>, speedMps: Double?, rules: SceneRules = SceneRules.current, source: String = "overpass"): RoadInfo {
        var river = false; var coastal = false
        val roads = ArrayList<Pair<String, OsmTags>>()
        for (t in elements) {
            if (t["natural"] == "coastline") coastal = true
            if (t["waterway"] == "river" || t["waterway"] == "riverbank" || (t["natural"] == "water" && t["water"] == "river")) river = true
            val hw = t["highway"]; val type = hw?.let { rules.highwayMap[it] }
            if (type != null) roads.add(type to t)
        }
        if (roads.isEmpty()) return RoadInfo(RoadType.UNKNOWN, null, coastal, source, river)
        var c: List<Pair<String, OsmTags>> = roads.sortedBy { rules.importance.indexOf(it.first) } // stable, like JS sort
        val slow = speedMps != null && speedMps < rules.slowSpeedMps
        if (slow && c.size > 1 && (c[0].first == "MOTORWAY" || c[0].first == "URBAN_EXPRESSWAY")) {
            val local = c.filter { it.first == "DOWNTOWN" || it.first == "ALLEY" }
            if (local.isNotEmpty()) c = local
        }
        val top = c.filter { it.first == c[0].first }
        val pick = top.firstOrNull { yes(it.second["tunnel"]) } ?: top.firstOrNull { yes(it.second["bridge"]) } ?: top[0]
        val t = pick.second
        val type = if (yes(t["tunnel"])) "TUNNEL" else if (yes(t["bridge"])) "BRIDGE" else pick.first
        return RoadInfo(RoadType.of(type), t["name:ko"] ?: t["name"] ?: t["ref"], coastal, source, river, cls = pick.first,
            highway = t["highway"], wayId = t["_id"]?.toLongOrNull())
    }
}

data class SceneInput(val roadType: String, val rain: Boolean, val coastal: Boolean, val river: Boolean, val hour: Int, val speedMps: Double?)

/** Mirrors engine.js pickScene: first matching entry of rules.scenes wins. Time of day is NOT a scene input (v3). */
object ScenePicker {
    fun pick(ctx: SceneInput, r: SceneRules = SceneRules.current): Scene {
        val f = mapOf("rain" to ctx.rain, "coastal" to ctx.coastal, "river" to ctx.river)
        for (rule in r.rules) {
            val w = rule.whenRule
            if (w.roadTypes != null && ctx.roadType !in w.roadTypes) continue
            var ok = w.flags.all { (k, v) -> f[k] == v }
            if (w.minSpeedMps != null && !(ctx.speedMps != null && ctx.speedMps >= w.minSpeedMps)) ok = false
            if (w.maxSpeedMps != null && !(ctx.speedMps != null && ctx.speedMps <= w.maxSpeedMps)) ok = false
            if (ok) return r.sceneList.first { it.id == rule.scene.id }
        }
        return r.rules.last().scene
    }
}

/** Mirrors engine.js Stabilizer: N proposals + confirm time + min hold; urgent scenes skip the hold. */
class SceneStabilizer(private val r: SceneRules = SceneRules.current) {
    data class Result(val id: String, val changed: Boolean)
    var current: String? = null; private set
    private var cand: String? = null; private var count = 0; private var candAt = 0L
    private var at = Long.MIN_VALUE / 2
    fun reset() { current = null; cand = null; count = 0; at = Long.MIN_VALUE / 2 }

    /** Mirrors engine.js Stabilizer.status: what is still needed before the next change (small on-screen line). */
    data class Status(val current: String?, val cand: String?, val count: Int, val confirmCount: Int, val urgent: Boolean,
                      val confirmLeftMs: Long, val holdLeftMs: Long)
    fun status(now: Long): Status {
        val holdLeft = maxOf(0L, r.minHoldMs - (now - at))
        val c = cand ?: return Status(current, null, 0, r.confirmCount, false, 0, holdLeft)
        val urgent = c in r.urgentScenes
        val need = if (urgent) r.urgentConfirmMs else r.confirmMs
        return Status(current, c, count, r.confirmCount, urgent, maxOf(0L, need - (now - candAt)), if (urgent) 0 else holdLeft)
    }
    fun propose(id: String, now: Long): Result {
        if (current == null) { current = id; at = now; return Result(id, true) }
        if (id == current) { cand = null; count = 0; return Result(id, false) }
        if (id == cand) count++ else { cand = id; count = 1; candAt = now }
        val urgent = id in r.urgentScenes
        val need = if (urgent) r.urgentConfirmMs else r.confirmMs
        if (count >= r.confirmCount && now - candAt >= need && (urgent || now - at >= r.minHoldMs)) {
            current = id; at = now; cand = null; count = 0; return Result(id, true)
        }
        return Result(current!!, false)
    }
}

/** Mirrors engine.js speedRoad / resolveRoad: road type from speed when map lookups failed or are stale. */
object SpeedRoad {
    fun speedRoad(speedMps: Double?, r: SceneRules = SceneRules.current): RoadInfo {
        if (speedMps == null || speedMps < r.minSpeedForBandMps) return RoadInfo.UNKNOWN
        val b = r.speedBands.firstOrNull { speedMps <= it.maxMps } ?: r.speedBands.last()
        return RoadInfo(RoadType.of(b.roadType), null, false, "speed")
    }
    fun resolve(mapRoad: RoadInfo?, mapAt: Long, now: Long, speedMps: Double?, r: SceneRules = SceneRules.current, fastSec: Double = 0.0): RoadInfo {
        if (mapRoad != null && mapRoad.type != RoadType.UNKNOWN && now - mapAt <= r.speedBandStaleMs) return mapRoad
        val x = speedRoad(speedMps, r); val fs = r.fastSustain
        // 60 km/h+ sustained -> expressway candidate
        if (fs != null && x.source == "speed" && fastSec >= fs.sec && speedMps != null && speedMps >= fs.mps) return x.copy(type = RoadType.of(fs.roadType), sustained = true)
        return x
    }
}

/** Mirrors engine.js DriveTracker: sustained-fast seconds, jam (slow for road type / frequent stops), continuous driving minutes. */
class DriveTracker(private val r: SceneRules = SceneRules.current) {
    private data class S(val t: Long, val v: Double)
    private val s = ArrayDeque<S>()
    private var fastSince: Long? = null; private var driveStart: Long? = null; private var lastMove: Long? = null
    private var road: String? = null
    private val c get() = r.taste.context
    fun push(now: Long, speedMps: Double?, roadType: String?) {
        if (speedMps == null) return
        s.addLast(S(now, speedMps)); val w = c.jamWindowSec * 1000
        while (s.isNotEmpty() && now - s.first().t > w) s.removeFirst()
        val fs = r.fastSustain
        if (fs != null && speedMps >= fs.mps) { if (fastSince == null) fastSince = now } else fastSince = null
        if (speedMps >= r.drivingMps) {
            if (driveStart == null || now - (lastMove ?: now) > c.breakMin * 60000) driveStart = now
            lastMove = now
        }
        road = roadType
    }
    fun fastSec(now: Long): Double = fastSince?.let { (now - it) / 1000.0 } ?: 0.0
    fun driveMin(now: Long): Double {
        val st = driveStart ?: return 0.0
        return if (now - (lastMove ?: now) > c.breakMin * 60000) 0.0 else (now - st) / 60000.0
    }
    fun jam(): Boolean {
        if (s.size < 2 || s.last().t - s.first().t < c.jamWindowSec * 500) return false
        val avg = s.sumOf { it.v } / s.size; val lim = road?.let { c.jamMaxAvgMps[it] }
        var stops = 0; var run = 0.0; val list = s.toList()
        for (i in 1 until list.size) {
            val dt = (list[i].t - list[i - 1].t) / 1000.0
            if (list[i].v < c.jamStopMps) { run += dt; if (run >= c.jamStopSec && run - dt < c.jamStopSec) stops++ } else run = 0.0
        }
        return (lim != null && avg < lim && avg >= c.jamStopMps * 0.5) || stops >= c.jamStops
    }
}

/** Mirrors engine.js Throttle (distance + time, with backoff after errors/429). */
class QueryThrottle(private val minIntervalMs: Long, private val minMoveMeters: Double, private val maxAgeMs: Long) {
    private var lastMs = 0L; private var lastLat = Double.NaN; private var lastLon = Double.NaN
    private var blockedUntil = Long.MIN_VALUE
    fun shouldQuery(nowMs: Long, lat: Double, lon: Double): Boolean {
        if (nowMs < blockedUntil) return false
        if (lastLat.isNaN()) return true
        if (nowMs - lastMs < minIntervalMs) return false
        return distanceMeters(lat, lon, lastLat, lastLon) >= minMoveMeters || nowMs - lastMs >= maxAgeMs
    }
    fun mark(nowMs: Long, lat: Double, lon: Double) { lastMs = nowMs; lastLat = lat; lastLon = lon }
    fun backoff(nowMs: Long, ms: Long) { blockedUntil = nowMs + ms }
    companion object {
        fun distanceMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(bLat - aLat); val dLon = Math.toRadians(bLon - aLon)
            val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLon / 2).pow(2)
            return 2 * r * asin(sqrt(h))
        }
        fun gridKey(lat: Double, lon: Double, g: Double) = "${(lat / g).roundJs()}:${(lon / g).roundJs()}"
        private fun Double.roundJs(): Long = floor(this + 0.5).toLong()
        /** Short query: roads only (coast/river come from built-in data) — lighter for busy servers. */
        fun overpassQueryShort(lat: Double, lon: Double, r: SceneRules) = "[out:json][timeout:10];way(around:${r.roadRadiusM},$lat,$lon)[highway];out tags;"
        fun overpassQuery(lat: Double, lon: Double, r: SceneRules) =
            "[out:json][timeout:10];(way(around:${r.roadRadiusM},$lat,$lon)[highway];" +
            "way(around:${r.riverRadiusM},$lat,$lon)[waterway~\"^(river|riverbank)$\"];" +
            "way(around:${r.riverRadiusM},$lat,$lon)[natural=water][water=river];" +
            "relation(around:${r.riverRadiusM},$lat,$lon)[natural=water][water=river];" +
            "way(around:${r.coastRadiusM},$lat,$lon)[natural=coastline];);out tags;"
    }
}

/** Mirrors engine.js fixSpeed: GPS speed if valid, else distance/time between fixes (accuracy-filtered). */
object SpeedEstimator {
    data class Fix(val lat: Double, val lon: Double, val t: Long, val acc: Double?, val speed: Double?)
    fun fixSpeed(prev: Fix?, fix: Fix, r: SceneRules = SceneRules.current): Double? {
        if (fix.speed != null && fix.speed.isFinite() && fix.speed >= 0) return fix.speed
        if (prev == null) return null
        val dt = (fix.t - prev.t) / 1000.0
        if (dt < 1 || dt > 30) return null
        if ((fix.acc ?: 0.0) > r.maxAccuracyM || (prev.acc ?: 0.0) > r.maxAccuracyM) return null
        val d = QueryThrottle.distanceMeters(prev.lat, prev.lon, fix.lat, fix.lon)
        if (d < maxOf(fix.acc ?: 0.0, prev.acc ?: 0.0) * 0.5) return 0.0
        val v = d / dt; return if (v > r.maxPlausibleMps) null else v
    }
}

/** Mirrors engine.js nominatimToElements: reverse(zoom 17) result -> OSM-like tags. */
object NominatimMapper {
    /**
     * Nominatim reverse (zoom 17) -> (elements, source). A road way: its highway class directly ("nominatim").
     * Not a road: guess from the address road name ("address"): …대로 primary, …로 tertiary, …길 residential.
     */
    fun road(j: org.json.JSONObject?): Pair<List<OsmTags>, String> {
        if (j == null) return emptyList<OsmTags>() to "none"
        val nd = j.optJSONObject("namedetails"); val addrRoad = j.optJSONObject("address")?.optString("road", "").orEmpty()
        val name = (nd?.optString("name:ko", "")?.ifEmpty { null } ?: nd?.optString("name", "")?.ifEmpty { null } ?: j.optString("name", "").ifEmpty { null } ?: addrRoad.ifEmpty { null })
        val cat = j.optString("category", j.optString("class", "")); val type = j.optString("type", "")
        if (cat == "highway" && type.isNotEmpty()) {
            val x = j.optJSONObject("extratags"); val tags = linkedMapOf("highway" to type)
            x?.optString("bridge", "")?.takeIf { it.isNotEmpty() }?.let { tags["bridge"] = it }
            x?.optString("tunnel", "")?.takeIf { it.isNotEmpty() }?.let { tags["tunnel"] = it }
            if (name != null) tags["name"] = name
            if (j.optString("osm_type") == "way") tags["_id"] = j.optLong("osm_id").toString()
            return listOf<OsmTags>(tags) to "nominatim"
        }
        if (addrRoad.isEmpty()) return emptyList<OsmTags>() to "none"
        val hw = when { addrRoad.endsWith("대로") -> "primary"; addrRoad.endsWith("로") -> "tertiary"; else -> "residential" }
        return listOf<OsmTags>(mapOf("highway" to hw, "name" to addrRoad, "_guess" to "1")) to "address"
    }
    fun toElements(j: org.json.JSONObject?): List<OsmTags> {
        if (j == null) return emptyList()
        val cat = j.optString("category", j.optString("class", ""))
        val type = j.optString("type", "")
        if (cat != "highway" || type.isEmpty()) return emptyList()
        val x = j.optJSONObject("extratags"); val tags = linkedMapOf("highway" to type)
        x?.optString("bridge", "")?.takeIf { it.isNotEmpty() }?.let { tags["bridge"] = it }
        x?.optString("tunnel", "")?.takeIf { it.isNotEmpty() }?.let { tags["tunnel"] = it }
        val name = j.optString("name", "").ifEmpty { j.optJSONObject("address")?.optString("road", "").orEmpty() }
        if (name.isNotEmpty()) tags["name"] = name
        return listOf(tags)
    }
}
