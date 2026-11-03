package com.sanora.giltune

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaDescription
import android.media.MediaMetadata
import android.media.MediaPlayer
import android.media.browse.MediaBrowser
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.media.MediaBrowserService
import java.util.Calendar
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Playback + real-drive engine. Runs as a foreground service (mediaPlayback|location) while
 * playing, so position tracking and scene switching continue with the screen off.
 * Location: Pleos FusedLocation when the runtime supports it (pleos flavor), otherwise
 * Android LocationManager (GPS/network) - no Google Play Services dependency.
 */
class PlaybackService : MediaBrowserService() {
    companion object {
        const val ACTION_PLAY = "com.sanora.giltune.PLAY"
        const val ACTION_PAUSE = "com.sanora.giltune.PAUSE"
        const val ACTION_STOP = "com.sanora.giltune.STOP"
        const val ACTION_NEXT = "com.sanora.giltune.NEXT"
        const val ACTION_PREV = "com.sanora.giltune.PREV"
        const val ACTION_PLAY_TRACK = "com.sanora.giltune.PLAY_TRACK"
        /** Track picked on the search screen (handed to the service with ACTION_PLAY_TRACK). */
        @Volatile var requestedTrack: Found? = null
        const val ACTION_SCENE = "com.sanora.giltune.SCENE"
        const val ACTION_DEMO_BEGIN = "com.sanora.giltune.DEMO_BEGIN"
        const val ACTION_DEMO_END = "com.sanora.giltune.DEMO_END"
        const val ACTION_MANUAL_RAIN = "com.sanora.giltune.MANUAL_RAIN"
        const val EXTRA_SCENE = "scene"
        const val EXTRA_DEMO = "demo"
        const val EXTRA_RAIN = "rain"
        const val SOURCE_PLEOS = "PLEOS FUSED LOCATION"
        const val SOURCE_ANDROID = "ANDROID GPS"
        private const val CHANNEL_ID = "giltune_playback"
        private const val NOTIFICATION_ID = 821
        private const val ROOT_ID = "giltune_library"
        private const val CROSSFADE_MS = 2_000L
        private const val MANUAL_HOLD_MS = 120_000L
        private val observers = CopyOnWriteArraySet<() -> Unit>()
        private val mainHandler = Handler(Looper.getMainLooper())
        val provider: MusicProvider = LocalSynthProvider()

        @Volatile var isPlaying = false; private set
        @Volatile var currentScene: Scene = Scene.CRUISE; private set
        @Volatile var currentTrack: Track = provider.trackFor(Scene.CRUISE); private set
        val currentTrackName: String get() = currentTrack.title
        @Volatile var gpsSource: String = SOURCE_ANDROID; private set
        @Volatile var sdkStatus: String = "Pleos SDK 연결 전"; private set
        @Volatile var latestPosition: PositionSample? = null; private set
        @Volatile var speedMps: Float? = null; private set
        /** Last OSM/Nominatim result and when it arrived. */
        @Volatile var mapRoad: RoadInfo = RoadInfo.UNKNOWN; private set
        @Volatile var mapAt: Long = 0L; private set
        /** Road used for the scene: map result if fresh (speedBands.staleMs), else speed band (30/70 km/h). */
        val road: RoadInfo get() {
            val r = SceneRules.currentOrNull() ?: return mapRoad
            val now = System.currentTimeMillis(); val p = latestPosition
            val x = SpeedRoad.resolve(mapRoad, mapAt, now, speedMps?.toDouble(), r, drive?.fastSec(now) ?: 0.0)
            return GeoIndex.applyGeo(x, p?.lat, p?.lon, GeoIndex.currentOrNull(), r)   // coast / river / elevated (not over water)
        }
        /** Rolling stats: 60 km/h+ seconds, jam, continuous driving minutes. */
        @Volatile var drive: DriveTracker? = null; private set
        @Volatile var wx: WeatherClient.Result? = null; private set
        /** "선곡 이유" for the current track (scene · road · factors · genre). */
        @Volatile var reason: List<String> = emptyList(); private set
        /** What the next scene change still needs (small status line). */
        /** Taste changed (genre chip): drop auto upcoming songs and search again; the current song keeps playing. */
        fun rebuildUpcoming() {
            SpotifyLink.tasteChanged()   // 대기열·미리 받은 결과 즉시 버림(서비스 시작 전이어도)
            val sv = instance ?: return
            if (sv.spotifyActive && SpotifyLink.ready) { val sc = currentScene; SpotifyLink.prepare(sc, sv.planCtx(sc)) { SpotifyLink.enterScene(sc) } }
            else if (!sv.spotifyActive && isPlaying) sv.trySpotify(currentScene)   // reconnected: leave the own-track fallback
        }
        val usingSpotify: Boolean get() = instance?.spotifyActive == true
        fun sceneStatus(): SceneStabilizer.Status? = instance?.stabilizer?.status(System.currentTimeMillis())
        @Volatile var region: String? = null; private set
        @Volatile var weatherText: String? = null; private set
        @Volatile var weatherRainy: Boolean? = null; private set
        @Volatile var activeBridge: BridgeZone? = null; private set
        @Volatile var distanceToBridge: Double? = null; private set
        /** Gear from Pleos Vehicle SDK; UNKNOWN in demo builds or before the first callback. */
        @Volatile var gearState: GearState = GearState.UNKNOWN; private set
        @Volatile var wiperOn: Boolean? = null; private set
        @Volatile var naviAddress: String? = null; private set
        @Volatile var roadSpeedLimit: Int? = null; private set
        /** Next special segment predicted from NaviHelper route guidance (TBT), if any. */
        @Volatile var upcoming: UpcomingSegment? = null; private set
        /** Track that is ready (Spotify search result cached / local player buffered) for [upcoming]. */
        @Volatile var preparedText: String? = null; private set
        @Volatile var spotifyNow: Found? = null; private set
        @Volatile private var instance: PlaybackService? = null

        val isDriving: Boolean get() = DrivingJudge.isDriving(gearState, speedMps)
        fun catalog(): List<Track> = Scene.all().map { provider.trackFor(it) }
        fun positionMs(): Int = if (instance?.spotifyActive == true) SpotifyLink.positionMs().toInt()
            else try { instance?.player?.currentPosition ?: 0 } catch (_: Exception) { 0 }
        fun durationMs(): Int = if (instance?.spotifyActive == true) SpotifyLink.durationMs.toInt()
            else try { instance?.player?.duration ?: 0 } catch (_: Exception) { 0 }
        /** Seek: Spotify via App Remote PlayerApi.seekTo / seekToRelativePosition, local track via MediaPlayer.seekTo. */
        fun seekTo(ms: Int) {
            val s = instance ?: return
            if (s.spotifyActive) SpotifyLink.seekTo(ms.toLong()) else try { s.player?.seekTo(ms.coerceIn(0, durationMs())) } catch (_: Exception) {}
            notifyObservers()
        }
        fun seekBy(deltaMs: Int) {
            val s = instance ?: return
            if (s.spotifyActive) SpotifyLink.seekBy(deltaMs.toLong()) else seekTo(positionMs() + deltaMs)
            notifyObservers()
        }

        fun addObserver(listener: () -> Unit) { observers.add(listener); listener() }
        fun removeObserver(listener: () -> Unit) { observers.remove(listener) }
        private fun notifyObservers() { mainHandler.post { observers.forEach { it.invoke() } } }
        fun command(context: android.content.Context, action: String, scene: Scene? = null,
                    demoMode: Boolean = false, rainDemo: Boolean = false) {
            val intent = Intent(context, PlaybackService::class.java).apply {
                this.action = action
                if (scene != null) putExtra(EXTRA_SCENE, scene.name)
                putExtra(EXTRA_DEMO, demoMode)
                putExtra(EXTRA_RAIN, rainDemo)
            }
            if (action == ACTION_PLAY || action == ACTION_PLAY_TRACK) context.startForegroundService(intent) else context.startService(intent)
        }
    }

