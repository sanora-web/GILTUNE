package com.sanora.giltune

/** Vehicle gear as GILTUNE needs it. Mapped from Pleos VehicleGear in the pleos flavor. */
enum class GearState { PARK, NOT_PARK, UNKNOWN }

/**
 * Callbacks from the vehicle bridge. In the `pleos` flavor they are driven by the official
 * FusedLocation / NaviHelper / Vehicle SDK; in the `demo` flavor nothing is ever delivered.
 * All callbacks are posted on the main thread.
 */
class PleosCallbacks(
    val onLocation: (PositionSample) -> Unit,
    val onRoadAddress: (String?) -> Unit,
    val onSpeedLimit: (Int) -> Unit,
    val onUpcoming: (UpcomingSegment?) -> Unit,
    val onGear: (GearState) -> Unit,
    val onWiper: (Boolean?) -> Unit,
    val onStatus: (group: String, message: String) -> Unit
)

/** A special road segment ahead on the active NaviHelper route. */
data class UpcomingSegment(val sceneId: String, val distanceM: Int, val description: String)

/**
 * Predicts the next tunnel / bridge / highway entry from NaviHelper turn-by-turn guidance.
 * Input = onTBTInfo(List<TBTInfo>) fields that exist in navi-helper 3.0.5 (javap):
 * TBTInfo.type (TurnType), TBTInfo.distance (m), TBTInfo.description.
 * TurnType.TURN_TUNNEL -> TUNNEL, TURN_ENTER_IC_* -> HIGHWAY; a guidance text naming a
 * bridge ("대교" / "교") -> BRIDGE. Only items within [horizonM] are used.
 */
object RoutePredictor {
    const val HORIZON_M = 2_000
    data class Tbt(val turnType: String, val distanceM: Double, val description: String?)
    private val bridgeWord = Regex("(대교|철교)(?![가-힣])")
    fun next(items: List<Tbt>, horizonM: Int = HORIZON_M): UpcomingSegment? = items
        .filter { it.distanceM >= 0 && it.distanceM <= horizonM }
        .sortedBy { it.distanceM }
        .firstNotNullOfOrNull { t ->
            val d = t.description.orEmpty()
            val id = when {
                t.turnType == "TURN_TUNNEL" || d.contains("터널") -> "TUNNEL"
                bridgeWord.containsMatchIn(d) -> "BRIDGE"
                t.turnType.startsWith("TURN_ENTER_IC") -> "HIGHWAY"
                else -> null
            }
            id?.let { UpcomingSegment(it, t.distanceM.toInt(), d.ifBlank { t.turnType }) }
        }
}

/**
 * GILTUNE's OWN driving rule (not an official Pleos driving-restriction API — none was found
 * in the Pleos documentation). Gear != P means driving. If the gear is unknown, fall back to
 * GPS speed above a small noise margin.
 */
object DrivingJudge {
    /** scene_rules.json speed.drivingMps (2.5 m/s) - same threshold as the web version. */
    val movingSpeedMps: Float get() = (SceneRules.currentOrNull()?.drivingMps ?: 2.5).toFloat()
    fun isDriving(gear: GearState, speedMps: Float?): Boolean = when (gear) {
        GearState.PARK -> false
        GearState.NOT_PARK -> true
        GearState.UNKNOWN -> speedMps != null && speedMps.isFinite() && speedMps >= movingSpeedMps
    }
}

/**
 * Rain scene = vehicle wiper + weather. Wiper ON alone while the weather service says
 * "clear" is treated as washer use, not rain.
 * @param wiperOn null when the vehicle does not report it
 * @param weatherRainy null when weather is unavailable
 */
object RainScene {
    fun isRainy(wiperOn: Boolean?, weatherRainy: Boolean?, manualDemo: Boolean): Boolean = when {
        manualDemo -> true
        weatherRainy == true -> true
        wiperOn == true && weatherRainy == null -> true
        else -> false
    }
}
