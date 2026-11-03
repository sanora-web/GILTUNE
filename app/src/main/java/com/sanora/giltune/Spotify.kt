package com.sanora.giltune

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Spotify = main music source; synthesized local tracks = fallback.
 *  1. Token: official Spotify Android auth-lib (com.spotify.android:auth), Authorization Code + PKCE
 *     (code_challenge passed as custom params, code exchanged at accounts.spotify.com/api/token).
 *     https://developer.spotify.com/documentation/android/tutorials/authorization
 *     https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
 *  2. Track choice: Web API Search (GET /v1/search, type=track, limit<=10, random offset) with
 *     queries from QueryPlanner (scene keyword × mood × user genres/artists, scene_rules.json "taste"),
 *     ranked by learned weights, recent tracks/artists removed; plain scene keyword when nothing is found.
 *     No track URI is written by hand. https://developer.spotify.com/documentation/web-api/reference/search
 *  3. Playback: App Remote PlayerApi.play(uri) / queue(uri) / subscribeToPlayerState, UserApi.addToLibrary (♡).
 *     The upcoming list itself lives in the app (PlayQueue). Any failure -> local track.
 * Author: SANORA WEB
 */
typealias Found = SpotifyTrack

object SpotifyLink {
    const val REDIRECT_URI = "giltune://spotify-callback"
    const val AUTH_REQUEST = 4711
    const val SEARCH_LIMIT = 10
    /**
     * app-remote-control: App Remote playback. user-library-modify: ♡ saves the track to "Your Music"
     * ("Save Tracks for User" requires it - https://developer.spotify.com/documentation/web-api/concepts/scopes).
     * Search needs no scope.
     */
    val SCOPES = arrayOf("app-remote-control", "user-library-modify")
    private const val TAG = "GILTUNE_SPOTIFY"
    private const val PREFS = "giltune_spotify"
    private const val CACHE_TTL_MS = 30 * 60_000L



    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    @Volatile var remote: SpotifyAppRemote? = null; private set
    @Volatile var status: String = if (BuildConfig.SPOTIFY_CLIENT_ID.isBlank()) "Spotify: Client ID 없음 · 합성 음원" else "Spotify: 미연결"
        private set
    val enabled get() = BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank()
    val connected get() = remote?.isConnected == true
    private var appContext: Context? = null
    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val hasToken get() = prefs()?.getString("refresh", null) != null || prefs()?.getString("access", null) != null
    val ready get() = enabled && connected && hasToken
    /** Token from an older login without user-library-modify -> ask to log in again (♡ save). */
    val needsRelogin get() = hasToken && SCOPES.any { it !in (prefs()?.getString("scope", "") ?: "").split(" ") }

