package com.victormuha.vplayer.settings

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Small process-wide settings shared by UI, PlaybackService and the download worker. */
object AppSettings {
    const val PREFS = "vplayer"
    private var prefs: SharedPreferences? = null

    private val _forcedOffline = MutableStateFlow(false)
    val forcedOffline: StateFlow<Boolean> = _forcedOffline.asStateFlow()

    private val _economyDownloads = MutableStateFlow(false)
    val economyDownloads: StateFlow<Boolean> = _economyDownloads.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        _forcedOffline.value = p.getBoolean("forced_offline", false)
        _economyDownloads.value = p.getBoolean("economy_downloads", false)
    }

    fun setForcedOffline(value: Boolean) {
        _forcedOffline.value = value
        prefs?.edit()?.putBoolean("forced_offline", value)?.apply()
    }

    fun setEconomyDownloads(value: Boolean) {
        _economyDownloads.value = value
        prefs?.edit()?.putBoolean("economy_downloads", value)?.apply()
    }
}

/** Tracks whether any network with INTERNET capability is available. */
object NetworkMonitor {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private var registered = false
    private val networks = mutableSetOf<Network>()

    @Synchronized
    fun init(context: Context) {
        if (registered) return
        registered = true
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            _online.value = cm.getNetworkCapabilities(cm.activeNetwork)
                ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    synchronized(networks) { networks += network }
                    _online.value = true
                }
                override fun onLost(network: Network) {
                    synchronized(networks) { networks -= network; _online.value = networks.isNotEmpty() }
                }
            })
        }
    }

    /** True when playback/browsing must use only local data. */
    fun isOffline(): Boolean = AppSettings.forcedOffline.value || !online.value
}
