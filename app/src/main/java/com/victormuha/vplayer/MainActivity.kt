package com.victormuha.vplayer

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.player.PlaybackService
import org.json.JSONArray

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("vplayer", Context.MODE_PRIVATE) }
    private val handler = Handler(Looper.getMainLooper())
    private var currentScreen: View? = null

    // polling state for device flow
    private var deviceCode: ApiClient.DeviceCode? = null
    private var pollRunnable: Runnable? = null

    // liked list state
    private var likedTracks: List<ApiClient.Track> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupApiToken()
        route()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopPolling()
    }

    override fun onResume() {
        super.onResume()
        PlaybackService.onStateChange = { handler.post { updatePlayerUi() } }
        updatePlayerUi()
        // If polling was interrupted (Activity recreated), restart it
        if (pollRunnable == null && deviceCode != null) {
            deviceCode?.let { code -> startPolling(code) }
        }
    }

    override fun onPause() {
        super.onPause()
        PlaybackService.onStateChange = null
        // Keep polling alive — user may go to phone to authorize and come back to the watch.
    }

    // ---------------- routing ----------------
    private fun route() {
        if (ApiClient.token.isNullOrEmpty()) showLogin()
        else showLiked()
    }

    private fun showLogin() {
        val root = buildRoot()
        currentScreen = root
        val info = TextView(this).apply {
            text = getString(R.string.login_hint)
            setTextColor(getColor(R.color.text_primary))
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val deviceBtn = Button(this).apply {
            text = getString(R.string.login)
            setOnClickListener { startDeviceFlow() }
        }
        val manualBtn = Button(this).apply {
            text = getString(R.string.login_manual_token)
            setOnClickListener { showManualToken() }
        }
        root.addView(info)
        root.addView(deviceBtn)
        root.addView(manualBtn)
        setContentView(root)
        currentScreen = root
    }

    private fun showManualToken() {
        val root = buildRoot()
        currentScreen = root
        val hint = TextView(this).apply { text = "OAuth token:" }
        val et = EditText(this).apply { this.hint = "y0_..." }
        val btn = Button(this).apply {
            text = "OK"
            setOnClickListener {
                val tok = et.text.toString().trim()
                if (tok.isNotEmpty()) {
                    prefs.edit().putString("token", tok).apply()
                    ApiClient.token = tok
                    route()
                }
            }
        }
        root.addView(hint)
        root.addView(et)
        root.addView(btn)
        setContentView(root)
    }

    private fun startDeviceFlow() {
        stopPolling()
        val deviceId = prefs.getString("device_id", null) ?: run {
            val id = (1..10).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            prefs.edit().putString("device_id", id).apply()
            id
        }
        val root = buildRoot()
        val waitLabel = TextView(this).apply { text = getString(R.string.login_waiting) }
        val urlLabel = TextView(this).apply { text = getString(R.string.login_step_open) }
        val urlValue = TextView(this).apply {
            text = "…"
            setTextColor(getColor(R.color.accent))
            textSize = 14f
        }
        val codeLabel = TextView(this).apply { text = getString(R.string.login_step_enter) }
        val codeValue = TextView(this).apply {
            textSize = 22f
            setTextColor(getColor(R.color.accent))
            gravity = Gravity.CENTER
            text = "…"
        }
        val cancelBtn = Button(this).apply {
            text = getString(R.string.login_cancel)
            setOnClickListener { stopPolling(); showLogin() }
        }
        root.addView(waitLabel)
        root.addView(urlLabel)
        root.addView(urlValue)
        root.addView(codeLabel)
        root.addView(codeValue)
        root.addView(cancelBtn)
        setContentView(root)

        ApiClient.requestDeviceCode(deviceId, "vplayer_watch") { code, err ->
            if (err != null) {
                handler.post {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    showLogin()
                }
                return@requestDeviceCode
            }
            deviceCode = code
            handler.post {
                urlValue.text = code?.verificationUrl ?: ""
                codeValue.text = code?.userCode ?: ""
            }
            startPolling(code)
        }
    }

    private fun startPolling(code: ApiClient.DeviceCode?) {
        code ?: return
        val interval = (code.interval * 1000).coerceAtLeast(3000)
        val runnable = object : Runnable {
            override fun run() {
                if (pollRunnable == null) return
                ApiClient.pollDeviceToken(code.deviceCode) { result ->
                    when (result) {
                        is ApiClient.TokenResult.Success -> {
                            stopPolling()
                            prefs.edit().putString("token", result.token).apply()
                            ApiClient.token = result.token
                            handler.post { route() }
                        }
                        is ApiClient.TokenResult.Expired -> {
                            stopPolling()
                            handler.post {
                                Toast.makeText(this@MainActivity, "Код истёк", Toast.LENGTH_LONG).show()
                                showLogin()
                            }
                        }
                        is ApiClient.TokenResult.Denied -> {
                            stopPolling()
                            handler.post {
                                Toast.makeText(this@MainActivity, "Отказано", Toast.LENGTH_LONG).show()
                                showLogin()
                            }
                        }
                        is ApiClient.TokenResult.Error -> {
                            stopPolling()
                            handler.post {
                                Toast.makeText(this@MainActivity, result.msg, Toast.LENGTH_LONG).show()
                                showLogin()
                            }
                        }
                        ApiClient.TokenResult.Pending -> handler.postDelayed(this, interval.toLong())
                    }
                }
            }
        }
        pollRunnable = runnable
        runnable.run()
    }

    private fun stopPolling() {
        pollRunnable?.let { handler.removeCallbacks(it) }
        pollRunnable = null
        deviceCode = null
    }

    // ---------------- liked list ----------------
    private fun showLiked() {
        val root = buildRoot()
        currentScreen = root
        val loading = TextView(this).apply { text = "…" }
        root.addView(loading)
        setContentView(root)

        ApiClient.accountStatus { uid, err ->
            if (err != null) {
                handler.post {
                    Toast.makeText(this, err, Toast.LENGTH_LONG).show()
                    // possibly invalid token
                    if (err == "unauthorized") {
                        prefs.edit().remove("token").apply()
                        ApiClient.token = null
                    }
                    handler.post { showLogin() }
                }
                return@accountStatus
            }
            ApiClient.likedTracks(uid ?: "") { tracks, terr ->
                handler.post {
                    if (terr != null) {
                        Toast.makeText(this, terr, Toast.LENGTH_LONG).show()
                    }
                    likedTracks = tracks ?: emptyList()
                    renderLiked(root)
                }
            }
        }
    }

    @SuppressLint("SetTextI18n")
    private fun renderLiked(root: LinearLayout) {
        root.removeAllViews()
        if (likedTracks.isEmpty()) {
            root.addView(TextView(this).apply { text = getString(R.string.empty_list) })
            return
        }
        val header = TextView(this).apply {
            text = getString(R.string.liked_title) + " (${likedTracks.size})"
            setTextColor(getColor(R.color.accent))
            gravity = Gravity.CENTER
        }
        root.addView(header)
        val listWrap = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        likedTracks.forEachIndexed { idx, t ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(6), dp(10), dp(6))
                setBackgroundColor(getColor(R.color.surface))
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                lp.bottomMargin = dp(4)
                layoutParams = lp
                setOnClickListener { playIndex(idx) }
            }
            row.addView(TextView(this).apply {
                text = t.title.ifEmpty { "(без названия)" }
                setTextColor(getColor(R.color.text_primary))
                textSize = 15f
                maxLines = 2
            })
            row.addView(TextView(this).apply {
                text = t.artist
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                maxLines = 1
            })
            listWrap.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(listWrap) }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        renderPlayer(root)
    }

    private fun playIndex(idx: Int) {
        val arr = JSONArray()
        likedTracks.forEach {
            arr.put(org.json.JSONObject().apply {
                put("id", it.trackId)
                put("t", it.title)
                put("a", it.artist)
            })
        }
        val startIntent = Intent(this, PlaybackService::class.java).apply {
            action = PlaybackService.ACTION_START
            putExtra(PlaybackService.EXTRA_QUEUE, arr.toString())
            putExtra("index", idx)
        }
        startForegroundService(startIntent)
        // Service plays directly on ACTION_START; no delayed PLAY intent needed.
    }

    // ---------------- player bar ----------------
    private fun renderPlayer(root: LinearLayout) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val prevBtn = smallBtn("⏮") {
            startService(Intent(this, PlaybackService::class.java).apply { action = PlaybackService.ACTION_PREV })
        }
        val playBtn = smallBtn("▶") {
            val action = if (PlaybackService.isPlaying) PlaybackService.ACTION_PAUSE else PlaybackService.ACTION_PLAY
            startService(Intent(this, PlaybackService::class.java).apply { this.action = action })
        }
        val nextBtn = smallBtn("⏭") {
            startService(Intent(this, PlaybackService::class.java).apply { action = PlaybackService.ACTION_NEXT })
        }
        val info = TextView(this).apply {
            text = ""
            setTextColor(getColor(R.color.text_secondary))
            textSize = 11f
            maxLines = 2
            tag = "player_info"
        }
        playBtn.tag = "play_btn"
        bar.addView(prevBtn)
        bar.addView(playBtn)
        bar.addView(nextBtn)
        bar.addView(info, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(bar)
        updatePlayerUi()
    }

    private fun smallBtn(label: String, onClick: () -> Unit): Button = Button(this).apply {
        text = label
        minWidth = 0
        minimumWidth = 0
        setPadding(dp(12), dp(6), dp(12), dp(6))
        setOnClickListener { onClick() }
    }

    private fun updatePlayerUi() {
        val root = currentScreen as? LinearLayout ?: return
        val playBtn = findTagged(root, "play_btn") as? Button
        val info = findTagged(root, "player_info") as? TextView
        playBtn?.text = if (PlaybackService.isPlaying) "⏸" else "▶"
        val t = PlaybackService.currentTitle
        val a = PlaybackService.currentArtist
        val err = PlaybackService.lastError
        info?.text = when {
            !err.isNullOrEmpty() -> "Ошибка: $err"
            t.isEmpty() -> ""
            else -> "$t — $a"
        }
    }

    private fun findTagged(parent: ViewGroup, tag: String): View? {
        for (i in 0 until parent.childCount) {
            val c = parent.getChildAt(i)
            if (c.tag == tag) return c
            if (c is ViewGroup) findTagged(c, tag)?.let { return it }
        }
        return null
    }

    private fun buildRoot(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(getColor(R.color.bg))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun setupApiToken() {
        ApiClient.token = prefs.getString("token", null)
    }
}
