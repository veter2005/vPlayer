package com.victormuha.vplayer.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class NoSpaceException : IOException("Недостаточно места")

/** Streams a URL into `<dest>.part`, verifies the size and atomically renames it to [dest]. */
class TrackDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val reserveBytes: Long = 200L * 1024 * 1024
) {
    suspend fun download(url: String, dest: File, freeBytes: () -> Long): Long = withContext(Dispatchers.IO) {
        val part = File(dest.parentFile, dest.name + ".part")
        try {
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("empty body")
                val expected = body.contentLength()
                val needed = (if (expected > 0) expected else 12L * 1024 * 1024) + reserveBytes
                if (freeBytes() < needed) throw NoSpaceException()
                part.outputStream().use { out ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            out.write(buffer, 0, read)
                        }
                    }
                }
                val size = part.length()
                if (size == 0L) throw IOException("empty file")
                if (expected > 0 && size != expected) throw IOException("incomplete: $size/$expected")
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw IOException("rename failed")
                size
            }
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }
}
