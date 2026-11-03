package com.sanora.giltune

import android.app.Activity
import android.app.AlertDialog
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/*
 * Taste / queue / search sheets. Every row is >= 76dp (Pleos Large button). Opened only from the
 * ≡ menu, which is locked while driving, so search and editing happen only when stopped.
 * Author: SANORA WEB
 */
class Sheets(private val a: Activity, private val onChange: () -> Unit) {
    private fun dp(n: Int) = (n * a.resources.displayMetrics.density + .5f).toInt()
    private fun row(text: String, onClick: (() -> Unit)? = null) = TextView(a).apply {
        this.text = text; textSize = 18f; minHeight = dp(76); gravity = Gravity.CENTER_VERTICAL
        setTextColor(a.getColor(R.color.text_primary)); setPadding(dp(24), dp(8), dp(24), dp(8))
        onClick?.let { c -> setOnClickListener { c() } }
    }
    private fun header(text: String) = TextView(a).apply {
        this.text = text; textSize = 14f; setTextColor(a.getColor(R.color.text_secondary)); setPadding(dp(24), dp(16), dp(24), dp(4))
    }
    private fun dialog(title: String, body: LinearLayout, positive: String = "닫기", onPositive: () -> Unit = {}): AlertDialog =
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle(title)
            .setView(ScrollView(a).apply { addView(body) }).setPositiveButton(positive) { _, _ -> onPositive() }.show()

