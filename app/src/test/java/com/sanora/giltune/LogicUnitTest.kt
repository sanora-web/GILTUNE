package com.sanora.giltune

import org.junit.Assert.*
import org.junit.Test

class DrivingJudgeTest {
    init { TestRules.load() }
    @Test fun parkIsNotDrivingEvenWithSpeed() = assertFalse(DrivingJudge.isDriving(GearState.PARK, 20f))
    @Test fun notParkIsDrivingEvenWhenStopped() = assertTrue(DrivingJudge.isDriving(GearState.NOT_PARK, 0f))
    @Test fun unknownGearUsesSpeed() {
        assertTrue(DrivingJudge.isDriving(GearState.UNKNOWN, 3f))
        assertFalse(DrivingJudge.isDriving(GearState.UNKNOWN, 0f))
        assertFalse(DrivingJudge.isDriving(GearState.UNKNOWN, 2.4f))   // below scene_rules speed.drivingMps 2.5
        assertTrue(DrivingJudge.isDriving(GearState.UNKNOWN, 2.5f))
        assertFalse(DrivingJudge.isDriving(GearState.UNKNOWN, null))
        assertFalse(DrivingJudge.isDriving(GearState.UNKNOWN, Float.NaN))
    }
}

class RainSceneTest {
    @Test fun manualDemoAlwaysRain() = assertTrue(RainScene.isRainy(false, false, true))
    @Test fun weatherRainIsRain() {
        assertTrue(RainScene.isRainy(null, true, false))
        assertTrue(RainScene.isRainy(false, true, false))
        assertTrue(RainScene.isRainy(true, true, false))
    }
    @Test fun wiperOnlyCountsWhenWeatherUnknown() {
        assertTrue(RainScene.isRainy(true, null, false))
        assertFalse(RainScene.isRainy(true, false, false))  // washer use on a clear day
    }
    @Test fun nothingKnownIsDry() {
        assertFalse(RainScene.isRainy(null, null, false))
        assertFalse(RainScene.isRainy(false, false, false))
    }
}

class RecommendationTest {
    init { TestRules.load() }
    private val engine = RuleBasedRecommendationEngine()
    private val yanghwa = RoadEngine.zones.first { it.id == "yanghwa" }
    @Test fun bridgeBeatsRainAndNight() =
        assertEquals(Scene.BRIDGE, engine.recommend(SceneContext(yanghwa, true, 23)))
    @Test fun rainBeatsNight() = assertEquals(S("RAIN"), engine.recommend(SceneContext(null, true, 23)))
    @Test fun nightHours() {
        // v3: time of day never decides the scene (mood weight only)
        assertEquals(Scene.CRUISE, engine.recommend(SceneContext(null, false, 20)))
        assertEquals(Scene.CRUISE, engine.recommend(SceneContext(null, false, 5)))
        assertEquals(Scene.CRUISE, engine.recommend(SceneContext(null, false, 6)))
        assertEquals(Scene.CRUISE, engine.recommend(SceneContext(null, false, 19)))
    }
    @Test fun wiperAndWeatherCombinationDrivesRainScene() {
        val rainy = RainScene.isRainy(wiperOn = true, weatherRainy = null, manualDemo = false)
        assertEquals(S("RAIN"), engine.recommend(SceneContext(null, rainy, 14)))
        val washer = RainScene.isRainy(wiperOn = true, weatherRainy = false, manualDemo = false)
        assertEquals(Scene.CRUISE, engine.recommend(SceneContext(null, washer, 14)))
    }
    @Test fun everySceneMapsToAnExistingSynthTrack() {
        for (s in Scene.all()) assertTrue(s.local, s.local in LocalSynthProvider.FILES)
        for (r in SceneRules.current.rules) assertTrue(r.scene.local in LocalSynthProvider.FILES)
        // legacy files are gone from the project
        for (f in listOf("clear", "bridge", "rain", "autumn")) assertFalse(java.io.File("src/main/res/raw/$f.mp3").exists())
        for (f in LocalSynthProvider.FILES) assertTrue(java.io.File("src/main/res/raw/$f").exists())
    }
}

class BridgeNaviTest {
    // ~100 m from the Yanghwa bridge axis: outside the 55 m GPS entry radius.
    private val near = PositionSample(37.5420, 126.9027, 6f)
    @Test fun gpsAloneDoesNotEnterAt100m() {
        val e = RoadEngine()
        e.update(near, 0L); assertNull(e.update(near, 2000L).currentZone)
    }
    @Test fun naviAddressConfirmsBridgeWithinRadius() {
        val e = RoadEngine()
        val r = e.update(near, 0L, naviAddress = "서울 마포구 양화대교")
        assertEquals("yanghwa", r.currentZone?.id); assertTrue(r.switched)
    }
    @Test fun naviAddressIgnoredWhenFarAway() {
        val e = RoadEngine()
        val far = PositionSample(37.5600, 126.9500, 6f)
        assertNull(e.update(far, 0L, naviAddress = "양화대교").currentZone)
    }
    @Test fun inaccurateFixRejected() {
        val e = RoadEngine()
        assertFalse(e.update(PositionSample(37.5421, 126.9016, 250f), 0L).accurate)
    }
    @Test fun unknownAccuracyNeedsFourSamples() {
        val e = RoadEngine()
        val onBridge = PositionSample(37.5421, 126.9016, 35f, accuracyKnown = false)
        for (i in 0 until 3) assertNull(e.update(onBridge, i * 2000L).currentZone)
        assertEquals("yanghwa", e.update(onBridge, 8000L).currentZone?.id)
    }
}
