package com.sanora.giltune

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/*
 * 선곡 기록: 곡마다 왜 골랐는지(위치·판정 근거·요소 점수·장르·출처·후보 순위·제외 수), 최근 50곡.
 * web/js/engine.js (selectionRecord, pushRecord, logText, plainReason) 와 같은 내용.
 * Author: SANORA WEB
 */
data class FactorScore(val name: String, val label: String, val score: Double)
data class SelRecord(
    val at: Long, val title: String, val artist: String, val source: String, val scene: String, val road: String, val roadSrc: String,
    val highway: String, val wayId: Long?, val factors: List<FactorScore>, val genres: List<String>, val pool: String, val favorite: Boolean,
    val cand: Int?, val rank: Int?, val exRecent: Int, val exArtist: Int, val reason: String, val matched: List<String>
) {
    fun toJson(): JSONObject = JSONObject().put("at", at).put("title", title).put("artist", artist).put("source", source).put("scene", scene)
        .put("road", road).put("roadSrc", roadSrc).put("highway", highway).put("wayId", wayId ?: JSONObject.NULL)
        .put("factors", JSONArray(factors.map { JSONObject().put("name", it.name).put("label", it.label).put("score", it.score) }))
        .put("genres", JSONArray(genres)).put("pool", pool).put("favorite", favorite).put("cand", cand ?: JSONObject.NULL).put("rank", rank ?: JSONObject.NULL)
        .put("exRecent", exRecent).put("exArtist", exArtist).put("reason", reason).put("matched", JSONArray(matched))
    companion object {
        private fun JSONArray.strs() = (0 until length()).map { getString(it) }
        fun fromJson(o: JSONObject) = SelRecord(o.getLong("at"), o.optString("title"), o.optString("artist"), o.optString("source"), o.optString("scene"),
            o.optString("road"), o.optString("roadSrc"), o.optString("highway"), if (o.isNull("wayId")) null else o.optLong("wayId"),
            o.optJSONArray("factors")?.let { a -> (0 until a.length()).map { a.getJSONObject(it).let { f -> FactorScore(f.optString("name"), f.optString("label"), f.optDouble("score")) } } } ?: emptyList(),
            o.optJSONArray("genres")?.strs() ?: emptyList(), o.optString("pool"), o.optBoolean("favorite"),
            if (o.isNull("cand")) null else o.optInt("cand"), if (o.isNull("rank")) null else o.optInt("rank"), o.optInt("exRecent"), o.optInt("exArtist"),
            o.optString("reason"), o.optJSONArray("matched")?.strs() ?: emptyList())
    }
}
data class PlainReason(val text: String, val words: List<String>)

object SelectionLog {
    const val MAX = 50
    private const val KEY = "giltune_sellog"
    private val FACTOR = mapOf("wx" to "날씨", "temp" to "기온", "time" to "시간대", "twilight" to "일출·일몰", "day" to "주말·공휴일", "commute" to "출퇴근",
        "jam" to "정체", "longDrive" to "장거리 주행", "lateNight" to "심야", "season" to "계절", "speed" to "속도")

    fun push(list: List<SelRecord>, rec: SelRecord, max: Int = MAX): List<SelRecord> = (listOf(rec) + list).take(max)

    private fun josa(w: String, a: String, b: String): String { val c = w.lastOrNull() ?: return b; return if (c in '\uac00'..'\ud7a3' && (c - '\uac00') % 28 != 0) a else b }

    /** Plain Korean reason: a real title/album word if any, otherwise the top of genre / favorite artist / weather / time. */
    fun plainReason(t: SpotifyTrack, sceneLabel: String, sceneId: String?, factors: List<Factor>, r: SceneRules): PlainReason {
        val (ti, al) = QueryPlanner.titleMatches(t, t.words); val w = ti.firstOrNull() ?: al.firstOrNull()
        if (w != null) {
            val where = if (ti.isNotEmpty()) "제목" else "앨범 이름"
            fun inG(k: String?) = (r.titleWords[k] ?: emptyList()).map { it.lowercase() }.contains(w)
            val why = when { inG("RAIN") && !inG(sceneId) -> "비가 와서"; inG("night") -> "밤이라서"; inG("morning") -> "아침이라서"; else -> sceneLabel + josa(sceneLabel, "이라서", "라서") }
            return PlainReason("$why 이 곡을 틀었어요. ${where}에 \"$w\"${josa(w, "이", "가")} 들어 있어요", (ti + al).distinct())
        }
        val c = ArrayList<Pair<Double, String>>()
        val k = t.key ?: ""
        if (k.startsWith("genre:")) r.genreLabels[k.removePrefix("genre:")]?.let { c += 1.0 to "고른 장르($it)에 맞춰 이 곡을 틀었어요" }
        if (k.startsWith("artist:")) c += 1.1 to "좋아하는 아티스트(${t.artist})의 곡이에요"
        factors.filter { it.label != null && it.id.startsWith("wx") }.forEach { c += it.weight to "${it.label} 날씨에 어울리는 곡이에요" }
        factors.filter { it.label != null && Regex("^(time|lateNight|twilight)").containsMatchIn(it.id) }.forEach { c += it.weight to "${it.label}에 어울리는 곡이에요" }
        return PlainReason(c.maxByOrNull { it.first }?.second ?: "${sceneLabel}에 어울리는 곡이에요", emptyList())
    }

