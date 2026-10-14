package com.victormuha.vplayer.api

import org.json.JSONArray
import org.json.JSONObject

/** The list endpoint and the playlist detail endpoint have different response shapes. */
internal object PlaylistResponses {
    fun list(body: String, accountUid: String): List<ApiClient.Playlist> {
        val result = JSONObject(body).getJSONArray("result")
        return (0 until result.length()).mapNotNull { index ->
            val playlist = result.optJSONObject(index) ?: return@mapNotNull null
            val kind = playlist.stringValue("kind")
            if (kind.isBlank()) return@mapNotNull null
            ApiClient.Playlist(
                kind = kind,
                title = playlist.optString("title", "Без названия"),
                trackCount = playlist.optInt("trackCount"),
                uid = playlist.optJSONObject("owner")?.stringValue("uid")?.takeIf { it.isNotBlank() }
                    ?: playlist.stringValue("uid").takeIf { it.isNotBlank() }
                    ?: accountUid
            )
        }
    }

    fun tracks(body: String): List<ApiClient.Track> {
        val playlist = JSONObject(body).getJSONObject("result")
        val entries = playlist.optJSONArray("tracks") ?: JSONArray()
        return (0 until entries.length()).mapNotNull { index ->
            val entry = entries.optJSONObject(index) ?: return@mapNotNull null
            val track = entry.optJSONObject("track") ?: entry
            val id = track.stringValue("id").ifBlank { entry.stringValue("id") }
            if (id.isBlank()) return@mapNotNull null
            val albumId = entry.stringValue("albumId")
                .ifBlank { track.stringValue("albumId") }
                .ifBlank { track.optJSONArray("albums")?.optJSONObject(0)?.stringValue("id") ?: "" }
            ApiClient.Track(
                id = id,
                albumId = albumId,
                title = track.optString("title", ""),
                artist = track.optJSONArray("artists")?.let { artists ->
                    (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }
                        .joinToString(", ")
                } ?: "",
                durationMs = track.optLong("durationMs"),
                available = track.optBoolean("available", true)
            )
        }
    }

    private fun JSONObject.stringValue(key: String): String =
        if (isNull(key)) "" else optString(key, "")
}
