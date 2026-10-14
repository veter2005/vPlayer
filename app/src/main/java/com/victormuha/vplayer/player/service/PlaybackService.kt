package com.victormuha.vplayer.player.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.victormuha.vplayer.MainActivity
import com.victormuha.vplayer.R
import com.victormuha.vplayer.api.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PlaybackService : MediaSessionService() {
    private val TAG = "PlaybackService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession

    // Track queue metadata (kept in sync with player's own queue)
    data class TrackItem(
        val trackId: String,
        val title: String,
        val artist: String,
        val durationMs: Long
    )

    sealed class PlayerState {
        object Idle : PlayerState()
        data class Loading(val title: String, val artist: String) : PlayerState()
        data class Ready(
            val title: String,
            val artist: String,
            val isPlaying: Boolean,
            val positionMs: Long,
            val durationMs: Long,
            val shuffle: Boolean,
            val repeat: RepeatMode
        ) : PlayerState()
        data class Error(val message: String) : PlayerState()
    }

    enum class RepeatMode { OFF, ONE, ALL }

    data class QueueInfo(
        val items: List<TrackItem> = emptyList(),
        val currentIndex: Int = -1
    )

    private val _queue = MutableStateFlow(QueueInfo())
    val queue: StateFlow<QueueInfo> = _queue.asStateFlow()

    private val _playerState = MutableStateFlow<PlayerState>(PlayerState.Idle)
    val playerState: StateFlow<PlayerState> = _playerState.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    private val _liked = MutableStateFlow(false)
    val liked: StateFlow<Boolean> = _liked.asStateFlow()

    // User's last intent for playback (pause during load must be respected)
    private var wantPlay: Boolean = true

    // Load job — only one active at a time, cancelled on new selection
    private var loadJob: Job? = null

    // ID of currently loading/playing track
    private var currentLoadingTrackId: String? = null

    private var positionPollJob: Job? = null

    companion object {
        private const val CHANNEL_ID = "vplayer_playback"
        private const val NOTIF_ID = 1
    }

    inner class PlaybackBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = PlaybackBinder()

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .build()

        player.addListener(playerListener)

        mediaSession = MediaSession.Builder(this, player).build()

        // Poll position for UI
        positionPollJob = serviceScope.launch {
            while (isActive) {
                updatePositionState()
                delay(500L)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            Log.d(TAG, "onIsPlayingChanged: $isPlaying")
            updatePositionState()
        }

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    Log.d(TAG, "Player STATE_READY")
                    // Only transition to Ready if we're in Loading (don't override Ready)
                    if (_playerState.value is PlayerState.Loading) {
                        updateReadyState()
                    }
                }
                Player.STATE_BUFFERING -> {
                    Log.d(TAG, "Player STATE_BUFFERING")
                    // Stay in Loading state
                }
                Player.STATE_ENDED -> {
                    Log.d(TAG, "Player STATE_ENDED")
                    onTrackEnded()
                }
                Player.STATE_IDLE -> {
                    Log.d(TAG, "Player STATE_IDLE")
                    // Don't override Loading — it may be transitioning
                }
            }
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            Log.d(TAG, "onMediaItemTransition: reason=$reason")
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                // ExoPlayer auto-advanced; refresh our index & load fresh URL
                val idx = player.currentMediaItemIndex
                _queue.update { it.copy(currentIndex = idx) }
                loadTrackUrl(idx)
            }
        }

        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName}")
            _playerState.value = PlayerState.Error("Playback: ${error.errorCodeName}")
        }
    }

    // ---------------- Queue management ----------------

    fun setQueue(items: List<TrackItem>, startIndex: Int, uid: String) {
        handler.post {
            _queue.value = QueueInfo(items, startIndex)
            _playerState.value = PlayerState.Idle
            _liked.value = false

            // Build full ExoPlayer queue with placeholder URIs (resolved lazily on play)
            val mediaItems = items.map { item ->
                MediaItem.Builder()
                    .setUri(Uri.parse("placeholder://${item.trackId}"))
                    .setMediaId(item.trackId)
                    .setMediaMetadata(
                        MediaMetadata.Builder()
                            .setTitle(item.title)
                            .setArtist(item.artist)
                            .build()
                    )
                    .build()
            }
            player.setMediaItems(mediaItems, startIndex, 0L)
            loadTrackUrl(startIndex)
        }
    }

    fun playIndex(index: Int) {
        handler.post {
            val q = _queue.value
            if (index < 0 || index >= q.items.size) return@post
            _queue.value = q.copy(currentIndex = index)
            loadTrackUrl(index)
        }
    }

    fun next() {
        val q = _queue.value
        if (q.items.isEmpty()) return
        val nextIdx = (q.currentIndex + 1).coerceAtMost(q.items.size - 1)
        playIndex(nextIdx)
    }

    fun prev() {
        val q = _queue.value
        if (q.items.isEmpty()) return
        val prevIdx = (q.currentIndex - 1).coerceAtLeast(0)
        playIndex(prevIdx)
    }

    fun seekTo(positionMs: Long) {
        handler.post { player.seekTo(positionMs) }
    }

    fun togglePlayPause() {
        handler.post {
            if (player.isPlaying) {
                wantPlay = false
                player.pause()
            } else {
                wantPlay = true
                player.play()
            }
            updatePositionState()
        }
    }

    fun pause() {
        handler.post {
            wantPlay = false
            player.pause()
        }
    }

    fun play() {
        handler.post {
            wantPlay = true
            player.play()
        }
    }

    fun setVolume(vol: Float) {
        handler.post {
            _volume.value = vol.coerceIn(0f, 1f)
            player.volume = _volume.value
        }
    }

    fun setShuffle(enabled: Boolean) {
        handler.post {
            _shuffleEnabled.value = enabled
            player.shuffleModeEnabled = enabled
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        handler.post {
            _repeatMode.value = mode
            player.repeatMode = when (mode) {
                RepeatMode.OFF -> Player.REPEAT_MODE_OFF
                RepeatMode.ONE -> Player.REPEAT_MODE_ONE
                RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            }
        }
    }

    fun setLiked(liked: Boolean) {
        handler.post { _liked.value = liked }
    }

    fun stop() {
        handler.post {
            loadJob?.cancel()
            loadJob = null
            currentLoadingTrackId = null
            wantPlay = true
            _playerState.value = PlayerState.Idle
            player.stop()
            player.clearMediaItems()
            _queue.value = QueueInfo()
        }
    }

    // ---------------- Playback core (single job, cancellable) ----------------

    private fun loadTrackUrl(index: Int) {
        val item = _queue.value.items.getOrNull(index) ?: return
        val trackId = item.trackId

        // Cancel previous load
        loadJob?.cancel()
        currentLoadingTrackId = trackId

        _playerState.value = PlayerState.Loading(item.title, item.artist)

        loadJob = serviceScope.launch {
            try {
                val variants = suspendCoroutine<List<ApiClient.DownloadVariant>?> { cont ->
                    ApiClient.downloadInfo(trackId) { v, e ->
                        cont.resume(if (e != null) null else v)
                    }
                }
                if (variants == null || variants.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        if (currentLoadingTrackId == trackId) {
                            _playerState.value = PlayerState.Error("No full stream available")
                        }
                    }
                    return@launch
                }

                // Choose best quality mp3
                val full = variants
                    .filter { it.codec == "mp3" && !it.preview && !it.direct }
                    .maxByOrNull { it.bitrate }
                    ?: variants.filter { it.codec == "mp3" && !it.preview }.maxByOrNull { it.bitrate }

                if (full == null) {
                    withContext(Dispatchers.Main) {
                        if (currentLoadingTrackId == trackId) {
                            _playerState.value = PlayerState.Error("No full stream available")
                        }
                    }
                    return@launch
                }

                val url = suspendCoroutine<String?> { cont ->
                    ApiClient.buildDirectLink(full.downloadInfoUrl) { u, e ->
                        cont.resume(if (e != null) null else u)
                    }
                }
                if (url == null) {
                    withContext(Dispatchers.Main) {
                        if (currentLoadingTrackId == trackId) {
                            _playerState.value = PlayerState.Error("Could not build direct link")
                        }
                    }
                    return@launch
                }

                withContext(Dispatchers.Main) {
                    if (currentLoadingTrackId != trackId) return@withContext // stale
                    applyTrackToPlayer(url, item)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Load cancelled by newer selection
                Log.d(TAG, "Load cancelled: $trackId")
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (currentLoadingTrackId == trackId) {
                        _playerState.value = PlayerState.Error("Network: ${e.message}")
                    }
                }
            }
        }
    }

    private fun applyTrackToPlayer(url: String, item: TrackItem) {
        val mediaItem = MediaItem.Builder()
            .setUri(Uri.parse(url))
            .setMediaId(item.trackId)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .build()
            )
            .build()

        // Replace current item with real URL; other items in queue keep placeholder
        val idx = _queue.value.currentIndex
        if (idx >= 0 && idx < player.mediaItemCount) {
            player.removeMediaItem(idx)
            player.addMediaItem(idx, mediaItem)
            player.seekTo(idx, 0L)
            player.prepare()
        } else {
            player.setMediaItem(mediaItem)
            player.prepare()
        }
        if (wantPlay) player.play() else player.pause()

        // Foreground notification
        startForegroundWithNotification(item)
    }

    private fun onTrackEnded() {
        val q = _queue.value
        if (q.items.isEmpty()) return
        val mode = _repeatMode.value
        val currentIdx = q.currentIndex

        when (mode) {
            RepeatMode.ONE -> playIndex(currentIdx)
            RepeatMode.ALL -> {
                val nextIdx = (currentIdx + 1) % q.items.size
                playIndex(nextIdx)
            }
            RepeatMode.OFF -> {
                if (currentIdx < q.items.size - 1) {
                    playIndex(currentIdx + 1)
                } else {
                    _playerState.value = PlayerState.Idle
                }
            }
        }
    }

    private fun updatePositionState() {
        val item = _queue.value.items.getOrNull(_queue.value.currentIndex) ?: return
        when (val cur = _playerState.value) {
            is PlayerState.Ready -> {
                _playerState.value = cur.copy(
                    isPlaying = player.isPlaying,
                    positionMs = player.currentPosition,
                    durationMs = player.duration.coerceAtLeast(0L),
                    shuffle = _shuffleEnabled.value,
                    repeat = _repeatMode.value
                )
            }
            is PlayerState.Loading -> {
                // Player may be ready — check
                if (player.playbackState == Player.STATE_READY) {
                    updateReadyState()
                }
            }
            else -> {}
        }
    }

    private fun updateReadyState() {
        val item = _queue.value.items.getOrNull(_queue.value.currentIndex) ?: return
        if (_playerState.value is PlayerState.Loading) {
            _playerState.value = PlayerState.Ready(
                title = item.title,
                artist = item.artist,
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition,
                durationMs = player.duration.coerceAtLeast(0L),
                shuffle = _shuffleEnabled.value,
                repeat = _repeatMode.value
            )
        }
    }

    // ---------------- Notification ----------------

    private fun startForegroundWithNotification(item: TrackItem) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIF_ID, buildNotification(item.title, item.artist, true))
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, buildNotification(item.title, item.artist, true))
        }
    }

    private fun buildNotification(title: String, artist: String, playing: Boolean): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(title)
            .setContentText(artist)
            .setContentIntent(openIntent)
            .setOngoing(playing)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .build()
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

    override fun onDestroy() {
        super.onDestroy()
        positionPollJob?.cancel()
        serviceScope.cancel()
        player.release()
        mediaSession.release()
    }
}

// suspend helper for ApiClient callbacks
private suspend fun <T> awaitApi(block: (callback: (T?, String?) -> Unit) -> Unit): T? =
    suspendCoroutine { cont ->
        block { value, _ -> cont.resume(value) }
    }
