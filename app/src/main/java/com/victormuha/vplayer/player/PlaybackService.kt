package com.victormuha.vplayer.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.victormuha.vplayer.MainActivity
import com.victormuha.vplayer.R
import com.victormuha.vplayer.api.ApiClient
import java.io.IOException

class PlaybackService : Service() {

    companion object {
        private const val TAG = "PlaybackService"
        private const val CHANNEL_ID = "vplayer_playback"
        private const val NOTIF_ID = 1

        // Commands
        const val ACTION_PLAY = "com.victormuha.vplayer.PLAY"
        const val ACTION_PAUSE = "com.victormuha.vplayer.PAUSE"
        const val ACTION_NEXT = "com.victormuha.vplayer.NEXT"
        const val ACTION_PREV = "com.victormuha.vplayer.PREV"
        const val ACTION_STOP = "com.victormuha.vplayer.STOP"
        const val ACTION_START = "com.victormuha.vplayer.START"

        // Data
        const val EXTRA_TRACK_ID = "track_id"
        const val EXTRA_URL = "url"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ARTIST = "artist"
        const val EXTRA_QUEUE = "queue_json"

        // State singleton for UI
        @Volatile
        var isPlaying: Boolean = false
        @Volatile
        var currentTitle: String = ""
        @Volatile
        var currentArtist: String = ""
        @Volatile
        var onStateChange: (() -> Unit)? = null
        @Volatile
        var lastError: String? = null
    }

    private var mediaPlayer: MediaPlayer? = null
    private var queue: List<TrackItem> = emptyList()
    private var queueIndex: Int = -1
    private var pendingIndex: Int = -1
    private var isPrepared: Boolean = false
    private var generation: Long = 0L
    private var isLoading: Boolean = false

