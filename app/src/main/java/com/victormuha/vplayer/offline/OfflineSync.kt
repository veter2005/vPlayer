package com.victormuha.vplayer.offline

import com.victormuha.vplayer.api.ApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Download/refresh algorithm; pure enough to unit-test with fake API functions. */
class OfflineSync(
    private val store: OfflineStore,
    private val downloader: TrackDownloader,
    private val fetchTracks: suspend () -> List<ApiClient.Track>,
    private val resolveUrl: suspend (trackId: String) -> Pair<String, Int>,
    private val now: () -> Long = System::currentTimeMillis
) {
    suspend fun run(
        key: String, ownerUid: String, kind: String, title: String,
        onProgress: suspend (done: Int, total: Int) -> Unit = { _, _ -> }
    ): SyncStatus {
        val fresh = try { fetchTracks() } catch (e: CancellationException) { throw e } catch (e: Exception) { null }
        val previous = store.library.value
        val tracks: List<OfflineTrack> = fresh?.filter { it.available && it.id.isNotBlank() }
            ?.distinctBy { it.id }?.map { OfflineTrack.from(it) }
            ?: previous.tracksOf(key).ifEmpty {
                upsertPlaylist(key, ownerUid, kind, title, emptyList(), SyncStatus.FAILED, "Нет сети или ошибка API")
                return SyncStatus.FAILED
            }

        // Keep already downloaded file info, refresh titles from the API.
        store.update { lib ->
            val merged = tracks.associate { t ->
                val old = lib.tracks[t.id]
                t.id to t.copy(fileName = old?.fileName, sizeBytes = old?.sizeBytes ?: 0, bitrate = old?.bitrate ?: 0)
            }
            lib.copy(tracks = lib.tracks + merged)
        }
        upsertPlaylist(key, ownerUid, kind, title, tracks.map { it.id }, SyncStatus.DOWNLOADING, null)
        store.pruneOrphans() // tracks removed from this playlist (and not used elsewhere)

        var failures = 0
        var done = tracks.count { store.isDownloaded(it.id) }
        updateProgress(key, done, tracks.size)
        onProgress(done, tracks.size)
        for (track in tracks) {
            currentCoroutineContext().ensureActive()
            if (store.isDownloaded(track.id)) continue
            try {
                val (url, bitrate) = resolveUrl(track.trackId)
                val dest = store.fileFor(track.id)
                val size = downloader.download(url, dest) { store.freeBytes() }
                store.update { lib ->
                    val t = lib.tracks[track.id] ?: track
                    lib.copy(tracks = lib.tracks + (track.id to t.copy(fileName = dest.name, sizeBytes = size, bitrate = bitrate)))
                }
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: NoSpaceException) {
                store.setPlaylistStatus(key, SyncStatus.NO_SPACE, "Мало места: скачано $done из ${tracks.size}")
                return SyncStatus.NO_SPACE
            } catch (e: Exception) {
                failures++
            }
            updateProgress(key, done, tracks.size)
            onProgress(done, tracks.size)
        }
        val status = if (failures == 0) SyncStatus.DONE else SyncStatus.PARTIAL
        store.setPlaylistStatus(key, status, if (failures == 0) null else "Не скачано: $failures")
        return status
    }

    private suspend fun upsertPlaylist(
        key: String, ownerUid: String, kind: String, title: String,
        ids: List<String>, status: SyncStatus, message: String?
    ) = store.update { lib ->
        val old = lib.playlists[key]
        val p = OfflinePlaylist(
            key, ownerUid, kind, title.ifBlank { old?.title ?: key },
            ids.ifEmpty { old?.trackIds ?: emptyList() }, status,
            old?.done ?: 0, ids.size.takeIf { it > 0 } ?: old?.total ?: 0, message, now()
        )
        lib.copy(playlists = lib.playlists + (key to p))
    }

    private suspend fun updateProgress(key: String, done: Int, total: Int) = store.update { lib ->
        val p = lib.playlists[key] ?: return@update lib
        lib.copy(playlists = lib.playlists + (key to p.copy(done = done, total = total)))
    }
}
