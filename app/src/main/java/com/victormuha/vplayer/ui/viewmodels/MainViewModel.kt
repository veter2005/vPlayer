package com.victormuha.vplayer.ui.viewmodels

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.victormuha.vplayer.api.ApiClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainViewModel : ViewModel() {
    private val _navigation = MutableStateFlow(NavigationState("login"))
    val navigation: StateFlow<NavigationState> = _navigation.asStateFlow()

    private val _token = MutableStateFlow<String?>(null)
    val token: StateFlow<String?> = _token.asStateFlow()

    private val _uid = MutableStateFlow<String?>(null)
    val uid: StateFlow<String?> = _uid.asStateFlow()

    private val _likedTracks = MutableStateFlow<List<ApiClient.Track>>(emptyList())
    val likedTracks: StateFlow<List<ApiClient.Track>> = _likedTracks.asStateFlow()

    private val _playlists = MutableStateFlow<List<ApiClient.Playlist>>(emptyList())
    val playlists: StateFlow<List<ApiClient.Playlist>> = _playlists.asStateFlow()

    private val _playlistTracks = MutableStateFlow<Map<String, List<ApiClient.Track>>>(emptyMap())
    val playlistTracks: StateFlow<Map<String, List<ApiClient.Track>>> = _playlistTracks.asStateFlow()

    private val _searchResults = MutableStateFlow<List<ApiClient.Track>>(emptyList())
    val searchResults: StateFlow<List<ApiClient.Track>> = _searchResults.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private lateinit var prefs: SharedPreferences
    private var initialized = false

    // Device code state
    private val _deviceCode = MutableStateFlow<ApiClient.DeviceCode?>(null)
    val deviceCode: StateFlow<ApiClient.DeviceCode?> = _deviceCode.asStateFlow()

    // Poll job — cancelled on new flow or cancel
    private var pollJob: Job? = null
    private var pollSessionId: Long = 0L

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        prefs = context.getSharedPreferences("vplayer", Context.MODE_PRIVATE)

        val tok = prefs.getString("token", null)
        _token.value = tok
        ApiClient.token = tok

        if (tok != null) {
            loadAccount()
        } else {
            _navigation.value = NavigationState("login")
        }
    }

    fun saveToken(token: String) {
        prefs.edit().putString("token", token).apply()
        _token.value = token
        ApiClient.token = token
        loadAccount()
    }

    fun logout() {
        pollJob?.cancel()
        pollJob = null
        _deviceCode.value = null
        prefs.edit().remove("token").apply()
        _token.value = null
        _uid.value = null
        ApiClient.token = null
        _likedTracks.value = emptyList()
        _playlists.value = emptyList()
        _playlistTracks.value = emptyMap()
        _searchResults.value = emptyList()
        _navigation.value = NavigationState("login")
    }

    private fun loadAccount() {
        viewModelScope.launch {
            _loading.value = true
            ApiClient.accountStatus { uid, err ->
                if (err != null) {
                    _error.value = "Account: $err"
                    _navigation.value = NavigationState("login")
                } else {
                    _uid.value = uid
                    _navigation.value = NavigationState("home")
                }
                _loading.value = false
            }
        }
    }

    // ---------------- Navigation ----------------
    data class NavigationState(
        val screen: String,
        val playlist: String? = null,
        val playlistTitle: String = ""
    )

    fun navigate(screen: String, playlist: String? = null, playlistTitle: String = "") {
        _navigation.value = NavigationState(screen, playlist, playlistTitle)
    }

    fun goBack() {
        val current = _navigation.value.screen
        _navigation.value = when (current) {
            "playlist" -> NavigationState("playlists")
            "playlists" -> NavigationState("home")
            "liked" -> NavigationState("home")
            "search" -> NavigationState("home")
            "player" -> NavigationState("home")
            "queue" -> NavigationState("player")
            "settings" -> NavigationState("home")
            "home" -> NavigationState("home")
            else -> NavigationState("home")
        }
    }

    // ---------------- Device code flow ----------------
    fun startDeviceFlow() {
        val deviceId = prefs.getString("device_id", null) ?: run {
            val id = (1..16).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
            prefs.edit().putString("device_id", id).apply()
            id
        }
        // Cancel any existing poll
        pollJob?.cancel()
        pollJob = null
        _deviceCode.value = null

        viewModelScope.launch {
            ApiClient.requestDeviceCode(deviceId, "vplayer_watch") { code, err ->
                if (err != null) {
                    _error.value = "Device code: $err"
                } else if (code != null) {
                    _deviceCode.value = code
                    pollDeviceToken(code)
                }
            }
        }
    }

    fun cancelDeviceFlow() {
        pollJob?.cancel()
        pollJob = null
        _deviceCode.value = null
    }

    private fun pollDeviceToken(code: ApiClient.DeviceCode) {
        pollJob = viewModelScope.launch {
            val interval = (code.interval * 1000L).coerceAtLeast(3000L)
            val startTime = System.currentTimeMillis()
            val expiresAt = startTime + (code.expiresIn * 1000L)
            val myJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!
            val mySessionId = ++pollSessionId

            while (System.currentTimeMillis() < expiresAt && myJob.isActive && mySessionId == pollSessionId) {
                delay(interval)
                // Cancel check after resume
                if (!myJob.isActive || mySessionId != pollSessionId) break

                val result = suspendCancellableCoroutine<ApiClient.TokenResult> { cont ->
                    ApiClient.pollDeviceToken(code.deviceCode) { r ->
                        if (cont.isActive) cont.resume(r)
                    }
                }

                when (result) {
                    is ApiClient.TokenResult.Success -> {
                        if (mySessionId == pollSessionId) saveToken(result.token)
                        return@launch
                    }
                    is ApiClient.TokenResult.Expired -> {
                        if (mySessionId == pollSessionId) _error.value = "Код истёк"
                        return@launch
                    }
                    is ApiClient.TokenResult.Denied -> {
                        if (mySessionId == pollSessionId) _error.value = "Отказано"
                        return@launch
                    }
                    is ApiClient.TokenResult.Error -> {
                        if (mySessionId == pollSessionId) _error.value = result.msg
                        return@launch
                    }
                    ApiClient.TokenResult.Pending -> { /* continue */ }
                }
            }
        }
    }

    // ---------------- Liked tracks ----------------
    fun loadLikedTracks() {
        val uid = _uid.value ?: return
        viewModelScope.launch {
            _loading.value = true
            ApiClient.likedTracks(uid) { tracks, err ->
                if (err != null) {
                    _error.value = "Liked: $err"
                } else {
                    _likedTracks.value = tracks ?: emptyList()
                }
                _loading.value = false
            }
        }
    }

    // ---------------- Playlists ----------------
    fun loadPlaylists() {
        val uid = _uid.value ?: return
        viewModelScope.launch {
            _loading.value = true
            ApiClient.playlistsList(uid) { pl, err ->
                if (err != null) {
                    _error.value = "Playlists: $err"
                } else {
                    _playlists.value = pl ?: emptyList()
                }
                _loading.value = false
            }
        }
    }

    fun loadPlaylistTracks(kind: String) {
        val uid = _uid.value ?: return
        viewModelScope.launch {
            _loading.value = true
            ApiClient.playlistTracks(uid, kind) { tracks, err ->
                if (err != null) {
                    _error.value = "Playlist: $err"
                } else if (tracks != null) {
                    _playlistTracks.value = _playlistTracks.value + (kind to tracks)
                }
                _loading.value = false
            }
        }
    }

    // ---------------- Search ----------------
    fun search(text: String) {
        if (text.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            _loading.value = true
            ApiClient.search(text, "track") { result, err ->
                if (err != null) {
                    _error.value = "Search: $err"
                } else if (result != null) {
                    _searchResults.value = result.tracks
                }
                _loading.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
