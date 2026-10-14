package com.victormuha.vplayer.offline

import com.victormuha.vplayer.api.ApiClient
import kotlinx.serialization.Serializable

@Serializable
data class OfflineTrack(
    val id: String,
    val albumId: String = "",
    val title: String = "",
    val artist: String = "",
    val durationMs: Long = 0,
    /** File name inside the offline directory; null until the audio is fully downloaded. */
    val fileName: String? = null,
    val sizeBytes: Long = 0,
    val bitrate: Int = 0
) {
    val trackId: String get() = if (albumId.isNotEmpty()) "$id:$albumId" else id
    fun toApiTrack() = ApiClient.Track(id, albumId, title, artist, durationMs)

    companion object {
        fun from(t: ApiClient.Track) = OfflineTrack(t.id, t.albumId, t.title, t.artist, t.durationMs)
    }
}

@Serializable
enum class SyncStatus { QUEUED, DOWNLOADING, DONE, PARTIAL, FAILED, NO_SPACE }

@Serializable
data class OfflinePlaylist(
    /** "liked" or "ownerUid:kind". */
    val key: String,
    val ownerUid: String,
    val kind: String,
    val title: String,
    val trackIds: List<String> = emptyList(),
    val status: SyncStatus = SyncStatus.QUEUED,
    val done: Int = 0,
    val total: Int = 0,
    val message: String? = null,
    val updatedAt: Long = 0
)

@Serializable
data class OfflineLibrary(
    val version: Int = 1,
    val playlists: Map<String, OfflinePlaylist> = emptyMap(),
    /** Keyed by plain track id (without album id). */
    val tracks: Map<String, OfflineTrack> = emptyMap()
) {
    fun tracksOf(key: String): List<OfflineTrack> =
        playlists[key]?.trackIds?.mapNotNull { tracks[it] } ?: emptyList()
}

object OfflineKeys {
    const val LIKED = "liked"
    fun playlist(ownerUid: String, kind: String) = "$ownerUid:$kind"
}
