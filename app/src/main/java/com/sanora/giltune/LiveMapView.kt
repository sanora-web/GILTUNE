package com.sanora.giltune

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.view.View
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.Executors
import kotlin.math.*

/**
 * Real OpenStreetMap raster tiles, with a simple offline route illustration fallback.
 * Minimal requests: only visible tiles, capped background workers, bounded cache.
 * This is NOT a navigation map or road/lane matcher.
 * OSM attribution is always visible, including in offline/demo mode.
 */
class LiveMapView(context: Context) : View(context) {
    var latitude: Double = 37.5420
        private set
    var longitude: Double = 126.9015
        private set
    var zoom: Int = 14
        private set
    var demo: Boolean = false
        private set
    var bridgeActive: Boolean = false
        private set
    var vehicle: PositionSample? = null
        private set
    var routeSamples: List<PositionSample> = emptyList()
        private set

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val executor = Executors.newFixedThreadPool(2)
    private val ui = Handler(Looper.getMainLooper())
    private val pending = Collections.synchronizedSet(mutableSetOf<String>())
    private val lastFailure = Collections.synchronizedMap(mutableMapOf<String, Long>())
    private val inMemory = object : LinkedHashMap<String, Bitmap>(40, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?): Boolean = size > 32
    }
    private val cacheDir = File(context.cacheDir, "osm-map-cache").apply { mkdirs() }
    private val accent = context.getColor(R.color.accent)
    private val demoColor = context.getColor(R.color.demo)
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Float) = v * density
    /** Real GPS trail (accent). */
    private val trail = ArrayList<PositionSample>()
    // Darken OSM raster tiles for the night UI. NOTE: visual result not verified on device;
    // dark-tile provider terms not checked, so standard OSM tiles are recolored locally.
    private val darkTiles = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(
        -0.30f, -0.45f, -0.10f, 0f, 235f,
        -0.30f, -0.45f, -0.10f, 0f, 245f,
        -0.25f, -0.40f, -0.05f, 0f, 262f,
        0f, 0f, 0f, 1f, 0f)).apply { postConcat(android.graphics.ColorMatrix().apply { setScale(.42f, .45f, .52f, 1f) }) })
    private var tileCount = 0
    private var bearingDegrees = 0f

    fun showPosition(sample: PositionSample, isDemo: Boolean, prior: PositionSample? = null) {
        if (prior != null) {
            val dx = (sample.lon - prior.lon) * cos(Math.toRadians(sample.lat))
            val dy = sample.lat - prior.lat
            if (abs(dx) + abs(dy) > .0000001) bearingDegrees = Math.toDegrees(atan2(dx, dy)).toFloat()
        }
        latitude = sample.lat.coerceIn(-85.0, 85.0)
        longitude = sample.lon.coerceIn(-179.999, 179.999)
        vehicle = sample
        demo = isDemo
        if (!isDemo) { trail.add(sample); if (trail.size > 400) trail.removeAt(0) }
        invalidate()
    }

    fun showDemoRoute(samples: List<PositionSample>) {
        routeSamples = samples
        demo = true
        zoom = 14
        if (samples.isNotEmpty()) showPosition(samples.first(), true)
        invalidate()
    }

    fun showLiveMode() {
        demo = false
        routeSamples = emptyList()
        trail.clear()
        vehicle = null
        invalidate()
    }

    fun setBridge(active: Boolean) { bridgeActive = active; invalidate() }
    fun adjustZoom(delta: Int) { zoom = (zoom + delta).coerceIn(12, 16); invalidate() }

    private fun worldX(lon: Double): Double = (lon + 180.0) / 360.0 * 256.0 * (1 shl zoom)
    private fun worldY(lat: Double): Double {
        val phi = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(phi) + 1.0 / cos(phi)) / PI) / 2.0 * 256.0 * (1 shl zoom)
    }
    private fun screenX(lon: Double): Float = (width / 2.0 + worldX(lon) - worldX(longitude)).toFloat()
    private fun screenY(lat: Double): Float = (height / 2.0 + worldY(lat) - worldY(latitude)).toFloat()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat(); val h = height.toFloat()
        if (w < 1f || h < 1f) return
        canvas.drawColor(context.getColor(R.color.map_bg))
        // Geographic fallback while offline / before tiles arrive. Clearly labeled.
        drawFallback(canvas, w, h)
        val cx = worldX(longitude); val cy = worldY(latitude)
        val startX = floor((cx - w / 2.0) / 256.0).toInt()
        val endX = floor((cx + w / 2.0) / 256.0).toInt()
        val startY = floor((cy - h / 2.0) / 256.0).toInt()
        val endY = floor((cy + h / 2.0) / 256.0).toInt()
        val bound = 1 shl zoom
        tileCount = 0
        // Viewport is bounded in a small demo view; avoids bulk tile requests.
        for (tx in startX..endX) for (ty in startY..endY) {
            if (ty !in 0 until bound) continue
            val xWrapped = ((tx % bound) + bound) % bound
            val key = "$zoom-$xWrapped-$ty"
            val px = (tx * 256.0 - cx + w / 2.0).toFloat()
            val py = (ty * 256.0 - cy + h / 2.0).toFloat()
            val cached = synchronized(inMemory) { inMemory[key] }
            if (cached != null && !cached.isRecycled) {
                paint.alpha = 255
                paint.colorFilter = darkTiles
                canvas.drawBitmap(cached, px, py, paint)
                paint.colorFilter = null
                tileCount++
            } else {
                requestTile(zoom, xWrapped, ty, key)
            }
        }
        drawRoute(canvas)
        drawVehicle(canvas)
        drawHud(canvas, w, h)
    }

    private fun drawFallback(canvas: Canvas, w: Float, h: Float) {
        // Distinct illustration; never presented as geographically accurate tiles.
        paint.color = context.getColor(R.color.map_water); paint.style = Paint.Style.FILL
        val river = Path().apply {
            moveTo(-30f, h * .53f); cubicTo(w * .22f, h * .37f, w * .55f, h * .62f, w + 30f, h * .36f)
            lineTo(w + 30f, h * .68f)
            cubicTo(w * .60f, h * .94f, w * .28f, h * .68f, -30f, h * .84f)
            close()
        }
        canvas.drawPath(river, paint)
        paint.color = context.getColor(R.color.map_road); paint.style = Paint.Style.STROKE; paint.strokeWidth = dp(8f)
        val road = Path().apply { moveTo(w * .10f, h * .92f); cubicTo(w * .42f, h * .70f, w * .54f, h * .34f, w * .88f, h * .08f) }
        canvas.drawPath(road, paint)
        paint.style = Paint.Style.FILL
    }

    private fun drawRoute(canvas: Canvas) {
        val points = if (demo) routeSamples else trail
        if (points.size < 2) return
        val route = Path()
        points.forEachIndexed { index, point ->
            if (index == 0) route.moveTo(screenX(point.lon), screenY(point.lat))
            else route.lineTo(screenX(point.lon), screenY(point.lat))
        }
        paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND; paint.strokeCap = Paint.Cap.ROUND
        // single flat 7dp line, no outline. Demo = blue, real GPS = accent.
        paint.color = if (demo) demoColor else accent; paint.strokeWidth = dp(7f)
        canvas.drawPath(route, paint)
        paint.style = Paint.Style.FILL
        if (!demo) return
        val bridge = RoadEngine.zones[0]
        val mx = screenX((bridge.longitudeA + bridge.longitudeB) / 2.0)
        val my = screenY((bridge.latitudeA + bridge.latitudeB) / 2.0)
        if (mx in 24f..(width - 40f) && my in 40f..(height - 40f)) {
            paint.textSize = dp(16f); paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
            paint.color = Color.rgb(8, 17, 28)
            canvas.drawText("양화대교", mx + 2, my - 15, paint)
            paint.color = Color.WHITE
            canvas.drawText("양화대교", mx, my - 17, paint)
            paint.typeface = null
        }
    }

    private fun drawVehicle(canvas: Canvas) {
        val sample = vehicle ?: return
        val x = screenX(sample.lon); val y = screenY(sample.lat)
        if (x < -25f || y < -25f || x > width + 25f || y > height + 25f) return
        paint.style = Paint.Style.FILL
        paint.color = Color.WHITE
        canvas.drawCircle(x, y, dp(12f), paint)
        paint.color = if (demo) demoColor else accent
        canvas.drawCircle(x, y, dp(8f), paint)
        canvas.save()
        canvas.rotate(bearingDegrees, x, y)
        val tip = Path().apply {
            moveTo(x, y - dp(6f)); lineTo(x - dp(4f), y + dp(4f)); lineTo(x, y + dp(2f)); lineTo(x + dp(4f), y + dp(4f)); close()
        }
        paint.color = Color.WHITE
        canvas.drawPath(tip, paint)
        canvas.restore()
    }

    private fun drawHud(canvas: Canvas, w: Float, h: Float) {
        // no overlay badge (status-bar chip shows demo). Only attribution, 14sp on bg.
        paint.style = Paint.Style.FILL
        paint.textSize = 14f * resources.displayMetrics.scaledDensity
        val footer = "© OpenStreetMap contributors"
        val fw = paint.measureText(footer)
        paint.color = context.getColor(R.color.bg)
        canvas.drawRect(0f, h - dp(26f), fw + dp(16f), h, paint)
        paint.color = context.getColor(R.color.text_tertiary)
        canvas.drawText(footer, dp(8f), h - dp(8f), paint)
        if (tileCount == 0) {
            paint.textSize = 14f * resources.displayMetrics.scaledDensity; paint.color = context.getColor(R.color.text_tertiary)
            canvas.drawText("지도는 인터넷 연결 시 표시됩니다", dp(16f), h - dp(38f), paint)
        }
    }

    private fun requestTile(z: Int, x: Int, y: Int, key: String) {
        if ((lastFailure[key] ?: 0L) > System.currentTimeMillis()) return
        if (!pending.add(key)) return
        executor.execute {
            try {
                val file = File(cacheDir, "$key.png")
                val fresh = file.exists() && System.currentTimeMillis() - file.lastModified() < 7L * 24 * 3600 * 1000
                if (!fresh) {
                    val connection = URL("https://tile.openstreetmap.org/$z/$x/$y.png").openConnection() as HttpURLConnection
                    try {
                        connection.connectTimeout = 3500; connection.readTimeout = 5500
                        connection.setRequestProperty("User-Agent", "GILTUNE/5.1 (SANORA WEB; contest prototype)")
                        connection.setRequestProperty("Accept", "image/png")
                        if (connection.responseCode == 200) {
                            connection.inputStream.use { input ->
                                val temp = File(cacheDir, "$key.tmp")
                                temp.outputStream().use { output -> input.copyTo(output) }
                                if (!temp.renameTo(file)) { temp.copyTo(file, overwrite = true); temp.delete() }
                            }
                        }
                    } finally { connection.disconnect() }
                }
                val bitmap = if (file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
                if (bitmap == null) lastFailure[key] = System.currentTimeMillis() + 60_000L
                if (bitmap != null) {
                    synchronized(inMemory) { inMemory[key] = bitmap }
                    ui.post { if (isAttachedToWindow) invalidate() }
                }
            } catch (_: Exception) {
                lastFailure[key] = System.currentTimeMillis() + 60_000L
                // No internet or rate-limited: preserve offline illustration and retry later.
            } finally { pending.remove(key) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        executor.shutdownNow()
    }
}
