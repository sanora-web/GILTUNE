package com.sanora.giltune

import org.json.JSONArray
import org.json.JSONObject

/*
 * 장르 곡 풀 — web/js/engine.js (buildGenrePool, genreFit, pickFromPools) 와 같은 규칙.
 * 장르 플레이리스트 → 장르 대표 아티스트 → 장르명+연도 검색(장르 근거 있는 곡만). 근거 없는 곡은 절대 넣지 않음.
 * Author: SANORA WEB
 */
data class GenreSource(val playlist: List<String>, val text: String, val keywords: List<String>, val artists: List<String>) {
    companion object {
        private fun JSONArray.ss() = (0 until length()).map { getString(it) }
        fun parse(o: JSONObject) = GenreSource(o.getJSONArray("playlist").ss(), o.getString("text"), o.getJSONArray("keywords").ss(), o.getJSONArray("artists").ss())
    }
}
data class GenrePoolConfig(val playlistsPerGenre: Int = 3, val playlistTracks: Int = 50, val artistsPerBuild: Int = 4,
                           val refreshMin: Int = 30, val years: List<String> = emptyList(), val minPool: Int = 10, val maxArtistLookups: Int = 8) {
    companion object {
        fun parse(o: JSONObject) = GenrePoolConfig(o.optInt("playlistsPerGenre", 3), o.optInt("playlistTracks", 50), o.optInt("artistsPerBuild", 4),
            o.optInt("refreshMin", 30), o.optJSONArray("years")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(), o.optInt("minPool", 10), o.optInt("maxArtistLookups", 8))
    }
}
data class PlaylistRef(val id: String, val name: String, val owner: String?, val total: Int)
data class PlaylistResult(val name: String, val ok: Boolean, val n: Int, val err: String?)
data class GenrePoolResult(val g: String, val tracks: List<SpotifyTrack>, val playlists: List<PlaylistResult>,
                           val fromPlaylist: Int, val fromArtist: Int, val fromText: Int, val at: Long)

/** Spotify calls the pool needs (throw on failure). */
interface GenreApi {
    fun searchPlaylists(q: String): List<PlaylistRef>
    fun playlistItems(id: String): List<SpotifyTrack>
    fun searchTracks(q: String, offset: Int): List<SpotifyTrack>
    fun artistGenres(id: String): List<String>
}

object GenrePool {
    private fun hasKw(text: String, kws: List<String>) = text.lowercase().let { t -> kws.any { t.contains(it.lowercase()) } }

    /** Genre evidence or null (drop). */
    fun genreFit(t: SpotifyTrack, g: String, r: SceneRules, artistGenres: Map<String, List<String>> = emptyMap()): String? {
        val src = r.genreSources[g] ?: return null
        val names = t.artist.lowercase().split(", ")
        if (src.artists.any { it.lowercase() in names }) return "대표 아티스트"
        if (t.artistIds.any { hasKw((artistGenres[it] ?: emptyList()).joinToString(" "), src.keywords) }) return "아티스트 장르"
        if (hasKw(t.album, src.keywords) || hasKw(t.name, src.keywords)) return "앨범·곡 이름"
        return null
    }

    /** Name must carry a genre keyword; user-owned before Spotify-owned (blocked in Development Mode). */
    fun choosePlaylists(list: List<PlaylistRef>, g: String, r: SceneRules): List<PlaylistRef> {
        val src = r.genreSources[g] ?: return emptyList()
        return list.filter { hasKw(it.name, src.keywords + src.text) }
            .sortedWith(compareBy<PlaylistRef> { if (it.owner == "spotify") 1 else 0 }.thenByDescending { it.total }).take(r.genrePool.playlistsPerGenre)
    }