    private lateinit var session: MediaSession
    private lateinit var manager: AudioManager
    private var focusRequest: AudioFocusRequest? = null
    private var player: MediaPlayer? = null
    private var fadingOut: MediaPlayer? = null
    private var focusHeld = false
    private var resumedAfterTransientLoss = false
    private var inForeground = false
    private val roadEngine = RoadEngine()
    private val stabilizer by lazy { SceneStabilizer(SceneRules.current) }
    private var prepared: Pair<Track, MediaPlayer>? = null
    private var lastPleosLat = Double.NaN; private var lastPleosLon = Double.NaN
    private val recommender: RecommendationEngine = RuleBasedRecommendationEngine()
    private var inDemoMode = false
    private var manualRain = false
    private var manualHoldUntil = 0L
    private var locationManager: LocationManager? = null
    private var gpsIntervalMs = 0L
    private var stillSinceMs = 0L
    private val weatherClient = WeatherClient()
    private val osm = OsmRoadClient()
    private var logger: DriveLogger? = null
    private var pleosBridge: PleosSdkBridge? = null
    private val sdkStatuses = linkedMapOf<String, String>()
    private var lastPleosFixMs = 0L
    private var lastAndroidSpeedMs = 0L
    /** True while the Spotify app (App Remote) is the audio source instead of the local MP3. */
    private var spotifyActive = false
    /** Mood context for the taste planner: time of day / speed / rain only weight moods, never the scene. */
    private fun planCtx(scene: Scene = currentScene): PlanContext {
        val now = System.currentTimeMillis(); val r = road; val w = wx
        return PlanContext(now, wxCode = w?.code, wxClass = w?.wxClass, rain = RainScene.isRainy(wiperOn, weatherRainy, manualRain),
            tempC = w?.temperature?.takeIf { it.isFinite() }, sunrise = w?.sunrise, sunset = w?.sunset, speedMps = speedMps?.toDouble(),
            jam = drive?.jam() == true, driveMin = drive?.driveMin(now) ?: 0.0, sceneLabel = scene.label, roadReasons = roadReasons(r, scene))
    }
    private fun roadReasons(r: RoadInfo, scene: Scene) = listOfNotNull(if (r.coastal) "해안도로" else null, if (r.river && scene.id != "RIVERSIDE") "강변" else null)
    /** Refresh the "선곡 이유" line (Spotify: reason of the queue item's plan, local: scene · road · factors). */
    private var lastRecordedUri: String? = null
    private fun updateReason() {
        val r = SceneRules.current; val ctx = planCtx(); val sc = currentScene; val fs = QueryPlanner.contextFactors(ctx, r)
        val item = (if (spotifyActive) SpotifyLink.queue.now?.track else null)
            ?: SpotifyTrack("local:" + currentTrack.id, currentTrack.title, "SANORA WEB 자체 제작", emptyList(), key = "scene:${sc.id}",
                words = QueryPlanner.situationWords(sc, null, r, ctx), sel = SelInfo(5, 0, 0, 0, "자체 음원(직접 합성)", false))
        val plain = SelectionLog.plainReason(item, sc.label, sc.id, fs, r)
        reason = listOf(plain.text)
        // 선곡 기록: 곡이 바뀔 때 한 번
        if (isPlaying && item.uri != lastRecordedUri) {
            lastRecordedUri = item.uri
            SelectionLog.add(this, SelectionLog.record(System.currentTimeMillis(), item, spotifyActive, road, sc.label, fs, TasteStore.load(this).genres.filter { r.genres.containsKey(it) }, r, plain))
        }
    }
    private var lastFix: SpeedEstimator.Fix? = null

