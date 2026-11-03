package com.sanora.giltune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/*
 * Taste-based track selection. Mirrors web/js/engine.js (buildPlans, pickPlans, rankTracks,
 * diversify, listenKind, applyFeedback) 1:1; rules come from scene_rules.json "taste".
 * Time of day only changes mood weights, never the scene.
 * Author: SANORA WEB
 */

class TasteRules(
    val moods: Map<String, String>, val moodLabels: Map<String, String>,
    val plainWeight: Double, val artistShare: Double, val delta: Map<String, Double>,
    val minW: Double, val maxW: Double, val skipSec: Double, val completeRatio: Double, val banAfterSkips: Int,
    val batchSize: Int, val plansPerBatch: Int, val searchLimit: Int,
    val timeMoods: Map<String, List<String>>, val timeBands: List<Triple<String, Int, Int>>, val timeMoodBoost: Double,
    val fastMps: Double, val fastMoods: List<String>, val slowMps: Double, val slowMoods: List<String>, val rainMoods: List<String>,
    val offsetMax: Int, val recentTracks: Int, val recentArtists: Int, val maxPerArtist: Int,
    val context: TasteContext,
    /** Favorite-artist share of songs when a genre is selected (e.g. 0.22). */
    val artistWeightWithGenre: Double = 0.0
) {
    companion object {
        private fun JSONObject.sm() = keys().asSequence().associateWith { getString(it) }
        private fun JSONArray.ss() = (0 until length()).map { getString(it) }
        fun parse(t: JSONObject): TasteRules {
            val d = t.getJSONObject("delta"); val tb = t.getJSONObject("timeBands"); val tm = t.getJSONObject("timeMoods")
            val sp = t.getJSONObject("speedMoods")
            return TasteRules(t.getJSONObject("moods").sm(), t.getJSONObject("moodLabels").sm(),
                t.getDouble("plainWeight"), t.getDouble("artistShare"), d.keys().asSequence().associateWith { d.getDouble(it) },
                t.getDouble("minW"), t.getDouble("maxW"), t.getDouble("skipSec"), t.getDouble("completeRatio"), t.getInt("banAfterSkips"),
                t.getInt("batchSize"), t.getInt("plansPerBatch"), t.getInt("searchLimit"),
                tm.keys().asSequence().associateWith { tm.getJSONArray(it).ss() },
                tb.keys().asSequence().map { Triple(it, tb.getJSONArray(it).getInt(0), tb.getJSONArray(it).getInt(1)) }.toList(),
                t.getDouble("timeMoodBoost"),
                sp.getDouble("fastMps"), sp.getJSONArray("fast").ss(), sp.getDouble("slowMps"), sp.getJSONArray("slow").ss(),
                t.optJSONObject("weatherMoods")?.optJSONArray("rain")?.ss() ?: emptyList(),
                t.getInt("offsetMax"), t.getInt("recentTracks"), t.getInt("recentArtists"), t.getInt("maxPerArtist"),
                TasteContext(t.getJSONObject("context")), t.optDouble("artistWeightWithGenre", 0.0))
        }
    }
}

/** taste.context (v5 selection factors). Each factor only adds mood weight; it never decides the scene. */
class TasteContext(val j: JSONObject) {
    private fun JSONObject.ss(k: String) = optJSONArray(k)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
    val factorWeight = j.getDouble("factorWeight"); val userMoodWeight = j.getDouble("userMoodWeight"); val maxReasons = j.getInt("maxReasons")
    private val wx = j.getJSONObject("weather")
    val weatherCodes: List<Pair<String, Set<Int>>> = wx.getJSONObject("codes").let { o -> o.keys().asSequence().map { k -> k to o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } }.toList() }
    val weatherLabels: Map<String, String> = wx.getJSONObject("labels").let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    val rainScene = wx.ss("rainScene").toSet()
    fun weatherMoods(k: String) = wx.getJSONObject("moods").ss(k)
    private val jam = j.getJSONObject("jam"); private val ld = j.getJSONObject("longDrive")
    val jamWindowSec = jam.getLong("windowSec"); val jamStops = jam.getInt("stops"); val jamStopMps = jam.getDouble("stopMps"); val jamStopSec = jam.getDouble("stopSec")
    val jamMaxAvgMps: Map<String, Double> = jam.getJSONObject("maxAvgMps").let { o -> o.keys().asSequence().associateWith { o.getDouble(it) } }
    val breakMin = ld.getLong("breakMin")
    fun obj(k: String): JSONObject = j.getJSONObject(k)
    fun labels(k: String, sub: String): String? = obj(k).getJSONObject("labels").optString(sub).ifEmpty { null }
    fun moods(k: String, sub: String) = obj(k).getJSONObject("moods").ss(sub)
    fun list(o: JSONObject, k: String) = o.ss(k)
}

