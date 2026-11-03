package com.sanora.giltune

import kotlin.math.*

/** Schematic demo geofences. NOT lane-level map matching. */
data class BridgeZone(val id: String, val title: String, val latitudeA: Double, val longitudeA: Double,
                      val latitudeB: Double, val longitudeB: Double) {
    fun distanceMeters(lat: Double, lon: Double): Double {
        val radians = Math.toRadians((latitudeA + latitudeB) / 2.0)
        val xScale = 111_320.0 * cos(radians)
        val ax = longitudeA * xScale; val ay = latitudeA * 111_320.0
        val bx = longitudeB * xScale; val by = latitudeB * 111_320.0
        val px = lon * xScale; val py = lat * 111_320.0
        val dx = bx - ax; val dy = by - ay
        val divisor = dx * dx + dy * dy
        val fraction = if (divisor < 1.0) 0.0
            else (((px - ax) * dx + (py - ay) * dy) / divisor).coerceIn(0.0, 1.0)
        return hypot(px - (ax + fraction * dx), py - (ay + fraction * dy))
    }
}

data class PositionSample(val lat: Double, val lon: Double, val accuracy: Float, val speedMps: Float? = null, val accuracyKnown: Boolean = true)
data class RoadUpdate(val currentZone: BridgeZone?, val switched: Boolean, val accurate: Boolean,
                      val distanceToZone: Double?)

class RoadEngine {
    companion object {
        val zones = listOf(
            BridgeZone("yanghwa", "양화대교", 37.5397, 126.9020, 37.5474, 126.9008),
            BridgeZone("mapo", "마포대교", 37.5286, 126.9339, 37.5373, 126.9324),
            BridgeZone("banpo", "반포대교", 37.5130, 126.9975, 37.5220, 126.9943)
        )
    }
    private val NAVI_CONFIRM_METERS = 150.0
    private var activeZone: BridgeZone? = null
    private var candidateId: String? = null
    private var consecutive = 0
    private var lockedUntilMs = 0L

    fun reset() { activeZone = null; candidateId = null; consecutive = 0; lockedUntilMs = 0L }

    /**
     * Requires 2 accurate samples and a short cooldown against GPS jitter.
     * [naviAddress]: current address from Pleos NaviHelper onCurrentLocationInfo (optional).
     * If it names a bridge and GPS is within [NAVI_CONFIRM_METERS], one sample is enough.
     */
    fun update(sample: PositionSample, nowMs: Long = System.currentTimeMillis(),
               naviAddress: String? = null): RoadUpdate {
        if (!sample.lat.isFinite() || !sample.lon.isFinite() ||
            sample.lat !in -90.0..90.0 || sample.lon !in -180.0..180.0 ||
            sample.accuracy > 35f || sample.accuracy < 0f) {
            return RoadUpdate(activeZone, false, false, null)
        }
        val nearest = zones.map { it to it.distanceMeters(sample.lat, sample.lon) }
            .minByOrNull { it.second }
        // 55m enters; 90m exits (hysteresis). GPS alone cannot exclude roads under bridges.
        val inZone = nearest?.takeIf { (zone, distance) ->
            distance <= if (zone.id == activeZone?.id) 90.0 else if (sample.accuracyKnown) 55.0 else 35.0
        }?.first
        val naviZone = naviAddress?.let { addr -> zones.firstOrNull { addr.contains(it.title) } }
            ?.takeIf { it.distanceMeters(sample.lat, sample.lon) <= NAVI_CONFIRM_METERS }
        val target = naviZone ?: inZone
        val id = target?.id
        if (candidateId == id) consecutive++ else { candidateId = id; consecutive = 1 }
        var switched = false
        val needed = if (naviZone != null) 1 else if (sample.accuracyKnown) 2 else 4
        if (consecutive >= needed && activeZone?.id != id && nowMs >= lockedUntilMs) {
            activeZone = target
            switched = true
            lockedUntilMs = nowMs + 1500L
        }
        return RoadUpdate(activeZone, switched, true, nearest?.second)
    }
}

