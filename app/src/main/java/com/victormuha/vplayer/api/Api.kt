package com.victormuha.vplayer.api

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ApiException(message: String) : Exception(message) {
    val unauthorized: Boolean get() = message == "unauthorized"
}

/** Coroutine facade over the callback-based [ApiClient] used by the service and offline worker. */
object Api {
    private suspend fun <T> await(block: ((T?, String?) -> Unit) -> Unit): T =
        suspendCancellableCoroutine { cont ->
            block { value, error ->
                if (!cont.isActive) return@block
                if (error != null || value == null) cont.resumeWithException(ApiException(error ?: "empty response"))
                else cont.resume(value)
            }
        }

    suspend fun likedTracks(uid: String): List<ApiClient.Track> = await { ApiClient.likedTracks(uid, it) }

    suspend fun playlistTracks(uid: String, kind: String): List<ApiClient.Track> =
        await { ApiClient.playlistTracks(uid, kind, it) }

    /** Returns a short-lived direct MP3 URL and its bitrate. */
    suspend fun resolveTrackUrl(trackId: String, economy: Boolean = false): Pair<String, Int> {
        val variants = await<List<ApiClient.DownloadVariant>> { ApiClient.downloadInfo(trackId, it) }
        val variant = chooseVariant(variants, economy) ?: throw ApiException("Нет полной версии трека")
        val url = await<String> { ApiClient.buildDirectLink(variant.downloadInfoUrl, it) }
        return url to variant.bitrate
    }

    /** Full (non-preview) MP3 only. Economy picks the smallest bitrate that is still >= 128 kbps. */
    internal fun chooseVariant(variants: List<ApiClient.DownloadVariant>, economy: Boolean): ApiClient.DownloadVariant? {
        val full = variants.filter { it.codec == "mp3" && !it.preview }
        val pool = full.filter { !it.direct }.ifEmpty { full }
        if (pool.isEmpty()) return null
        return if (economy) pool.filter { it.bitrate >= 128 }.minByOrNull { it.bitrate } ?: pool.maxByOrNull { it.bitrate }
        else pool.maxByOrNull { it.bitrate }
    }
}
