package com.victormuha.vplayer.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.player.service.PlaybackService
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PlaybackViewModel : ViewModel() {
    private var service: PlaybackService? = null
    private val collectJobs = mutableListOf<Job>()

    private val _state = MutableStateFlow<PlaybackService.PlayerState>(PlaybackService.PlayerState.Idle)
    val state: StateFlow<PlaybackService.PlayerState> = _state.asStateFlow()

    private val _queue = MutableStateFlow<PlaybackService.QueueInfo>(PlaybackService.QueueInfo())
    val queue: StateFlow<PlaybackService.QueueInfo> = _queue.asStateFlow()

    private val _volume = MutableStateFlow(1.0f)
    val volume: StateFlow<Float> = _volume.asStateFlow()

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle.asStateFlow()

    private val _repeat = MutableStateFlow(PlaybackService.RepeatMode.OFF)
    val repeat: StateFlow<PlaybackService.RepeatMode> = _repeat.asStateFlow()

    private val _liked = MutableStateFlow(false)
    val liked: StateFlow<Boolean> = _liked.asStateFlow()

    fun onServiceConnected(service: PlaybackService?) {
        this.service = service ?: return
        // Cancel all previous
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()

        collectJobs.add(viewModelScope.launch { service.playerState.collect { _state.value = it } })
        collectJobs.add(viewModelScope.launch { service.queue.collect { _queue.value = it } })
        collectJobs.add(viewModelScope.launch { service.volume.collect { _volume.value = it } })
        collectJobs.add(viewModelScope.launch { service.shuffleEnabled.collect { _shuffle.value = it } })
        collectJobs.add(viewModelScope.launch { service.repeatMode.collect { _repeat.value = it } })
        collectJobs.add(viewModelScope.launch { service.liked.collect { _liked.value = it } })
    }

    fun onServiceDisconnected() {
        service = null
        collectJobs.forEach { it.cancel() }
        collectJobs.clear()
    }

    // ---------- Playback controls (delegate to service via local binder) ----------
    fun setQueue(items: List<ApiClient.Track>, startIndex: Int, uid: String) {
        val queueItems = items.map {
            PlaybackService.TrackItem(it.trackId, it.title, it.artist, it.durationMs)
        }
        service?.setQueue(queueItems, startIndex, uid)
    }

    fun playIndex(index: Int) { service?.playIndex(index) }
    fun next() { service?.next() }
    fun prev() { service?.prev() }
    fun togglePlayPause() { service?.togglePlayPause() }
    fun pause() { service?.pause() }
    fun play() { service?.play() }
    fun seekTo(positionMs: Long) { service?.seekTo(positionMs) }
    fun setVolume(vol: Float) { service?.setVolume(vol) }
    fun setShuffle(enabled: Boolean) { service?.setShuffle(enabled) }
    fun setRepeatMode(mode: PlaybackService.RepeatMode) { service?.setRepeatMode(mode) }
    fun stop() { service?.stop() }

    fun toggleLike(trackId: String, uid: String) {
        val wasLiked = _liked.value
        // Optimistic update
        _liked.value = !wasLiked
        if (wasLiked) {
            ApiClient.unlikeTrack(trackId, uid) { ok, err ->
                if (!ok) _liked.value = wasLiked
            }
        } else {
            ApiClient.likeTrack(trackId, uid) { ok, err ->
                if (!ok) _liked.value = wasLiked
            }
        }
    }
}
