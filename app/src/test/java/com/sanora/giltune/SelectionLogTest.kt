package com.sanora.giltune

import org.junit.Assert.*
import org.junit.Test

/** 선곡 기록 · 쉬운 선곡 이유 · 도로 근거 (web/test/sellog_test.js 와 같은 내용). SANORA WEB */
class SelectionLogTest {
    private val r = TestRules.load()
    private val road = RoadInfo(RoadType.values().first(), "선화로6길", false, "nominatim", highway = "residential", wayId = 926615730L)
    private fun tr(name: String, album: String = "", words: List<String> = emptyList()) =
        SpotifyTrack("u:$name", name, "A", listOf("a1"), key = "genre:pop", album = album, words = words,
            sel = SelInfo(120, 3, 5, 2, "장르 플레이리스트", false))
    private fun rec(t: SpotifyTrack) = SelectionLog.record(1_000L, t, true, road, "골목길", listOf(Factor("wxRain", "비", emptyList(), 0.6)), listOf("pop"), r,
        SelectionLog.plainReason(t, "골목길", "ALLEY", listOf(Factor("wxRain", "비", emptyList(), 0.6)), r))

    @Test fun capAt50() {
        var l = emptyList<SelRecord>(); repeat(60) { l = SelectionLog.push(l, rec(tr("s$it"))) }
        assertEquals(50, l.size); assertEquals("s59", l.first().title)
    }
    @Test fun allFieldsAndJsonRoundTrip() {
        val x = rec(tr("Rain Song", words = listOf("rain")))
        assertEquals(926615730L, x.wayId); assertEquals("residential", x.highway); assertEquals(120, x.cand); assertEquals(3, x.rank)
        assertEquals(5, x.exRecent); assertEquals(2, x.exArtist); assertEquals("장르 플레이리스트", x.pool); assertTrue(x.factors.isNotEmpty())
        assertEquals(x, SelRecord.fromJson(x.toJson()))
    }
    @Test fun textExport() {
        val t = SelectionLog.logText(listOf(rec(tr("Rain Song", words = listOf("rain")))))
        for (s in listOf("Rain Song", "926615730", "residential", "120", "이유:")) assertTrue(s, t.contains(s))
        assertFalse(t.contains("{")); assertFalse(t.contains("null"))
    }
    @Test fun reasonUsesRealTitleWord() {
        val p = SelectionLog.plainReason(tr("Rain Song", words = listOf("rain")), "골목길", "ALLEY", emptyList(), r)
        assertTrue(p.text, p.text.contains("\"rain\"")); assertEquals(listOf("rain"), p.words)
    }
    @Test fun reasonFallsBackWithoutInventing() {
        val p = SelectionLog.plainReason(tr("Hello", words = listOf("rain", "비")), "골목길", "ALLEY", emptyList(), r)
        assertTrue(p.words.isEmpty()); assertTrue(p.text, p.text.contains("장르"))
    }
    @Test fun oneLetterKoreanNeedsWholeWord() {
        assertTrue(QueryPlanner.titleMatches(tr("비밀"), listOf("비")).first.isEmpty())
        assertEquals(listOf("비"), QueryPlanner.titleMatches(tr("비가 오는 날"), listOf("비")).first)
    }
    @Test fun nominatimWayClassAndAddressGuess() {
        val way = org.json.JSONObject("""{"category":"highway","type":"residential","osm_type":"way","osm_id":926615730,"address":{"road":"선화로6길"}}""")
        val (e, src) = NominatimMapper.road(way); assertEquals("nominatim", src); assertEquals("residential", e[0]["highway"])
        val ri = RoadClassifier.classify(e, null, r, src); assertEquals("residential", ri.highway); assertEquals(926615730L, ri.wayId)
        val (e2, src2) = NominatimMapper.road(org.json.JSONObject("""{"category":"building","type":"yes","address":{"road":"무왕대로"}}"""))
        assertEquals("address", src2); assertEquals("primary", e2[0]["highway"])
        assertEquals("none", NominatimMapper.road(null).second)
    }
}
