package com.victormuha.vplayer.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.victormuha.vplayer.R
import com.victormuha.vplayer.VPlayerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Our own foreground `dataSync` service. Unlike a WorkManager job it is not deferred,
 * throttled or time-limited by the job scheduler, so it keeps downloading while music plays
 * and the app is in the background / the screen is off.
 */
class DownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VPlayerApp.initGlobals(applicationContext)
        startInForeground(DownloadNotifications.build(this, "", 0, 0))
        if (job?.isActive != true) {
            job = scope.launch {
                acquireLocks()
                try {
                    val finished = DownloadCoordinator.runPending(this@DownloadService, waitIfBusy = true) { title, done, total ->
                        DownloadNotifications.notify(this@DownloadService, NOTIFICATION_ID, title, done, total)
                    }
                    // Network gone: let WorkManager resume when it is back (constraint CONNECTED).
                    if (!finished) OfflineSyncWorker.enqueue(this@DownloadService)
                } finally {
                    releaseLocks()
                    withContext(Dispatchers.Main) {
                        ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun startInForeground(notification: android.app.Notification) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun acquireLocks() {
        runCatching {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vplayer:downloads")
                .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            wifiLock = (applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vplayer:downloads")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
    }

    override fun onDestroy() {
        Log.i("Downloads", "DownloadService destroyed")
        scope.cancel()
        releaseLocks()
        super.onDestroy()
    }

    companion object {
        const val NOTIFICATION_ID = 43
    }
}

object DownloadNotifications {
    private const val CHANNEL = "vplayer_downloads"

    fun build(context: Context, title: String, done: Int, total: Int): android.app.Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW))
        }
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle(if (title.isBlank()) "Загрузка для оффлайна" else "Загрузка: $title")
            .setContentText(if (total > 0) "$done из $total" else "Подготовка")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun notify(context: Context, id: Int, title: String, done: Int, total: Int) {
        runCatching {
            context.getSystemService(NotificationManager::class.java).notify(id, build(context, title, done, total))
        }
    }
}
