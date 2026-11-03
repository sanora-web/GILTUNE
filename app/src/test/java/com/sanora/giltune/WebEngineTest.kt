package com.sanora.giltune

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/** The 18 cases of web/test/engine.test.js, ported 1:1 (same names, same assertions). */
class WebEngineTest {
    private val r = TestRules.load()
    private fun H(h: String, vararg x: Pair<String, String>): OsmTags = mapOf("highway" to h) + x
    private fun ctx(road: String = "DOWNTOWN", hour: Int = 14, speed: Double? = 12.0, rain: Boolean = false, coastal: Boolean = false, river: Boolean = false) =
        SceneInput(road, rain, coastal, river, hour, speed)
    private fun profile(genres: List<String> = emptyList(), moods: List<String> = emptyList(), artists: List<Artist> = emptyList()) =
        TasteProfile(genres.toMutableList(), moods.toMutableList(), artists.toMutableList())
    private fun t(uri: String, vararg a: String) = SpotifyTrack(uri, uri, "", a.toList())

    /** Web asserts this before its tests: assets copy == shared/scene_rules.json (skipped only outside the workspace). */
    @Test fun t00_assetsEqualSharedRules() {
        val shared = File("../../../shared/scene_rules.json"); org.junit.Assume.assumeTrue(shared.isFile)
        assertEquals(shared.readText(), File("src/main/assets/scene_rules.json").readText())
    }
    @Test fun t01_classifyMotorwayTrunkBridgeTunnel() {
        assertEquals(RoadType.MOTORWAY, RoadClassifier.classify(listOf(H("motorway")), 25.0, r).type)
        assertEquals(RoadType.URBAN_EXPRESSWAY, RoadClassifier.classify(listOf(H("trunk"), H("residential")), 20.0, r).type)
        assertEquals("양화대교", RoadClassifier.classify(listOf(H("primary", "bridge" to "yes", "name" to "양화대교")), 15.0, r).name)
        assertEquals(RoadType.BRIDGE, RoadClassifier.classify(listOf(H("primary"), H("primary", "bridge" to "yes")), 15.0, r).type)
        assertEquals(RoadType.TUNNEL, RoadClassifier.classify(listOf(H("motorway", "tunnel" to "yes")), 25.0, r).type)
        assertEquals(RoadType.UNKNOWN, RoadClassifier.classify(emptyList(), 10.0, r).type)
    }
    @Test fun t03_slowUnderElevatedExpresswayIsLocal() =
        assertEquals(RoadType.ALLEY, RoadClassifier.classify(listOf(H("motorway"), H("residential")), 3.0, r).type)
    @Test fun t04_riverCoastFlags() {
        val x = RoadClassifier.classify(listOf(H("primary"), mapOf("waterway" to "river"), mapOf("natural" to "coastline")), 15.0, r)
        assertTrue(x.river && x.coastal)
    }
    @Test fun t05_scenePriority() {
        assertEquals("DOWNTOWN", ScenePicker.pick(ctx(), r).id)
        assertEquals("TUNNEL", ScenePicker.pick(ctx(road = "TUNNEL", rain = true), r).id)
        assertEquals("RAIN", ScenePicker.pick(ctx(rain = true), r).id)
        assertEquals("COASTAL", ScenePicker.pick(ctx(coastal = true), r).id)
        assertEquals("RIVERSIDE", ScenePicker.pick(ctx(river = true), r).id)
        assertEquals("ALLEY", ScenePicker.pick(ctx(road = "ALLEY", river = true), r).id)
        assertEquals("DOWNTOWN", ScenePicker.pick(ctx(hour = 22), r).id)   // time never decides the scene
        assertEquals("HIGHWAY", ScenePicker.pick(ctx(road = "UNKNOWN", speed = 30.0), r).id)
        assertEquals("CRUISE", ScenePicker.pick(ctx(road = "UNKNOWN", speed = 5.0), r).id)
    }
    @Test fun t06_everySceneHasLocalTrack() {
        for (s in r.sceneList) {
            assertTrue(s.local, s.local in LocalSynthProvider.FILES)
            assertTrue(s.local, File("src/main/res/raw/${s.local}").isFile)
        }
    }
    @Test fun t07_throttle() {
        val q = QueryThrottle(15_000, 150.0, 180_000)
        assertTrue(q.shouldQuery(0, 37.5, 127.0)); q.mark(0, 37.5, 127.0)
        assertFalse(q.shouldQuery(20_000, 37.5005, 127.0))
        assertTrue(q.shouldQuery(20_000, 37.502, 127.0))
        assertFalse(q.shouldQuery(5_000, 37.51, 127.0))
        q.backoff(20_000, 60_000); assertFalse(q.shouldQuery(30_000, 37.6, 127.0))
    }
    @Test fun t08_stabilizer() {
        val s = SceneStabilizer(r)
        assertTrue(s.propose("DOWNTOWN", 0).changed)
        assertFalse(s.propose("ALLEY", 5_000).changed)
        assertFalse(s.propose("ALLEY", 10_000).changed)
        assertTrue(s.propose("ALLEY", 41_000).changed)
        assertFalse(s.propose("BRIDGE", 42_000).changed)
        assertTrue(s.propose("BRIDGE", 45_000).changed)
    }
    @Test fun t09_fakeGpsRouteEndToEnd() {
        data class Seg(val dur: Int, val v: Double, val els: List<OsmTags>, val rain: Boolean)
        val segs = listOf(
            Seg(120, 6.0, listOf(H("residential")), false),
            Seg(180, 14.0, listOf(H("primary", "name" to "강변북로 진입로")), false),
            Seg(60, 15.0, listOf(H("primary", "bridge" to "yes", "name" to "양화대교"), mapOf("waterway" to "river")), false),
            Seg(300, 22.0, listOf(H("trunk", "name" to "올림픽대로"), mapOf("waterway" to "riverbank")), false),
            Seg(40, 22.0, listOf(H("trunk", "tunnel" to "yes")), false),
            Seg(600, 28.0, listOf(H("motorway", "ref" to "1")), false),
            Seg(300, 28.0, listOf(H("motorway")), true),
            Seg(300, 15.0, listOf(H("secondary"), mapOf("natural" to "coastline")), false),
            Seg(8, 15.0, listOf(H("residential")), false),
            Seg(300, 15.0, listOf(H("secondary"), mapOf("natural" to "coastline")), false))
        val thr = QueryThrottle(r.osmMinIntervalMs, r.osmMinMoveM, r.osmMaxAgeMs); val st = SceneStabilizer(r)
        var t = 0L; var lat = 37.5; val lon = 126.9; var road = RoadInfo.UNKNOWN; var queries = 0; val changes = ArrayList<String>()
        for (sg in segs) for (k in 0 until sg.dur step 2) {
            t += 2000; lat += sg.v * 2 / 111000
            if (thr.shouldQuery(t, lat, lon)) { thr.mark(t, lat, lon); queries++; road = RoadClassifier.classify(sg.els, sg.v, r) }
            val sc = ScenePicker.pick(SceneInput(road.type.name, sg.rain, road.coastal, road.river, 15, sg.v), r)
            val res = st.propose(sc.id, t); if (res.changed) changes.add(res.id)
        }
        assertEquals(listOf("ALLEY", "DOWNTOWN", "BRIDGE", "RIVERSIDE", "TUNNEL", "HIGHWAY", "RAIN", "COASTAL"), changes)
        assertTrue(queries < t / r.osmMinIntervalMs + 1)
    }
    @Test fun t10_plansVaryPerCall() {
        val p = profile(listOf("kpop", "trot"), listOf("chill"), listOf(Artist("A1", "IU")))
        val sc = Scene.byId("BRIDGE")!!
        val plans = QueryPlanner.buildPlans(sc, p, r, PlanContext(speedMps = 15.0), Math::random)
        assertEquals(listOf("genre:kpop", "genre:trot", "artist:A1"), plans.map { it.key })   // 장르 + 좋아하는 아티스트 섞기
        assertTrue(plans[0].q.startsWith("genre:\"k-pop\"") && plans[1].q.startsWith("genre:\"trot\""))
        assertEquals(listOf("genre:\"k-pop\"", "K-pop"), plans[0].fallback)
        plans.forEach { assertTrue(it.offset % 10 == 0 && it.offset <= r.taste.offsetMax) }
        val qs = HashSet<String>()
        repeat(40) { val a = QueryPlanner.buildPlans(sc, p, r, PlanContext(), Math::random)[0]; qs.add(a.q + "@" + a.offset) }
        assertTrue("queries vary: ${qs.size}", qs.size >= 8)
    }
    @Test fun t11_timeOnlyAffectsMoods() {
        val night = QueryPlanner.moodPool(profile(), r, PlanContext(PlanContext.at(2026, 10, 28, 23))).map { it.m }
        listOf("chill", "dreamy", "emotional").forEach { assertTrue(it, it in night) }
        assertEquals("morning", QueryPlanner.timeBand(8, r)); assertEquals("night", QueryPlanner.timeBand(2, r))
        val fr = QueryPlanner.moodPool(profile(), r, PlanContext(PlanContext.at(2026, 10, 28, 14), speedMps = 25.0, rain = true)).map { it.m }
        assertTrue("energetic" in fr && "calm" in fr)
    }
    @Test fun t12_diversify() =
        assertEquals(listOf("1", "5"), QueryPlanner.diversify(listOf(t("1", "A"), t("2", "A"), t("3", "B"), t("4", "C"), t("5", "D")), listOf("4"), listOf("B"), r).map { it.uri })
    @Test fun t13_learning() {
        val p = profile(listOf("kpop", "jazz"))
        repeat(20) { QueryPlanner.applyFeedback(p, SpotifyTrack("u1", "", "", listOf("J"), key = "genre:jazz"), "TUNNEL", "skip", r) }
        QueryPlanner.applyFeedback(p, SpotifyTrack("u2", "", "", listOf("K"), key = "genre:kpop", moodKey = "mood:chill"), "TUNNEL", "like", r)
        assertEquals(r.taste.minW, p.w["genre:jazz"]!!, 0.0)
        assertEquals(1.5, p.w["genre:kpop"]!!, 0.0); assertEquals(1.5, p.w["TUNNEL|genre:kpop"]!!, 0.0); assertEquals(1.5, p.w["mood:chill"]!!, 0.0)
        val tun = QueryPlanner.buildPlans(Scene.byId("TUNNEL")!!, p, r, PlanContext(), Math::random)
        assertTrue(tun.first { it.key == "genre:kpop" }.weight > tun.first { it.key == "genre:jazz" }.weight * 50)
        val city = QueryPlanner.buildPlans(Scene.byId("DOWNTOWN")!!, p, r, PlanContext(), Math::random)
        assertEquals(1.5, city.first { it.key == "genre:kpop" }.weight, 0.0)
        assertTrue("u2" in p.liked)
    }
    @Test fun t14_pickPlansWeightedNoDuplicates() {
        val plans = listOf(Plan("", "a", null, 0, 100.0), Plan("", "b", null, 0, 0.001), Plan("", "c", null, 0, 1.0))
        val seq = listOf(0.1, 0.99).iterator(); val got = QueryPlanner.pickPlans(plans, 2) { seq.next() }
        assertEquals("a", got[0].key); assertEquals(2, got.map { it.key }.toSet().size)
    }
    @Test fun t15_rankTracks() {
        val p = profile(); p.w["artist:X"] = 2.5; p.skips["bad"] = 2
        assertEquals(listOf("x", "n"), QueryPlanner.rankTracks(listOf(t("n", "Y"), t("x", "X"), t("bad", "X")), p, r) { 0.5 }.map { it.uri })
    }
    @Test fun t16_listenKind() {
        assertEquals("skip", QueryPlanner.listenKind(10.0, 200.0, r))
        assertEquals("complete", QueryPlanner.listenKind(170.0, 200.0, r))
        assertNull(QueryPlanner.listenKind(90.0, 200.0, r))
    }
    @Test fun t17_nominatimFallback() {
        fun c(j: String) = RoadClassifier.classify(NominatimMapper.toElements(JSONObject(j)), 20.0, r)
        assertEquals(RoadType.MOTORWAY, c("""{"category":"highway","type":"motorway","name":"경부고속도로"}""").type)
        assertEquals(RoadType.BRIDGE, c("""{"category":"highway","type":"primary","extratags":{"bridge":"yes"},"name":"양화대교"}""").type)
        assertEquals(RoadType.TUNNEL, c("""{"category":"highway","type":"trunk","extratags":{"tunnel":"yes"}}""").type)
        assertEquals(RoadType.ALLEY, c("""{"category":"highway","type":"residential"}""").type)
        assertEquals(RoadType.UNKNOWN, c("""{"category":"building","type":"yes"}""").type)
        assertEquals("양화로", c("""{"category":"highway","type":"primary","address":{"road":"양화로"}}""").name)
    }
    @Test fun t17b_fixSpeed() {
        fun P(lat: Double, t: Long, acc: Double = 5.0, speed: Double? = null) = SpeedEstimator.Fix(lat, 127.0, t, acc, speed)
        assertTrue(abs(SpeedEstimator.fixSpeed(P(37.5, 0), P(37.5 + 30 / 111195.0, 2000), r)!! - 15) < 0.3)
        assertTrue(abs(SpeedEstimator.fixSpeed(P(37.5, 0), P(37.5 + 20 / 111195.0, 1000, 5.0, -1.0), r)!! - 20) < 0.3)
        assertNull(SpeedEstimator.fixSpeed(P(37.5, 0, 80.0), P(37.501, 2000, 80.0), r))
        assertEquals(0.0, SpeedEstimator.fixSpeed(P(37.5, 0, 20.0), P(37.5 + 5 / 111195.0, 2000, 20.0), r)!!, 0.0)
    }
    @Test fun t18_drivingThresholdShared() = assertEquals(2.5, r.drivingMps, 0.0)

