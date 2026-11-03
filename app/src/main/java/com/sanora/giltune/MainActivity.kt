package com.sanora.giltune

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * GILTUNE UI: status bar / map pane / player pane, flat colors. SANORA WEB
 * Landscape: map left, player right. Portrait (phones): player top, map bottom.
 * Real driving is handled by PlaybackService; this screen shows its state.
 * The Yanghwa route is a SIMULATED DRIVE (demo) and is always labelled so.
 */
class MainActivity : Activity(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private val demoEngine = RoadEngine()
    private val recommender: RecommendationEngine = RuleBasedRecommendationEngine()
    private lateinit var locationManager: LocationManager
    private val weatherClient = WeatherClient()

    // views
    private lateinit var roadLabel: TextView
    private lateinit var weatherLabel: TextView
    private lateinit var demoChip: TextView
    private lateinit var clockLabel: TextView
    private lateinit var map: LiveMapView
    private lateinit var sceneChip: LinearLayout
    private lateinit var sceneChipTitle: TextView
    private lateinit var sceneChipCaption: TextView
    private lateinit var zoomPlus: TextView
    private lateinit var zoomMinus: TextView
    private lateinit var art: FrameLayout
    private lateinit var trackBlock: View
    private lateinit var sceneLabel: TextView
    private lateinit var titleLabel: TextView
    private lateinit var subtitleLabel: TextView
    private lateinit var progressFill: View
    private lateinit var progressBar: FrameLayout
    private lateinit var timeLeft: TextView
    private lateinit var timeRight: TextView
    private lateinit var nextTitle: TextView
    private lateinit var nextHint: TextView
    private lateinit var playButton: TextView
    private lateinit var prevButton: TextView
    private lateinit var nextButton: TextView
    private lateinit var heartButton: TextView
    private lateinit var menuButton: TextView
    private lateinit var restrictionRow: View
    private lateinit var statusLine: TextView
    private lateinit var reasonLine: TextView
    private lateinit var sourceRow: View
    private lateinit var sourceText: TextView
    private lateinit var genreRow: LinearLayout
    private lateinit var genreLock: TextView
    private lateinit var seekRow: View
    private var dragFraction: Float? = null
    private var built = false

    // state
    private var demo = false
    private var demoFinished = false
    private var simulatedRain = false
    private var demoStep = 0
    private var demoToken = 0
    private var demoBridge: BridgeZone? = null
    private var shownScene: Scene? = null
    private var shownTrackId: String? = null
    private var lastSpeedMps: Float? = null
    private var localWeatherRainy: Boolean? = null
    private var localWeatherText: String? = null
    private var lastMapPoint: PositionSample? = null
    private var drivingLocked = false
    private lateinit var selLogBtn: TextView

    private val samples = listOf(
        PositionSample(37.5330, 126.9120, 6f), PositionSample(37.5352, 126.9090, 6f),
        PositionSample(37.5385, 126.9043, 6f), PositionSample(37.5414, 126.9017, 6f),
        PositionSample(37.5421, 126.9016, 6f), PositionSample(37.5445, 126.9013, 6f),
        PositionSample(37.5467, 126.9010, 6f), PositionSample(37.5500, 126.9029, 6f),
        PositionSample(37.5511, 126.9046, 6f), PositionSample(37.5520, 126.9050, 6f))

    private val observer: () -> Unit = { if (built) refresh() }
    private val ticker = object : Runnable {
        override fun run() { if (built) { updateClock(); updateProgress() }; handler.postDelayed(this, 1000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = col(R.color.bg)
        window.navigationBarColor = col(R.color.bg)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        buildUi()
        PlaybackService.addObserver(observer)
        handler.post(ticker)
        if (favorites().getBoolean("privacy_ok", false)) { requestStartupPermissions(); onboardIfNeeded() } else showPrivacyNotice()
        if (SpotifyLink.enabled) SpotifyLink.connect(this) { refresh() }
        startGps()
    }

    private fun hasLocation() = checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** First run: what location data is used for and where it goes. */
    private fun showPrivacyNotice() {
        val tv = TextView(this).apply {
            text = "길튠은 지나가는 길에 맞는 음악을 고르기 위해 위치를 사용합니다.\n\n" +
                "• 위치(GPS / Pleos FusedLocation)로 도로 종류(OpenStreetMap Overpass), 지역명(Nominatim), 날씨(Open-Meteo)를 조회합니다. 이때 대략적인 좌표가 각 서비스로 전송됩니다.\n" +
                "• 주행 로그(시각·좌표·속도·도로·곡)는 이 기기에만 저장되며, 직접 공유할 때만 밖으로 나갑니다.\n" +
                "• Spotify를 연결하면 곡 검색어(장면·장르)가 Spotify로 전송됩니다. 위치는 보내지 않습니다.\n" +
                "• 위치를 허용하지 않거나 오프라인이어도 재생됩니다. 이때는 시간·날씨 기준으로 곡을 고릅니다.\n\n" +
                "SANORA WEB"
            textSize = 16f; setTextColor(col(R.color.text_primary)); setPadding(dp(24), dp(16), dp(24), dp(8))
        }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("위치·개인정보 안내").setView(ScrollView(this).apply { addView(tv) }).setCancelable(false)
            .setPositiveButton("확인") { _, _ -> favorites().edit().putBoolean("privacy_ok", true).apply(); requestStartupPermissions(); onboardIfNeeded() }
            .show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == SpotifyLink.AUTH_REQUEST) SpotifyLink.onAuthResult(this, resultCode, data) { refresh() }
    }

    /** Vehicle data as received from the Pleos Vehicle SDK / Virtual Console (or GPS), always visible. */
    private fun vehicleText(): String? {
        if (demo) return null
        val gear = when (PlaybackService.gearState) { GearState.PARK -> "P 정차"; GearState.NOT_PARK -> "주행 기어"; GearState.UNKNOWN -> null }
        val wiper = when (PlaybackService.wiperOn) { true -> "와이퍼 ON"; false -> null; null -> null }
        val speed = (PlaybackService.speedMps ?: lastSpeedMps)?.let { "%.0fkm/h".format(it * 3.6f) }
        return listOfNotNull(gear, wiper, speed).joinToString(" · ").ifBlank { null }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val keepDemo = demo
        buildUi()
        if (keepDemo) { map.showDemoRoute(samples); lastMapPoint?.let { map.showPosition(it, true) } }
        refresh(force = true)
    }

    // ---------- helpers ----------
    private fun dp(n: Int): Int = (n * resources.displayMetrics.density + .5f).toInt()
    private fun col(id: Int): Int = getColor(id)
    private fun rect(fill: Int, radius: Int, stroke: Int? = null, strokeDp: Int = 1) = GradientDrawable().apply {
        setColor(fill); cornerRadius = dp(radius).toFloat(); if (stroke != null) setStroke(dp(strokeDp), stroke)
    }
    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = sp; setTextColor(color); includeFontPadding = false
        if (bold) setTypeface(Typeface.DEFAULT, Typeface.BOLD)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }
    private fun button(glyph: String, size: Int, sp: Float = 26f) = text(glyph, sp, col(R.color.text_primary)).apply {
        gravity = Gravity.CENTER; background = rect(col(R.color.surface_btn), 12)
        isClickable = true; isFocusable = true
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    private fun lp(w: Int, h: Int, weight: Float = 0f) = LinearLayout.LayoutParams(w, h, weight)
    private fun divider(vertical: Boolean) = View(this).apply {
        setBackgroundColor(col(R.color.divider))
        layoutParams = if (vertical) LinearLayout.LayoutParams(dp(1), -1) else LinearLayout.LayoutParams(-1, dp(1))
    }

    private val widthDp get() = resources.configuration.screenWidthDp
    private val heightDp get() = resources.configuration.screenHeightDp
    private val portrait get() = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
    private val compact get() = heightDp < 600 && !portrait   // phone landscape

    // ---------- layout ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(col(R.color.bg)) }
        root.addView(buildStatusBar())
        root.addView(divider(false))
        val mapPane = buildMapPane()
        val player = buildPlayerPane()
        if (portrait) {
            val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            body.addView(player, lp(-1, -2))
            body.addView(divider(false))
            body.addView(mapPane, lp(-1, 0, 1f))
            root.addView(body, lp(-1, 0, 1f))
        } else {
            val body = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val playerWidth = when { widthDp >= 1600 -> 720; widthDp >= 1200 -> 600; else -> (widthDp * 0.48f).toInt() }
            body.addView(mapPane, lp(0, -1, 1f))
            body.addView(divider(true))
            body.addView(player, lp(dp(playerWidth), -1))
            root.addView(body, lp(-1, 0, 1f))
        }
        setContentView(root)
        built = true
        shownScene = null; shownTrackId = null; drivingLocked = false
        refresh(force = true)
        updateClock()
    }

    private fun buildStatusBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (portrait) 16 else 32), 0, dp(if (portrait) 16 else 32), 0)
        }
        roadLabel = text("위치 확인 중", 18f, col(R.color.text_primary), true)
        weatherLabel = text("", 18f, col(R.color.text_secondary))
        demoChip = text("SIMULATED DRIVE · 데모", 15f, col(R.color.demo), true).apply {
            gravity = Gravity.CENTER; background = rect(col(R.color.bg), 6, col(R.color.demo), 2)
            setPadding(dp(12), 0, dp(12), 0); visibility = View.GONE
        }
        clockLabel = text("--:--", 22f, col(R.color.text_primary), true)
        bar.addView(roadLabel, lp(-2, -2).apply { marginEnd = dp(16) })
        bar.addView(weatherLabel, lp(-2, -2).apply { marginEnd = dp(16) })
        bar.addView(demoChip, lp(-2, dp(32)))
        bar.addView(View(this), lp(0, 1, 1f))
        bar.addView(clockLabel)
        roadLabel.layoutParams = (roadLabel.layoutParams as LinearLayout.LayoutParams).apply { width = -2 }
        roadLabel.maxWidth = dp(if (portrait) 150 else 420)
        return bar.also { it.layoutParams = lp(-1, dp(56)) }
    }

    private fun buildMapPane(): View {
        val pane = FrameLayout(this).apply { setBackgroundColor(col(R.color.map_bg)) }
        map = LiveMapView(this)
        pane.addView(map, FrameLayout.LayoutParams(-1, -1))
        val m = dp(if (portrait) 16 else 24)
        sceneChip = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = rect(col(R.color.bg), 8); alpha = 0f
        }
        val stripe = View(this).apply { setBackgroundColor(col(R.color.accent)) }
        sceneChip.addView(stripe, lp(dp(4), -1))
        sceneChipTitle = text("", 20f, col(R.color.text_primary), true)
        sceneChipCaption = text("", 15f, col(R.color.text_secondary))
        sceneChip.addView(sceneChipTitle, lp(-2, -2).apply { marginStart = dp(16); marginEnd = dp(12) })
        sceneChip.addView(sceneChipCaption, lp(-2, -2).apply { marginEnd = dp(16) })
        pane.addView(sceneChip, FrameLayout.LayoutParams(-2, dp(56), Gravity.TOP or Gravity.START).apply { setMargins(m, m, m, 0) })
        val zoom = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        zoomPlus = button("+", 76); zoomMinus = button("−", 76)
        zoomPlus.setOnClickListener { map.adjustZoom(1) }
        zoomMinus.setOnClickListener { map.adjustZoom(-1) }
        zoom.addView(zoomPlus); zoom.addView(zoomMinus, lp(dp(76), dp(76)).apply { topMargin = dp(12) })
        pane.addView(zoom, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, m, m) })
        return pane
    }

    private fun buildPlayerPane(): View {
        val pad = dp(if (portrait) 16 else if (compact) 20 else 32)
        val pane = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad, pad, pad) }
        val artSize = when { portrait -> 112; compact -> 0; widthDp >= 1600 -> 240; else -> 200 }
        val titleSp = when { portrait || compact -> 26f; widthDp >= 1600 -> 40f; else -> 34f }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        art = FrameLayout(this)
        if (artSize > 0) head.addView(art, lp(dp(artSize), dp(artSize)).apply { marginEnd = dp(if (portrait) 16 else 24) })
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sceneLabel = text("", 14f, col(R.color.text_secondary))
        titleLabel = text("", titleSp, col(R.color.text_primary), true)
        subtitleLabel = text("", 18f, col(R.color.text_secondary))
        texts.addView(sceneLabel); texts.addView(titleLabel, lp(-1, -2).apply { topMargin = dp(8) })
        texts.addView(subtitleLabel, lp(-1, -2).apply { topMargin = dp(8) })
        head.addView(texts, lp(0, -2, 1f))
        trackBlock = head
        pane.addView(head)

        progressBar = FrameLayout(this).apply { setBackgroundColor(col(R.color.progress_track)) }
        progressFill = View(this).apply { setBackgroundColor(col(R.color.accent)) }
        progressBar.addView(progressFill, FrameLayout.LayoutParams(0, -1))
        // 4dp bar inside a 32dp touch strip: drag to seek while stopped (locked while driving -> ±10s buttons).
        val seekStrip = FrameLayout(this)
        seekStrip.addView(progressBar, FrameLayout.LayoutParams(-1, dp(4), Gravity.CENTER_VERTICAL))
        seekStrip.setOnTouchListener { v, e ->
            if (drivingLocked || PlaybackService.durationMs() <= 0) return@setOnTouchListener false
            val f = (e.x / v.width).coerceIn(0f, 1f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { dragFraction = f; updateProgress() }
                MotionEvent.ACTION_UP -> { dragFraction = null; PlaybackService.seekTo((f * PlaybackService.durationMs()).toInt()); v.performClick() }
                MotionEvent.ACTION_CANCEL -> { dragFraction = null; updateProgress() }
            }
            true
        }
        pane.addView(seekStrip, lp(-1, dp(32)).apply { topMargin = dp(if (portrait || compact) 2 else 14) })
        val times = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        timeLeft = text("0:00", 14f, col(R.color.text_secondary)); timeRight = text("0:00", 14f, col(R.color.text_secondary))
        times.addView(timeLeft); times.addView(View(this), lp(0, 1, 1f)); times.addView(timeRight)
        pane.addView(times, lp(-1, -2).apply { topMargin = dp(2) })
        // While driving: big ±10s buttons instead of dragging (76dp).
        seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(button("−10초", 76, 18f).apply { layoutParams = lp(dp(120), dp(76)); setOnClickListener { PlaybackService.seekBy(-10_000) } })
            addView(View(this@MainActivity), lp(dp(16), 1))
            addView(button("+10초", 76, 18f).apply { layoutParams = lp(dp(120), dp(76)); setOnClickListener { PlaybackService.seekBy(10_000) } })
        }
        pane.addView(seekRow, lp(-1, -2).apply { topMargin = dp(8) })
        // Small line like the web: road judgment · source · what the next change still needs.
        statusLine = text("", 13f, col(R.color.text_tertiary))
        pane.addView(statusLine, lp(-1, -2).apply { topMargin = dp(6) })
        // Own-track fallback is always said out loud, with a 76dp reconnect button.
        sourceText = text("Spotify 미연결 — 자체 음원 5곡", 15f, col(R.color.demo))
        sourceRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE
            addView(sourceText, lp(0, -2, 1f))
            addView(button("다시 연결", 76, 16f).apply { layoutParams = lp(dp(140), dp(76)); setOnClickListener { reconnectSpotify() } })
        }
        pane.addView(sourceRow, lp(-1, -2).apply { topMargin = dp(6) })
        reasonLine = text("", 14f, col(R.color.text_secondary))
        pane.addView(reasonLine, lp(-1, -2).apply { topMargin = dp(4) })
        selLogBtn = button("ⓘ 선곡 기록", 76, 16f).apply { setOnClickListener { openSelLog() } }
        pane.addView(selLogBtn, lp(dp(180), dp(76)).apply { topMargin = dp(4) })
        // Genre chips on the main screen: stopped only, applied from the next song; locked while driving.
        genreRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        genreLock = text("주행 중 잠김", 14f, col(R.color.text_tertiary)).apply { visibility = View.GONE; setPadding(0, 0, dp(12), 0) }
        genreRow.addView(genreLock)
        for ((k, label) in SceneRules.current.genreLabels) genreRow.addView(text(label, 16f, col(R.color.text_primary)).apply {
            tag = k; gravity = Gravity.CENTER; minWidth = dp(76); setPadding(dp(16), 0, dp(16), 0); isClickable = true
            setOnClickListener { toggleGenre(k) }
        }, lp(-2, dp(76)).apply { marginEnd = dp(8) })
        pane.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(genreRow) }, lp(-1, -2).apply { topMargin = dp(8) })
        renderGenres()

        if (!compact) {
            pane.addView(divider(false).apply { layoutParams = lp(-1, dp(1)).apply { topMargin = dp(16) } })
            val next = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            nextTitle = text("", 18f, col(R.color.text_primary), true); nextHint = text("", 16f, col(R.color.text_secondary))
            next.addView(text("다음", 16f, col(R.color.text_secondary)), lp(-2, -2).apply { marginEnd = dp(16) })
            next.addView(nextTitle, lp(-2, -2).apply { marginEnd = dp(12) }); next.addView(nextHint, lp(0, -2, 1f))
            pane.addView(next, lp(-1, -2).apply { topMargin = dp(16) })
        } else { nextTitle = text("", 1f, 0); nextHint = text("", 1f, 0) }

        if (!portrait) pane.addView(View(this), lp(1, 0, 1f)) // push controls to bottom
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val gap = dp(if (portrait) 12 else 20)
        prevButton = button("◀◀", 76, 22f)
        playButton = text("▶", 34f, col(R.color.bg), true).apply {
            gravity = Gravity.CENTER; isClickable = true; isFocusable = true
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(R.color.accent)) }
        }
        nextButton = button("▶▶", 76, 22f)
        heartButton = button("♡", 76, 26f)
        menuButton = button("≡", 76, 30f)
        controls.addView(prevButton)
        controls.addView(playButton, lp(dp(96), dp(96)).apply { marginStart = gap; marginEnd = gap })
        controls.addView(nextButton)
        controls.addView(View(this), lp(0, 1, 1f))
        controls.addView(heartButton)
        controls.addView(menuButton, lp(dp(76), dp(76)).apply { marginStart = gap })
        pane.addView(controls, lp(-1, -2).apply { topMargin = dp(if (portrait) 16 else 12) })
        val restriction = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; visibility = View.GONE }
        restriction.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(R.color.warn)) } }, lp(dp(8), dp(8)).apply { marginEnd = dp(8) })
        restriction.addView(text("주행 중에는 재생 조작만 할 수 있어요", 15f, col(R.color.text_secondary)))
        restrictionRow = restriction
        pane.addView(restriction, lp(-1, -2).apply { topMargin = dp(12) })

        playButton.setOnClickListener {
            if (PlaybackService.isPlaying) PlaybackService.command(this, PlaybackService.ACTION_PAUSE)
            else PlaybackService.command(this, PlaybackService.ACTION_PLAY, if (demo) displayScene() else null, demo, simulatedRain)
        }
        prevButton.setOnClickListener { PlaybackService.command(this, PlaybackService.ACTION_PREV) }
        nextButton.setOnClickListener { PlaybackService.command(this, PlaybackService.ACTION_NEXT) }
        heartButton.setOnClickListener { toggleFavorite() }
        menuButton.setOnClickListener { showInfoSheet() }
        return pane
    }

    // ---------- state -> view ----------
    private fun displayScene(): Scene = if (demo) {
        recommender.recommend(SceneContext(demoBridge, simulatedRain, Calendar.getInstance().get(Calendar.HOUR_OF_DAY)))
    } else if (PlaybackService.isPlaying || PlaybackService.latestPosition != null) PlaybackService.currentScene
    else recommender.recommend(SceneContext(null, localWeatherRainy == true, Calendar.getInstance().get(Calendar.HOUR_OF_DAY), null, lastSpeedMps))

    private fun refresh(force: Boolean = false) {
        if (!built) return
        val playing = PlaybackService.isPlaying
        playButton.text = if (playing) "❚❚" else "▶"
        val scene = displayScene()
        val track = if (playing) PlaybackService.currentTrack else PlaybackService.provider.trackFor(scene)
        if (force || track.id != shownTrackId) { showTrack(track, animate = !force && shownTrackId != null); shownTrackId = track.id }
        if (scene != shownScene) { if (shownScene != null) showSceneChip(scene); shownScene = scene }
        // status bar
        val r = PlaybackService.road
        roadLabel.text = when {
            demo -> if (demoBridge != null) "양화대교" else "양화로 (시연)"
            r.name != null -> r.name
            PlaybackService.naviAddress != null -> PlaybackService.naviAddress
            r.type != RoadType.UNKNOWN -> r.type.label
            PlaybackService.region != null -> PlaybackService.region
            !hasLocation() -> "위치 없음 · 시간·날씨 기준 선곡"
            else -> "위치 확인 중"
        }
        weatherLabel.text = if (demo) (if (simulatedRain) "비 (시연)" else "맑음 (시연)")
            else listOfNotNull(vehicleText(), PlaybackService.weatherText ?: localWeatherText).joinToString("  ·  ")
        demoChip.visibility = if (demo) View.VISIBLE else View.GONE
        map.setBridge(scene == Scene.BRIDGE)
        if (!demo) PlaybackService.latestPosition?.let { p ->
            if (p != lastMapPoint) { map.showPosition(p, false, lastMapPoint); lastMapPoint = p }
        }
        // next row
        val d = PlaybackService.distanceToBridge
        val up = PlaybackService.upcoming
        if (!demo && up != null && up.sceneId != scene.id) {
            nextTitle.text = PlaybackService.preparedText ?: (Scene.byId(up.sceneId)?.label ?: up.sceneId)
            nextHint.text = "내비 경로: ${up.distanceM}m 앞 · 미리 준비됨"
        } else if (scene != Scene.BRIDGE && ((demo && demoBridge == null && demoStep < 5) || (!demo && d != null && d < 2_000))) {
            nextTitle.text = PlaybackService.provider.trackFor(Scene.BRIDGE).title.substringBefore(" —")
            nextHint.text = "다리 진입 시 자동 전환"
        } else {
            nextTitle.text = "자동 선곡"
            nextHint.text = "도로·날씨·시간이 바뀌면 전환"
        }
        updateFavoriteGlyph()
        applyDrivingRestriction(); renderGenres()
        updateProgress()
    }

    private fun showTrack(track: Track, animate: Boolean) {
        val apply = {
            sceneLabel.text = track.scene.label
            titleLabel.text = track.title.substringBefore(" —")
            subtitleLabel.text = track.title.substringAfter("— ", "GILTUNE")
            drawArt(track.scene)
        }
        if (!animate) { apply(); return }
        trackBlock.animate().alpha(0f).setDuration(100).withEndAction {
            apply(); trackBlock.animate().alpha(1f).setDuration(100).start()
        }.start()
    }

    /** Self-made flat album art: base color + optional bottom band + optional circle. No image files. */
    private fun drawArt(scene: Scene) {
        if (!::art.isInitialized || art.parent == null) return
        art.removeAllViews()
        data class Art(val base: Int, val band: Int?, val circle: Int?)
        val a = when (scene.id) {
            "BRIDGE" -> Art(R.color.map_water, R.color.demo, null)
            "RAIN" -> Art(R.color.art_blue, R.color.accent, R.color.text_primary)
            "NIGHT" -> Art(R.color.art_night, null, R.color.text_primary)
            "HIGHWAY" -> Art(R.color.map_road, R.color.accent, null)
            "CITY_EXPRESS" -> Art(R.color.art_blue, R.color.demo, R.color.text_primary)
            "ALLEY" -> Art(R.color.art_night, R.color.art_amber, null)
            "TUNNEL" -> Art(R.color.art_tunnel, null, R.color.art_amber)
            "COASTAL" -> Art(R.color.map_water, R.color.accent, R.color.art_amber)
            "RIVERSIDE" -> Art(R.color.map_water, R.color.art_amber, null)
            "DOWNTOWN" -> Art(R.color.map_road, R.color.demo, R.color.text_primary)
            else -> Art(R.color.art_blue, null, R.color.accent)
        }
        art.background = rect(col(a.base), 8)
        art.clipToOutline = true
        a.band?.let { art.addView(View(this).apply { setBackgroundColor(col(it)) },
            FrameLayout.LayoutParams(-1, -1).apply { topMargin = art.layoutParams.height * 62 / 100 }) }
        a.circle?.let {
            val s = art.layoutParams.height * 42 / 100
            art.addView(View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(col(it)) } },
                FrameLayout.LayoutParams(s, s, Gravity.CENTER_HORIZONTAL or Gravity.TOP).apply { topMargin = art.layoutParams.height * 14 / 100 })
        }
    }

    private fun showSceneChip(scene: Scene) {
        sceneChipTitle.text = scene.label
        sceneChipCaption.text = "음악을 ${PlaybackService.provider.trackFor(scene).title.substringBefore(" —")}(으)로 바꿨어요"
        sceneChip.animate().cancel()
        sceneChip.animate().alpha(1f).setDuration(200).withEndAction {
            sceneChip.animate().alpha(0f).setStartDelay(4000).setDuration(200).start()
        }.start()
    }

    private fun updateProgress() {
        if (!built) return
        val pos = PlaybackService.positionMs(); val dur = PlaybackService.durationMs()
        val w = progressBar.width
        val shown = dragFraction?.let { (it * dur).toInt() } ?: pos
        progressFill.layoutParams = progressFill.layoutParams.apply { width = if (dur > 0) (w.toLong() * shown / dur).toInt() else 0 }
        progressFill.requestLayout()
        timeLeft.text = fmt(shown); timeRight.text = fmt(dur)
        statusLine.text = statusText()
        sourceRow.visibility = if (PlaybackService.isPlaying && !PlaybackService.usingSpotify) View.VISIBLE else View.GONE
        val why = PlaybackService.reason
        reasonLine.text = if (why.isEmpty()) "" else "선곡 이유 · " + why.joinToString(" · ")
    }
    private fun reconnectSpotify() {
        if (!SpotifyLink.enabled) { Toast.makeText(this, "Spotify Client ID가 없어 자체 음원만 재생해요", Toast.LENGTH_SHORT).show(); return }
        if (!SpotifyLink.hasToken || SpotifyLink.needsRelogin) SpotifyLink.login(this)
        SpotifyLink.connect(this) { refresh(); if (SpotifyLink.ready && PlaybackService.isPlaying) PlaybackService.rebuildUpcoming() }
    }
    /** 진단: 고른 장르(없으면 13개 전체)의 곡 풀을 실제 로그인으로 확인. */
    private fun diagnoseGenres() {
        val sel = TasteStore.load(this).genres.filter { SceneRules.current.genres.containsKey(it) }.ifEmpty { SceneRules.current.genres.keys.toList() }
        val tv = TextView(this).apply { text = "점검 중…"; textSize = 14f; setTextColor(col(R.color.text_primary)); setPadding(dp(24), dp(16), dp(24), dp(8)); setTextIsSelectable(true) }
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("장르 곡 풀 점검").setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("닫기", null).show()
        Thread { val s = SpotifyLink.diagnose(sel); runOnUiThread { tv.text = s } }.start()
    }
    /** 선곡 기록: 정차 중에만 (장르 칩과 같은 잠금). */
    private fun openSelLog() {
        if (drivingLocked) { Toast.makeText(this, "주행 중에는 선곡 기록을 볼 수 없어요 — 정차 후 열어 주세요", Toast.LENGTH_SHORT).show(); return }
        sheets.selectionLog()
    }
    private fun toggleGenre(k: String) {
        if (drivingLocked) { Toast.makeText(this, "주행 중에는 장르를 바꿀 수 없어요", Toast.LENGTH_SHORT).show(); return }
        if (!SpotifyLink.hasToken) {   // 장르 선곡은 Spotify에서 고름 → 연결 안내
            val p = TasteStore.load(this); if (!p.genres.remove(k)) p.genres.add(k); TasteStore.save(this, p); renderGenres()
            AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert).setTitle("Spotify 연결이 필요해요")
                .setMessage("장르 선곡은 Spotify에서 골라요. 연결 전에는 자체 음원(직접 합성 5곡)으로 재생해요.")
                .setPositiveButton("Spotify 연결") { _, _ -> if (SpotifyLink.enabled) SpotifyLink.login(this) else Toast.makeText(this, "Spotify Client ID가 없어요", Toast.LENGTH_SHORT).show() }
                .setNegativeButton("닫기", null).show()
            return
        }
        val p = TasteStore.load(this); if (!p.genres.remove(k)) p.genres.add(k); TasteStore.save(this, p)
        PlaybackService.rebuildUpcoming()   // upcoming auto songs rebuilt; the current song keeps playing
        Toast.makeText(this, "다음 곡부터 반영돼요", Toast.LENGTH_SHORT).show(); renderGenres()
    }
    private fun renderGenres() {
        val sel = TasteStore.load(this).genres
        for (i in 0 until genreRow.childCount) {
            val v = genreRow.getChildAt(i) as TextView; val k = v.tag as? String ?: continue
            val on = k in sel
            v.background = rect(col(if (on) R.color.accent else R.color.surface_btn), 38)
            v.setTextColor(col(if (on) R.color.bg else R.color.text_primary))
            v.isEnabled = !drivingLocked; v.alpha = if (drivingLocked) .4f else 1f
        }
        if (::selLogBtn.isInitialized) selLogBtn.alpha = if (drivingLocked) 0.3f else 1f
        genreLock.visibility = if (drivingLocked) View.VISIBLE else View.GONE
    }
    private fun statusText(): String {
        if (demo) return "판단: 데모 경로 · 출처 ${SceneRules.current.sourceLabels["demo"] ?: "데모"}"
        val r = PlaybackService.road
        val head = "판단: ${r.type.label} · 출처 ${r.sourceLabel}${r.highway?.let { " ($it)" } ?: ""}"
        val st = PlaybackService.sceneStatus() ?: return head
        fun sec(ms: Long) = ((ms + 999) / 1000)
        val next = when {
            st.cand != null -> "다음: ${Scene.byId(st.cand)?.label ?: st.cand} 확인 ${st.count}/${st.confirmCount}" +
                (if (st.confirmLeftMs > 0) " · ${sec(st.confirmLeftMs)}초" else "") +
                (if (st.holdLeftMs > 0) " · 유지 ${sec(st.holdLeftMs)}초" else "") + (if (st.urgent) " (즉시 전환 장면)" else "")
            st.holdLeftMs > 0 -> "현재 장면 최소 유지 ${sec(st.holdLeftMs)}초"
            else -> "도로가 바뀌면 전환"
        }
        return "$head  ·  $next"
    }
    private fun fmt(ms: Int) = "%d:%02d".format(ms / 60000, (ms / 1000) % 60)
    private fun updateClock() { clockLabel.text = SimpleDateFormat("HH:mm", Locale.KOREA).format(Date()) }

    /**
     * GILTUNE's own rule (no official Pleos driving-restriction API documented):
     * gear != P -> driving; unknown gear -> speed >= scene_rules speed.drivingMps (2.5 m/s). Locks ♡, ≡ and zoom.
     */
    private fun applyDrivingRestriction() {
        val gear = PlaybackService.gearState
        val driving = !demo && DrivingJudge.isDriving(gear, PlaybackService.speedMps ?: lastSpeedMps)
        if (driving == drivingLocked && restrictionRow.visibility == (if (driving) View.VISIBLE else View.GONE)) return
        drivingLocked = driving
        for (v in listOf(heartButton, menuButton, zoomPlus, zoomMinus)) { v.isEnabled = !driving; v.alpha = if (driving) .3f else 1f }
        restrictionRow.visibility = if (driving) View.VISIBLE else View.GONE
        seekRow.visibility = if (driving) View.VISIBLE else View.GONE
        renderGenres()
    }

    private fun favorites() = getSharedPreferences("giltune", MODE_PRIVATE)
    private val sheets by lazy { Sheets(this) { refresh() } }
    /** First run: privacy notice -> permissions -> genre / mood pick (once). */
    private fun onboardIfNeeded() { if (!TasteStore.load(this).done) sheets.taste(first = true) }
    private fun currentTrackId() = shownTrackId ?: PlaybackService.currentTrack.id
    private fun toggleFavorite() {
        val set = favorites().getStringSet("fav", emptySet())!!.toMutableSet()
        if (!set.add(currentTrackId())) set.remove(currentTrackId())
        else if (PlaybackService.currentTrack.uri != null) SpotifyLink.like()   // learn + save to Spotify library
        favorites().edit().putStringSet("fav", set).apply(); updateFavoriteGlyph()
    }
    private fun updateFavoriteGlyph() {
        heartButton.text = if (favorites().getStringSet("fav", emptySet())!!.contains(currentTrackId())) "♥" else "♡"
    }

    // ---------- ≡ sheet: notices, demo controls, drive log ----------
    private fun showInfoSheet() {
        val info = buildString {
            appendLine("■ 위치: ${PlaybackService.gpsSource}  ·  도로: ${PlaybackService.road.type.label} (${PlaybackService.road.sourceLabel})")
            appendLine("■ 차량 SDK: ${PlaybackService.sdkStatus}")
            appendLine("■ 빌드: " + if (BuildConfig.PLEOS_SDK_INCLUDED) "Pleos SDK 포함 (Pleos 미지원 기기에서는 Android GPS 사용)" else "Pleos SDK 제외 데모 빌드")
            appendLine("■ 주행 제한: 길튠 자체 기준(기어≠P, 기어 미상이면 속도). Pleos 공식 주행규제 API 문서 없음")
            appendLine("■ 음원: Spotify(검색으로 선곡, App Remote 재생) 우선, 실패 시 길튠 합성곡 5곡(생성 스크립트 보관)")
            appendLine("■ 장면 규칙: scene_rules.json v${SceneRules.current.version} (웹 버전과 공용)")
            PlaybackService.upcoming?.let { appendLine("■ 내비 예측: ${it.description} ${it.distanceM}m 앞") }
            appendLine("■ 지도·도로: © OpenStreetMap contributors (Overpass/Nominatim), 내장 해안·강 데이터 ODbL 1.0  ·  날씨: Open-Meteo (CC BY 4.0)")
            appendLine("■ 양화대교 시연 경로는 데모입니다. 실제 내비게이션 아님. 실차 주행 검증 전")
        }
        val taste = TasteStore.load(this).let { p -> p.genres.mapNotNull { SceneRules.current.genreLabels[it] }.joinToString(", ").ifBlank { "자동" } }
        val items = arrayOf(
            if (demo && !demoFinished) "시연 중지" else "양화대교 시연 주행 (데모)",
            if (simulatedRain) "비 시연 끄기" else "비 시연 켜기",
            "주행 로그 보기 · CSV 공유",
            "취향(장르·분위기): $taste",
            "재생목록 · 다음 곡 / 들은 곡",
            "노래 검색 (정차 중)",
            if (SpotifyLink.connected) "Spotify 연결됨" else "Spotify 연결 (${SpotifyLink.status})",
            when { SpotifyLink.needsRelogin -> "Spotify 다시 로그인 (♡ 저장 권한 추가)"; SpotifyLink.hasToken -> "Spotify 로그인됨"; else -> "Spotify 검색 로그인" },
            "앱 정보·고지 보기",
            "진단: 장르 곡 풀 점검",
            "선곡 기록 (최근 50곡)")
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("길튠").setItems(items) { _, which ->
                when (which) {
                    0 -> toggleDemo()
                    1 -> toggleRain()
                    2 -> showDriveLog()
                    3 -> sheets.taste()
                    4 -> sheets.queue()
                    5 -> sheets.search()
                    6 -> SpotifyLink.connect(this) { refresh() }
                    7 -> SpotifyLink.login(this)
                    8 -> {
                        val tv = TextView(this).apply { text = info + "■ " + SpotifyLink.status + "\n"; textSize = 15f; setTextColor(col(R.color.text_primary)); setPadding(dp(24), dp(16), dp(24), dp(8)) }
                        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
                            .setTitle("길튠 정보").setView(ScrollView(this).apply { addView(tv) }).setPositiveButton("닫기", null).show()
                    }
                    9 -> diagnoseGenres()
                    10 -> openSelLog()
                }
            }.show()
    }

    private fun showDriveLog() {
        val rows = synchronized(DriveLogger.recent) { DriveLogger.recent.toList() }.takeLast(60).reversed()
        val body = if (rows.isEmpty()) "아직 기록이 없습니다. 재생을 누르고 주행하면 2초마다 기록됩니다."
            else rows.joinToString("\n") { r ->
                "%s  %s  %s  %s".format(SimpleDateFormat("HH:mm:ss", Locale.KOREA).format(Date(r.timeMs)),
                    r.speedMps?.let { "%.0fkm/h".format(it * 3.6f) } ?: "-", r.road, r.scene.label)
            }
        val tv = TextView(this).apply { text = body; textSize = 14f; typeface = Typeface.MONOSPACE; setTextColor(col(R.color.text_primary)); setPadding(dp(24), dp(16), dp(24), dp(8)) }
        val file = DriveLogger.currentFile
        AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("주행 로그" + (file?.let { " · ${it.name}" } ?: ""))
            .setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton("CSV 공유") { _, _ -> shareLog() }
            .setNegativeButton("닫기", null).show()
    }

    private fun shareLog() {
        val file = DriveLogger.currentFile ?: DriveLogger(this).dir.listFiles()?.maxByOrNull { it.lastModified() } ?: return
        val uri = FileProvider.getUriForFile(this, "com.sanora.giltune.logs", file)
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }, "주행 로그 공유"))
    }

    // ---------- demo (SIMULATED DRIVE) ----------
    private fun toggleRain() {
        simulatedRain = !simulatedRain
        if (PlaybackService.isPlaying) PlaybackService.command(this, PlaybackService.ACTION_MANUAL_RAIN, rainDemo = simulatedRain)
        if (demo && PlaybackService.isPlaying) PlaybackService.command(this, PlaybackService.ACTION_SCENE, displayScene())
        refresh()
    }

    private fun toggleDemo() {
        if (!demo || demoFinished) {
            demo = true; demoFinished = false; demoToken++; demoStep = 0; demoBridge = null
            demoEngine.reset(); lastMapPoint = null
            map.showDemoRoute(samples)
            if (PlaybackService.isPlaying) PlaybackService.command(this, PlaybackService.ACTION_DEMO_BEGIN)
            runDemo(demoToken)
        } else {
            demo = false; demoToken++; demoBridge = null; lastMapPoint = null
            map.showLiveMode()
            if (PlaybackService.isPlaying) PlaybackService.command(this, PlaybackService.ACTION_DEMO_END)
        }
        refresh()
    }

    private fun runDemo(token: Int) {
        if (!demo || token != demoToken) return
        val sample = samples[demoStep]
        map.showPosition(sample, true, lastMapPoint); lastMapPoint = sample
        val result = demoEngine.update(sample)
        if (result.accurate) demoBridge = result.currentZone
        if (PlaybackService.isPlaying) PlaybackService.command(this, PlaybackService.ACTION_SCENE, displayScene())
        demoStep++
        if (demoStep < samples.size) handler.postDelayed({ runDemo(token) }, 2200L) else demoFinished = true
        refresh()
    }

    // ---------- permissions & local GPS (map + speed while not playing) ----------
    private fun requestStartupPermissions() {
        val wanted = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        if (BuildConfig.PLEOS_SDK_INCLUDED) {
            // FUSED_LOCATION: docs (dangerous). CAR_DRIVE (Gear) / CAR_WINDOWS (Wiper): AAR @RequiresPermission.
            // These only exist on Pleos images; skipped on ordinary phones.
            for (p in listOf("pleos.car.permission.FUSED_LOCATION", "pleos.car.permission.CAR_DRIVE", "pleos.car.permission.CAR_WINDOWS"))
                try { packageManager.getPermissionInfo(p, 0); wanted += p } catch (_: Exception) {}
        }
        val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) requestPermissions(missing.toTypedArray(), 301)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 301) startGps()
    }

    private fun startGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            locationManager.removeUpdates(this)
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (locationManager.isProviderEnabled(p)) locationManager.requestLocationUpdates(p, 2000L, 5f, this)
        } catch (_: Exception) {}
    }

    override fun onLocationChanged(location: Location) {
        lastSpeedMps = if (location.hasSpeed()) location.speed else null
        if (demo || PlaybackService.latestPosition != null) { refresh(); return }
        val sample = PositionSample(location.latitude, location.longitude, location.accuracy, lastSpeedMps)
        map.showPosition(sample, false, lastMapPoint); lastMapPoint = sample
        weatherClient.fetch(sample.lat, sample.lon) { data ->
            if (data != null) {
                localWeatherRainy = data.rainy
                localWeatherText = (if (data.rainy) "비" else "맑음/흐림") +
                    (if (data.temperature.isFinite()) " · ${"%.0f".format(data.temperature)}°C" else "")
            }
            refresh()
        }
        refresh()
    }
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onDestroy() {
        demoToken++
        handler.removeCallbacksAndMessages(null)
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        weatherClient.shutdown()
        PlaybackService.removeObserver(observer)
        super.onDestroy()
    }
}
