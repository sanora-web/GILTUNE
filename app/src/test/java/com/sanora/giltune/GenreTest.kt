package com.sanora.giltune

import org.junit.Assert.*
import org.junit.Test

/** 장르 칩 고정 + 상황 반영 (web/test/genre_test.js 와 같은 내용). Spotify 검색은 가짜 응답 — 실제 결과 수 아님. */
class GenreTest {
    private val r = TestRules.load()
    private var seed = 7L
    private val rnd = { seed = (seed * 16807) % 2147483647; seed / 2147483647.0 }
    private val sparse = setOf("korean ballad", "trot")   // 희소 태그 가정: genre: 검색 0건
    private val words = (r.sceneList.flatMap { it.keywords } + r.taste.moods.values).joinToString(" ").lowercase().split(Regex("\\s+")).filter { it.length > 2 }.distinct()
    private fun catalog(g: String) = (0 until 30).map { i ->
        SpotifyTrack("spotify:track:$g$i", "$g song $i ${words[(i * 7 + g.length) % words.size]}", "A", listOf("${g}A$i"), popularity = (i * 13) % 100) }
    private fun genreOf(uri: String) = uri.substringAfterLast(':').trimEnd { it.isDigit() }
    private val calls = ArrayList<String>()
    private fun fake(q: String, off: Int): SearchPage {
        calls += "$q@$off"
        val tag = Regex("genre:\"([^\"]+)\"").find(q)?.groupValues?.get(1)
        val g = if (tag != null) tag.takeUnless { it in sparse } else r.genreText.entries.firstOrNull { it.value == q }?.let { r.genres[it.key] }
        val all = g?.let { catalog(it) } ?: emptyList()
        return SearchPage(all.drop(off).take(10), all.size)
    }
    private fun prof(g: String) = TasteProfile(mutableListOf(g), mutableListOf(), mutableListOf())   // 아티스트 없음 → 100% 장르

    @Test fun all13GenresOnlyThatGenre() {
        assertEquals(13, r.genres.size)
        for (g in r.genres.keys) {
            val plans = QueryPlanner.buildPlans(Scene.byId("DOWNTOWN")!!, prof(g), r, PlanContext(), rnd)
            assertEquals(listOf("genre:$g"), plans.map { it.key })
            calls.clear(); val (found, q) = QueryPlanner.searchPlan(plans[0], rnd, ::fake)
            assertTrue(g, found.isNotEmpty()); found.forEach { assertEquals(r.genres[g], genreOf(it.uri)) }
            println("[MOCK] $g: ${found.size}곡 (검색어 $q, 호출 ${calls.size}회)")
        }
    }
    @Test fun zeroResultFallsBackToGenreName() {
        for (g in listOf("ballad", "trot")) {
            val pl = QueryPlanner.buildPlans(Scene.byId("CRUISE")!!, prof(g), r, PlanContext(), rnd)[0]
            val (f, q) = QueryPlanner.searchPlan(pl, rnd, ::fake); assertEquals(r.genreText[g], q); assertTrue(f.isNotEmpty())
        }
    }
    @Test fun offsetClamp() {
        assertEquals(20, QueryPlanner.clampOffset(80, 30) { 0.99 }); assertEquals(10, QueryPlanner.clampOffset(10, 30, rnd))
        assertEquals(0, QueryPlanner.clampOffset(50, 5, rnd)); assertEquals(40, QueryPlanner.clampOffset(40, null, rnd))
        val (f, _) = QueryPlanner.searchPlan(Plan("genre:\"jazz\"", "genre:jazz", null, 90, 1.0, fallback = listOf("genre:\"jazz\"", "jazz")), rnd, ::fake)
        assertTrue(f.size >= 3)
    }
    private fun combo(g: String, sid: String, ctx: PlanContext, label: String) {
        val sc = Scene.byId(sid)!!
        repeat(30) {
            val pl = QueryPlanner.buildPlans(sc, prof(g), r, ctx, rnd)[0]
            val f = QueryPlanner.sortBySituation(QueryPlanner.searchPlan(pl, rnd, ::fake).first, pl.words, rnd)
            f.forEach { assertEquals("off-genre", r.genres[g], genreOf(it.uri)) }
            assertTrue(pl.reason.joinToString(), pl.reason.contains(ctx.sceneLabel!!) && pl.reason.contains(r.genreLabels[g]!!))
        }
        println("$label: " + QueryPlanner.buildPlans(sc, prof(g), r, ctx, rnd)[0].let { "q=${it.q} · 이유=${it.reason.joinToString(" · ")}" })
    }
    @Test fun balladRainCoastal() = combo("ballad", "COASTAL", PlanContext(PlanContext.at(2026, 10, 29, 21), wxCode = 61, rain = true, sceneLabel = "바닷길"), "발라드+비+해안")
    @Test fun kpopClearHighway() = combo("kpop", "HIGHWAY", PlanContext(PlanContext.at(2026, 10, 29, 13), wxCode = 0, speedMps = 28.0, sceneLabel = "고속도로"), "케이팝+맑음+고속도로")
    @Test fun sceneChangesPicks() {
        val tracks = catalog("k-pop")
        val tops = r.sceneList.map { sc -> QueryPlanner.sortBySituation(tracks, QueryPlanner.situationWords(sc, null, r)) { 0.0 }.take(5).joinToString { it.uri } }.toSet()
        assertTrue("scene changes order: ${tops.size}", tops.size >= 2)
    }
    @Test fun favoriteArtistsMixedAbout22Percent() {
        val sc = Scene.byId("COASTAL")!!
        val p = TasteProfile(mutableListOf("ballad", "kpop"), mutableListOf(), mutableListOf(Artist("A1", "IU"), Artist("A2", "성시경")))
        var art = 0; var tot = 0
        repeat(2000) {
            QueryPlanner.allocate(QueryPlanner.buildPlans(sc, p, r, PlanContext(sceneLabel = sc.label, rain = true), rnd), r.taste.batchSize, rnd)
                .forEach { (pl, n) -> tot += n; if (pl.key.startsWith("artist:")) art += n }
        }
        val ratio = art.toDouble() / tot; println("아티스트 곡 비율 ${"%.1f".format(ratio * 100)}% (곡 $tot 자리)")
        assertTrue("ratio $ratio", ratio in 0.20..0.25)
        val plans = QueryPlanner.buildPlans(sc, p, r, PlanContext(sceneLabel = sc.label), rnd)
        val ap = plans.first { it.key == "artist:A1" }
        assertTrue(ap.reason.joinToString(), "좋아하는 아티스트: IU" in ap.reason && ap.reason[0] == sc.label && ap.words.isNotEmpty())
        println("아티스트 이유: " + ap.reason.joinToString(" · "))
        for (pl in plans.filter { it.fallback.isNotEmpty() }) QueryPlanner.searchPlan(pl, rnd, ::fake).first.forEach { assertTrue(genreOf(it.uri) in listOf(r.genres["ballad"], r.genres["kpop"])) }
        assertTrue(QueryPlanner.buildPlans(sc, prof("jazz"), r, PlanContext(), rnd).all { it.key.startsWith("genre:") })
    }
}
