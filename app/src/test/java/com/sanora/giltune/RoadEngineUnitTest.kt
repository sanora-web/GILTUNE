package com.sanora.giltune

import org.junit.Assert.*
import org.junit.Test

class RoadEngineUnitTest {
    init { TestRules.load() }
    @Test fun bridgeEntryAndExit() {
        val engine = RoadEngine()
        val route = listOf(
            PositionSample(37.5330, 126.9120, 6f),
            PositionSample(37.5352, 126.9090, 6f),
            PositionSample(37.5385, 126.9043, 6f),
            PositionSample(37.5414, 126.9017, 6f),
            PositionSample(37.5421, 126.9016, 6f),
            PositionSample(37.5445, 126.9013, 6f),
            PositionSample(37.5467, 126.9010, 6f),
            PositionSample(37.5500, 126.9029, 6f),
            PositionSample(37.5511, 126.9046, 6f),
            PositionSample(37.5520, 126.9050, 6f)
        )
        val results = route.mapIndexed { index, sample -> engine.update(sample, 1000L + 2200L * index) }
        assertNull(results.first().currentZone)
        assertTrue(results.any { it.currentZone?.id == "yanghwa" })
        assertNull(results.last().currentZone)
    }

    @Test fun ignoresInaccurateGps() {
        val response = RoadEngine().update(PositionSample(37.543, 126.901, 250f))
        assertFalse(response.accurate)
        assertFalse(response.switched)
    }

    @Test fun preferencesDoNotChangeBridgePriority() {
        val e = RuleBasedRecommendationEngine()
        assertEquals(Scene.BRIDGE, e.recommend(SceneContext(RoadEngine.zones.first(), true, 21)))
        assertEquals("RAIN", e.recommend(SceneContext(null, true, 21)).id)
        assertEquals("CRUISE", e.recommend(SceneContext(null, false, 21)).id)
    }
}