/** A selection factor: id (e.g. "wx:rain"), label shown in "선곡 이유" (null = not shown), moods, total weight. */
class Factor(val id: String, val label: String?, val moods: List<String>, val weight: Double)

data class Artist(val id: String, val name: String)

/** Genres / moods / artists picked by the user + learned weights (persisted as JSON, same shape as the web). */
class TasteProfile(
    val genres: MutableList<String> = mutableListOf(), val moods: MutableList<String> = mutableListOf(),
    val artists: MutableList<Artist> = mutableListOf(), val w: MutableMap<String, Double> = linkedMapOf(),
    val skips: MutableMap<String, Int> = linkedMapOf(), val liked: MutableList<String> = mutableListOf(), var done: Boolean = false
) {
    fun weight(k: String) = w[k] ?: 1.0
    fun toJson(): String = JSONObject().put("genres", JSONArray(genres)).put("moods", JSONArray(moods))
        .put("artists", JSONArray(artists.map { JSONObject().put("id", it.id).put("name", it.name) }))
        .put("w", JSONObject(w as Map<*, *>)).put("skips", JSONObject(skips as Map<*, *>)).put("liked", JSONArray(liked)).put("done", done).toString()
    companion object {
        fun fromJson(s: String?): TasteProfile {
            if (s.isNullOrBlank()) return TasteProfile()
            val j = JSONObject(s); fun a(k: String) = j.optJSONArray(k) ?: JSONArray()
            val p = TasteProfile(done = j.optBoolean("done"))
            a("genres").let { x -> (0 until x.length()).forEach { p.genres += x.getString(it) } }
            a("moods").let { x -> (0 until x.length()).forEach { p.moods += x.getString(it) } }
            a("artists").let { x -> (0 until x.length()).forEach { val o = x.getJSONObject(it); p.artists += Artist(o.getString("id"), o.getString("name")) } }
            a("liked").let { x -> (0 until x.length()).forEach { p.liked += x.getString(it) } }
            j.optJSONObject("w")?.let { o -> o.keys().forEach { k -> p.w[k] = o.getDouble(k) } }
            j.optJSONObject("skips")?.let { o -> o.keys().forEach { k -> p.skips[k] = o.getInt(k) } }
            return p
        }
    }
}

/** SharedPreferences storage for the profile. */
object TasteStore {
    private const val KEY = "taste"
    fun load(c: Context) = TasteProfile.fromJson(c.getSharedPreferences("giltune", Context.MODE_PRIVATE).getString(KEY, null))
    fun save(c: Context, p: TasteProfile) = c.getSharedPreferences("giltune", Context.MODE_PRIVATE).edit().putString(KEY, p.toJson()).apply()
}

data class Plan(val q: String, val key: String, val moodKey: String?, val offset: Int, val weight: Double, val reason: List<String> = emptyList(),
                /** Genre plans: fallback queries (tag alone -> genre name text) and situation words for sorting. */
                val fallback: List<String> = emptyList(), val words: List<String> = emptyList())
/** One search page: tracks + total result count (for the offset clamp). */
data class SearchPage(val tracks: List<SpotifyTrack>, val total: Int?)