    /**
     * Spotify first. Cached search result (e.g. prefetched for a predicted tunnel) plays at once;
     * otherwise the search runs in the background while the local track keeps playing, and
     * Spotify takes over when a result arrives for the still-current scene.
     */
    private fun trySpotify(scene: Scene): Boolean {
        if (!SpotifyLink.ready) return false
        SpotifyLink.enterScene(scene)?.let { return startSpotify(scene, it) }   // batch prepared ahead (NaviHelper)
        SpotifyLink.prepare(scene, planCtx()) { f ->
            if (f != null && isPlaying && currentScene == scene && !spotifyActive) SpotifyLink.enterScene(scene)?.let { startSpotify(scene, it) }
        }
        return false
    }
    private fun startSpotify(scene: Scene, f: Found): Boolean {
        if (!SpotifyLink.play(f, scene)) return false
        try { player?.pause() } catch (_: Exception) {}
        spotifyActive = true; spotifyNow = f
        currentTrack = Track("spotify:${f.uri}", "${f.title} — ${f.artist}", scene, uri = f.uri, artist = f.artist)
        updateState(); return true
    }

    /** NaviHelper TBT says a tunnel/bridge/IC is ahead: search Spotify and buffer the local track now. */
    private fun prepareUpcoming(seg: UpcomingSegment?) {
        upcoming = seg
        val scene = seg?.let { Scene.byId(it.sceneId) }
        if (scene == null || scene == currentScene) { preparedText = null; notifyObservers(); return }
        val track = provider.trackFor(scene)
        if (prepared?.first?.id != track.id) {
            prepared?.second?.release(); prepared = null
            try { prepared = track to createPlayer(track) } catch (_: Exception) {}
        }
        preparedText = "${scene.label} 대비: ${track.title.substringBefore(" —")}"
        // Prepare the next segment's whole batch now, so the switch at the tunnel/bridge/IC is instant.
        if (SpotifyLink.ready) SpotifyLink.prepare(scene, planCtx()) { f ->
            if (f != null && upcoming?.sceneId == scene.id) { preparedText = "${scene.label} 대비: ${f.title} (Spotify)"; notifyObservers() }
        }
        notifyObservers()
    }

