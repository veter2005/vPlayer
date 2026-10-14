package com.victormuha.vplayer

import android.app.Application
import android.content.Context
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.offline.DownloadCoordinator
import com.victormuha.vplayer.offline.OfflineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import com.victormuha.vplayer.settings.AppSettings
import com.victormuha.vplayer.settings.NetworkMonitor

class VPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initGlobals(this)
        // Network back while the process is alive (e.g. music playing): resume the queue.
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            NetworkMonitor.online.drop(1).collect { online ->
                if (online && DownloadCoordinator.activeKey.value == null) DownloadCoordinator.start(this@VPlayerApp)
            }
        }
    }

    companion object {
        /** Idempotent; also called by the worker/service in case they start without UI. */
        fun initGlobals(context: Context) {
            AppSettings.init(context)
            NetworkMonitor.init(context)
            if (ApiClient.token == null) {
                ApiClient.token = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
                    .getString("token", null)
            }
            OfflineStore.get(context)
        }
    }
}
