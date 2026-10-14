package com.victormuha.vplayer

import android.app.Application
import android.content.Context
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.offline.OfflineStore
import com.victormuha.vplayer.settings.AppSettings
import com.victormuha.vplayer.settings.NetworkMonitor

class VPlayerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        initGlobals(this)
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