    private fun hour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun context() = SceneContext(activeBridge,
        RainScene.isRainy(wiperOn, weatherRainy, manualRain), hour(), road, speedMps)

    /** Re-evaluate the scene from all live inputs, through the anti-flapping stabilizer. */
    private fun evaluate() {
        if (inDemoMode) return
        val now = System.currentTimeMillis()
        val proposed = recommender.recommend(context())
        val stable = Scene.byId(stabilizer.propose(proposed.id, now).id) ?: return
        if (now < manualHoldUntil) return
        if (stable != currentScene) setScene(stable)
    }

    private fun applyLocation(sample: PositionSample, source: String) {
        if (inDemoMode) return
        latestPosition = sample
        gpsSource = source
        val now = System.currentTimeMillis()
        val result = roadEngine.update(sample, now, naviAddress)
        drive?.push(now, speedMps?.toDouble(), road.type.name)
        if (result.accurate) { activeBridge = result.currentZone; distanceToBridge = result.distanceToZone }
        evaluate()
        osm.lookup(sample.lat, sample.lon, speedMps, now,
            onRoad = { info -> mapRoad = info; mapAt = System.currentTimeMillis(); evaluate(); notifyObservers() },
            onRegion = { r -> region = r; notifyObservers() })
        weatherClient.fetch(sample.lat, sample.lon) { data ->
            if (data != null) {
                weatherRainy = data.rainy; wx = data
                weatherText = data.description +
                    (if (data.temperature.isFinite()) " · ${"%.0f".format(data.temperature)}°C" else "")
                evaluate(); notifyObservers()
            }
        }
        logger?.append(DriveLogRow(now, sample.lat, sample.lon, speedMps, road.type.label, road.name ?: naviAddress,
            region, currentScene, currentTrack.title, source, isDriving))
        notifyObservers()
    }