/** Selection context (mirrors app.js selCtx). [nowMs] + [zone] give local date/time. */
data class PlanContext(
    val nowMs: Long = System.currentTimeMillis(), val zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    val wxCode: Int? = null, val wxClass: String? = null, val rain: Boolean = false, val tempC: Double? = null,
    val sunrise: Long? = null, val sunset: Long? = null, val speedMps: Double? = null, val jam: Boolean = false,
    val driveMin: Double = 0.0, val sceneLabel: String? = null, val roadReasons: List<String> = emptyList()
) {
    val local: java.time.LocalDateTime get() = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nowMs), zone)
    companion object {
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()) =
            java.time.LocalDateTime.of(y, mo, d, h, mi).atZone(zone).toInstant().toEpochMilli()
    }
}
data class MoodEntry(val m: String, val w: Double, val from: List<Factor>)
/** A Spotify search result (+ which plan found it, for learning). */
data class SpotifyTrack(val uri: String, val name: String, val artist: String, val artistIds: List<String>, val durationMs: Long = 0,
                 val key: String? = null, val moodKey: String? = null, val q: String? = null, val album: String = "", val popularity: Int = 0,
                 /** Genre evidence (playlist / representative artist / artist genre tag / name). */
                 val fit: String = "",
                 /** How it was chosen (선곡 기록) and the situation words used for title matching. */
                 val sel: SelInfo? = null, val words: List<String> = emptyList()) {
    val title get() = name
}
/** Candidates, rank, exclusions, pool source, favorite-artist slot. */
data class SelInfo(val cand: Int, val rank: Int, val exRecent: Int, val exArtist: Int, val pool: String, val favorite: Boolean)

object QueryPlanner {
    private fun <T> pick(arr: List<T>, rnd: () -> Double) = arr[floor(rnd() * arr.size).toInt() % arr.size]

    fun timeBand(hour: Int, r: SceneRules): String {
        for ((k, a, b) in r.taste.timeBands) if (if (a < b) hour >= a && hour < b else hour >= a || hour < b) return k
        return "day"
    }

    /** Open-Meteo weather_code -> clear / cloudy / fog / rain / heavyRain / snow (taste.context.weather.codes). */
    fun weatherClass(code: Int?, r: SceneRules): String? = code?.let { c -> r.taste.context.weatherCodes.firstOrNull { c in it.second }?.first }

    /** Active factors: weather, temperature, season, sunrise/sunset, holiday/weekend/commute, late night or time band, speed, jam, long drive. */
    fun contextFactors(ctx: PlanContext, r: SceneRules): List<Factor> {
        val c = r.taste.context; val t = r.taste; val out = ArrayList<Factor>()
        val now = ctx.local; val h = now.hour + now.minute / 60.0
        fun add(id: String, label: String?, moods: List<String>, weight: Double? = null) { if (moods.isNotEmpty()) out.add(Factor(id, label, moods, weight ?: c.factorWeight)) }
        val wx = ctx.wxClass ?: weatherClass(ctx.wxCode, r) ?: if (ctx.rain) "rain" else null
        if (wx != null) add("wx:$wx", c.weatherLabels[wx], c.weatherMoods(wx))
        ctx.tempC?.let { tc -> val tp = c.obj("temp")
            if (tc >= tp.getDouble("hotC")) add("temp:hot", c.labels("temp", "hot"), c.moods("temp", "hot"))
            else if (tc <= tp.getDouble("coldC")) add("temp:cold", c.labels("temp", "cold"), c.moods("temp", "cold")) }
        val sea = c.obj("season"); val months = sea.getJSONObject("months")
        months.keys().asSequence().firstOrNull { k -> months.getJSONArray(k).let { a -> (0 until a.length()).any { a.getInt(it) == now.monthValue } } }
            ?.let { se -> add("season:$se", c.labels("season", se), c.moods("season", se), sea.getDouble("weight")) }
        val m = c.obj("twilight").getDouble("marginMin") * 60000; val ts = ctx.nowMs
        if (ctx.sunrise != null && ctx.sunrise != 0L && kotlin.math.abs(ts - ctx.sunrise) <= m) add("twilight:sunrise", c.labels("twilight", "sunrise"), c.moods("twilight", "sunrise"))
        else if (ctx.sunset != null && ctx.sunset != 0L && kotlin.math.abs(ts - ctx.sunset) <= m) add("twilight:sunset", c.labels("twilight", "sunset"), c.moods("twilight", "sunset"))
        val holiday = "%04d-%02d-%02d".format(now.year, now.monthValue, now.dayOfMonth) in r.holidays
        val weekend = now.dayOfWeek == java.time.DayOfWeek.SATURDAY || now.dayOfWeek == java.time.DayOfWeek.SUNDAY
        val cm = c.obj("commute"); fun band(k: String) = cm.getJSONArray(k).let { it.getDouble(0) to it.getDouble(1) }
        val am = band("am"); val pm = band("pm")
        if (holiday) add("day:holiday", c.labels("dayType", "holiday"), c.moods("dayType", "holiday"))
        else if (weekend) add("day:weekend", c.labels("dayType", "weekend"), c.moods("dayType", "weekend"))
        else if (h >= am.first && h < am.second) add("commute:am", c.labels("commute", "am"), c.moods("commute", "am"))
        else if (h >= pm.first && h < pm.second) add("commute:pm", c.labels("commute", "pm"), c.moods("commute", "pm"))
        val ln = c.obj("lateNight"); val lh = ln.getJSONArray("hours")
        if (h >= lh.getDouble(0) && h < lh.getDouble(1)) add("lateNight", ln.getString("label"), c.list(ln, "moods"))
        else { val tb = timeBand(now.hour, r); add("time:$tb", null, t.timeMoods[tb].orEmpty(), c.obj("time").getDouble("weight")) }
        val sw = c.obj("speed").getDouble("weight")
        if (ctx.speedMps != null && ctx.speedMps >= t.fastMps) add("speed:fast", null, t.fastMoods, sw)
        else if (ctx.speedMps != null && ctx.speedMps <= t.slowMps && !ctx.jam) add("speed:slow", null, t.slowMoods, sw)
        if (ctx.jam) c.obj("jam").let { add("jam", it.getString("label"), c.list(it, "moods")) }
        val ld = c.obj("longDrive")
        if (ctx.driveMin >= ld.getDouble("minutes")) add("longDrive", ld.getString("label"), c.list(ld, "moods"), ld.getDouble("weight"))
        return out
    }

