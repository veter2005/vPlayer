package com.victormuha.vplayer.offline

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.victormuha.vplayer.api.Api
import com.victormuha.vplayer.settings.AppSettings
import com.victormuha.vplayer.settings.NetworkMonitor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

/**
 * Single download queue per process. Queue state lives in the offline library
 * (QUEUED/DOWNLOADING playlists), so it survives process death and app restarts.
 *
 * Normally the queue is drained by [DownloadService] (our own foreground `dataSync` service,
 * running alongside music playback). [OfflineSyncWorker] is only a fallback for when the
 * service cannot be started (background start limits) or the network is gone.
 * Both go through [runPending], whose lock guarantees that a file is never written twice.
 */
object DownloadCoordinator {
    private const val TAG = "Downloads"
    private val loopLock = Mutex()
    @Volatile private var currentJob: Job? = null
    @Volatile private var currentKey: String? = null

    private val _activeKey = MutableStateFlow<String?>(null)
    val activeKey: StateFlow<String?> = _activeKey.asStateFlow()

    fun pending(lib: OfflineLibrary): List<OfflinePlaylist> =
        lib.playlists.values.filter { it.status == SyncStatus.QUEUED || it.status == SyncStatus.DOWNLOADING }
            .sortedBy { it.updatedAt }

    fun hasPending(context: Context) = pending(OfflineStore.get(context).library.value).isNotEmpty()

    /** User pressed "download"/"resume": mark queued and start the foreground service. */
    suspend fun request(context: Context, key: String, ownerUid: String, kind: String, title: String) {
        OfflineStore.get(context).update { lib ->
            val old = lib.playlists[key]
            val p = (old ?: OfflinePlaylist(key, ownerUid, kind, title)).copy(
                status = SyncStatus.QUEUED, message = null,
                title = title.ifBlank { old?.title ?: key }, updatedAt = System.currentTimeMillis()
            )
            lib.copy(playlists = lib.playlists + (key to p))
        }
        start(context)
    }

    /** Starts the download service; falls back to WorkManager if the OS forbids it right now. */
    fun start(context: Context) {
        if (!hasPending(context)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java))
        } catch (e: Exception) { // ForegroundServiceStartNotAllowedException / IllegalStateException
            Log.w(TAG, "DownloadService start not allowed (${e.javaClass.simpleName}), using WorkManager")
            OfflineSyncWorker.enqueue(context)
        }
    }

    /** Stops the playlist currently being downloaded (pause/delete) and waits for it to stop writing. */
    suspend fun cancelAndWait(key: String? = null) {
        val job = currentJob
        if (job != null && (key == null || key == currentKey)) {
            job.cancel()
            job.join()
        }
    }

    /**
     * @param waitIfBusy the service waits for a running worker loop and then takes over
     *   (the system may stop that worker at any moment); the worker itself does not wait.
     */
    suspend fun runPending(
        context: Context, waitIfBusy: Boolean = false,
        onProgress: suspend (String, Int, Int) -> Unit = { _, _, _ -> }
    ): Boolean {
        val store = OfflineStore.get(context)
        val economy = AppSettings.economyDownloads.value
        return runPending(store, NetworkMonitor::isOffline, waitIfBusy, onProgress) { p ->
            OfflineSync(
                store, TrackDownloader(),
                fetchTracks = {
                    if (p.key == OfflineKeys.LIKED) Api.likedTracks(p.ownerUid) else Api.playlistTracks(p.ownerUid, p.kind)
                },
                resolveUrl = { Api.resolveTrackUrl(it, economy) }
            )
        }
    }

    /**
     * Downloads queued playlists one after another.
     * @return true when nothing is left (or another runner is active), false if work remains (offline).
     */
    internal suspend fun runPending(
        store: OfflineStore,
        isOffline: () -> Boolean,
        waitIfBusy: Boolean,
        onProgress: suspend (String, Int, Int) -> Unit,
        makeSync: (OfflinePlaylist) -> OfflineSync
    ): Boolean {
        if (waitIfBusy) {
            if (!loopLock.tryLock()) {
                Log.i(TAG, "waiting for the running queue to finish")
                loopLock.lock()
            }
        } else if (!loopLock.tryLock()) {
            Log.i(TAG, "queue already running in this process")
            return true
        }
        val attempted = mutableSetOf<String>()
        try {
            while (true) {
                currentCoroutineContext().ensureActive()
                val next = pending(store.library.value).firstOrNull { it.key !in attempted } ?: return true
                if (isOffline()) {
                    Log.i(TAG, "offline, ${pending(store.library.value).size} playlist(s) wait for network")
                    return false
                }
                attempted += next.key
                Log.i(TAG, "start ${next.key} (${next.done}/${next.total})")
                coroutineScope {
                    val job = async {
                        makeSync(next).run(next.key, next.ownerUid, next.kind, next.title) { d, t -> onProgress(next.title, d, t) }
                    }
                    currentJob = job
                    currentKey = next.key
                    _activeKey.value = next.key
                    try {
                        Log.i(TAG, "finished ${next.key}: ${job.await()}")
                    } catch (e: CancellationException) {
                        currentCoroutineContext().ensureActive() // rethrow if the runner itself was stopped
                        Log.i(TAG, "stopped by user ${next.key}")
                    } catch (e: Exception) {
                        Log.w(TAG, "failed ${next.key}: ${e.message}")
                        store.setPlaylistStatus(next.key, SyncStatus.FAILED, e.message)
                    }
                }
                currentJob = null
                currentKey = null
                _activeKey.value = null
            }
        } finally {
            currentJob = null
            currentKey = null
            _activeKey.value = null
            loopLock.unlock()
        }
    }
}