    private val gpsListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            // GPS speed when valid, otherwise distance/time between fixes (accuracy-filtered, scene_rules speed.*)
            val fix = SpeedEstimator.Fix(location.latitude, location.longitude, location.time,
                if (location.hasAccuracy()) location.accuracy.toDouble() else null, if (location.hasSpeed()) location.speed.toDouble() else null)
            speedMps = SpeedEstimator.fixSpeed(lastFix, fix)?.toFloat() ?: speedMps.takeIf { location.time - (lastFix?.t ?: 0) < SceneRules.current.speedHoldMs }
            lastFix = fix
            if (location.hasSpeed()) lastAndroidSpeedMs = System.currentTimeMillis()
            adaptGpsInterval()
            // A fresh Pleos fix takes precedence; Android speed is still used.
            if (System.currentTimeMillis() - lastPleosFixMs < 12_000L) return
            applyLocation(PositionSample(location.latitude, location.longitude, location.accuracy, speedMps), SOURCE_ANDROID)
        }
        override fun onProviderDisabled(provider: String) {}
        override fun onProviderEnabled(provider: String) {}
        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    }

    private fun hasLocationPermission() =
        checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Battery: 2 s / 5 m while moving, 10 s / 20 m after 60 s standing still. */
    private fun adaptGpsInterval() {
        val now = System.currentTimeMillis()
        val moving = (speedMps ?: 0f) > 1f
        if (moving) stillSinceMs = 0L else if (stillSinceMs == 0L) stillSinceMs = now
        val wanted = if (moving || stillSinceMs == 0L || now - stillSinceMs < 60_000L) 2_000L else 10_000L
        if (wanted != gpsIntervalMs) requestGps(wanted)
    }

    @android.annotation.SuppressLint("MissingPermission") // checked by hasLocationPermission(); SecurityException caught
    private fun requestGps(intervalMs: Long) {
        if (!hasLocationPermission()) return
        try {
            val lm = locationManager ?: (getSystemService(LOCATION_SERVICE) as LocationManager).also { locationManager = it }
            lm.removeUpdates(gpsListener)
            val dist = if (intervalMs <= 2_000L) 5f else 20f
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER))
                if (lm.isProviderEnabled(p)) lm.requestLocationUpdates(p, intervalMs, dist, gpsListener, Looper.getMainLooper())
            gpsIntervalMs = intervalMs
        } catch (_: Exception) { /* GPS unavailable: playback keeps working */ }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        drive = DriveTracker(SceneRules.ensure(this))
        SpotifyLink.onNeedMore = { if (spotifyActive && isPlaying) moveTrack(1) }
        Thread { try { GeoIndex.ensure(this, SceneRules.current) } catch (_: Exception) {} }.start()   // ~0.4 MB built-in coast/river data
        manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val channel = NotificationChannel(CHANNEL_ID, "길튠 음악 재생", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        session = MediaSession(this, "GILTUNE_MediaSession").apply {
            setCallback(object : MediaSession.Callback() {
                override fun onPlay() { resumePlayback() }
                override fun onPause() { pausePlayback() }
                override fun onStop() { stopPlayback() }
                override fun onSkipToNext() { moveTrack(1) }
                override fun onSkipToPrevious() { moveTrack(-1) }
                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    val target = catalog().firstOrNull { it.id == mediaId }?.scene ?: return
                    manualHoldUntil = System.currentTimeMillis() + MANUAL_HOLD_MS
                    setScene(target); resumePlayback()
                }
                override fun onPlayFromSearch(query: String?, extras: Bundle?) {
                    if (SpotifyLink.ready && !query.isNullOrBlank()) {
                        manualHoldUntil = System.currentTimeMillis() + MANUAL_HOLD_MS
                        isPlaying = true; obtainFocus(); ensureForeground()
                        SpotifyLink.searchText(query) { f -> if (f == null || !startSpotify(currentScene, f)) resumePlayback() }
                        return
                    }
                    val target = catalog().firstOrNull { it.title.contains(query.orEmpty(), true) }?.scene
                    if (target != null) setScene(target)
                    resumePlayback()
                }
            })
            isActive = true
        }
        sessionToken = session.sessionToken
        logger = DriveLogger(this)
        updateState()
        requestGps(2_000L)
        // Runtime choice: the bridge reports whether Pleos services exist; Android GPS stays as fallback.
        val bridge = PleosSdkBridge(this, PleosCallbacks(
            onLocation = { sample ->
                val now = System.currentTimeMillis()
                // Emulator/Virtual Console: FusedLocation has no speed field, so derive it from consecutive fixes.
                if (!lastPleosLat.isNaN() && now - lastPleosFixMs in 500L..15_000L &&
                    now - lastAndroidSpeedMs > 5_000L) {
                    speedMps = (QueryThrottle.distanceMeters(lastPleosLat, lastPleosLon, sample.lat, sample.lon) /
                        ((now - lastPleosFixMs) / 1000.0)).toFloat()
                }
                lastPleosLat = sample.lat; lastPleosLon = sample.lon
                lastPleosFixMs = now
                applyLocation(sample.copy(speedMps = speedMps), SOURCE_PLEOS)
            },
            onRoadAddress = { address -> naviAddress = address; notifyObservers() },
            onSpeedLimit = { limit -> roadSpeedLimit = limit; notifyObservers() },
            onUpcoming = { seg -> prepareUpcoming(seg) },
            onGear = { gear -> gearState = gear; notifyObservers() },
            onWiper = { on -> wiperOn = on; evaluate(); notifyObservers() },
            onStatus = { group, message ->
                sdkStatuses[group] = message
                sdkStatus = sdkStatuses.values.joinToString(" | ")
                notifyObservers()
            }))
        pleosBridge = bridge
        bridge.start()
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot =
        BrowserRoot(ROOT_ID, Bundle())

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowser.MediaItem>>) {
        val items = mutableListOf<MediaBrowser.MediaItem>()
        if (parentId == ROOT_ID) for (t in catalog()) {
            val d = MediaDescription.Builder().setMediaId(t.id).setTitle(t.title)
                .setSubtitle("길튠 합성 음원 · ${t.scene.label}").build()
            items.add(MediaBrowser.MediaItem(d, MediaBrowser.MediaItem.FLAG_PLAYABLE))
        }
        result.sendResult(items)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_PLAY) {
            inDemoMode = intent.getBooleanExtra(EXTRA_DEMO, false)
            manualRain = intent.getBooleanExtra(EXTRA_RAIN, false)
            ensureForeground() // must happen soon after startForegroundService
        }
        when (action) {
            ACTION_PLAY -> {
                intent.getStringExtra(EXTRA_SCENE)?.let { sceneByName(it)?.let { s -> if (inDemoMode) setScene(s) } }
                resumePlayback()
            }
            ACTION_PAUSE -> pausePlayback()
            ACTION_STOP -> stopPlayback()
            ACTION_NEXT -> moveTrack(1)
            ACTION_PREV -> moveTrack(-1)
            ACTION_PLAY_TRACK -> requestedTrack?.let { f ->
                requestedTrack = null; manualHoldUntil = System.currentTimeMillis() + MANUAL_HOLD_MS
                isPlaying = true; obtainFocus(); ensureForeground()
                if (!startSpotify(currentScene, f)) resumePlayback()
            }
            ACTION_SCENE -> intent.getStringExtra(EXTRA_SCENE)?.let { sceneByName(it)?.let(::setScene) }
            ACTION_DEMO_BEGIN -> inDemoMode = true
            ACTION_MANUAL_RAIN -> { manualRain = intent.getBooleanExtra(EXTRA_RAIN, false); evaluate() }
            ACTION_DEMO_END -> {
                inDemoMode = false; manualRain = false
                roadEngine.reset(); stabilizer.reset(); activeBridge = null
                evaluate()
            }
        }
        return Service.START_NOT_STICKY
    }

    private fun sceneByName(name: String): Scene? = Scene.byId(name)

    private val focusListener = AudioManager.OnAudioFocusChangeListener { focus ->
        when (focus) {
            AudioManager.AUDIOFOCUS_LOSS -> { resumedAfterTransientLoss = false; pausePlayback() }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> { resumedAfterTransientLoss = isPlaying; pausePlayback(abandonFocus = false) }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> player?.setVolume(.25f, .25f)
            AudioManager.AUDIOFOCUS_GAIN -> {
                player?.setVolume(1f, 1f)
                if (resumedAfterTransientLoss) { resumedAfterTransientLoss = false; resumePlayback() }
            }
        }
    }

    private fun obtainFocus(): Boolean {
        if (focusHeld) return true
        val attrs = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build()
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attrs).setOnAudioFocusChangeListener(focusListener).build()
        focusRequest = request
        focusHeld = manager.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return focusHeld
    }

    private fun abandonFocus() {
        if (!focusHeld) return
        focusRequest?.let { manager.abandonAudioFocusRequest(it) }
        focusHeld = false
    }

    // ---------- local fallback: the 5 own tracks, shuffled one round at a time ----------
    private val bag = ArrayDeque<String>()
    private val localHistory = ArrayDeque<String>()
    private fun lastLocal(): String? = localHistory.lastOrNull()
    /** New scene: reshuffle with the scene's file first (unless it just played). */
    private fun refillBag(scene: Scene) {
        bag.clear(); bag.addAll(QueryPlanner.shuffleBag(LocalSynthProvider.FILES.sorted(), scene.local, lastLocal(), Math::random))
    }
    private fun takeLocal(scene: Scene): Track {
        if (bag.isEmpty()) refillBag(scene)
        val f = bag.removeFirst(); localHistory.addLast(f); while (localHistory.size > 20) localHistory.removeFirst()
        return (provider as LocalSynthProvider).trackForFile(f, scene)
    }
    /** Next own track (skip / natural end); never the same file twice in a row. */
    private fun nextLocal() { currentTrack = takeLocal(currentScene); if (isPlaying) crossfadeTo(currentTrack) else updateState() }

    private fun setScene(newScene: Scene) {
        if (newScene == currentScene && player != null) return
        currentScene = newScene
        refillBag(newScene); currentTrack = takeLocal(newScene)
        if (isPlaying && trySpotify(newScene)) { updateState(); return }
        if (spotifyActive) SpotifyLink.pause()
        spotifyActive = false; spotifyNow = null
        if (isPlaying) crossfadeTo(currentTrack) else { player?.release(); player = null; updateState() }
    }

    private fun moveTrack(delta: Int) {
        if (spotifyActive) {  // Spotify mode: next/previous result of the same scene search
            manualHoldUntil = System.currentTimeMillis() + MANUAL_HOLD_MS
            val f = SpotifyLink.step(delta)
            if (f != null) { if (startSpotify(currentScene, f)) return }
            else if (delta > 0 && SpotifyLink.ready) {   // queue used up: new batch for this scene (recent songs excluded), stay on Spotify
                val sc = currentScene
                SpotifyLink.prepare(sc, planCtx(sc)) { SpotifyLink.enterScene(sc)?.let { n -> SpotifyLink.step(1); startSpotify(sc, n) } }
                return
            } else if (delta < 0) return
        }
        // Own tracks: next = next file of the shuffle bag, previous = the file before (scene stays automatic).
        if (delta < 0 && localHistory.size >= 2) {
            localHistory.removeLast(); val f = localHistory.last()
            currentTrack = (provider as LocalSynthProvider).trackForFile(f, currentScene); if (isPlaying) crossfadeTo(currentTrack) else updateState()
        } else nextLocal()
        if (!isPlaying) resumePlayback()
    }

    private fun createPlayer(track: Track): MediaPlayer {
        val res = track.rawResId ?: throw IllegalStateException("no local file for ${track.id}")
        val p = MediaPlayer.create(this, res) ?: throw IllegalStateException("No playable audio")
        p.setOnCompletionListener { if (isPlaying && it === player) nextLocal() }  // next file of the shuffle bag
        return p
    }

    private fun resumePlayback() {
        session.isActive = true
        if (!obtainFocus()) {
            isPlaying = false; updateState("오디오 포커스를 얻지 못했습니다"); exitForeground(); return
        }
        ensureForeground()
        isPlaying = true
        // No location (permission denied / offline): choose by time + weather only.
        if (latestPosition == null && !inDemoMode) { val s = recommender.recommend(context()); if (s != currentScene) { currentScene = s; refillBag(s); currentTrack = takeLocal(s); player?.release(); player = null } }
        if (trySpotify(currentScene)) { updateState(); return }
        spotifyActive = false
        try {
            val p = player ?: createPlayer(currentTrack).also { player = it }
            p.setVolume(1f, 1f); p.start()
            isPlaying = true
            updateState()
        } catch (_: Exception) {
            isPlaying = false; updateState("재생 실패"); exitForeground()
        }
    }

    /** Scene change while playing: old track fades out while the new one fades in (2 s). */
    private fun crossfadeTo(track: Track) {
        val old = player
        val ready = prepared?.takeIf { it.first.id == track.id }?.second.also { if (it != null) prepared = null }
        val next = try { ready ?: createPlayer(track) } catch (_: Exception) { updateState("음원 재생 오류"); return }
        fadingOut?.release(); fadingOut = old
        player = next
        next.setVolume(0f, 0f); next.start()
        val steps = 20
        for (i in 1..steps) mainHandler.postDelayed({
            val v = i / steps.toFloat()
            try { next.setVolume(v, v) } catch (_: Exception) {}
            try { old?.setVolume(1f - v, 1f - v) } catch (_: Exception) {}
            if (i == steps) { try { old?.release() } catch (_: Exception) {}; if (fadingOut === old) fadingOut = null }
        }, CROSSFADE_MS * i / steps)
        updateState()
    }

    private fun pausePlayback(abandonFocus: Boolean = true) {
        if (spotifyActive) SpotifyLink.pause()
        try { player?.pause() } catch (_: Exception) {}
        isPlaying = false
        if (abandonFocus) abandonFocus()
        updateState()
        exitForeground()
    }

    private fun stopPlayback() {
        if (spotifyActive) { SpotifyLink.pause(); spotifyActive = false }
        isPlaying = false; resumedAfterTransientLoss = false
        player?.release(); player = null
        abandonFocus(); updateState(); exitForeground()
        session.isActive = false
        stopSelf()
    }

    private fun updateState(error: String? = null) {
        try { updateReason() } catch (_: Exception) {}
        val actions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_STOP or PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_PLAY_FROM_MEDIA_ID or PlaybackState.ACTION_PLAY_FROM_SEARCH
        val state = if (error != null) PlaybackState.STATE_ERROR
                    else if (isPlaying) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED
        val builder = PlaybackState.Builder().setActions(actions)
            .setState(state, positionMs().toLong(), if (isPlaying) 1f else 0f)
        if (error != null) builder.setErrorMessage(error)
        session.setPlaybackState(builder.build())
        session.setMetadata(MediaMetadata.Builder()
            .putString(MediaMetadata.METADATA_KEY_TITLE, currentTrack.title)
            .putString(MediaMetadata.METADATA_KEY_ARTIST, currentTrack.artist ?: "GILTUNE · ${currentScene.label}")
            .putString(MediaMetadata.METADATA_KEY_ALBUM, "Driving Scenes")
            .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMs().toLong()).build())
        if (inForeground) (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification())
        notifyObservers()
    }

    private fun pending(action: String, request: Int): PendingIntent = PendingIntent.getService(
        this, request, Intent(this, PlaybackService::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun act(icon: Int, title: String, action: String, req: Int) =
            Notification.Action.Builder(android.graphics.drawable.Icon.createWithResource(this, icon), title, pending(action, req)).build()
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("길튠 · ${currentTrack.title}")
            .setContentText("${currentScene.label} · ${road.type.label}")
            .setContentIntent(open)
            .setOngoing(isPlaying)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .addAction(act(android.R.drawable.ic_media_previous, "이전", ACTION_PREV, 1))
            .addAction(if (isPlaying) act(android.R.drawable.ic_media_pause, "일시정지", ACTION_PAUSE, 2)
                       else act(android.R.drawable.ic_media_play, "재생", ACTION_PLAY, 2))
            .addAction(act(android.R.drawable.ic_media_next, "다음", ACTION_NEXT, 3))
            .setStyle(Notification.MediaStyle().setMediaSession(session.sessionToken).setShowActionsInCompactView(0, 1, 2))
            .build()
    }

    private fun ensureForeground() {
        if (inForeground) return
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                // Location type only when permission is granted (Android 14 throws otherwise).
                if (hasLocationPermission()) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                startForeground(NOTIFICATION_ID, buildNotification(), type)
            } else startForeground(NOTIFICATION_ID, buildNotification())
            inForeground = true
        } catch (_: Exception) { inForeground = false }
    }

    private fun exitForeground() {
        if (!inForeground) return
        stopForeground(STOP_FOREGROUND_DETACH)
        inForeground = false
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopPlayback()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        instance = null
        isPlaying = false
        try { player?.release() } catch (_: Exception) {}
        try { fadingOut?.release() } catch (_: Exception) {}
        try { prepared?.second?.release() } catch (_: Exception) {}; prepared = null; upcoming = null; preparedText = null
        player = null; fadingOut = null
        abandonFocus()
        try { locationManager?.removeUpdates(gpsListener) } catch (_: Exception) {}
        weatherClient.shutdown(); osm.shutdown()
        pleosBridge?.release(); pleosBridge = null
        gearState = GearState.UNKNOWN; wiperOn = null; naviAddress = null; roadSpeedLimit = null; speedMps = null
        session.isActive = false
        session.release()
        exitForeground()
        notifyObservers()
        super.onDestroy()
    }
}