    /** 선곡 기록 (최근 50곡): 곡마다 판단 근거, 텍스트 복사·공유. */
    fun selectionLog() {
        val list = SelectionLog.load(a); val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(header("곡마다 왜 골랐는지 남겨요 · GILTUNE · SANORA WEB"))
        if (list.isEmpty()) body.addView(row("아직 기록이 없어요. 재생하면 곡마다 남아요."))
        for (x in list) body.addView(TextView(a).apply {
            text = SelectionLog.recordText(x); textSize = 15f; setLineSpacing(0f, 1.15f); setTextColor(a.getColor(R.color.text_primary))
            setBackgroundColor(0xFF111A28.toInt()); setPadding(dp(20), dp(14), dp(20), dp(14))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(8)) }
        })
        val text = SelectionLog.logText(list)
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle("선곡 기록 · ${list.size}곡")
            .setView(ScrollView(a).apply { addView(body) })
            .setPositiveButton("공유") { _, _ ->
                a.startActivity(android.content.Intent.createChooser(android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(android.content.Intent.EXTRA_SUBJECT, "길튠 선곡 기록").putExtra(android.content.Intent.EXTRA_TEXT, text), "선곡 기록 공유"))
            }
            .setNeutralButton("텍스트 복사") { _, _ ->
                (a.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                    .setPrimaryClip(android.content.ClipData.newPlainText("길튠 선곡 기록", text))
                android.widget.Toast.makeText(a, "선곡 기록을 복사했어요", android.widget.Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("닫기", null).show()
    }

    /** First run and settings: genres (incl. ballad / trot) and moods, multi-select. */
    fun taste(first: Boolean = false) {
        val r = SceneRules.current; val p = TasteStore.load(a)
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun toggles(title: String, labels: Map<String, String>, sel: MutableList<String>) {
            body.addView(header(title))
            for ((k, label) in labels) {
                lateinit var v: TextView
                v = row((if (k in sel) "✓ " else "   ") + label) { if (!sel.remove(k)) sel.add(k); v.text = (if (k in sel) "✓ " else "   ") + label }
                body.addView(v)
            }
        }
        toggles("좋아하는 장르 (여러 개 선택)", r.genreLabels, p.genres)
        toggles("분위기 (선택 안 하면 시간대·속도·날씨로 자동)", r.taste.moodLabels, p.moods)
        // Artists: search (stopped only) -> tap to add; tap a chosen artist to remove.
        body.addView(header("좋아하는 아티스트 (눌러서 삭제)"))
        val chosen = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        fun renderChosen() {
            chosen.removeAllViews()
            if (p.artists.isEmpty()) chosen.addView(header("아직 없음"))
            p.artists.toList().forEach { ar -> chosen.addView(row("✓ ${ar.name}") { p.artists.remove(ar); renderChosen() }) }
        }
        renderChosen(); body.addView(chosen)
        if (PlaybackService.isDriving) body.addView(header("아티스트 검색은 정차 중에만 할 수 있어요"))
        else if (!SpotifyLink.hasToken) body.addView(header("아티스트 검색은 Spotify 로그인 후 사용할 수 있어요"))
        else {
            val found = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val input = EditText(a).apply {
                hint = "아티스트 검색"; textSize = 18f; minHeight = dp(76); setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
                setTextColor(a.getColor(R.color.text_primary))
            }
            input.setOnEditorActionListener { _, _, _ ->
                found.removeAllViews(); found.addView(header("검색 중…"))
                SpotifyLink.searchArtists(input.text.toString()) { list ->
                    found.removeAllViews()
                    if (list.isEmpty()) found.addView(header("결과 없음"))
                    list.forEach { ar -> found.addView(row("+ ${ar.name}") {
                        if (p.artists.none { it.id == ar.id }) { p.artists.add(ar); renderChosen() }
                    }) }
                }
                true
            }
            body.addView(input); body.addView(found)
        }
        body.addView(header("선택은 곡을 고를 때만 쓰이고, 넘기기·♡로 조금씩 학습합니다."))
        dialog(if (first) "취향 고르기" else "취향 다시 고르기", body, "저장") { p.done = true; TasteStore.save(a, p); onChange() }
            .also { if (first) it.setCancelable(false) }
    }

    /** Now playing / upcoming (by scene) / history. Tap an upcoming row to remove it. */
    fun queue() {
        val q = SpotifyLink.queue
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(header("지금 재생"))
        body.addView(row(q.now?.let { "${it.track.title} — ${it.track.artist}" } ?: PlaybackService.currentTrack.title))
        body.addView(header("다음 곡 (눌러서 삭제)"))
        if (q.upcoming.isEmpty()) body.addView(row("Spotify 연결 후 장면마다 채워집니다"))
        var lastScene: String? = null
        q.upcoming.toList().forEachIndexed { i, it ->
            if (it.sceneLabel != lastScene) { body.addView(header("· " + it.sceneLabel.ifBlank { "직접 추가" })); lastScene = it.sceneLabel }
            lateinit var v: TextView
            v = row("${it.track.title} — ${it.track.artist}") { q.remove(q.upcoming.indexOf(it)); body.removeView(v); onChange() }
            body.addView(v)
        }
        body.addView(header("들은 곡"))
        q.history.takeLast(30).reversed().forEach { (it, res) ->
            val mark = when (res) { "skip" -> "넘김"; "complete" -> "끝까지"; "like" -> "♥"; else -> "" }
            body.addView(row("${it.track.title} — ${it.track.artist}  $mark"))
        }
        dialog("재생목록", body)
    }

    /** Web API search (Development Mode: up to 10 results) -> play now or add to queue. */
    fun search() {
        val body = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val input = EditText(a).apply {
            hint = "곡·아티스트 검색"; textSize = 18f; minHeight = dp(76); setSingleLine(); imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(a.getColor(R.color.text_primary))
        }
        val results = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        body.addView(input); body.addView(results)
        input.setOnEditorActionListener { _, _, _ ->
            results.removeAllViews(); results.addView(header("검색 중…"))
            SpotifyLink.searchList(input.text.toString()) { list ->
                results.removeAllViews()
                if (list.isEmpty()) results.addView(header(if (SpotifyLink.hasToken) "결과 없음" else "Spotify 검색 로그인이 필요합니다"))
                list.forEach { f ->
                    results.addView(row("${f.title} — ${f.artist}") {
                        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert).setTitle(f.title)
                            .setItems(arrayOf("바로 재생", "다음 곡에 추가")) { _, w ->
                                if (w == 0) { PlaybackService.requestedTrack = f; PlaybackService.command(a, PlaybackService.ACTION_PLAY_TRACK) }
                                else SpotifyLink.addToQueue(f, PlaybackService.currentScene)
                                onChange()
                            }.show()
                    })
                }
            }
            true
        }
        dialog("노래 검색 (정차 중)", body)
    }
}
