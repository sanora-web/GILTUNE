package com.sanora.giltune

/** Inputs of the recommendation engine. Independent of any music source. */
data class SceneContext(val zone: BridgeZone?, val rainy: Boolean, val hour: Int,
                        val road: RoadInfo? = null, val speedMps: Float? = null)

/** Decides WHAT kind of music fits the moment (scene_rules.json). Knows nothing about audio. */
interface RecommendationEngine { fun recommend(context: SceneContext): Scene }

class RuleBasedRecommendationEngine : RecommendationEngine {
    override fun recommend(context: SceneContext): Scene {
        val road = context.road
        // An entered bridge geofence (RoadEngine) counts as roadType BRIDGE.
        val type = if (context.zone != null) "BRIDGE" else (road?.type ?: RoadType.UNKNOWN).name
        return ScenePicker.pick(SceneInput(type, context.rainy, road?.coastal == true, road?.river == true,
            context.hour, context.speedMps?.toDouble()))
    }
}

/** A playable item. Local tracks carry a raw resource id; Spotify tracks carry [uri]. */
data class Track(val id: String, val title: String, val scene: Scene,
                 val rawResId: Int? = null, val uri: String? = null, val artist: String? = null)

interface MusicProvider {
    val name: String
    fun trackFor(scene: Scene): Track
}

/**
 * Fallback: tracks synthesized by tools/music/generate_tracks.py (pure math oscillators,
 * no samples; SHA-256 in tools/music/TRACKS_SHA256.txt). Which file plays for a scene is the
 * `local` field of scene_rules.json. Made by SANORA WEB.
 */
class LocalSynthProvider : MusicProvider {
    override val name = "GILTUNE 합성 음원"
    private val files = mapOf(
        "highway.mp3" to (R.raw.highway to "하이웨이 펄스"),
        "city_express.mp3" to (R.raw.city_express to "시티 라인"),
        "alley.mp3" to (R.raw.alley to "골목 산책"),
        "tunnel.mp3" to (R.raw.tunnel to "터널 에코"),
        "coastal.mp3" to (R.raw.coastal to "바닷바람"))
    /** One of the 5 files for [scene] (shuffle bag). */
    fun trackForFile(file: String, scene: Scene): Track {
        val (res, title) = files[file] ?: files.getValue("highway.mp3")
        val shown = SceneRules.currentOrNull()?.localTitles?.get(file) ?: title
        return Track("local:$file", "$shown — GILTUNE", scene, rawResId = res)
    }
    override fun trackFor(scene: Scene): Track {
        val (res, title) = files[scene.local] ?: files.getValue("highway.mp3")
        val shown = SceneRules.currentOrNull()?.localTitles?.get(scene.local) ?: title
        return Track("local:${scene.id}", "$shown — GILTUNE", scene, rawResId = res)
    }
    companion object { val FILES = setOf("highway.mp3", "city_express.mp3", "alley.mp3", "tunnel.mp3", "coastal.mp3") }
}
