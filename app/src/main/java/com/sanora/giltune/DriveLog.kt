package com.sanora.giltune

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DriveLogRow(val timeMs: Long, val lat: Double, val lon: Double, val speedMps: Float?,
                       val road: String, val roadName: String?, val region: String?, val scene: Scene,
                       val track: String, val source: String, val driving: Boolean) {
    fun csv(): String {
        val t = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.KOREA).format(Date(timeMs))
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        return listOf(t, "%.6f".format(Locale.US, lat), "%.6f".format(Locale.US, lon),
            speedMps?.let { "%.1f".format(Locale.US, it * 3.6f) } ?: "", q(road), q(roadName), q(region),
            scene.name, q(track), q(source), if (driving) "1" else "0").joinToString(",")
    }
    companion object {
        const val HEADER = "time_kst,lat,lon,speed_kmh,road_type,road_name,region,scene,track,location_source,driving"
    }
}

/** Real-drive log: CSV under app-specific external storage (…/files/drive_logs), plus last 300 rows in memory. */
class DriveLogger(context: Context) {
    val dir = File(context.getExternalFilesDir(null) ?: context.filesDir, "drive_logs").apply { mkdirs() }
    var file: File? = null; private set
    private var lastWriteMs = 0L
    companion object {
        val recent = ArrayDeque<DriveLogRow>()
        @Volatile var currentFile: File? = null
    }

    fun append(row: DriveLogRow, force: Boolean = false) {
        if (!force && row.timeMs - lastWriteMs < 2_000L) return
        lastWriteMs = row.timeMs
        val f = file ?: File(dir, "drive_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.KOREA).format(Date(row.timeMs)) + ".csv")
            .also { it.writeText("\uFEFF" + DriveLogRow.HEADER + "\n"); file = it; currentFile = it }
        try { f.appendText(row.csv() + "\n") } catch (_: Exception) {}
        synchronized(recent) { recent.addLast(row); while (recent.size > 300) recent.removeFirst() }
    }
}