    @Test fun t19_speedBandFallback() {
        assertEquals(RoadType.ALLEY, SpeedRoad.speedRoad(4.0, r).type)
        assertEquals(RoadType.DOWNTOWN, SpeedRoad.speedRoad(12.0, r).type)
        assertEquals(RoadType.URBAN_EXPRESSWAY, SpeedRoad.speedRoad(25.0, r).type)
        assertEquals("none", SpeedRoad.speedRoad(null, r).source)
        val m = RoadInfo(RoadType.BRIDGE, null, false, "overpass")
        assertEquals(RoadType.BRIDGE, SpeedRoad.resolve(m, 0, 10_000, 25.0, r).type)
        assertEquals("speed", SpeedRoad.resolve(m, 0, r.speedBandStaleMs + 1, 25.0, r).source)
        assertEquals(RoadType.ALLEY, SpeedRoad.resolve(null, 0, 1, 4.0, r).type)
        fun pick(x: RoadInfo, v: Double) = ScenePicker.pick(SceneInput(x.type.name, false, x.coastal, x.river, 3, v), r).id
        assertEquals("CITY_EXPRESS", pick(SpeedRoad.speedRoad(25.0, r), 25.0))
        assertEquals("ALLEY", pick(SpeedRoad.speedRoad(4.0, r), 4.0))
    }
    @Test fun t20_lookupThrottle() {
        assertEquals(100.0, r.osmMinMoveM, 0.0); assertEquals(8000L, r.osmMinIntervalMs)
        assertTrue(r.nominatimMinIntervalMs >= 1000)
    }
    @Test fun t21_stabilizerStatus() {
        val st = SceneStabilizer(r); st.propose("DOWNTOWN", 0)
        var s = st.status(10_000); assertNull(s.cand); assertEquals(30L, (s.holdLeftMs + 999) / 1000)
        st.propose("ALLEY", 12_000); s = st.status(12_000)
        assertEquals("ALLEY", s.cand); assertEquals(1, s.count); assertEquals(2, s.confirmCount)
        assertEquals(r.confirmMs, s.confirmLeftMs); assertEquals(28_000L, s.holdLeftMs)
        st.propose("BRIDGE", 13_000); s = st.status(14_000); assertTrue(s.urgent); assertEquals(0L, s.holdLeftMs)
    }

