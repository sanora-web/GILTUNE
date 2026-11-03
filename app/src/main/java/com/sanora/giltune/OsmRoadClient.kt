package com.sanora.giltune

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.LinkedHashMap
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Road type (Overpass API) and region name (Nominatim reverse) for the current position.
 * Usage-policy measures:
 *  - identifying User-Agent on every request (Nominatim policy requires it);
 *  - one worker thread, >= 1.1 s between any two requests to the same host (Nominatim max 1 req/s);
 *  - distance/time throttles from scene_rules.json osm.* (150 m / 15 s / 180 s stale, 60 s backoff on errors),
 *    Nominatim every >= 2 km AND >= 60 s (or 10 min stale);
 *  - grid result cache (osm.cacheGridDeg, osm.cacheMax entries, 1 h) so the same road is not asked twice;
 *  - any failure -> RoadInfo.UNKNOWN; the scene engine then falls back to speed + bridge geofences.
 * https://operations.osmfoundation.org/policies/nominatim/ , https://wiki.openstreetmap.org/wiki/Overpass_API
 */
class OsmRoadClient(private val rules: SceneRules = SceneRules.current,
                    private val userAgent: String = "GILTUNE/6.0 (SANORA WEB; contest prototype)") {
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val lastHostCall = HashMap<String, Long>()
    private val roadThrottle = QueryThrottle(rules.osmMinIntervalMs, rules.osmMinMoveM, rules.osmMaxAgeMs)
    private val regionThrottle = QueryThrottle(60_000L, 2_000.0, 600_000L)
    private val cache = object : LinkedHashMap<String, Pair<Long, RoadInfo>>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, RoadInfo>>?) = size > rules.cacheMax
    }
    @Volatile private var busy = false

    fun lookup(lat: Double, lon: Double, speedMps: Float?, nowMs: Long,
               onRoad: (RoadInfo) -> Unit, onRegion: (String) -> Unit) {
        val key = QueryThrottle.gridKey(lat, lon, rules.cacheGridDeg)
        synchronized(cache) { cache[key] }?.let { (at, info) ->
            if (nowMs - at < 3_600_000L) { onRoad(info); return }
        }
        val wantRoad = roadThrottle.shouldQuery(nowMs, lat, lon)
        val wantRegion = regionThrottle.shouldQuery(nowMs, lat, lon)
        if ((!wantRoad && !wantRegion) || busy) return
        if (wantRoad) roadThrottle.mark(nowMs, lat, lon)
        if (wantRegion) regionThrottle.mark(nowMs, lat, lon)
        busy = true
        worker.execute {
            try {
                if (wantRoad) {
                    val info = try { queryRoad(lat, lon, speedMps) } catch (_: Exception) {
                        null }   // next lookup follows the normal 100 m / 8 s throttle
                    if (info != null) synchronized(cache) { cache[key] = System.currentTimeMillis() to info }
                    main.post { onRoad(info ?: RoadInfo.UNKNOWN) }
                }
                if (wantRegion) {
                    val region = try { queryRegion(lat, lon) } catch (_: Exception) { null }
                    if (region != null) main.post { onRegion(region) }
                }
            } finally { busy = false }
        }
    }

    /** Index of the Overpass mirror that answered last (tried first next time). */
    @Volatile private var goodMirror = 0
    @Volatile private var overpassRestUntil = 0L

    /** Overpass mirrors in order (scene_rules osm.endpoints), then Nominatim reverse zoom=17 road class. */
    private fun queryRoad(lat: Double, lon: Double, speedMps: Float?): RoadInfo {
        val q = QueryThrottle.overpassQueryShort(String.format(Locale.US, "%.6f", lat).toDouble(), String.format(Locale.US, "%.6f", lon).toDouble(), rules)
        val eps = rules.osmEndpoints
        // All mirrors failed recently: rest Overpass only (backoffMs), keep using Nominatim meanwhile.
        if (System.currentTimeMillis() >= overpassRestUntil) for (k in eps.indices) {
            val i = (goodMirror + k) % eps.size
            val json = try { JSONObject(get(eps[i] + "?data=" + URLEncoder.encode(q, "UTF-8"), rules.osmTimeoutMs.toInt())) } catch (_: Exception) { continue }
            goodMirror = i
            val elements = json.getJSONArray("elements")
            val list = ArrayList<OsmTags>()
            for (n in 0 until elements.length()) {
                val el = elements.getJSONObject(n); val tags = el.optJSONObject("tags") ?: continue
                val map = HashMap<String, String>(); if (el.has("id")) map["_id"] = el.optLong("id").toString()
                tags.keys().forEach { key -> map[key] = tags.getString(key) }
                list.add(map)
            }
            return RoadClassifier.classify(list, speedMps?.toDouble(), rules, "overpass")
        }
        val t = System.currentTimeMillis(); if (t >= overpassRestUntil) overpassRestUntil = t + rules.backoffMs
        val url = String.format(Locale.US, "%s?format=jsonv2&zoom=%d&extratags=1&namedetails=1&accept-language=ko&lat=%.6f&lon=%.6f",
            rules.nominatimEndpoint, rules.nominatimZoom, lat, lon)
        val (els, src) = NominatimMapper.road(JSONObject(get(url)))   // 도로(way)면 그 등급, 아니면 주소 이름으로 추정
        return RoadClassifier.classify(els, speedMps?.toDouble(), rules, src)
    }

    private fun queryRegion(lat: Double, lon: Double): String? {
        val url = String.format(Locale.US,
            "https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&accept-language=ko&lat=%.5f&lon=%.5f", lat, lon)
        val address = JSONObject(get(url)).optJSONObject("address") ?: return null
        val parts = listOf("city", "county", "borough", "suburb", "town", "village")
            .mapNotNull { address.optString(it).takeIf { s -> s.isNotBlank() } }.distinct().take(2)
        return parts.joinToString(" ").ifBlank { null }
    }

    private fun get(url: String, timeoutMs: Int = 12_000): String {
        val host = URL(url).host
        synchronized(lastHostCall) {
            val wait = (lastHostCall[host] ?: 0L) + maxOf(1_100L, rules.nominatimMinIntervalMs) - System.currentTimeMillis()
            if (wait > 0) Thread.sleep(wait)
            lastHostCall[host] = System.currentTimeMillis()
        }
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = minOf(5_000, timeoutMs); c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", userAgent)
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    fun shutdown() { worker.shutdownNow() }
}