    /** Each factor spreads its weight over its moods (no factor dominates); user picks weigh userMoodWeight each. */
    fun moodPool(p: TasteProfile, r: SceneRules, ctx: PlanContext, factors: List<Factor>? = null): List<MoodEntry> {
        val t = r.taste; val pool = LinkedHashMap<String, Double>(); val from = HashMap<String, MutableList<Factor>>()
        fun add(m: String, w: Double, f: Factor?) {
            if (!t.moods.containsKey(m)) return
            pool[m] = (pool[m] ?: 0.0) + w * p.weight("mood:$m"); if (f != null) from.getOrPut(m) { ArrayList() }.add(f)
        }
        p.moods.forEach { add(it, t.context.userMoodWeight, null) }
        (factors ?: contextFactors(ctx, r)).forEach { f -> f.moods.forEach { add(it, f.weight / f.moods.size, f) } }
        return pool.entries.map { MoodEntry(it.key, it.value, from[it.key].orEmpty()) }
    }

    private fun weighted(list: List<MoodEntry>, rnd: () -> Double): MoodEntry {
        var x = rnd() * list.sumOf { it.w }
        for (e in list) { x -= e.w; if (x <= 0) return e }
        return list.last()
    }
    private val PRI = listOf("wx", "day", "commute", "jam", "longDrive", "twilight", "temp", "lateNight", "season")
    private fun pri(f: Factor) = PRI.indexOf(f.id.substringBefore(':')).let { if (it < 0) 99 else it }

