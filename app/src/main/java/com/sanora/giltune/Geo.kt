package com.sanora.giltune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/*
 * Built-in coastline / river data (assets/geo_kr.json, Data © OpenStreetMap contributors, ODbL 1.0).
 * Grid index (rules.geo.cellDeg): only nearby segments are checked -> distance (m), and point-in-polygon
 * for "on the river". Works without Overpass. Mirrors web/js/geo.js 1:1.
 * Author: SANORA WEB
 */
class GeoIndex(data: JSONObject, private val cell: Double = 0.02) {
    private class Poly(val ring: List<DoubleArray>, val bbox: DoubleArray)
    private val grid = mapOf("coast" to HashMap<Long, ArrayList<DoubleArray>>(), "river" to HashMap())
    private val water = HashMap<Long, ArrayList<Int>>()
    private val polys = ArrayList<Poly>()

    private fun key(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xffffffffL)
    private fun cellOf(v: Double) = floor(v / cell).toInt()

    init {
        fun addLines(kind: String, lines: JSONArray?) {
            val g = grid.getValue(kind)
            for (k in 0 until (lines?.length() ?: 0)) {
                val p = decode(lines!!.getJSONArray(k))
                for (i in 0 until p.size - 1) {
                    val s = doubleArrayOf(p[i][0], p[i][1], p[i + 1][0], p[i + 1][1])
                    for (x in cellOf(min(s[0], s[2]))..cellOf(max(s[0], s[2])))
                        for (y in cellOf(min(s[1], s[3]))..cellOf(max(s[1], s[3]))) g.getOrPut(key(x, y)) { ArrayList() }.add(s)
                }
            }
        }
        addLines("coast", data.optJSONArray("coast")); addLines("river", data.optJSONArray("river"))
        val w = data.optJSONArray("water")
        for (k in 0 until (w?.length() ?: 0)) {
            val ring = decode(w!!.getJSONArray(k))
            var a = 180.0; var b = 90.0; var c = -180.0; var d = -90.0
            for (pt in ring) { a = min(a, pt[0]); b = min(b, pt[1]); c = max(c, pt[0]); d = max(d, pt[1]) }
            polys.add(Poly(ring, doubleArrayOf(a, b, c, d))); val id = polys.size - 1
            for (x in cellOf(a)..cellOf(c)) for (y in cellOf(b)..cellOf(d)) water.getOrPut(key(x, y)) { ArrayList() }.add(id)
        }
        addLines("river", w)   // water edges count as "river side" too
    }

    /** Nearest distance (m) to [kind] ("coast"/"river") within [maxM], else +Infinity. */
    fun distM(kind: String, lat: Double, lon: Double, maxM: Double): Double {
        val kx = cos(lat * RAD) * R * RAD; val ky = R * RAD; val n = ceil(maxM / (ky * cell)).toInt()
        val cx = cellOf(lon); val cy = cellOf(lat); var best = Double.POSITIVE_INFINITY; val g = grid.getValue(kind)
        for (x in cx - n - 1..cx + n + 1) for (y in cy - n - 1..cy + n + 1) {
            val segs = g[key(x, y)] ?: continue
            for (s in segs) {
                val ax = (s[0] - lon) * kx; val ay = (s[1] - lat) * ky; val bx = (s[2] - lon) * kx; val by = (s[3] - lat) * ky
                val dx = bx - ax; val dy = by - ay; val l = dx * dx + dy * dy
                val t = if (l != 0.0) max(0.0, min(1.0, -(ax * dx + ay * dy) / l)) else 0.0
                val d = hypot(ax + t * dx, ay + t * dy); if (d < best) best = d
            }
        }
        return if (best <= maxM) best else Double.POSITIVE_INFINITY
    }

    /** True if the point is inside a river/water polygon (e.g. on a Han river bridge). */
    fun onWater(lat: Double, lon: Double): Boolean {
        val ids = water[key(cellOf(lon), cellOf(lat))] ?: return false
        for (id in ids) {
            val p = polys[id]; val bb = p.bbox
            if (lon < bb[0] || lon > bb[2] || lat < bb[1] || lat > bb[3]) continue
            var inside = false; var j = p.ring.size - 1
            for (i in p.ring.indices) {
                val xi = p.ring[i][0]; val yi = p.ring[i][1]; val xj = p.ring[j][0]; val yj = p.ring[j][1]
                if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            if (inside) return true
        }
        return false
    }

    companion object {
        const val ASSET = "geo_kr.json"
        private const val R = 6371000.0
        private const val RAD = Math.PI / 180
        /** [dLon,dLat,...] 1e-5 degree integer deltas -> [[lon,lat],...] */
        fun decode(a: JSONArray): List<DoubleArray> {
            val out = ArrayList<DoubleArray>(a.length() / 2); var x = 0L; var y = 0L; var i = 0
            while (i + 1 < a.length()) { x += a.getLong(i); y += a.getLong(i + 1); out.add(doubleArrayOf(x / 1e5, y / 1e5)); i += 2 }
            return out
        }
        @Volatile private var loaded: GeoIndex? = null
        fun currentOrNull() = loaded
        fun install(g: GeoIndex) { loaded = g }
        /** Parse once (call off the main thread). */
        fun ensure(context: Context, rules: SceneRules): GeoIndex = loaded ?: synchronized(this) {
            loaded ?: GeoIndex(JSONObject(context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }), rules.geo.cellDeg).also { loaded = it }
        }

        /** Mirrors geo.js applyGeo: coastal/river flags from built-in data; "bridge" on an expressway only over water with a bridge name. */
        fun applyGeo(road: RoadInfo, lat: Double?, lon: Double?, geo: GeoIndex?, r: SceneRules): RoadInfo {
            if (geo == null || lat == null || lon == null) return road
            val g = r.geo
            val rr = if (g.riverRadiusM > 0) g.riverRadiusM else r.riverRadiusM.toDouble()
            val cr = if (g.coastRadiusM > 0) g.coastRadiusM else r.coastRadiusM.toDouble()
            val onWater = geo.onWater(lat, lon)
            val coast = road.coastal || geo.distM("coast", lat, lon, cr).isFinite(); val riv = road.river || onWater || geo.distM("river", lat, lon, rr).isFinite()
            var out = road.copy(coastal = coast, river = riv, geoHit = (coast && !road.coastal) || (riv && !road.river))   // 내장 해안·강 데이터로 판정됨
            if (out.type == RoadType.BRIDGE && out.cls != null && out.cls in g.bridgeNeedsWater) {
                val overWater = onWater || (g.bridgeWaterMarginM > 0 && geo.distM("river", lat, lon, g.bridgeWaterMarginM).isFinite())
                val named = g.bridgeNamePattern.isEmpty() || Regex(g.bridgeNamePattern).containsMatchIn(out.name ?: "")
                if (!overWater || !named) out = out.copy(type = RoadType.of(out.cls), elevated = true)
            }
            return out
        }
    }
}
