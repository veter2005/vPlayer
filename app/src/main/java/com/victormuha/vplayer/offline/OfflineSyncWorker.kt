package com.victormuha.vplayer.offline

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.victormuha.vplayer.VPlayerApp
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit

/**
 * Fallback runner: resumes the download queue when the network comes back or after a
 * process restart, if [DownloadService] could not be started. Shares the queue lock.
 */
class OfflineSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        VPlayerApp.initGlobals(applicationContext)
        Log.i(TAG, "worker start, attempt $runAttemptCount")
        runCatching { setForeground(getForegroundInfo()) }
            .onFailure { Log.w(TAG, "worker foreground not allowed: ${it.javaClass.simpleName}") }
        return try {
            val finished = DownloadCoordinator.runPending(applicationContext) { title, done, total ->
                runCatching { setForeground(info(title, done, total)) }
            }
            if (finished) Result.success() else Result.retry()
        } catch (e: CancellationException) {
            Log.w(TAG, "worker stopped by system, reason=${if (Build.VERSION.SDK_INT >= 31) stopReason else -1}")
            throw e
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info("", 0, 0)

    private fun info(title: String, done: Int, total: Int): ForegroundInfo {
        val n = DownloadNotifications.build(applicationContext, title, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else ForegroundInfo(NOTIFICATION_ID, n)
    }

    companion object {
        private const val TAG = "Downloads"
        private const val NOTIFICATION_ID = 42
        private const val WORK = "offline-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        fun cancel(context: Context) {
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(WORK) }
        }
    }
}
