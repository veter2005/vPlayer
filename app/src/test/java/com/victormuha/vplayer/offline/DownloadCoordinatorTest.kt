package com.victormuha.vplayer.offline

import com.victormuha.vplayer.api.ApiClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DownloadCoordinatorTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var store: OfflineStore

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        store = OfflineStore(tmp.newFolder("offline"))
    }

    @After fun tearDown() = server.shutdown()

    private fun track(id: String) = ApiClient.Track(id, "a", "T$id", "A", 1000)

    private suspend fun queue(key: String, ids: List<String>, at: Long) = store.update { lib ->
        lib.copy(playlists = lib.playlists + (key to OfflinePlaylist(key, "u", key, key, ids, SyncStatus.QUEUED, updatedAt = at)))
    }

    private fun sync(ids: Map<String, List<String>>, gate: CompletableDeferred<Unit>? = null, resolves: AtomicInteger? = null) =
        { p: OfflinePlaylist ->
            OfflineSync(store, TrackDownloader(reserveBytes = 0), { ids.getValue(p.key).map(::track) }, { _ ->
                resolves?.incrementAndGet()
                gate?.await()
                server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(300))))
                server.url("/f").toString() to 192
            })
        }

    @Test fun drainsAllQueuedPlaylistsInOrder() = runBlocking {
        queue("liked", listOf("1", "2"), 1)
        queue("u:5", listOf("3"), 2)
        val done = DownloadCoordinator.runPending(store, { false }, false, { _, _, _ -> },
            sync(mapOf("liked" to listOf("1", "2"), "u:5" to listOf("3"))))
        assertTrue(done)
        assertEquals(SyncStatus.DONE, store.library.value.playlists["liked"]!!.status)
        assertEquals(SyncStatus.DONE, store.library.value.playlists["u:5"]!!.status)
        assertTrue(listOf("1", "2", "3").all { store.isDownloaded(it) })
    }

    @Test fun offlineLeavesQueueForLater() = runBlocking {
        queue("liked", listOf("1"), 1)
        assertFalse(DownloadCoordinator.runPending(store, { true }, false, { _, _, _ -> }, sync(mapOf("liked" to listOf("1")))))
        assertEquals(SyncStatus.QUEUED, store.library.value.playlists["liked"]!!.status)
    }

    @Test fun secondRunnerDoesNotDownloadTwiceAndServiceTakesOver() = runBlocking(Dispatchers.IO) {
        queue("liked", listOf("1", "2"), 1)
        val gate = CompletableDeferred<Unit>()
        val resolves = AtomicInteger()
        val ids = mapOf("liked" to listOf("1", "2"))
        val worker = async { DownloadCoordinator.runPending(store, { false }, false, { _, _, _ -> }, sync(ids, gate, resolves)) }
        while (DownloadCoordinator.activeKey.value == null) delay(10)
        // Worker-style call returns immediately, no parallel writes.
        assertTrue(DownloadCoordinator.runPending(store, { false }, false, { _, _, _ -> }, sync(ids, gate, resolves)))
        // Service-style call waits; the system then stops the worker mid-download.
        val service = async { DownloadCoordinator.runPending(store, { false }, true, { _, _, _ -> }, sync(ids, null, resolves)) }
        delay(100)
        worker.cancel()
        withTimeout(10_000) { assertTrue(service.await()) }
        assertEquals(SyncStatus.DONE, store.library.value.playlists["liked"]!!.status)
        assertTrue(store.isDownloaded("1") && store.isDownloaded("2"))
    }

    @Test fun pauseStopsCurrentPlaylistKeepsFilesAndContinuesWithNext() = runBlocking(Dispatchers.IO) {
        queue("liked", listOf("1", "2", "3"), 1)
        queue("u:5", listOf("9"), 2)
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val factory = { p: OfflinePlaylist ->
            OfflineSync(store, TrackDownloader(reserveBytes = 0),
                { (if (p.key == "liked") listOf("1", "2", "3") else listOf("9")).map(::track) },
                { id ->
                    if (p.key == "liked" && ++calls > 1) gate.await() // block on track 2
                    server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(300))))
                    server.url("/$id").toString() to 192
                })
        }
        val run = async { DownloadCoordinator.runPending(store, { false }, false, { _, _, _ -> }, factory) }
        while (!store.isDownloaded("1")) delay(10)
        store.setPlaylistStatus("liked", SyncStatus.PAUSED)
        DownloadCoordinator.cancelAndWait("liked")
        assertTrue(withTimeout(10_000) { run.await() })
        val liked = store.library.value.playlists["liked"]!!
        assertEquals(SyncStatus.PAUSED, liked.status)
        assertTrue("downloaded track kept", store.isDownloaded("1"))
        assertFalse(store.isDownloaded("2"))
        assertEquals("next playlist still downloaded", SyncStatus.DONE, store.library.value.playlists["u:5"]!!.status)
    }
}