    /** Random scene keyword × mood × genre, random offset (Development Mode search limit 10); each plan carries its "선곡 이유". */
    fun buildPlans(scene: Scene, p: TasteProfile, r: SceneRules, ctx: PlanContext, rnd: () -> Double): List<Plan> {
        val t = r.taste; val out = ArrayList<Plan>(); val fs = contextFactors(ctx, r); val moods = moodPool(p, r, ctx, fs)
        fun offset() = 10 * floor(rnd() * (t.offsetMax / 10 + 1)).toInt()
        fun mood(): String? = if (moods.isNotEmpty() && rnd() < 0.8) weighted(moods, rnd).m else null
        // reason: factors behind this mood first, then priority (weather > day/commute > jam/long drive > twilight > temp > late night > season)
        fun why2(m: String?, extra: List<String>): List<String> {
            val mine = moods.firstOrNull { it.m == m }?.from.orEmpty().toSet()
            val labeled = fs.filter { it.label != null }.sortedWith(compareBy<Factor> { if (it in mine) 0 else 1 }.thenBy { pri(it) })
            val head = listOf(ctx.sceneLabel ?: scene.label) + ctx.roadReasons
            return (head + labeled.take(t.context.maxReasons).map { it.label!! } + extra).filter { it.isNotEmpty() }.distinct()
        }
        fun why(m: String?, extra: String?): List<String> = why2(m, listOfNotNull(extra))
        fun add(q: String, key: String, moodKey: String?, w0: Double, off: Int, reason: List<String>) =
            out.add(Plan(q.replace(Regex("\\s+"), " ").trim(), key, moodKey, off, w0 * p.weight(key) * p.weight(scene.id + "|" + key), reason))
        // 장르를 하나라도 고르면 그 장르 결과만(장면 일반·아티스트 검색 없음). 상황은 무드 한 단어 + 정렬 단서로 반영.
        val genres = p.genres.filter { r.genres.containsKey(it) }
        if (genres.isNotEmpty()) {
            for (g in genres) {
                val m = if (moods.isNotEmpty()) weighted(moods, rnd).m else null; val tag = "genre:\"${r.genres[g]}\""
                val label = r.genreLabels[g] ?: g
                add(if (m != null) "$tag ${t.moods[m]}" else tag, "genre:$g", m?.let { "mood:$it" }, 1.0, offset(), why2(m, listOfNotNull(label, m?.let { t.moodLabels[it] })))
                out[out.size - 1] = out.last().copy(fallback = listOf(tag, r.genreText[g] ?: label), words = situationWords(scene, m, r, ctx))
            }
            // 좋아하는 아티스트도 섞기: 전체의 artistWeightWithGenre(약 22%)만큼 아티스트끼리 나눔. 저장된 아티스트 없으면 100% 장르.
            val share = t.artistWeightWithGenre; val gSum = out.sumOf { it.weight }
            if (p.artists.isNotEmpty() && share > 0) for (a in p.artists) {
                val m = if (moods.isNotEmpty()) weighted(moods, rnd).m else null
                add("artist:\"${a.name}\"", "artist:${a.id}", null, share / (1 - share) * gSum / p.artists.size, 0, why2(m, listOf("좋아하는 아티스트: ${a.name}")))
                out[out.size - 1] = out.last().copy(words = situationWords(scene, m, r, ctx))
            }
            return out
        }
        for (a in p.artists) add("artist:\"${a.name}\"", "artist:${a.id}", null, t.artistShare, 0, why(null, a.name))
        val m = mood(); val kw = pick(scene.keywords, rnd)
        add("$kw ${m?.let { t.moods[it] } ?: ""}", "scene:${scene.id}", m?.let { "mood:$it" }, 1.0, offset(),
            why(m, m?.let { t.moodLabels[it] }))
        return out
    }