    // ───────── v5: built-in coast / river data, elevated fix, sustained fast, selection factors ─────────
    private val geoFile = File("src/main/assets/geo_kr.json")
    private val geo by lazy { GeoIndex(JSONObject(geoFile.readText()), r.geo.cellDeg) }
    private fun H2(h: String, vararg x: Pair<String, String>) = listOf(H(h, *x))

    @Test fun t22_geoDataSize() = assertTrue(geoFile.length().toString(), geoFile.length() < 500 * 1024)
    @Test fun t23_geoPlaces() {
        assertTrue(geo.onWater(37.544, 126.902)); assertFalse(geo.onWater(37.5665, 126.978))
        assertTrue(geo.distM("river", 37.5665, 126.978, 200.0).isInfinite())
        assertTrue(geo.distM("coast", 33.4630, 126.3110, r.coastRadiusM.toDouble()).isFinite())
        assertTrue(geo.distM("coast", 33.5430, 126.6690, r.coastRadiusM.toDouble()).isFinite())
    }
    @Test fun t24_geoFast() {
        geo.distM("river", 37.5, 126.9, 200.0)
        val t0 = System.currentTimeMillis()
        for (i in 0 until 10000) geo.distM("river", 37.5 + (i % 100) / 1000.0, 126.9 + (i % 37) / 1000.0, 200.0)
        assertTrue(System.currentTimeMillis() - t0 < 500)
    }
    @Test fun t25_elevatedVsRealBridge() {
        val elev = GeoIndex.applyGeo(RoadClassifier.classify(H2("trunk", "bridge" to "yes", "name" to "강변북로"), 20.0, r), 37.5465, 126.913, geo, r)
        assertEquals(RoadType.URBAN_EXPRESSWAY, elev.type); assertTrue(elev.elevated)
        val br = GeoIndex.applyGeo(RoadClassifier.classify(H2("trunk", "bridge" to "yes", "name" to "양화대교"), 20.0, r), 37.544, 126.902, geo, r)
        assertEquals(RoadType.BRIDGE, br.type); assertTrue(br.river)
        val along = GeoIndex.applyGeo(RoadClassifier.classify(H2("trunk", "bridge" to "yes", "name" to "강변북로"), 20.0, r), 37.544, 126.902, geo, r)
        assertEquals(RoadType.URBAN_EXPRESSWAY, along.type)
        val near = GeoIndex.applyGeo(RoadClassifier.classify(H2("trunk", "bridge" to "yes", "name" to "강변북로"), 20.0, r), 37.51964, 126.99559, geo, r)
        assertEquals(RoadType.URBAN_EXPRESSWAY, near.type); assertTrue(near.river)
        val down = GeoIndex.applyGeo(RoadClassifier.classify(H2("primary", "bridge" to "yes"), 10.0, r), 37.5665, 126.978, geo, r)
        assertEquals(RoadType.BRIDGE, down.type)
    }
    @Test fun t26_riversideFromBuiltInData() {
        val x = GeoIndex.applyGeo(RoadInfo(RoadType.URBAN_EXPRESSWAY, null, false, "nominatim", cls = "URBAN_EXPRESSWAY"), 37.5465, 126.913, geo, r)
        assertTrue(x.river); assertEquals("RIVERSIDE", ScenePicker.pick(SceneInput(x.type.name, false, x.coastal, x.river, 14, 20.0), r).id)
    }
    @Test fun t27_fastSustain() {
        val d = DriveTracker(r); for (t in 0..31) d.push(t * 1000L, 17.5, "DOWNTOWN")
        assertEquals(RoadType.URBAN_EXPRESSWAY, SpeedRoad.resolve(null, 0, 31_000, 17.5, r, d.fastSec(31_000)).type)
        assertEquals(RoadType.DOWNTOWN, SpeedRoad.resolve(null, 0, 31_000, 17.5, r, 10.0).type)
    }
    @Test fun t28_jamAndLongDrive() {
        val d = DriveTracker(r); for (t in 0..200) d.push(t * 1000L, 4.0, "URBAN_EXPRESSWAY"); assertTrue(d.jam())
        val f = DriveTracker(r); for (t in 0..200) f.push(t * 1000L, 20.0, "URBAN_EXPRESSWAY"); assertFalse(f.jam())
        val s = DriveTracker(r); for (t in 0..200) s.push(t * 1000L, if (t % 40 < 8) 0.0 else 9.0, "DOWNTOWN"); assertTrue("stop-and-go", s.jam())
        val l = DriveTracker(r); for (m in 0..91) l.push(m * 60_000L, 15.0, "DOWNTOWN"); assertTrue(l.driveMin(91 * 60_000L) >= 90)
        val b = DriveTracker(r); for (m in 0..50) b.push(m * 60_000L, 15.0, "DOWNTOWN"); for (m in 62..100) b.push(m * 60_000L, 15.0, "DOWNTOWN")
        assertTrue("10+ min break resets", b.driveMin(100 * 60_000L) < 90)
    }
    @Test fun t29_weatherClasses() =
        assertEquals(listOf("clear", "cloudy", "fog", "rain", "heavyRain", "heavyRain", "snow"), listOf(0, 3, 45, 61, 65, 95, 73).map { QueryPlanner.weatherClass(it, r) })
    @Test fun t30_contextFactors() {
        fun ids(c: PlanContext) = QueryPlanner.contextFactors(c, r).map { it.id }
        fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) = PlanContext.at(y, mo, d, h, mi)
        assertTrue("추석", "day:holiday" in ids(PlanContext(at(2026, 9, 25, 14))))
        assertTrue("설 대체공휴일", "day:holiday" in ids(PlanContext(at(2027, 2, 9, 14))))
        assertTrue("day:weekend" in ids(PlanContext(at(2026, 10, 31, 14))))
        assertTrue("commute:am" in ids(PlanContext(at(2026, 10, 28, 8))))
        assertTrue("commute:pm" in ids(PlanContext(at(2026, 10, 28, 18, 30))))
        assertFalse("commute:pm" in ids(PlanContext(at(2026, 10, 31, 18, 30))))
        assertTrue("lateNight" in ids(PlanContext(at(2026, 10, 28, 2))))
        val ss = at(2026, 10, 28, 17, 40)
        assertTrue("twilight:sunset" in ids(PlanContext(ss, sunset = ss + 20 * 60_000)))
        assertTrue("temp:hot" in ids(PlanContext(ss, tempC = 31.0))); assertTrue("temp:cold" in ids(PlanContext(ss, tempC = -2.0)))
        assertTrue("jam" in ids(PlanContext(ss, jam = true))); assertTrue("longDrive" in ids(PlanContext(ss, driveMin = 95.0)))
        assertTrue("wx:heavyRain" in ids(PlanContext(ss, wxCode = 65)))
    }
    @Test fun t31_holidayList() {
        val h = JSONObject(File("src/main/assets/scene_rules.json").readText()).getJSONObject("holidays")
        assertTrue(h.getString("source").length > 20); assertEquals(46, r.holidays.size)
    }
    @Test fun t32_noFactorDominates() {
        var x = 7L; val rnd = { x = (x * 16807) % 2147483647; x / 2147483647.0 }
        val ctx = PlanContext(PlanContext.at(2026, 10, 30, 18, 20), wxCode = 63, tempC = 2.0, sunset = PlanContext.at(2026, 10, 30, 17, 40),
            speedMps = 3.0, jam = true, driveMin = 100.0, sceneLabel = "해안", roadReasons = listOf("해안도로"))
        val factors = QueryPlanner.contextFactors(ctx, r); val fsum = factors.sumOf { it.weight }
        factors.forEach { assertTrue(it.id, it.weight / fsum <= 0.35) }
        val p = profile(listOf("ballad")); val cnt = HashMap<String, Int>()
        repeat(4000) { val pl = QueryPlanner.buildPlans(r.sceneList.first(), p, r, ctx, rnd)[0]; cnt.merge(pl.moodKey ?: "none", 1, Int::plus) }
        assertTrue("variety $cnt", cnt.size >= 5); assertTrue("top mood < 40% $cnt", cnt.values.max() / 4000.0 < 0.4)
    }
    @Test fun t33_reasonLine() {
        val p = profile(listOf("ballad")); var x = 3L; val rnd = { x = (x * 16807) % 2147483647; x / 2147483647.0 }
        val ctx = PlanContext(PlanContext.at(2026, 10, 28, 18, 30), wxCode = 61, sceneLabel = "바닷길", roadReasons = listOf("해안도로"))
        val why = QueryPlanner.buildPlans(Scene.byId("COASTAL")!!, p, r, ctx, rnd)[0].reason
        assertEquals("바닷길", why[0]); assertTrue("해안도로" in why); assertTrue("발라드" in why)
        assertTrue(why.joinToString(" · "), why.size <= 4 + r.taste.context.maxReasons)
    }
}