    fun record(now: Long, t: SpotifyTrack, spotify: Boolean, road: RoadInfo, sceneLabel: String, factors: List<Factor>, genres: List<String>, r: SceneRules, reason: PlainReason): SelRecord {
        val s = t.sel
        return SelRecord(now, t.name, t.artist, if (spotify) "Spotify" else "자체 음원", sceneLabel, road.type.label,
            (r.sourceLabels[road.source] ?: road.source) + if (road.geoHit) " + 내장 해안·강 데이터" else "", road.highway ?: "", road.wayId,
            factors.map { FactorScore(FACTOR[it.id.substringBefore(':')] ?: it.id, it.label ?: "", Math.round(it.weight * 100) / 100.0) },
            genres.map { r.genreLabels[it] ?: it }, s?.pool ?: if (spotify) "검색" else "자체 음원", s?.favorite == true || (t.key ?: "").startsWith("artist:"),
            s?.cand, s?.rank?.takeIf { it > 0 }, s?.exRecent ?: 0, s?.exArtist ?: 0, reason.text, reason.words)
    }

    private fun two(n: Int) = n.toString().padStart(2, '0')
    fun fmtTime(ms: Long): String { val d = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "${d.get(java.util.Calendar.MONTH) + 1}/${d.get(java.util.Calendar.DAY_OF_MONTH)} ${two(d.get(java.util.Calendar.HOUR_OF_DAY))}:${two(d.get(java.util.Calendar.MINUTE))}" }
    fun recordText(x: SelRecord, i: Int? = null): String {
        val f = if (x.factors.isEmpty()) "없음" else x.factors.joinToString(", ") { "${it.name}${if (it.label.isNotEmpty()) "(${it.label})" else ""} ${fmtScore(it.score)}" }
        return listOf(
            "${if (i != null) "${i + 1}. " else ""}${fmtTime(x.at)}  ${x.title} — ${x.artist} [${x.source}]",
            "   이유: ${x.reason.ifEmpty { "-" }}${if (x.matched.isNotEmpty()) " (제목 일치: ${x.matched.joinToString(", ")})" else ""}",
            "   위치: ${x.scene} · ${x.road} (판정: ${x.roadSrc}${if (x.highway.isNotEmpty()) " · 도로 등급 ${x.highway}" else ""}${x.wayId?.let { " · OSM way $it" } ?: ""})",
            "   요소 점수: $f",
            "   장르: ${x.genres.joinToString(", ").ifEmpty { "선택 없음(장면 기준)" }} · 출처: ${x.pool}${if (x.favorite) " · 좋아하는 아티스트 몫" else ""}",
            "   후보 ${x.cand ?: "-"}곡 중 ${x.rank ?: "-"}위 · 제외: 최근 40곡 ${x.exRecent} · 최근 8아티스트 ${x.exArtist}"
        ).joinToString("\n")
    }
    private fun fmtScore(d: Double) = if (d == Math.floor(d)) d.toLong().toString() else d.toString()
    fun logText(list: List<SelRecord>): String =
        (listOf("길튠 선곡 기록 (최근 ${list.size}곡)", "GILTUNE · SANORA WEB", "") + list.mapIndexed { i, x -> recordText(x, i) + "\n" }).joinToString("\n").trim() + "\n"

    fun load(c: Context): List<SelRecord> = try {
        val a = JSONArray(c.getSharedPreferences("giltune", Context.MODE_PRIVATE).getString(KEY, "[]")); (0 until a.length()).map { SelRecord.fromJson(a.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }
    fun add(c: Context, rec: SelRecord) {
        val list = push(load(c), rec)
        c.getSharedPreferences("giltune", Context.MODE_PRIVATE).edit().putString(KEY, JSONArray(list.map { it.toJson() }).toString()).apply()
    }
}
