package com.victormuha.vplayer.offline

import com.victormuha.vplayer.api.ApiClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OfflineTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var root: File

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        root = tmp.newFolder("offline")
    }

    @After fun tearDown() = server.shutdown()

    private fun audio(bytes: Int) = MockResponse().setBody(Buffer().write(ByteArray(bytes) { (it % 251).toByte() }))
    private fun track(id: String) = ApiClient.Track(id, "a$id", "Title $id", "Artist", 1000)

    @Test fun libraryIsPersistedAtomicallyAndReloaded() = runBlocking {
        val store = OfflineStore(root)
        store.update { it.copy(playlists = mapOf("liked" to OfflinePlaylist("liked", "1", "liked", "Мне нравится", listOf("1")))) }
        assertFalse(File(root, "library.json.tmp").exists())
        File(root, "1.mp3.part").writeText("partial")
        File(root, "library.json.tmp").writeText("{broken")
        val reloaded = OfflineStore(root)
        assertEquals("Мне нравится", reloaded.library.value.playlists["liked"]!!.title)
        assertTrue("partial file kept for resume", File(root, "1.mp3.part").exists())
        assertFalse(File(root, "library.json.tmp").exists())
    }

    @Test fun corruptLibraryFallsBackToEmpty() {
        File(root, "library.json").writeText("{not json")
        assertTrue(OfflineStore(root).library.value.playlists.isEmpty())
    }

    @Test fun downloaderWritesFileAndVerifiesLength() = runBlocking {
        server.enqueue(audio(150_000))
        val dest = File(root, "1.mp3")
        val size = TrackDownloader(reserveBytes = 0).download(server.url("/a.mp3").toString(), dest) { Long.MAX_VALUE }
        assertEquals(150_000L, size)
        assertEquals(150_000L, dest.length())
        assertFalse(File(root, "1.mp3.part").exists())
    }

    @Test fun interruptedDownloadResumesWithRange() = runBlocking {
        val full = ByteArray(200_000) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(Buffer().write(full)).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        val dest = File(root, "2.mp3")
        val part = File(root, "2.mp3.part")
        val first = runCatching {
            TrackDownloader(reserveBytes = 0).download(server.url("/b.mp3").toString(), dest) { Long.MAX_VALUE }
        }
        assertTrue(first.exceptionOrNull() is IOException)
        assertFalse("not visible as downloaded", dest.exists())
        val have = part.length()
        assertTrue("partial bytes kept: $have", have in 1 until full.size)
        server.takeRequest()

        server.enqueue(
            MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes $have-${full.size - 1}/${full.size}")
                .setBody(Buffer().write(full.copyOfRange(have.toInt(), full.size)))
        )
        val size = TrackDownloader(reserveBytes = 0).download(server.url("/b2.mp3").toString(), dest) { Long.MAX_VALUE }
        assertEquals("bytes=$have-", server.takeRequest().getHeader("Range"))
        assertEquals(full.size.toLong(), size)
        assertArrayEquals(full, dest.readBytes())
        assertFalse(part.exists())
    }

    @Test fun serverIgnoringRangeRestartsFile() = runBlocking {
        File(root, "4.mp3.part").writeBytes(ByteArray(777) { 9 })
        server.enqueue(audio(1_000))
        val dest = File(root, "4.mp3")
        assertEquals(1_000L, TrackDownloader(reserveBytes = 0).download(server.url("/d").toString(), dest) { Long.MAX_VALUE })
        assertEquals(0.toByte(), dest.readBytes()[0])
    }

    @Test fun httpErrorDiscardsPartialFile() = runBlocking {
        File(root, "5.mp3.part").writeBytes(ByteArray(100))
        server.enqueue(MockResponse().setResponseCode(416))
        val r = runCatching { TrackDownloader(reserveBytes = 0).download(server.url("/e").toString(), File(root, "5.mp3")) { Long.MAX_VALUE } }
        assertTrue(r.isFailure)
        assertFalse(File(root, "5.mp3.part").exists())
    }

    @Test fun pausedPlaylistKeepsFilesAndResumes() = runBlocking {
        val store = OfflineStore(root)
        val resolve: suspend (String) -> Pair<String, Int> = { server.enqueue(audio(500)); server.url("/x").toString() to 192 }
        // First run downloads only track 1 (track 2 fails), then the user pauses.
        var fail = true
        OfflineSync(store, TrackDownloader(reserveBytes = 0), { listOf(track("1"), track("2")) },
            { id -> if (fail && id.startsWith("2:")) throw IOException("x") else resolve(id) }).run("liked", "u", "liked", "L")
        store.setPlaylistStatus("liked", SyncStatus.PAUSED)
        File(root, "2.mp3.part").writeBytes(ByteArray(10))
        store.pruneOrphans()
        assertTrue("downloaded track kept on pause", store.isDownloaded("1"))
        assertTrue("partial of a playlist track kept", File(root, "2.mp3.part").exists())
        // A worker that was still running must not overwrite the paused state.
        assertEquals(SyncStatus.PAUSED, OfflineSync(store, TrackDownloader(reserveBytes = 0), { listOf(track("1")) }, resolve)
            .run("liked", "u", "liked", "L"))
        assertEquals(SyncStatus.PAUSED, store.library.value.playlists["liked"]!!.status)
        // Resume = re-enqueue (QUEUED), then sync finishes the rest.
        fail = false
        store.setPlaylistStatus("liked", SyncStatus.QUEUED)
        assertEquals(SyncStatus.DONE, OfflineSync(store, TrackDownloader(reserveBytes = 0), { listOf(track("1"), track("2")) },
            { id -> resolve(id) }).run("liked", "u", "liked", "L"))
        assertTrue(store.isDownloaded("2"))
    }

    @Test fun orphanPartialFilesArePruned() = runBlocking {
        val store = OfflineStore(root)
        File(root, "999.mp3.part").writeBytes(ByteArray(10))
        store.pruneOrphans()
        assertFalse(File(root, "999.mp3.part").exists())
    }

    @Test fun contentRangeParsing() {
        assertEquals(100L to 1000L, TrackDownloader.parseContentRange("bytes 100-999/1000"))
        assertNull(TrackDownloader.parseContentRange("bytes 100-999/*"))
        assertNull(TrackDownloader.parseContentRange(null))
    }

    @Test fun notEnoughSpaceIsReported() = runBlocking {
        server.enqueue(audio(10_000))
        val result = runCatching {
            TrackDownloader(reserveBytes = 1_000).download(server.url("/c.mp3").toString(), File(root, "3.mp3")) { 5_000 }
        }
        assertTrue(result.exceptionOrNull() is NoSpaceException)
    }

    @Test fun syncDownloadsRetriesAndPrunesRemovedTracks() = runBlocking {
        val store = OfflineStore(root)
        var remote = listOf(track("1"), track("2"), track("3"))
        var failId: String? = "2"
        val sync = OfflineSync(store, TrackDownloader(reserveBytes = 0),
            fetchTracks = { remote },
            resolveUrl = { id ->
                if (id.startsWith("$failId:")) throw IOException("no link")
                server.enqueue(audio(1000)); server.url("/$id").toString() to 320
            })

        assertEquals(SyncStatus.PARTIAL, sync.run("liked", "u", "liked", "Мне нравится"))
        assertTrue(store.isDownloaded("1:a1"))
        assertFalse(store.isDownloaded("2"))
        assertEquals(2, store.library.value.playlists["liked"]!!.done)
        assertEquals(3, store.library.value.tracksOf("liked").size)

        failId = null
        remote = listOf(track("1"), track("2")) // "3" removed from the playlist remotely
        assertEquals(SyncStatus.DONE, sync.run("liked", "u", "liked", "Мне нравится"))
        assertTrue(store.isDownloaded("2"))
        assertFalse("orphan file deleted", File(root, "3.mp3").exists())
        assertEquals(listOf("1", "2"), store.library.value.playlists["liked"]!!.trackIds)
    }

    @Test fun syncWithoutNetworkKeepsExistingDownload() = runBlocking {
        val store = OfflineStore(root)
        OfflineSync(store, TrackDownloader(reserveBytes = 0), { listOf(track("1")) },
            { server.enqueue(audio(500)); server.url("/1").toString() to 192 }).run("liked", "u", "liked", "L")
        val status = OfflineSync(store, TrackDownloader(reserveBytes = 0), { throw IOException("offline") },
            { throw IOException("offline") }).run("liked", "u", "liked", "L")
        assertEquals(SyncStatus.DONE, status)
        assertTrue(store.isDownloaded("1"))
    }

    @Test fun sharedTrackSurvivesRemovalOfOnePlaylist() = runBlocking {
        val store = OfflineStore(root)
        val resolve: suspend (String) -> Pair<String, Int> = { server.enqueue(audio(500)); server.url("/x").toString() to 192 }
        OfflineSync(store, TrackDownloader(reserveBytes = 0), { listOf(track("1")) }, resolve).run("liked", "u", "liked", "L")
        OfflineSync(store, TrackDownloader(reserveBytes = 0), { listOf(track("1"), track("5")) }, resolve).run("u:7", "u", "7", "P")
        store.removePlaylist("liked")
        assertTrue(store.isDownloaded("1"))
        store.removePlaylist("u:7")
        assertFalse(store.isDownloaded("1"))
        assertEquals(0L, store.usedBytes())
    }
}
