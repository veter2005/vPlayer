package com.victormuha.vplayer.player.service

import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.victormuha.vplayer.MainActivity
import com.victormuha.vplayer.VPlayerApp
import com.victormuha.vplayer.api.Api
import com.victormuha.vplayer.offline.OfflineStore
import com.victormuha.vplayer.settings.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Playback lives here, independent of the Activity:
 *  - the session is added with [addSession], so Media3 promotes the service to a foreground
 *    `mediaPlayback` service with a media notification while playing (screen-off/home safe);
 *  - the service also starts itself on [setQueue], so unbinding the UI never destroys it;
 *  - ExoPlayer holds wake+wifi locks while playing, and a short partial wake lock covers the
 *    gap between tracks while the next stream URL is resolved with the screen off.
 */
class PlaybackService : MediaSessionService() {
    private val TAG = "PlaybackService"
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    private var wakeLock: PowerManager.WakeLock? = null

    data class TrackItem(val trackId: String, val title: String, val artist: String, val durationMs: Long)

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
            val repeat: RepeatMode,
            val local: Boolean = false
        ) : PlayerState()
        data class Error(val message: String) : PlayerState()
    }

    enum class RepeatMode { OFF, ONE, ALL }

    data class QueueInfo(val items: List<TrackItem> = emptyList(), val currentIndex: Int = -1)

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

    private var order: List<Int> = emptyList()
    private var orderPos = -1
    private var wantPlay = true
    private var loadJob: Job? = null
    private var loadToken = 0L
    private var currentIsLocal = false
    private var consecutiveErrors = 0

    companion object {
        const val ACTION_LOCAL_BIND = "com.victormuha.vplayer.action.LOCAL_BIND"
        private const val SWITCH_WAKE_MS = 30_000L
        private const val MAX_AUTO_SKIPS = 3
    }

    inner class PlaybackBinder : Binder() {
        val service: PlaybackService get() = this@PlaybackService
    }

    private val binder = PlaybackBinder()

    /** UI binds with [ACTION_LOCAL_BIND]; Media3 controllers/system UI get the session binder. */
    override fun onBind(intent: Intent?): IBinder? =
        if (intent?.action == ACTION_LOCAL_BIND) binder else super.onBind(intent)

    override fun onCreate() {
        super.onCreate()
        VPlayerApp.initGlobals(applicationContext)
        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                /* handleAudioFocus = */ true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .build()
        player.addListener(playerListener)

        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        mediaSession = MediaSession.Builder(this, QueuePlayer(player)).setSessionActivity(openApp).build()
        addSession(mediaSession)

        wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vplayer:track-switch")
            .apply { setReferenceCounted(false) }

        serviceScope.launch {
            while (isActive) {
                if (player.isPlaying) updatePositionState()
                delay(1000L)
            }
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away keeps music playing; an idle/paused service is stopped.
        if (!player.playWhenReady || _queue.value.items.isEmpty()) stopSelf()
    }

    /** Exposes next/previous to notification, Bluetooth headset and watch media controls. */
    private inner class QueuePlayer(p: Player) : ForwardingPlayer(p) {
        private val extra = intArrayOf(
            Player.COMMAND_SEEK_TO_NEXT, Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS, Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
        )
        override fun getAvailableCommands(): Player.Commands =
            super.getAvailableCommands().buildUpon().addAll(*extra).build()
        override fun isCommandAvailable(command: Int) = command in extra || super.isCommandAvailable(command)
        override fun hasNextMediaItem() = _queue.value.items.isNotEmpty()
        override fun hasPreviousMediaItem() = _queue.value.items.isNotEmpty()
        override fun seekToNext() = next()
        override fun seekToNextMediaItem() = next()
        override fun seekToPrevious() = prev()
        override fun seekToPreviousMediaItem() = prev()
        override fun play() { wantPlay = true; super.play() }
        override fun pause() { wantPlay = false; super.pause() }
        override fun setPlayWhenReady(playWhenReady: Boolean) { wantPlay = playWhenReady; super.setPlayWhenReady(playWhenReady) }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = updatePositionState()

        override fun onPlaybackStateChanged(state: Int) {
            when (state) {
                Player.STATE_READY -> {
                    consecutiveErrors = 0
                    releaseSwitchWakeLock() // ExoPlayer's own wake lock now covers playback
                    updateReadyState()
                }
                Player.STATE_ENDED -> {
                    acquireSwitchWakeLock()
                    advance(forward = true, auto = true)
                }
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "onPlayerError: ${error.errorCodeName}")
            consecutiveErrors++
            if (consecutiveErrors <= MAX_AUTO_SKIPS && _queue.value.items.size > 1) {
                acquireSwitchWakeLock()
                advance(forward = true, auto = true)
            } else {
                releaseSwitchWakeLock()
                _playerState.value = PlayerState.Error("Ошибка воспроизведения: ${error.errorCodeName}")
            }
        }
    }

    // ---------------- Queue API (called by UI through the local binder) ----------------

    fun setQueue(items: List<TrackItem>, startIndex: Int, @Suppress("UNUSED_PARAMETER") uid: String) {
        handler.post {
            ensureStarted()
            _queue.value = QueueInfo(items, startIndex)
            _liked.value = false
            consecutiveErrors = 0
            wantPlay = true
            order = QueueLogic.order(items.size, _shuffleEnabled.value, startIndex)
            startTrack(order.indexOf(startIndex).coerceAtLeast(0))
        }
    }

    fun playIndex(index: Int) {
        handler.post {
            if (index !in _queue.value.items.indices) return@post
            ensureStarted()
            wantPlay = true
            startTrack(order.indexOf(index).coerceAtLeast(0))
        }
    }

    fun next() {
        handler.post { wantPlay = true; advance(forward = true, auto = false) }
    }

    fun prev() {
        handler.post {
            if (player.currentPosition > 3_000L || _queue.value.items.size <= 1) {
                player.seekTo(0)
            } else {
                wantPlay = true
                advance(forward = false, auto = false)
            }
        }
    }

    fun seekTo(positionMs: Long) { handler.post { player.seekTo(positionMs) } }

    fun togglePlayPause() {
        handler.post {
            if (player.playWhenReady) {
                wantPlay = false
                player.pause()
            } else {
                wantPlay = true
                if (player.playbackState == Player.STATE_IDLE && orderPos >= 0) startTrack(orderPos)
                else player.play()
            }
            updatePositionState()
        }
    }

    fun pause() { handler.post { wantPlay = false; player.pause() } }
    fun play() { handler.post { wantPlay = true; player.play() } }

    fun setVolume(vol: Float) {
        handler.post {
            _volume.value = vol.coerceIn(0f, 1f)
            player.volume = _volume.value
        }
    }

    fun setShuffle(enabled: Boolean) {
        handler.post {
            _shuffleEnabled.value = enabled
            val current = _queue.value.currentIndex
            order = QueueLogic.order(_queue.value.items.size, enabled, current)
            orderPos = order.indexOf(current)
            updatePositionState()
        }
    }

    fun setRepeatMode(mode: RepeatMode) {
        handler.post {
            _repeatMode.value = mode
            // Single-item player: ONE is handled natively, ALL by our queue wrap-around.
            player.repeatMode = if (mode == RepeatMode.ONE) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
            updatePositionState()
        }
    }

    fun setLiked(liked: Boolean) { handler.post { _liked.value = liked } }

    fun stop() {
        handler.post {
            loadJob?.cancel()
            loadToken++
            wantPlay = true
            player.stop()
            player.clearMediaItems()
            _queue.value = QueueInfo()
            order = emptyList()
            orderPos = -1
            _playerState.value = PlayerState.Idle
            releaseSwitchWakeLock()
            stopSelf()
        }
    }

    // ---------------- Core ----------------

    private fun ensureStarted() {
        // A started (not only bound) service survives the Activity going to background.
        try {
            startService(Intent(this, PlaybackService::class.java))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "startService not allowed now; Media3 will promote on play")
        }
    }

    private fun isPlayable(index: Int): Boolean =
        !NetworkMonitor.isOffline() || OfflineStore.get(this).isDownloaded(_queue.value.items[index].trackId)

    private fun advance(forward: Boolean, auto: Boolean) {
        val size = order.size
        if (size == 0) return
        val wrap = _repeatMode.value == RepeatMode.ALL || !auto
        val candidate = QueueLogic.step(orderPos, size, forward, wrap)
        if (candidate == null) {
            // End of queue: stop gracefully, keep the last track loaded.
            releaseSwitchWakeLock()
            wantPlay = false
            player.pause()
            player.seekTo(0)
            updatePositionState()
            return
        }
        val playable = QueueLogic.findPlayable(size, candidate, forward, wrap) { isPlayable(order[it]) }
        if (playable == null) {
            releaseSwitchWakeLock()
            player.stop()
            _playerState.value = PlayerState.Error("Нет сети и нет скачанных треков в очереди")
            return
        }
        startTrack(playable)
    }

    private fun startTrack(pos: Int) {
        if (pos !in order.indices) return
        val index = order[pos]
        val item = _queue.value.items.getOrNull(index) ?: return
        orderPos = pos
        _queue.value = _queue.value.copy(currentIndex = index)
        loadJob?.cancel()
        val token = ++loadToken
        acquireSwitchWakeLock()
        _playerState.value = PlayerState.Loading(item.title, item.artist)

        val store = OfflineStore.get(this)
        when (val source = QueueLogic.chooseSource(store.localFile(item.trackId), NetworkMonitor.isOffline())) {
            is TrackSource.Local -> applyUri(Uri.fromFile(source.file), item, local = true)
            TrackSource.Unavailable -> {
                val nextPos = QueueLogic.findPlayable(order.size, pos, true, true) { isPlayable(order[it]) }
                if (nextPos != null && nextPos != pos) startTrack(nextPos)
                else {
                    releaseSwitchWakeLock()
                    _playerState.value = PlayerState.Error("Трек не скачан, нет сети")
                }
            }
            TrackSource.Stream -> loadJob = serviceScope.launch {
                try {
                    val (url, _) = Api.resolveTrackUrl(item.trackId)
                    if (token == loadToken) applyUri(Uri.parse(url), item, local = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (token != loadToken) return@launch
                    Log.w(TAG, "resolve failed: ${e.message}")
                    // Lost network mid-queue: fall back to the next downloaded track.
                    val fallback = if (NetworkMonitor.isOffline())
                        QueueLogic.findPlayable(order.size, pos, true, true) { isPlayable(order[it]) } else null
                    if (fallback != null && fallback != pos) startTrack(fallback)
                    else {
                        releaseSwitchWakeLock()
                        _playerState.value = PlayerState.Error("Сеть: ${e.message}")
                    }
                }
            }
        }
    }

    private fun applyUri(uri: Uri, item: TrackItem, local: Boolean) {
        currentIsLocal = local
        val mediaItem = MediaItem.Builder()
            .setUri(uri)
            .setMediaId(item.trackId)
            .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.artist).build())
            .build()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = wantPlay
    }

    private fun acquireSwitchWakeLock() {
        runCatching { wakeLock?.acquire(SWITCH_WAKE_MS) }
    }

    private fun releaseSwitchWakeLock() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
    }

    private fun updatePositionState() {
        when (val cur = _playerState.value) {
            is PlayerState.Ready -> _playerState.value = cur.copy(
                isPlaying = player.isPlaying,
                positionMs = player.currentPosition,
                durationMs = player.duration.coerceAtLeast(0L),
                shuffle = _shuffleEnabled.value,
                repeat = _repeatMode.value,
                local = currentIsLocal
            )
            is PlayerState.Loading -> if (player.playbackState == Player.STATE_READY) updateReadyState()
            else -> Unit
        }
    }

    private fun updateReadyState() {
        val item = _queue.value.items.getOrNull(_queue.value.currentIndex) ?: return
        _playerState.value = PlayerState.Ready(
            item.title, item.artist, player.isPlaying, player.currentPosition,
            player.duration.coerceAtLeast(0L), _shuffleEnabled.value, _repeatMode.value, currentIsLocal
        )
    }

    override fun onDestroy() {
        serviceScope.cancel()
        releaseSwitchWakeLock()
        mediaSession.release()
        player.release()
        super.onDestroy()
    }
}
