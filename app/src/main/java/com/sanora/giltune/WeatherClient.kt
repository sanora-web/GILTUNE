package com.sanora.giltune

import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/** Actual current weather from Open-Meteo (no API key). Distinct from manual demo rain. */
class WeatherClient(private val rules: SceneRules = SceneRules.current) {
    /** code: Open-Meteo weather_code, sunrise/sunset: epoch ms of today (daily). */
    data class Result(val rainy: Boolean, val temperature: Double, val description: String,
                      val code: Int?, val wxClass: String?, val sunrise: Long?, val sunset: Long?)
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private var lastRequestMs = 0L

    fun fetch(lat: Double, lon: Double, onResult: (Result?) -> Unit) {
        if (!lat.isFinite() || !lon.isFinite()) return
        val now = System.currentTimeMillis()
        if (now - lastRequestMs < rules.weatherMinIntervalMs) return
        lastRequestMs = now
        executor.execute {
            var connection: HttpURLConnection? = null
            val weather = try {
                val endpoint = "${rules.weatherEndpoint}?latitude=$lat&longitude=$lon&current=temperature_2m,precipitation,weather_code&daily=sunrise,sunset&forecast_days=1&timezone=auto"
                connection = URL(endpoint).openConnection() as HttpURLConnection
                connection.connectTimeout = 6000
                connection.readTimeout = 6000
                connection.setRequestProperty("User-Agent", "GILTUNEContestPrototype/4.0 (weather display)")
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(text); val obj = root.getJSONObject("current")
                val off = java.time.ZoneOffset.ofTotalSeconds(root.optInt("utc_offset_seconds", 0))
                fun day(k: String) = root.optJSONObject("daily")?.optJSONArray(k)?.optString(0)?.takeIf { it.isNotEmpty() }
                    ?.let { java.time.LocalDateTime.parse(it).toEpochSecond(off) * 1000 }
                val code = obj.optInt("weather_code", -1)
                val precipitation = obj.optDouble("precipitation", 0.0)
                val temp = obj.optDouble("temperature_2m", Double.NaN)
                // 6 weather classes (taste.context.weather); rain / heavyRain also drive the RAIN scene
                val wx = if (code >= 0) QueryPlanner.weatherClass(code, rules) else null
                val rainy = wx != null && wx in rules.taste.context.rainScene || (wx == null && precipitation > 0.0)
                Result(rainy, temp, wx?.let { rules.taste.context.weatherLabels[it] } ?: if (rainy) "비" else "날씨",
                    code.takeIf { it >= 0 }, wx, day("sunrise"), day("sunset"))
            } catch (_: Exception) { null }
            finally { connection?.disconnect() }
            ui.post { onResult(weather) }
        }
    }

    fun shutdown() { executor.shutdownNow() }
}
