package com.victormuha.vplayer.api

import android.util.Log
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val TAG = "ApiClient"
    private const val OAUTH_BASE = "https://oauth.yandex.ru"
    private const val API_BASE = "https://api.music.yandex.net"
    private const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
    private const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"
    private const val SIGN_SALT = "XGRlBW9FXlekgbPrRHuSiA"

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    var token: String? = null

    private fun authHeader() = token?.let { "OAuth $it" }

    // ---------- OAuth Device Flow ----------
    data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val interval: Int,
        val expiresIn: Int
    )

    sealed class TokenResult {
        data class Success(val token: String) : TokenResult()
        object Pending : TokenResult()
        object Expired : TokenResult()
        object Denied : TokenResult()
        data class Error(val msg: String) : TokenResult()
    }

    fun requestDeviceCode(deviceId: String, deviceName: String, onResult: (DeviceCode?, String?) -> Unit) {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_id", deviceId)
            .add("device_name", deviceName)
            .build()
        post("$OAUTH_BASE/device/code", body, null) { resp, err ->
            if (err != null) return@post onResult(null, err)
            val json = JSONObject(resp)
            onResult(
                DeviceCode(
                    deviceCode = json.optString("device_code"),
                    userCode = json.optString("user_code"),
                    verificationUrl = json.optString("verification_url", "https://ya.ru/device"),
                    interval = json.optInt("interval", 5),
                    expiresIn = json.optInt("expires_in", 300)
                ),
                null
            )
        }
    }

    fun pollDeviceToken(deviceCode: String, onResult: (TokenResult) -> Unit) {
        val body = FormBody.Builder()
            .add("grant_type", "device_code")
            .add("code", deviceCode)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .build()
        post("$OAUTH_BASE/token", body, null) { resp, err ->
            if (err != null && resp.isEmpty()) return@post onResult(TokenResult.Error(err))
            if (resp.isEmpty()) return@post onResult(TokenResult.Error("empty response"))
            val json = try { JSONObject(resp) } catch (e: Exception) { null }
                ?: return@post onResult(TokenResult.Error("bad json"))
            if (json.has("access_token")) {
                onResult(TokenResult.Success(json.optString("access_token")))
            } else {
                when (json.optString("error")) {
                    "authorization_pending" -> onResult(TokenResult.Pending)
                    "expired_token", "invalid_grant" -> onResult(TokenResult.Expired)
                    "access_denied" -> onResult(TokenResult.Denied)
                    else -> onResult(TokenResult.Error(json.optString("error")))
                }
            }
        }
    }

    // ---------- Account ----------
    fun accountStatus(onResult: (String?, String?) -> Unit) {
        get("$API_BASE/account/status") { resp, err ->
            if (err != null) return@get onResult(null, err)
            val json = JSONObject(resp)
            val acc = json.optJSONObject("result")?.optJSONObject("account")
            val uid = acc?.optLong("uid")
            if (uid != null && uid != 0L) {
                onResult(uid.toString(), null)
            } else {
                onResult(null, "no account")
            }
        }
    }

    // ---------- Models ----------
    data class Track(
        val id: String,
        val albumId: String,
        val title: String,
        val artist: String,
        val durationMs: Long = 0L,
        val liked: Boolean = false,
        val available: Boolean = true
    ) {
        val trackId: String get() = if (albumId.isNotEmpty()) "$id:$albumId" else id
    }

    data class Playlist(
        val kind: String,
        val title: String,
        val trackCount: Int,
        val uid: String
    )

    // ---------- Liked tracks ----------
    fun likedTracks(uid: String, onResult: (List<Track>?, String?) -> Unit) {
        get("$API_BASE/users/$uid/likes/tracks") { resp, err ->
            if (err != null) return@get onResult(null, err)
            val json = JSONObject(resp)
            val lib = json.optJSONObject("result")?.optJSONObject("library")
                ?: return@get onResult(null, "no library")
            val arr = lib.optJSONArray("tracks") ?: JSONArray()
            val ids = (0 until arr.length()).mapNotNull {
                val t = arr.optJSONObject(it) ?: return@mapNotNull null
                Track(
                    id = t.optString("id"),
                    albumId = t.optString("albumId"),
                    title = "",
                    artist = "",
                    durationMs = t.optLong("durationMs"),
                    liked = true
                )
            }.filter { it.id.isNotEmpty() }

            if (ids.isEmpty()) return@get onResult(emptyList(), null)
            resolveTrackMetadata(ids) { fullList, mErr ->
                onResult(fullList, mErr)
            }
        }
    }

    // ---------- Playlists ----------
    fun playlistsList(uid: String, onResult: (List<Playlist>?, String?) -> Unit) {
        get("$API_BASE/users/$uid/playlists") { resp, err ->
            if (err != null) return@get onResult(null, err)
            val json = JSONObject(resp)
            val arr = json.optJSONArray("result") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull {
                val p = arr.optJSONObject(it) ?: return@mapNotNull null
                Playlist(
                    kind = p.optString("kind"),
                    title = p.optString("title", "Без названия"),
                    trackCount = p.optInt("trackCount"),
                    uid = p.optString("uid")
                )
            }
            onResult(list, null)
        }
    }

    fun playlistTracks(uid: String, kind: String, onResult: (List<Track>?, String?) -> Unit) {
        get("$API_BASE/users/$uid/playlists/$kind") { resp, err ->
            if (err != null) return@get onResult(null, err)
            val json = JSONObject(resp)
            val tracksArr = json.optJSONObject("result")?.optJSONArray("tracks") ?: JSONArray()
            val ids = (0 until tracksArr.length()).mapNotNull {
                val t = tracksArr.optJSONObject(it) ?: return@mapNotNull null
                val trackObj = t.optJSONObject("track") ?: return@mapNotNull null
                Track(
                    id = trackObj.optString("id"),
                    albumId = trackObj.optString("albumId"),
                    title = "",
                    artist = "",
                    durationMs = trackObj.optLong("durationMs"),
                    liked = trackObj.optBoolean("liked", false)
                )
            }.filter { it.id.isNotEmpty() }
            resolveTrackMetadata(ids) { fullList, mErr ->
                onResult(fullList, mErr)
            }
        }
    }

    // ---------- Search ----------
    data class SearchResult(
        val tracks: List<Track>,
        val playlists: List<Playlist>
    )

    fun search(text: String, type: String, onResult: (SearchResult?, String?) -> Unit) {
        val baseUrl = "$API_BASE/search".toHttpUrlOrNull() ?: return onResult(null, "bad url")
        val url = baseUrl.newBuilder()
            ?.addQueryParameter("text", text)
            ?.addQueryParameter("type", type)
            ?.addQueryParameter("nocorrect", "false")
            ?.addQueryParameter("page", "0")
            ?.addQueryParameter("playlist-in-best", "true")
            ?.build()
            ?.toString()
            ?: return onResult(null, "bad url")
        get(url) { resp, err ->
            if (err != null) return@get onResult(null, err)
            val json = JSONObject(resp)
            val res = json.optJSONObject("result") ?: return@get onResult(null, "no result")
            when (type) {
                "track" -> {
                    val tracksArr = res.optJSONArray("tracks") ?: JSONArray()
                    val tracks = (0 until tracksArr.length()).mapNotNull {
                        val t = tracksArr.optJSONObject(it) ?: return@mapNotNull null
                        Track(
                            id = t.optString("id"),
                            albumId = t.optString("albumId"),
                            title = "",
                            artist = "",
                            durationMs = t.optLong("durationMs")
                        )
                    }
                    resolveTrackMetadata(tracks) { full, mErr ->
                        onResult(SearchResult(full ?: emptyList(), emptyList()), mErr)
                    }
                }
                "playlist" -> {
                    val pArr = res.optJSONArray("playlists") ?: JSONArray()
                    val pl = (0 until pArr.length()).mapNotNull {
                        val p = pArr.optJSONObject(it) ?: return@mapNotNull null
                        Playlist(
                            kind = p.optString("kind"),
                            title = p.optString("title", "Без названия"),
                            trackCount = p.optInt("trackCount"),
                            uid = p.optString("uid")
                        )
                    }
                    onResult(SearchResult(emptyList(), pl), null)
                }
                else -> onResult(SearchResult(emptyList(), emptyList()), "unsupported type")
            }
        }
    }

    // ---------- Like / Dislike ----------
    fun likeTrack(trackId: String, uid: String, onResult: (Boolean, String?) -> Unit) {
        val body = FormBody.Builder().add("track-ids", trackId).build()
        post("$API_BASE/users/$uid/likes/tracks/add-multiple", body, null) { resp, err ->
            if (err != null) return@post onResult(false, err)
            val json = JSONObject(resp)
            val result = json.optJSONObject("result")
            onResult(result?.has("revision") == true, null)
        }
    }

    fun unlikeTrack(trackId: String, uid: String, onResult: (Boolean, String?) -> Unit) {
        val body = FormBody.Builder().add("track-ids", trackId).build()
        post("$API_BASE/users/$uid/likes/tracks/remove", body, null) { resp, err ->
            if (err != null) return@post onResult(false, err)
            val json = JSONObject(resp)
            val result = json.optJSONObject("result")
            onResult(result?.has("revision") == true, null)
        }
    }

    fun dislikeTrack(trackId: String, uid: String, onResult: (Boolean, String?) -> Unit) {
        val body = FormBody.Builder().add("track-ids", trackId).build()
        post("$API_BASE/users/$uid/dislikes/tracks/add-multiple", body, null) { resp, err ->
            if (err != null) return@post onResult(false, err)
            val json = JSONObject(resp)
            onResult(json.has("revision"), null)
        }
    }

    // ---------- Metadata batch ----------
    private fun resolveTrackMetadata(ids: List<Track>, onDone: (List<Track>?, String?) -> Unit) {
        val result = ids.toMutableList()
        val batches = ids.chunked(100)
        if (batches.isEmpty()) return onDone(emptyList(), null)
        fetchBatch(0, batches, result) { err ->
            if (err != null) onDone(null, err)
            else onDone(result.toList(), null)
        }
    }

    private fun fetchBatch(
        idx: Int,
        batches: List<List<Track>>,
        result: MutableList<Track>,
        onDone: (String?) -> Unit
    ) {
        if (idx >= batches.size) return onDone(null)
        val batch = batches[idx]
        val fb = FormBody.Builder().add("with-positions", "false")
        batch.forEach { fb.add("track-ids", it.trackId) }
        post("$API_BASE/tracks", fb.build(), null) { resp, err ->
            if (err != null) return@post onDone(err)
            val json = JSONObject(resp)
            val arr = json.optJSONArray("result") ?: JSONArray()
            val map = HashMap<String, Track>()
            for (i in 0 until arr.length()) {
                val t = arr.optJSONObject(i) ?: continue
                val tid = t.optString("id")
                val title = t.optString("title")
                val artist = t.optJSONArray("artists")?.optJSONObject(0)?.optString("name") ?: ""
                val dur = t.optLong("durationMs")
                val avail = t.optBoolean("available", true)
                map[tid] = Track(tid, "", title, artist, dur, available = avail)
            }
            for (i in result.indices) {
                val cur = result[i]
                map[cur.id]?.let {
                    result[i] = Track(cur.id, cur.albumId, it.title, it.artist, it.durationMs, cur.liked, it.available)
                }
            }
            fetchBatch(idx + 1, batches, result, onDone)
        }
    }

    // ---------- Download info & direct URL ----------
    data class DownloadVariant(
        val codec: String,
        val bitrate: Int,
        val preview: Boolean,
        val downloadInfoUrl: String,
        val direct: Boolean
    )

    fun downloadInfo(trackId: String, onResult: (List<DownloadVariant>?, String?) -> Unit) {
        get("$API_BASE/tracks/$trackId/download-info") { resp, err ->
            if (err != null) return@get onResult(null, err)
            val json = JSONObject(resp)
            val arr = json.optJSONArray("result") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull {
                val d = arr.optJSONObject(it) ?: return@mapNotNull null
                DownloadVariant(
                    codec = d.optString("codec"),
                    bitrate = d.optInt("bitrateInKbps"),
                    preview = d.optBoolean("preview"),
                    downloadInfoUrl = d.optString("downloadInfoUrl"),
                    direct = d.optBoolean("direct", false)
                )
            }
            onResult(list, null)
        }
    }

    fun buildDirectLink(downloadInfoUrl: String, onResult: (String?, String?) -> Unit) {
        getRaw(downloadInfoUrl) { raw, err ->
            if (err != null || raw == null) return@getRaw onResult(null, err ?: "no xml")
            try {
                val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder().parse(raw.byteInputStream())
                val host = doc.getElementsByTagName("host").item(0)?.textContent ?: ""
                val path = doc.getElementsByTagName("path").item(0)?.textContent ?: ""
                val ts = doc.getElementsByTagName("ts").item(0)?.textContent ?: ""
                val s = doc.getElementsByTagName("s").item(0)?.textContent ?: ""
                val sign = md5(SIGN_SALT + path.substring(1) + s)
                onResult("https://$host/get-mp3/$sign/$ts$path", null)
            } catch (e: Exception) {
                onResult(null, "xml parse: ${e.message}")
            }
        }
    }

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { String.format("%02x", it) }
    }

    // ---------- HTTP helpers ----------
    private fun get(url: String, onDone: (String, String?) -> Unit) {
        val rb = Request.Builder().url(url).apply {
            token?.let { header("Authorization", "OAuth $it") }
            if (url.startsWith(API_BASE)) header("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
        }
        http.newCall(rb.get().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onDone("", e.message ?: "network") }
            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                if (!response.isSuccessful) {
                    val err = when (response.code) {
                        401 -> "unauthorized"
                        403 -> "forbidden"
                        451 -> "legal unavailable"
                        else -> "http ${response.code}"
                    }
                    onDone(body, err)
                } else {
                    onDone(body, null)
                }
            }
        })
    }

    private fun getRaw(url: String, onDone: (String?, String?) -> Unit) {
        http.newCall(Request.Builder().url(url).get().build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onDone(null, e.message ?: "network") }
            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) return onDone(null, "http ${response.code}")
                onDone(response.body?.string(), null)
            }
        })
    }

    private fun post(url: String, body: RequestBody, auth: String?, onDone: (String, String?) -> Unit) {
        val rb = Request.Builder().url(url).post(body).apply {
            if (auth != null) header("Authorization", auth)
            else token?.let { header("Authorization", "OAuth $it") }
            if (url.startsWith(API_BASE)) header("X-Yandex-Music-Client", "YandexMusicAndroid/24023621")
        }
        http.newCall(rb.build()).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { onDone("", e.message ?: "network") }
            override fun onResponse(call: Call, response: Response) {
                val respBody = response.body?.string() ?: ""
                onDone(respBody, if (!response.isSuccessful) "http ${response.code}" else null)
            }
        })
    }
}
