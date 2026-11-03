package com.sanora.giltune

import org.junit.Assert.*
import org.junit.Test

/** 장르 곡 풀 (web/test/pool_test.js 와 같은 내용). Spotify 응답은 가짜 — 실제 결과 아님. */
class GenrePoolTest {
    private val r = TestRules.load()
    private var seed = 11L
    private val rnd = { seed = (seed * 16807) % 2147483647; seed / 2147483647.0 }
    private val G = r.genres.keys.toList()
    // 테스트용: q 필드에 정답 장르를 넣어 둠
    private fun tr(id: String, name: String, artist: String, album: String = "", aid: String = id + "A", g: String? = null) =
        SpotifyTrack("spotify:track:$id", name, artist, listOf(aid), album = album, q = g)

    private inner class Mock(val playlist403: Boolean, val artistEmpty: Boolean = false, val tagFor: (String) -> List<String> = { emptyList() }) : GenreApi {
        val calls = ArrayList<String>()
        override fun searchPlaylists(q: String): List<PlaylistRef> { calls += "pl:$q"; val g = G.first { q in r.genreSources[it]!!.playlist }
            return listOf(PlaylistRef("P$g", "$q 모음", "user9", 80), PlaylistRef("S$g", "$q (editorial)", "spotify", 200), PlaylistRef("X", "아무 노래", "u", 9)) }
        override fun playlistItems(id: String): List<SpotifyTrack> { calls += "items:$id"
            if (playlist403 || id.startsWith("S")) throw IllegalStateException("HTTP 403")
            val g = id.substring(1); return (0 until 30).map { tr("${g}p$it", "$g pl song $it", "Some $g Artist $it", g = g) } }
        override fun searchTracks(q: String, offset: Int): List<SpotifyTrack> { calls += "tr:$q"
            Regex("^artist:\"(.+)\"$").find(q)?.let { m -> if (artistEmpty) return emptyList(); val a = m.groupValues[1]; val g = G.first { a in r.genreSources[it]!!.artists }
                return (0 until 10).map { tr("${g}a$a$it", "$a hit $it", if (it == 9) "Other Person" else a, aid = "$a#${it % 3}", g = g) } }
            val g = G.first { q.startsWith(r.genreSources[it]!!.text + " ") }
            return (0 until 10).map { if (it < 3) tr("${g}t$q$it", "노래 $it", "x", "${r.genreSources[g]!!.keywords[0]} best", g = g) else tr("junk$q$it", "무관 $it", "Random Guy", "Whatever", aid = "R$it") } }
        override fun artistGenres(id: String) = tagFor(id)
    }

    @Test fun playlistPoolSkips403AndUnrelated() {
        val m = Mock(false); val p = GenrePool.build("ballad", r, m, rnd)
        assertTrue(p.playlists.any { it.ok && it.n == 30 }); assertTrue(p.playlists.any { !it.ok })
        assertFalse("items:X" in m.calls); assertEquals(30, p.fromPlaylist); assertTrue(p.tracks.all { it.q == "ballad" })
    }
    @Test fun devMode403UsesRepresentativeArtistsOnly() {
        val p = GenrePool.build("trot", r, Mock(true), rnd)
        assertEquals(0, p.fromPlaylist); assertTrue(p.fromArtist >= 30); assertTrue(p.tracks.all { it.q == "trot" && it.artist != "Other Person" })
    }
    @Test fun textFallbackKeepsOnlyEvidence() {
        val p = GenrePool.build("jazz", r, Mock(true, artistEmpty = true), rnd)
        assertTrue(p.fromText > 0); assertTrue(p.tracks.all { it.q == "jazz" })
        val q = GenrePool.build("rock", r, Mock(true, artistEmpty = true, tagFor = { if (it == "R4") listOf("korean rock") else emptyList() }), rnd)
        assertTrue(q.tracks.any { it.artistIds == listOf("R4") }); assertTrue(q.tracks.none { it.q == null && it.artistIds != listOf("R4") })
    }
    @Test fun genreFitRules() {
        assertEquals("대표 아티스트", GenrePool.genreFit(tr("1", "a", "성시경"), "ballad", r))
        assertEquals("앨범·곡 이름", GenrePool.genreFit(tr("1", "a", "nobody", "Best Jazz Standards"), "jazz", r))
        assertNull(GenrePool.genreFit(tr("1", "a", "BTS"), "ballad", r))
        assertNull(GenrePool.genreFit(tr("1", "Dynamite", "nobody", "Pop album"), "kpop", r))
    }
    @Test fun all13GenresPoolNonEmptyZeroOffGenre() {
        assertEquals(13, G.size)
        val rows = G.map { g -> val p = GenrePool.build(g, r, Mock(true), rnd); assertTrue(g, p.tracks.size >= 10); assertTrue(g, p.tracks.all { it.q == g }); "$g ${p.tracks.size}" }
        println("[MOCK] 풀 크기: " + rows.joinToString())
    }
    @Test fun multiGenreEvenSplitNoRepeat() {
        val pools = mapOf("ballad" to GenrePool.build("ballad", r, Mock(false), rnd), "pop" to GenrePool.build("pop", r, Mock(true), rnd))
        val cnt = HashMap<String, Int>(); val hist = ArrayList<SpotifyTrack>()
        repeat(10) {
            val recent = hist.takeLast(40)
            val picks = GenrePool.pick(pools, listOf("ballad", "pop"), 6, emptyMap(), emptyMap(), recent.map { it.uri }, recent.takeLast(8).flatMap { it.artistIds }, r, rnd)
            assertEquals(6, picks.size)
            picks.forEach { t -> val g = t.key!!.removePrefix("genre:"); assertEquals("off-genre", g, t.q); cnt.merge(g, 1, Int::plus); assertTrue(recent.none { it.uri == t.uri }) }
            hist += picks
        }
        assertEquals(cnt["ballad"], cnt["pop"]); println("발라드+팝송 60곡: $cnt")
    }
    @Test fun emptyPoolGivesNothing() {
        assertTrue(GenrePool.pick(mapOf("jazz" to GenrePoolResult("jazz", emptyList(), emptyList(), 0, 0, 0, 0)), listOf("jazz"), 6, emptyMap(), emptyMap(), emptyList(), emptyList(), r, rnd).isEmpty())
    }
}