    data class TrackItem(val trackId: String, val title: String, val artist: String)

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                // Ensure foreground so playback keeps going when screen off
                startForeground(NOTIF_ID, buildNotification(currentTitle, currentArtist, PlaybackService.isPlaying))
                intent.getStringExtra(EXTRA_QUEUE)?.let { q ->
                    generation++
                    queue = parseQueue(q)
                    val requestedIdx = intent.getIntExtra("index", -1)
                    queueIndex = if (requestedIdx in queue.indices) requestedIdx else 0
                    pendingIndex = -1
                    // Stop any currently playing track before starting fresh queue
                    stopPlayback()
                    // Play directly (Activity no longer sends a delayed PLAY intent)
                    playCurrent()
                }
            }
            ACTION_PLAY -> {
                if (pendingIndex >= 0) {
                    queueIndex = pendingIndex
                    pendingIndex = -1
                }
                if (isLoading) return@onStartCommand START_NOT_STICKY // ignore while loading
                val mp = mediaPlayer
                when {
                    mp == null -> playCurrent() // New session after service restart
                    mp.isPlaying -> Unit // already playing
                    isPrepared -> {
                        // resume paused
                        mp.start()
                        PlaybackService.isPlaying = true
                        notifyState()
                        startForeground(NOTIF_ID, buildNotification(currentTitle, currentArtist, true))
                    }
                    else -> playCurrent()
                }
            }
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREV -> prev()
            ACTION_STOP -> {
                stopPlayback()
                stopForeground(true)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun parseQueue(json: String): List<TrackItem> {
        val arr = org.json.JSONArray(json)
        val list = ArrayList<TrackItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(TrackItem(o.optString("id"), o.optString("t"), o.optString("a")))
        }
        return list
    }

    private fun queueToJson(): String {
        val arr = org.json.JSONArray()
        queue.forEach {
            arr.put(org.json.JSONObject().apply {
                put("id", it.trackId)
                put("t", it.title)
                put("a", it.artist)
            })
        }
        return arr.toString()
    }

    private fun playCurrent() {
        val idx = queueIndex
        if (idx < 0 || idx >= queue.size) {
            Log.w(TAG, "playCurrent: no valid index")
            return
        }
        if (isLoading) return // already loading; ignore
        isLoading = true
        val item = queue[idx]
        PlaybackService.currentTitle = item.title
        PlaybackService.currentArtist = item.artist
        notifyState()
        startForeground(NOTIF_ID, buildNotification(item.title, item.artist, true))

        // Fetch fresh download-info & build direct URL right before playback
        val myGen = generation
        ApiClient.downloadInfo(item.trackId) { variants, err ->
            if (myGen != generation) return@downloadInfo // stale
            if (err != null) {
                Log.e(TAG, "downloadInfo error: $err")
                isLoading = false
                notifyError("download info: $err")
                return@downloadInfo
            }
            val full = variants?.filter { it.codec == "mp3" && !it.preview && !it.direct }
                ?.maxByOrNull { it.bitrate }
                ?: variants?.filter { it.codec == "mp3" && !it.preview }?.maxByOrNull { it.bitrate }
            if (full == null) {
                isLoading = false
                notifyError("no full stream")
                return@downloadInfo
            }
            ApiClient.buildDirectLink(full.downloadInfoUrl) { url, uErr ->
                if (myGen != generation) return@buildDirectLink // stale
                if (uErr != null || url == null) {
                    Log.e(TAG, "buildDirectLink: $uErr")
                    isLoading = false
                    notifyError("direct link: $uErr")
                    return@buildDirectLink
                }
                playUrl(url, item.title, item.artist)
            }
        }
    }

    private fun playUrl(url: String, title: String, artist: String) {
        try {
            // Reset prepared state before replacing player (avoid resume-before-prepare)
            isPrepared = false
            PlaybackService.isPlaying = false
            mediaPlayer?.let {
                if (it.isPlaying) it.stop()
                it.release()
            }
            mediaPlayer = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
                )
                setDataSource(url)
                setOnPreparedListener { mp ->
                    isPrepared = true
                    isLoading = false
                    mp.start()
                    PlaybackService.isPlaying = true
                    PlaybackService.currentTitle = title
                    PlaybackService.currentArtist = artist
                    notifyState()
                    startForeground(NOTIF_ID, buildNotification(title, artist, true))
                }
                setOnCompletionListener {
                    isPrepared = false
                    PlaybackService.isPlaying = false
                    notifyState()
                    next() // auto-advance
                }
                setOnErrorListener { _, what, extra ->
                    Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                    notifyError("mediaplayer $what/$extra")
                    true
                }
                prepareAsync()
            }
        } catch (e: IOException) {
            Log.e(TAG, "playUrl: ${e.message}")
            notifyError("io: ${e.message}")
        }
    }

    private fun pause() {
        mediaPlayer?.let {
            if (it.isPlaying) it.pause()
            // keep isPrepared=true so future PLAY resumes rather than restarts
            PlaybackService.isPlaying = false
            notifyState()
            startForeground(NOTIF_ID, buildNotification(currentTitle, currentArtist, false))
        }
    }

    private fun next() {
        if (queue.isEmpty()) return
        generation++
        queueIndex = (queueIndex + 1) % queue.size
        playCurrent()
    }

    private fun prev() {
        if (queue.isEmpty()) return
        generation++
        queueIndex = (queueIndex - 1 + queue.size) % queue.size
        playCurrent()
    }

    private fun stopPlayback() {
        generation++
        isLoading = false
        mediaPlayer?.let {
            if (it.isPlaying) it.stop()
            it.release()
        }
        mediaPlayer = null
        isPrepared = false
        PlaybackService.isPlaying = false
        notifyState()
    }

    override fun onDestroy() {
        stopPlayback()
        super.onDestroy()
    }

    private fun notifyState() {
        onStateChange?.invoke()
    }

    private fun notifyError(msg: String) {
        Log.e(TAG, "error: $msg")
        isLoading = false
        isPrepared = false
        PlaybackService.isPlaying = false
        lastError = msg
        onStateChange?.invoke()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Воспроизведение",
                NotificationManager.IMPORTANCE_LOW
            )
            ch.description = "vplayer playback"
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, artist: String, playing: Boolean): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title.ifEmpty { "vplayer" })
            .setContentText(artist)
            .setContentIntent(openIntent)
            .setOngoing(playing)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            builder.addAction(
                Notification.Action.Builder(null, "Stop",
                    PendingIntent.getService(this, 1,
                        Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                ).build()
            )
        }
        return builder.build()
    }
}