    /** Situation words: mood search word + scene keywords (lowercase, 3+ letters). */
    fun situationWords(scene: Scene, m: String?, r: SceneRules, ctx: PlanContext? = null): List<String> {
        val base = (listOf(m?.let { r.taste.moods[it] } ?: "") + scene.keywords).joinToString(" ").lowercase().split(Regex("\\s+")).filter { it.length > 2 }
        val tw = r.titleWords; val h = ctx?.local?.hour
        val extra = (tw[scene.id] ?: emptyList()) + (if (ctx != null && (ctx.rain || ctx.wxClass == "rain")) tw["RAIN"] ?: emptyList() else emptyList()) +
            (if (h != null && (h >= 20 || h < 5)) tw["night"] ?: emptyList() else emptyList()) + (if (h != null && h in 6..9) tw["morning"] ?: emptyList() else emptyList())
        return (base + extra.map { it.lowercase() }).distinct()
    }
    private val particle = Regex("^(.)(가|이|에|의|은|는|을|를|와|도|야)$")
    private fun isKo(w: String) = w.any { it in '\uac00'..'\ud7a3' }
    /** Words really in the title / album (never lyrics). One-letter Korean (비, 밤) only as a word or with a particle ('비밀' is not '비'). */
    fun titleMatches(t: SpotifyTrack, words: List<String>): Pair<List<String>, List<String>> {
        fun toks(s: String) = s.lowercase().split(Regex("[^0-9a-z\\uac00-\\ud7a3']+")).filter { it.isNotEmpty() }
        fun hit(tk: String, w: String) = tk == w || (isKo(w) && (if (w.length >= 2) tk.contains(w) else particle.find(tk)?.groupValues?.get(1) == w))
        fun find(s: String) = words.filter { w -> toks(s).any { hit(it, w) } }
        return find(t.name) to find(t.album)
    }
    /** Title/album word matches + a little popularity (0..100). No deprecated audio-features API. */
    fun situationScore(t: SpotifyTrack, words: List<String>): Double {
        val (a, b) = titleMatches(t, words)
        return (a + b).toSet().size + t.popularity / 200.0   // 제목 일치 가산(장르 규칙은 그대로)
    }
    fun sortBySituation(tracks: List<SpotifyTrack>, words: List<String>, rnd: () -> Double): List<SpotifyTrack> =
        tracks.map { it to situationScore(it, words) + rnd() * 0.1 }.sortedByDescending { it.second }.map { it.first }
    /** Split n song slots by plan weight (with replacement) -> (plan, count); song ratio follows weights. */
    fun allocate(plans: List<Plan>, n: Int, rnd: () -> Double): List<Pair<Plan, Int>> {
        val cnt = LinkedHashMap<Plan, Int>()
        repeat(n) { pickPlans(plans, 1, rnd).firstOrNull()?.let { cnt.merge(it, 1, Int::plus) } }
        return plans.filter { it in cnt }.map { it to cnt[it]!! }
    }
    /** Offset past the total (empty page) -> a random page inside the results. Unknown total -> unchanged. */
    fun clampOffset(off: Int, total: Int?, rnd: () -> Double): Int {
        if (total == null) return off; if (total <= 10) return 0
        val mx = (total - 1) / 10 * 10
        return if (off <= mx) off else 10 * floor(rnd() * (mx / 10 + 1)).toInt()
    }
    /** One plan: clamp offset, and if fewer than 3 results try fallback queries (tag alone -> genre name). Returns (tracks, query used). */
    fun searchPlan(plan: Plan, rnd: () -> Double, search: (String, Int) -> SearchPage): Pair<List<SpotifyTrack>, String> {
        var best: Pair<List<SpotifyTrack>, String> = emptyList<SpotifyTrack>() to plan.q
        for (q in (listOf(plan.q) + plan.fallback).distinct()) {
            var pg = search(q, plan.offset)
            if (pg.tracks.isEmpty() && plan.offset > 0) { val o = clampOffset(plan.offset, pg.total, rnd); pg = search(q, if (o == plan.offset) 0 else o) }
            if (pg.tracks.size >= 3 || (pg.tracks.isNotEmpty() && plan.fallback.isEmpty())) return pg.tracks to q
            if (pg.tracks.size > best.first.size) best = pg.tracks to q
        }
        return best
    }

    /** "선곡 이유" at display time: scene · road · top factor labels · genre (or artist / mood) of the item. */
    fun reasonLine(item: SpotifyTrack?, ctx: PlanContext, r: SceneRules): List<String> {
        val fl = contextFactors(ctx, r).filter { it.label != null }.sortedBy { pri(it) }.take(r.taste.context.maxReasons).map { it.label!! }
        val k = item?.key ?: ""
        val g = when { k.startsWith("genre:") -> r.genreLabels[k.substring(6)]; k.startsWith("artist:") -> item?.artist; else -> null }
        val m = item?.moodKey?.let { r.taste.moodLabels[it.substring(5)] }
        return (listOfNotNull(ctx.sceneLabel) + ctx.roadReasons + fl + listOfNotNull(g ?: m)).filter { it.isNotEmpty() }.distinct()
    }

    /** Shuffle local files; the scene's file first unless it was just played; never the just-played file first. */
    fun shuffleBag(files: List<String>, sceneFile: String?, lastFile: String?, rnd: () -> Double): List<String> {
        val a = files.toMutableList()
        for (i in a.size - 1 downTo 1) { val j = floor(rnd() * (i + 1)).toInt(); val x = a[i]; a[i] = a[j]; a[j] = x }
        if (sceneFile != null && sceneFile != lastFile && sceneFile in a) { a.remove(sceneFile); a.add(0, sceneFile) }
        if (a.size > 1 && a[0] == lastFile) a.add(a.removeAt(0))
        return a
    }