    // ---------- auth (auth-lib + PKCE) ----------
    fun login(activity: Activity) {
        if (!enabled) return
        appContext = activity.applicationContext
        val verifier = Base64.encodeToString(ByteArray(64).also { SecureRandom().nextBytes(it) },
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        val challenge = Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        prefs()!!.edit().putString("verifier", verifier).apply()
        val req = AuthorizationRequest.Builder(BuildConfig.SPOTIFY_CLIENT_ID, AuthorizationResponse.Type.CODE, REDIRECT_URI)
            .setScopes(SCOPES)
            .setCustomParam("code_challenge_method", "S256")
            .setCustomParam("code_challenge", challenge)
            .build()
        AuthorizationClient.openLoginActivity(activity, AUTH_REQUEST, req)
    }

    /** Call from Activity.onActivityResult. */
    fun onAuthResult(context: Context, resultCode: Int, data: Intent?, onChange: () -> Unit) {
        appContext = context.applicationContext
        val r = AuthorizationClient.getResponse(resultCode, data)
        when (r.type) {
            AuthorizationResponse.Type.CODE -> io.execute {
                val ok = tokenRequest(mapOf("grant_type" to "authorization_code", "code" to r.code,
                    "redirect_uri" to REDIRECT_URI, "client_id" to BuildConfig.SPOTIFY_CLIENT_ID,
                    "code_verifier" to (prefs()?.getString("verifier", "") ?: "")))
                status = if (ok) "Spotify 로그인 완료" else "Spotify 토큰 교환 실패 · 합성 음원"
                main.post(onChange)
            }
            AuthorizationResponse.Type.ERROR -> { status = "Spotify 로그인 오류(${r.error}) · 합성 음원"; onChange() }
            else -> { status = "Spotify 로그인 취소 · 합성 음원"; onChange() }
        }
    }

    private fun tokenRequest(form: Map<String, String>): Boolean = try {
        val body = form.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val c = URL("https://accounts.spotify.com/api/token").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode != 200) { Log.w(TAG, "token HTTP ${c.responseCode}"); false } else {
                val j = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                prefs()!!.edit().putString("access", j.getString("access_token"))
                    .putLong("expiresAt", System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000 - 60_000)
                    .apply { j.optString("refresh_token").takeIf { it.isNotBlank() }?.let { putString("refresh", it) } }
                    .apply { j.optString("scope").takeIf { it.isNotBlank() }?.let { putString("scope", it) } }.apply()
                true
            }
        } finally { c.disconnect() }
    } catch (e: Exception) { Log.w(TAG, "token failed: ${e.javaClass.simpleName}"); false }

    /** Valid access token or null (refreshes on the worker thread). */
    private fun token(): String? {
        val p = prefs() ?: return null
        val a = p.getString("access", null)
        if (a != null && System.currentTimeMillis() < p.getLong("expiresAt", 0)) return a
        val refresh = p.getString("refresh", null) ?: return null
        return if (tokenRequest(mapOf("grant_type" to "refresh_token", "refresh_token" to refresh,
                "client_id" to BuildConfig.SPOTIFY_CLIENT_ID))) p.getString("access", null) else null
    }

    // ---------- App Remote ----------
    fun connect(context: Context, onChange: () -> Unit) {
        appContext = context.applicationContext
        if (!enabled || connected) return
        if (!SpotifyAppRemote.isSpotifyInstalled(context)) { status = "Spotify 앱 미설치 · 합성 음원"; onChange(); return }
        val params = ConnectionParams.Builder(BuildConfig.SPOTIFY_CLIENT_ID)
            .setRedirectUri(REDIRECT_URI).showAuthView(true).build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote; subscribed = false
                // Repeat off (PlayerApi.setRepeat, Repeat.OFF): with repeat on, Spotify loops back to an old song after ours ends.
                try { appRemote.playerApi.setRepeat(com.spotify.protocol.types.Repeat.OFF) } catch (_: Exception) {}
                subscribe(); status = if (hasToken) "Spotify 연결됨" else "Spotify 연결됨 · 검색 로그인 필요"; onChange()
            }
            override fun onFailure(throwable: Throwable) {
                Log.w(TAG, "connect failed: ${throwable.javaClass.simpleName}")
                remote = null; status = "Spotify 연결 실패(${throwable.javaClass.simpleName}) · 합성 음원"; onChange()
            }
        })
    }

    // ---------- Web API search -> taste plans -> app-side queue ----------
    /** Search batches per scene (prepared ahead, e.g. for a NaviHelper-predicted tunnel). */
    private val batches = HashMap<String, Pair<Long, List<Found>>>()
    /** Now / upcoming / history. App Remote has no "read queue" or "remove from queue", so the app owns it. */
    val queue = PlayQueue()
    private var startedAt = 0L
    private var nowScene: String? = null
    var onQueueChange: () -> Unit = {}

    private fun rules() = SceneRules.current
    private fun profile() = appContext?.let { TasteStore.load(it) } ?: TasteProfile()

    fun hasBatch(scene: Scene) = synchronized(batches) { batches[scene.id]?.let { System.currentTimeMillis() - it.first < CACHE_TTL_MS && it.second.isNotEmpty() } == true }

    /**
     * Build a batch for [scene]: random scene keyword × mood × genre plans (QueryPlanner),
     * weighted pick of plans, random offset, ranking by learned weights, no recent tracks/artists.
     * Runs off the main thread; result (first track) on main.
     */
    fun prepare(scene: Scene, ctx: PlanContext, onResult: (Found?) -> Unit = {}) {
        if (hasBatch(scene)) { main.post { onResult(synchronized(batches) { batches[scene.id]!!.second.first() }) }; return }
        if (!enabled || !hasToken) { main.post { onResult(null) }; return }
        io.execute {
            val r = rules(); val p = profile(); val rnd = { Math.random() }; val gen0 = gen
            val list = try {
                val tk = token() ?: throw IllegalStateException("no token")
                val all = QueryPlanner.buildPlans(scene, p, r, ctx, rnd)
                val genreOn = all.any { it.fallback.isNotEmpty() }
                val recentU = queue.recentUris(r.taste.recentTracks); val recentA = queue.recentArtists(r.taste.recentArtists)
                var found: List<Found>
                if (genreOn) {
                    // 장르 모드: 아티스트 몫(약 22%)은 좋아하는 아티스트 검색, 나머지는 장르 풀에서 장르끼리 번갈아(장르 밖 곡 0)
                    val gsel = p.genres.filter { r.genres.containsKey(it) }
                    val slots = QueryPlanner.allocate(all, r.taste.batchSize, rnd)
                    val artistSlots = slots.filter { it.first.key.startsWith("artist:") }
                    val genreN = r.taste.batchSize - artistSlots.sumOf { it.second }
                    val gPlans = all.filter { it.fallback.isNotEmpty() }.associateBy { it.key.removePrefix("genre:") }
                    val api = genreApi(tk)
                    fun pools(force: Boolean) = gsel.associateWith { g -> poolFor(g, r, api, rnd, force) }
                    val picked = ArrayList(GenrePool.pick(pools(false), gsel, genreN, gPlans.mapValues { it.value.words }, gPlans.mapValues { it.value.moodKey }, recentU, recentA, r, rnd))
                    if (picked.size < genreN) picked += GenrePool.pick(pools(true), gsel, genreN - picked.size, gPlans.mapValues { it.value.words },
                        gPlans.mapValues { it.value.moodKey }, recentU + picked.map { it.uri }, recentA, r, rnd)
                    for ((pl, n) in artistSlots) {
                        val (tr, q) = QueryPlanner.searchPlan(pl, rnd) { qq, off -> search(tk, qq, off) }
                        val sorted = QueryPlanner.sortBySituation(tr, pl.words, rnd); val (exR, exA) = GenrePool.exclusionCounts(sorted, recentU, recentA)
                        QueryPlanner.diversify(sorted, recentU + picked.map { it.uri }, recentA + picked.flatMap { it.artistIds }, r)
                            .take(n).forEach { picked.add((rnd() * (picked.size + 1)).toInt(), it.copy(key = pl.key, q = q, words = pl.words,
                                sel = SelInfo(sorted.size, sorted.indexOf(it) + 1, exR, exA, "좋아하는 아티스트 검색", true))) }
                    }
                    found = picked
                } else {
                    val plans = QueryPlanner.pickPlans(all, r.taste.plansPerBatch, rnd)
                    val sw = QueryPlanner.situationWords(scene, null, r, ctx)
                    found = plans.flatMap { pl -> val tr = search(tk, pl.q, pl.offset).tracks; val (exR, exA) = GenrePool.exclusionCounts(tr, recentU, recentA)
                        tr.mapIndexed { i, t -> t.copy(key = pl.key, moodKey = pl.moodKey, q = pl.q, words = sw, sel = SelInfo(tr.size, i + 1, exR, exA, "장면 검색", pl.key.startsWith("artist:"))) } }
                }
                // 0건: 장르 선택 중이면 장르 이름 텍스트로만(장르 밖 곡 0%), 아니면 장면 키워드
                // 0건: 장르 모드면 그대로 비움(장르 밖 곡 0% → 자체 음원), 아니면 장면 키워드
                if (found.isEmpty() && !genreOn) found = search(tk, scene.keywords.first(), 0).tracks.map { it.copy(key = "scene:${scene.id}") }
                val ranked = if (genreOn) found.distinctBy { it.uri } else QueryPlanner.rankTracks(found.distinctBy { it.uri }, p, r, rnd)
                QueryPlanner.diversify(ranked, recentU, recentA, r).ifEmpty { ranked }.take(r.taste.batchSize)
            } catch (e: Exception) { Log.w(TAG, "search failed: ${e.javaClass.simpleName}"); emptyList() }
            if (list.isNotEmpty() && gen0 == gen) synchronized(batches) { batches[scene.id] = System.currentTimeMillis() to list }   // 장르가 바뀐 뒤 늦게 온 옛 결과는 버림
            main.post { onResult(list.firstOrNull()) }
        }
    }

    @Volatile private var gen = 0
    fun tasteChanged() {
        gen++
        synchronized(batches) { batches.clear() }
        queue.upcoming.removeAll { !it.user }; queuedUri = null; onQueueChange()
    }

    /** Scene became current: its batch replaces the auto part of the queue (user-added items stay first). */
    fun enterScene(scene: Scene): Found? {
        val list = synchronized(batches) { batches.remove(scene.id)?.second } ?: return null
        queue.replaceAuto(list.map { PlayQueue.Item(it, scene.id, scene.label, false) }); onQueueChange()
        return queue.upcoming.firstOrNull()?.track
    }

    // ── 장르 곡 풀 (세션 동안 보관, refreshMin마다 새로) ──
    private val pools = HashMap<String, GenrePoolResult>()
    private fun poolFor(g: String, r: SceneRules, api: GenreApi, rnd: () -> Double, force: Boolean): GenrePoolResult = synchronized(pools) {
        val cur = pools[g]
        if (!force && cur != null && cur.tracks.isNotEmpty() && System.currentTimeMillis() - cur.at < r.genrePool.refreshMin * 60_000L) return cur
        GenrePool.build(g, r, api, rnd).also { pools[g] = it }
    }
    private fun getJson(token: String, path: String): JSONObject {
        val c = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }
    private fun trackOf(t: JSONObject, q: String? = null): Found {
        val ar = t.optJSONArray("artists"); val artists = (0 until (ar?.length() ?: 0)).map { ar!!.getJSONObject(it) }
        return Found(t.getString("uri"), t.optString("name"), artists.joinToString(", ") { it.optString("name") }, artists.map { it.optString("id") },
            t.optLong("duration_ms"), q = q, album = t.optJSONObject("album")?.optString("name") ?: "", popularity = t.optInt("popularity"))
    }
    private val artistGenreCache = HashMap<String, List<String>>()
    private fun genreApi(tk: String) = object : GenreApi {
        override fun searchPlaylists(q: String): List<PlaylistRef> {
            val a = getJson(tk, "/search?type=playlist&limit=$SEARCH_LIMIT&q=" + URLEncoder.encode(q, "UTF-8")).getJSONObject("playlists").getJSONArray("items")
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { p ->
                PlaylistRef(p.getString("id"), p.optString("name"), p.optJSONObject("owner")?.optString("id"), (p.optJSONObject("items") ?: p.optJSONObject("tracks"))?.optInt("total") ?: 0) }
        }
        /** GET /playlists/{id}/items (2026-02 이후 이름). 개발 모드에선 내 플레이리스트만 — 아니면 403 → throw. */
        override fun playlistItems(id: String): List<SpotifyTrack> {
            val a = getJson(tk, "/playlists/" + URLEncoder.encode(id, "UTF-8") + "/items?limit=50").optJSONArray("items") ?: throw IllegalStateException("곡 목록 없음")
            val list = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { x -> x.optJSONObject("item") ?: x.optJSONObject("track") } }
                .filter { it.optString("type") != "episode" && it.has("uri") && it.has("artists") }.map { trackOf(it) }
            if (list.isEmpty()) throw IllegalStateException("곡 목록 없음"); return list
        }
        override fun searchTracks(q: String, offset: Int) = search(tk, q, offset).tracks
        override fun artistGenres(id: String): List<String> = synchronized(artistGenreCache) { artistGenreCache[id] } ?: run {
            val g = getJson(tk, "/artists/" + URLEncoder.encode(id, "UTF-8")).optJSONArray("genres")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            synchronized(artistGenreCache) { artistGenreCache[id] = g }; g
        }
    }
    /** 진단: 장르별 풀 크기·출처·플레이리스트 접근·앞 5곡 (실제 로그인으로 기기에서 확인). 백그라운드에서 호출. */
    fun diagnose(genres: List<String>): String {
        val tk = token() ?: return "Spotify 로그인 후 점검할 수 있어요"
        val r = rules(); val api = genreApi(tk); val sb = StringBuilder()
        for (g in genres) try {
            val p = GenrePool.build(g, r, api) { Math.random() }; synchronized(pools) { pools[g] = p }
            sb.append("■ ${r.genreLabels[g]}: ${p.tracks.size}곡 (플레이리스트 ${p.fromPlaylist} · 대표아티스트 ${p.fromArtist} · 장르명검색 ${p.fromText})\n")
            sb.append("  플레이리스트: ").append(p.playlists.joinToString(" / ") { "${it.name} ${if (it.ok) "OK ${it.n}" else "실패(${it.err})"}" }.ifEmpty { "없음" }).append('\n')
            p.tracks.take(5).forEachIndexed { i, t -> sb.append("  ${i + 1}. ${t.name} — ${t.artist} [${t.fit}]\n") }
        } catch (e: Exception) { sb.append("■ ${r.genreLabels[g]}: 오류 ${e.message}\n") }
        return sb.toString()
    }

    private fun search(token: String, q: String, offset: Int = 0, limit: Int = SEARCH_LIMIT): SearchPage {
        val url = "https://api.spotify.com/v1/search?type=track&limit=$limit&offset=$offset&q=" + URLEncoder.encode(q, "UTF-8")
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("Authorization", "Bearer $token")
            if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
            val tj = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("tracks")
            val items = tj.getJSONArray("items")
            return SearchPage((0 until items.length()).map { i ->
                val t = items.getJSONObject(i); val ar = t.optJSONArray("artists")
                val artists = (0 until (ar?.length() ?: 0)).map { ar!!.getJSONObject(it) }
                Found(t.getString("uri"), t.optString("name"), artists.joinToString(", ") { it.optString("name") },
                    artists.map { it.optString("id") }, t.optLong("duration_ms"), q = q,
                    album = t.optJSONObject("album")?.optString("name") ?: "", popularity = t.optInt("popularity"))
            }, if (tj.has("total")) tj.optInt("total") else null)
        } finally { c.disconnect() }
    }

    /** Next (+1): learn from how long the current track played, then the next queue item. Previous (-1): history. */
    fun step(delta: Int): Found? {
        if (delta < 0) {
            val last = queue.history.lastOrNull()?.first ?: return null
            queue.history.removeAt(queue.history.size - 1)
            queue.now?.let { queue.upcoming.add(0, it) }; queue.now = last; onQueueChange()
            return last.track
        }
        finishNow(null)
        return queue.advance(null)?.track.also { onQueueChange() }
    }

    /** Learning: skip / complete from listened time (QueryPlanner.listenKind), weights saved. */
    private fun finishNow(forced: String?) {
        val item = queue.now ?: return; val ctx = appContext ?: return
        val kind = forced ?: QueryPlanner.listenKind((System.currentTimeMillis() - startedAt) / 1000.0, item.track.durationMs / 1000.0, rules())
        if (kind != null && item.track.key != null) TasteStore.load(ctx).also { QueryPlanner.applyFeedback(it, item.track, item.scene, kind, rules()); TasteStore.save(ctx, it) }
    }

    fun like() {
        val item = queue.now ?: return
        appContext?.let { c -> TasteStore.load(c).also { QueryPlanner.applyFeedback(it, item.track, item.scene, "like", rules()); TasteStore.save(c, it) } }
        if (needsRelogin) status = "♡ 보관함 저장 권한: Spotify 다시 로그인 필요"
        try { remote?.takeIf { it.isConnected }?.userApi?.addToLibrary(item.track.uri) } catch (_: Exception) {}
    }

    /** Free-text search for the search screen (Development Mode: limit 10). */
    fun searchList(q: String, onResult: (List<Found>) -> Unit) {
        if (!enabled || !hasToken || q.isBlank()) { main.post { onResult(emptyList()) }; return }
        io.execute {
            val l = try { token()?.let { search(it, q).tracks } ?: emptyList() } catch (_: Exception) { emptyList() }
            main.post { onResult(l) }
        }
    }
    /** Artist search for the taste screen (GET /v1/search type=artist, limit 10). */
    fun searchArtists(q: String, onResult: (List<Artist>) -> Unit) {
        if (!enabled || !hasToken || q.isBlank()) { main.post { onResult(emptyList()) }; return }
        io.execute {
            val l = try {
                val tk = token() ?: throw IllegalStateException("no token")
                val c = URL("https://api.spotify.com/v1/search?type=artist&limit=$SEARCH_LIMIT&q=" + URLEncoder.encode(q, "UTF-8")).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 6000; c.readTimeout = 8000; c.setRequestProperty("Authorization", "Bearer $tk")
                    if (c.responseCode != 200) throw IllegalStateException("HTTP ${c.responseCode}")
                    val items = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONObject("artists").getJSONArray("items")
                    (0 until items.length()).map { Artist(items.getJSONObject(it).getString("id"), items.getJSONObject(it).optString("name")) }
                } finally { c.disconnect() }
            } catch (_: Exception) { emptyList() }
            main.post { onResult(l) }
        }
    }
    fun searchText(q: String, onResult: (Found?) -> Unit) = searchList(q) { onResult(it.firstOrNull()) }

    fun addToQueue(f: Found, scene: Scene) { queue.addUser(PlayQueue.Item(f, scene.id, scene.label, true)); onQueueChange() }

    /**
     * Rapid "next" taps are serialized: the app queue advances at once (each tap = one song), but only the
     * last requested song is sent to Spotify after [PLAY_DEBOUNCE_MS]; player states right after a command are ignored.
     */
    private var pendingPlay: Runnable? = null
    @Volatile private var lastCmdAt = 0L
    private const val PLAY_DEBOUNCE_MS = 250L
    private const val STALE_STATE_MS = 1500L
    private fun sendPlay(uri: String) {
        pendingPlay?.let { main.removeCallbacks(it) }
        lastCmdAt = System.currentTimeMillis()
        pendingPlay = Runnable {
            pendingPlay = null; lastCmdAt = System.currentTimeMillis()
            try { remote?.takeIf { it.isConnected }?.playerApi?.play(uri) } catch (e: Exception) { Log.w(TAG, "play failed: ${e.javaClass.simpleName}") }
            queuedUri = null; queueNextInSpotify()
        }.also { main.postDelayed(it, PLAY_DEBOUNCE_MS) }
    }

    /** @return true if Spotify took over playback. [f] becomes "now" in the app queue. */
    fun play(f: Found, scene: Scene? = null): Boolean {
        remote?.takeIf { it.isConnected } ?: return false
        return try {
            sendPlay(f.uri)
            if (queue.now?.track?.uri != f.uri) {
                finishNow(null)
                val i = queue.upcoming.indexOfFirst { it.track.uri == f.uri }
                val item = if (i >= 0) queue.upcoming.removeAt(i) else PlayQueue.Item(f, scene?.id ?: nowScene ?: "", scene?.label ?: "", true)
                queue.now?.let { queue.history.add(it to "partial") }; queue.now = item
            }
            nowScene = scene?.id ?: nowScene; startedAt = System.currentTimeMillis()
            subscribe(); onQueueChange()
            true
        } catch (e: Exception) { Log.w(TAG, "play failed: ${e.javaClass.simpleName}"); false }
    }

    /** Hand the next app-queue item to Spotify (PlayerApi.queue) so it follows without a gap. */
    private var queuedUri: String? = null
    private fun queueNextInSpotify() {
        val next = queue.upcoming.firstOrNull()?.track?.uri ?: return
        if (next == queuedUri) return
        try { remote?.playerApi?.queue(next); queuedUri = next } catch (_: Exception) {}
    }
    private var subscribed = false
    @Volatile private var posMs = 0L; @Volatile private var posAt = 0L; @Volatile private var paused = true
    @Volatile var durationMs = 0L; private set
    /** Position from the last player state, advanced by wall time while playing. */
    fun positionMs(): Long = (posMs + if (paused) 0 else System.currentTimeMillis() - posAt).coerceIn(0, maxOf(durationMs, 0))
    fun seekTo(ms: Long) { try { remote?.takeIf { it.isConnected }?.playerApi?.seekTo(ms.coerceAtLeast(0)) } catch (_: Exception) {} }
    fun seekBy(ms: Long) { try { remote?.takeIf { it.isConnected }?.playerApi?.seekToRelativePosition(ms) } catch (_: Exception) {} }
    /** Called when Spotify plays a song outside our queue and our queue is empty (service builds a new batch). */
    var onNeedMore: () -> Unit = {}
    /** Track ended and Spotify moved to our queued track -> "complete" feedback, advance.
     *  Spotify moved to a song outside our queue (old song / autoplay) -> correct to our next song. */
    private fun subscribe() {
        if (subscribed) return
        val r = remote ?: return; subscribed = true
        r.playerApi.subscribeToPlayerState().setEventCallback { st ->
            val uri = st.track?.uri ?: return@setEventCallback
            posMs = st.playbackPosition; posAt = System.currentTimeMillis(); paused = st.isPaused; durationMs = st.track.duration
            val nowUri = queue.now?.track?.uri ?: return@setEventCallback
            if (pendingPlay != null || System.currentTimeMillis() - lastCmdAt < STALE_STATE_MS || uri == nowUri) return@setEventCallback
            if (uri == queue.upcoming.firstOrNull()?.track?.uri) {
                finishNow("complete"); queue.advance("complete"); startedAt = System.currentTimeMillis()
                queuedUri = null; queueNextInSpotify(); main.post(onQueueChange)
            } else {
                val next = queue.upcoming.firstOrNull()?.track
                if (next != null) { finishNow(null); queue.advance(null); startedAt = System.currentTimeMillis(); sendPlay(next.uri); main.post(onQueueChange) }
                else main.post(onNeedMore)
            }
        }
    }

    fun pause() { try { remote?.takeIf { it.isConnected }?.playerApi?.pause() } catch (_: Exception) {} }
    fun disconnect() { remote?.let { SpotifyAppRemote.disconnect(it) }; remote = null; subscribed = false }
}
