package com.victormuha.vplayer.offline

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Offline library: metadata in `library.json` (written atomically via tmp+rename),
 * audio in `<id>.mp3` files in app-private storage (no storage permission required).
 */
class OfflineStore(val root: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val libFile = File(root, LIBRARY)
    private val mutex = Mutex()

    init {
        root.mkdirs()
        // Partial .part files are kept for resume; only an interrupted library write is dropped.
        File(root, "$LIBRARY.tmp").delete()
    }

    private val _library = MutableStateFlow(load())
    val library: StateFlow<OfflineLibrary> = _library.asStateFlow()

    private fun load(): OfflineLibrary =
        runCatching { json.decodeFromString(OfflineLibrary.serializer(), libFile.readText()) }
            .getOrDefault(OfflineLibrary())

    private fun persist(lib: OfflineLibrary) {
        val tmp = File(root, "$LIBRARY.tmp")
        tmp.writeText(json.encodeToString(OfflineLibrary.serializer(), lib))
        if (!tmp.renameTo(libFile)) {
            libFile.delete()
            check(tmp.renameTo(libFile)) { "Cannot write offline library" }
        }
    }

    suspend fun update(transform: (OfflineLibrary) -> OfflineLibrary): OfflineLibrary = mutex.withLock {
        val next = transform(_library.value)
        withContext(Dispatchers.IO) { persist(next) }
        _library.value = next
        next
    }

    fun fileFor(trackId: String): File = File(root, sanitize(trackId.substringBefore(':')) + ".mp3")

    /** Downloaded file for a track id ("id" or "id:albumId"), or null if missing/corrupt. */
    fun localFile(trackId: String): File? {
        val track = _library.value.tracks[trackId.substringBefore(':')] ?: return null
        val name = track.fileName ?: return null
        return File(root, name).takeIf { it.isFile && it.length() > 0 }
    }

    fun isDownloaded(trackId: String) = localFile(trackId) != null

    suspend fun setPlaylistStatus(key: String, status: SyncStatus, message: String? = null) {
        update { lib ->
            val p = lib.playlists[key] ?: return@update lib
            lib.copy(playlists = lib.playlists + (key to p.copy(status = status, message = message)))
        }
    }

    suspend fun removePlaylist(key: String) {
        update { it.copy(playlists = it.playlists - key) }
        pruneOrphans()
    }

    suspend fun clearAll() = mutex.withLock {
        withContext(Dispatchers.IO) {
            root.listFiles()?.filter { it.name.endsWith(".mp3") || it.name.endsWith(".part") }?.forEach { it.delete() }
            persist(OfflineLibrary())
        }
        _library.value = OfflineLibrary()
    }

    /** Removes tracks/files not referenced by any playlist. Returns removed file count. */
    suspend fun pruneOrphans(): Int = mutex.withLock {
        val lib = _library.value
        val referenced = lib.playlists.values.flatMap { it.trackIds }.toSet()
        val kept = lib.tracks.filterKeys { it in referenced }
        val next = lib.copy(tracks = kept)
        val names = kept.values.mapNotNull { it.fileName }.toSet()
        val partNames = kept.keys.map { fileFor(it).name + ".part" }.toSet()
        withContext(Dispatchers.IO) {
            if (next != lib) persist(next)
            val orphans = root.listFiles()?.filter {
                (it.name.endsWith(".mp3") && it.name !in names) || (it.name.endsWith(".part") && it.name !in partNames)
            } ?: emptyList()
            orphans.forEach { it.delete() }
            _library.value = next
            orphans.size
        }
    }

    fun usedBytes(): Long =
        root.listFiles()?.filter { it.name.endsWith(".mp3") || it.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L
    fun freeBytes(): Long = root.usableSpace

    companion object {
        private const val LIBRARY = "library.json"
        @Volatile private var instance: OfflineStore? = null

        fun get(context: Context): OfflineStore = instance ?: synchronized(this) {
            instance ?: OfflineStore(File(context.applicationContext.filesDir, "offline")).also { instance = it }
        }

        internal fun sanitize(id: String) = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
    }
}