    fun pickPlans(plans: List<Plan>, n: Int, rnd: () -> Double): List<Plan> {
        val pool = plans.toMutableList(); val out = ArrayList<Plan>()
        while (pool.isNotEmpty() && out.size < n) {
            var x = rnd() * pool.sumOf { it.weight }; var i = 0
            while (i < pool.size - 1) { x -= pool[i].weight; if (x > 0) i++ else break }
            out.add(pool.removeAt(i))
        }
        return out
    }

    fun rankTracks(tracks: List<SpotifyTrack>, p: TasteProfile, r: SceneRules, rnd: () -> Double): List<SpotifyTrack> =
        tracks.filter { (p.skips[it.uri] ?: 0) < r.taste.banAfterSkips }
            .map { t ->
                val aw = if (t.artistIds.isEmpty()) 1.0 else t.artistIds.sumOf { p.weight("artist:$it") } / t.artistIds.size
                t to aw + (if (t.uri in p.liked) 0.5 else 0.0) + rnd() * 0.2
            }.sortedByDescending { it.second }.map { it.first }

    fun diversify(tracks: List<SpotifyTrack>, recentUris: Collection<String>, recentArtists: Collection<String>, r: SceneRules): List<SpotifyTrack> {
        val seen = HashMap<String, Int>(); val ru = recentUris.toHashSet(); val ra = recentArtists.toHashSet()
        return tracks.filter { t ->
            if (t.uri in ru || t.artistIds.any { it in ra } || t.artistIds.any { (seen[it] ?: 0) >= r.taste.maxPerArtist }) false
            else { t.artistIds.forEach { seen[it] = (seen[it] ?: 0) + 1 }; true }
        }
    }

    /** "skip" | "complete" | null */
    fun listenKind(playedSec: Double, durationSec: Double, r: SceneRules): String? = when {
        playedSec < r.taste.skipSec -> "skip"
        durationSec > 0 && playedSec >= durationSec * r.taste.completeRatio -> "complete"
        else -> null
    }

    fun applyFeedback(p: TasteProfile, t: SpotifyTrack, scene: String?, kind: String, r: SceneRules) {
        val d = r.taste.delta[kind] ?: return
        fun bump(k: String) { p.w[k] = min(r.taste.maxW, max(r.taste.minW, ((p.weight(k) + d) * 1000).roundToLong() / 1000.0)) }
        t.key?.let { bump(it); if (scene != null) bump("$scene|$it") }
        t.moodKey?.let { bump(it) }
        t.artistIds.forEach { bump("artist:$it") }
        if (kind == "skip") p.skips[t.uri] = (p.skips[t.uri] ?: 0) + 1
        if (kind == "like" && t.uri !in p.liked) p.liked.add(t.uri)
    }

    fun perPlan(r: SceneRules, plans: Int) = ceil(r.taste.batchSize.toDouble() / max(1, plans)).toInt()
}

/** Now playing / upcoming (grouped by scene) / history — the app-side queue (App Remote has no queue read/remove). */
class PlayQueue {
    data class Item(val track: SpotifyTrack, val scene: String, val sceneLabel: String, val user: Boolean)
    var now: Item? = null
    val upcoming = ArrayList<Item>()
    val history = ArrayList<Pair<Item, String>>()        // item + result (skip/complete/like/partial)

    /** Keep user-added items, replace auto items with the new scene batch. */
    fun replaceAuto(batch: List<Item>) { upcoming.removeAll { !it.user }; upcoming.addAll(batch) }
    fun addUser(item: Item) { val i = upcoming.indexOfFirst { !it.user }; upcoming.add(if (i < 0) upcoming.size else i, item) }
    fun remove(index: Int) { if (index in upcoming.indices) upcoming.removeAt(index) }
    fun advance(result: String?): Item? {
        now?.let { history.add(it to (result ?: "partial")); while (history.size > 100) history.removeAt(0) }
        now = if (upcoming.isEmpty()) null else upcoming.removeAt(0); return now
    }
    fun recentUris(n: Int) = history.takeLast(n).map { it.first.track.uri } + upcoming.map { it.track.uri }
    fun recentArtists(n: Int) = history.takeLast(n).flatMap { it.first.track.artistIds }
}
