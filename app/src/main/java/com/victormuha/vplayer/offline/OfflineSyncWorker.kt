package com.victormuha.vplayer.offline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.victormuha.vplayer.R
import com.victormuha.vplayer.VPlayerApp
import com.victormuha.vplayer.api.Api
import com.victormuha.vplayer.settings.AppSettings
import kotlinx.coroutines.runBlocking
import java.util.concurrent.TimeUnit

/** Persistent download job: survives the UI closing, screen-off and process restarts. */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        VPlayerApp.initGlobals(applicationContext)
        val key = inputData.getString(KEY) ?: return Result.failure()
        val owner = inputData.getString(OWNER).orEmpty()
        val kind = inputData.getString(KIND).orEmpty()
        val title = inputData.getString(TITLE).orEmpty()
        val economy = AppSettings.economyDownloads.value
        val store = OfflineStore.get(applicationContext)

        runCatching { setForeground(foregroundInfo(title, 0, 0)) }
        val sync = OfflineSync(
            store, TrackDownloader(),
            fetchTracks = {
                if (key == OfflineKeys.LIKED) Api.likedTracks(owner) else Api.playlistTracks(owner, kind)
            },
            resolveUrl = { Api.resolveTrackUrl(it, economy) }
        )
        val status = sync.run(key, owner, kind, title) { done, total ->
            setProgress(workDataOf("done" to done, "total" to total))
            runCatching { setForeground(foregroundInfo(title, done, total)) }
        }
        return if (status == SyncStatus.FAILED && runAttemptCount < 3) Result.retry() else Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        foregroundInfo(inputData.getString(TITLE).orEmpty(), 0, 0)

    private fun foregroundInfo(title: String, done: Int, total: Int): ForegroundInfo {
        val nm = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL)
            .setSmallIcon(R.drawable.ic_music)
            .setContentTitle("Загрузка: $title")
            .setContentText(if (total > 0) "$done из $total" else "Подготовка")
            .setProgress(total, done, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val KEY = "key"
        private const val OWNER = "owner"
        private const val KIND = "kind"
        private const val TITLE = "title"
        private const val CHANNEL = "vplayer_downloads"
        private const val NOTIFICATION_ID = 42

        fun workName(key: String) = "offline-$key"

        fun enqueue(context: Context, key: String, ownerUid: String, kind: String, title: String) {
            val store = OfflineStore.get(context)
            runBlocking {
                store.update { lib ->
                    val old = lib.playlists[key]
                    val p = (old ?: OfflinePlaylist(key, ownerUid, kind, title))
                        .copy(status = SyncStatus.QUEUED, message = null, title = title.ifBlank { old?.title ?: key })
                    lib.copy(playlists = lib.playlists + (key to p))
                }
            }
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setInputData(workDataOf(KEY to key, OWNER to ownerUid, KIND to kind, TITLE to title))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(workName(key), ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context, key: String) {
            WorkManager.getInstance(context).cancelUniqueWork(workName(key))
        }

        fun cancelAll(context: Context) {
            WorkManager.getInstance(context).cancelAllWork()
        }
    }
}
