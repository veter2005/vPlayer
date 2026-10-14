package com.victormuha.vplayer.offline

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

class NoSpaceException : IOException("Недостаточно места")

/** Data from the server is wrong/unusable: the partial file must be discarded. */
private class CorruptDownloadException(message: String) : IOException(message)

/**
 * Streams a URL into `<dest>.part`, verifies the size and atomically renames it to [dest].
 *
 * Pausing (coroutine cancellation) or a network drop keeps the `.part` file; the next call
 * resumes it with an HTTP `Range` request. A fresh direct link for the same track is fine:
 * it points to the same file. If the server ignores Range (HTTP 200), the file restarts.
 */
class TrackDownloader(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val reserveBytes: Long = 200L * 1024 * 1024
) {
    suspend fun download(url: String, dest: File, freeBytes: () -> Long): Long = withContext(Dispatchers.IO) {
        val part = partFile(dest)
        val already = if (part.isFile) part.length() else 0L
        val request = Request.Builder().url(url)
            // identity: byte offsets must match the file on disk (OkHttp would otherwise gzip).
            .header("Accept-Encoding", "identity")
            .apply { if (already > 0) header("Range", "bytes=$already-") }
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val append: Boolean
                val expectedTotal: Long
                when {
                    response.code == 206 && already > 0 -> {
                        val range = parseContentRange(response.header("Content-Range"))
                        if (range == null || range.first != already) throw CorruptDownloadException("bad Content-Range")
                        append = true
                        expectedTotal = range.second
                    }
                    response.code == 416 -> throw CorruptDownloadException("range not satisfiable")
                    response.isSuccessful -> {
                        append = false
                        expectedTotal = response.body?.contentLength() ?: -1L
                    }
                    else -> throw CorruptDownloadException("HTTP ${response.code}")
                }
                val body = response.body ?: throw CorruptDownloadException("empty body")
                val remaining = body.contentLength()
                val needed = (if (remaining > 0) remaining else 12L * 1024 * 1024) + reserveBytes
                if (freeBytes() < needed) throw NoSpaceException()

                FileOutputStream(part, append).use { out ->
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
                if (size == 0L) throw CorruptDownloadException("empty file")
                if (expectedTotal > 0 && size != expectedTotal) {
                    // Short read = connection dropped: keep for resume. Longer = corrupt.
                    if (size < expectedTotal) throw IOException("incomplete: $size/$expectedTotal")
                    throw CorruptDownloadException("too long: $size/$expectedTotal")
                }
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) throw CorruptDownloadException("rename failed")
                size
            }
        } catch (e: CancellationException) {
            throw e // paused: keep .part
        } catch (e: NoSpaceException) {
            throw e // keep what we have; user can free space and resume
        } catch (e: CorruptDownloadException) {
            part.delete()
            throw e
        }
        // Other IOExceptions (timeouts, disconnects) propagate and keep the .part for resume.
    }

    companion object {
        fun partFile(dest: File) = File(dest.parentFile, dest.name + ".part")

        /** "bytes 100-999/1000" -> (100, 1000). Total "*" is not accepted. */
        internal fun parseContentRange(value: String?): Pair<Long, Long>? {
            val m = Regex("""bytes (\d+)-(\d+)/(\d+)""").find(value ?: return null) ?: return null
            return m.groupValues[1].toLong() to m.groupValues[3].toLong()
        }
    }
}