    fun build(g: String, r: SceneRules, api: GenreApi, rnd: () -> Double): GenrePoolResult {
        val src = r.genreSources[g]!!; val cfg = r.genrePool
        val out = LinkedHashMap<String, SpotifyTrack>(); val pls = ArrayList<PlaylistResult>(); var nP = 0; var nA = 0; var nT = 0
        // 1) 장르 플레이리스트 (개발 모드: 내 것이 아니면 403 → 건너뜀)
        val q = src.playlist[(rnd() * src.playlist.size).toInt() % src.playlist.size]
        val chosen = try { choosePlaylists(api.searchPlaylists(q), g, r) } catch (e: Exception) { emptyList() }
        for (p in chosen) try {
            val items = api.playlistItems(p.id)
            items.forEach { if (out.putIfAbsent(it.uri, it.copy(fit = "장르 플레이리스트: ${p.name}")) == null) nP++ }
            pls += PlaylistResult(p.name, true, items.size, null)
        } catch (e: Exception) { pls += PlaylistResult(p.name, false, 0, e.message) }
        // 2) 장르 대표 아티스트 (그 아티스트가 실제 참여한 곡만)
        for (a in src.artists.shuffled(java.util.Random((rnd() * 1e9).toLong())).take(cfg.artistsPerBuild)) try {
            api.searchTracks("artist:\"$a\"", 0).forEach { if (genreFit(it, g, r) != null && out.putIfAbsent(it.uri, it.copy(fit = "대표 아티스트: $a")) == null) nA++ }
        } catch (_: Exception) { }
        // 3) 그래도 적으면 장르명+연도 — 장르 근거 있는 곡만
        if (out.size < cfg.minPool) {
            val ag = HashMap<String, List<String>>()
            for (y in cfg.years.shuffled(java.util.Random((rnd() * 1e9).toLong())).take(2)) {
                val found = try { api.searchTracks("${src.text} year:$y", 0) } catch (_: Exception) { continue }
                for (t in found) {
                    // 주 아티스트만, 묶음당 최대 maxArtistLookups번(요청 수 제한)
                    t.artistIds.firstOrNull()?.let { id -> if (id !in ag && ag.size < cfg.maxArtistLookups) ag[id] = try { api.artistGenres(id) } catch (_: Exception) { emptyList() } }
                    val fit = genreFit(t, g, r, ag) ?: continue
                    if (out.putIfAbsent(t.uri, t.copy(fit = fit)) == null) nT++
                }
            }
        }
        return GenrePoolResult(g, out.values.toList(), pls, nP, nA, nT, System.currentTimeMillis())
    }

    /** Excluded by recent tracks (last 40) / recent artists (last 8). */
    fun exclusionCounts(list: List<SpotifyTrack>, recentUris: Collection<String>, recentArtists: Collection<String>): Pair<Int, Int> {
        val ru = recentUris.toHashSet(); val ra = recentArtists.toHashSet()
        return list.count { it.uri in ru } to list.count { it.uri !in ru && it.artistIds.any { a -> a in ra } }
    }
    /** Round-robin over genres; inside each genre situation sort; recent tracks / artists excluded. Key = genre:g. */
    fun pick(pools: Map<String, GenrePoolResult>, genres: List<String>, n: Int, words: Map<String, List<String>>, moodKeys: Map<String, String?>,
             recentUris: Collection<String>, recentArtists: Collection<String>, r: SceneRules, rnd: () -> Double): List<SpotifyTrack> {
        val ru = recentUris.toHashSet(); val ra = recentArtists.toHashSet(); val seenA = HashMap<String, Int>(); val out = ArrayList<SpotifyTrack>()
        val lists = genres.associateWith { QueryPlanner.sortBySituation(pools[it]?.tracks ?: emptyList(), words[it] ?: emptyList(), rnd).toMutableList() }
        val rankOf = lists.mapValues { (_, l) -> l.withIndex().associate { it.value.uri to it.index + 1 } }
        val stat = lists.mapValues { (_, l) -> Triple(l.size, l.count { it.uri in ru }, l.count { it.uri !in ru && it.artistIds.any { a -> a in ra } }) }
        fun ok(t: SpotifyTrack) = t.uri !in ru && t.artistIds.none { it in ra || (seenA[it] ?: 0) >= r.taste.maxPerArtist }
        var i = 0; var idle = 0
        while (out.size < n && idle < genres.size) {
            val g = genres[i++ % genres.size]; val l = lists[g]!!; val k = l.indexOfFirst { ok(it) }
            if (k < 0) { idle++; continue }; idle = 0
            val t = l.removeAt(k); ru += t.uri; t.artistIds.forEach { seenA.merge(it, 1, Int::plus) }
            val st = stat[g]!!
            out += t.copy(key = "genre:$g", moodKey = moodKeys[g], words = words[g] ?: emptyList(), sel = SelInfo(st.first, rankOf[g]!![t.uri] ?: 0, st.second, st.third, t.fit, false))
        }
        return out
    }
}
