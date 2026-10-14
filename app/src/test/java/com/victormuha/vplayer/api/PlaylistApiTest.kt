package com.victormuha.vplayer.api

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaylistApiTest {
    private lateinit var server: MockWebServer
    private val productionBase = "https://api.music.yandex.net"

    @Before fun setup() {
        server = MockWebServer()
        server.start()
        ApiClient.API_BASE = server.url("/").toString().removeSuffix("/")
        ApiClient.token = "fixture-token-not-real"
    }

    @After fun teardown() {
        ApiClient.API_BASE = productionBase
        ApiClient.token = null
        server.shutdown()
    }

    @Test fun `playlist list uses GET list suffix and owner uid`() {
        server.enqueue(MockResponse().setBody(
            """{"result":[{"kind":1000,"title":"Тест","trackCount":1,"owner":{"uid":987}}]}"""
        ))
        val done = CountDownLatch(1)
        var result: List<ApiClient.Playlist>? = null
        var error: String? = null
        ApiClient.playlistsList("123") { playlists, err -> result = playlists; error = err; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(error)
        assertEquals("987", result!!.single().uid)
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", request.method)
        assertEquals("/users/123/playlists/list", request.path)
        assertEquals("OAuth fixture-token-not-real", request.getHeader("Authorization"))
        assertNotNull(request.getHeader("X-Yandex-Music-Client"))
    }

    @Test fun `playlist without owner defaults to account uid`() {
        val playlists = PlaylistResponses.list("""{"result":[{"kind":42,"title":"Test"}]}""", "123")
        assertEquals("123", playlists.single().uid)
    }

    @Test fun `full track retains outer album id and needs no batch request`() {
        server.enqueue(MockResponse().setBody(
            """{"result":{"tracks":[{"id":"7","albumId":"80","track":{"id":"7","title":"Песня","artists":[{"name":"Автор"}],"albums":[{"id":81}],"durationMs":10000}}]}}"""
        ))
        val done = CountDownLatch(1)
        var result: List<ApiClient.Track>? = null
        var error: String? = null
        ApiClient.playlistTracks("987", "1000") { tracks, err -> result = tracks; error = err; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(error)
        assertEquals("7:80", result!!.single().trackId)
        assertEquals("/users/987/playlists/1000", server.takeRequest().path)
        assertEquals(1, server.requestCount)
    }

    @Test fun `short track entries are resolved rather than dropped`() {
        server.enqueue(MockResponse().setBody("""{"result":{"tracks":[{"id":"7","albumId":"80"}]}}"""))
        server.enqueue(MockResponse().setBody(
            """{"result":[{"id":"7","title":"Песня","artists":[{"name":"Автор"}],"durationMs":10000}]}"""
        ))
        val done = CountDownLatch(1)
        var result: List<ApiClient.Track>? = null
        var error: String? = null
        ApiClient.playlistTracks("987", "1000") { tracks, err -> result = tracks; error = err; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(error)
        assertEquals("7:80", result!!.single().trackId)
        assertEquals("Песня", result!!.single().title)
        server.takeRequest()
        val batch = server.takeRequest()
        assertEquals("POST", batch.method)
        assertEquals("/tracks", batch.path)
        assertTrue(batch.body.readUtf8().contains("track-ids=7%3A80"))
    }

    @Test fun `null album falls back to albums array and never becomes literal null`() {
        val tracks = PlaylistResponses.tracks(
            """{"result":{"tracks":[{"id":7,"albumId":null,"track":{"id":7,"title":"Test","albums":[{"id":80}]}}]}}"""
        )
        assertEquals("7:80", tracks.single().trackId)
    }

    @Test fun `HTTP 400 is an error not an empty playlist`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":{"name":"Bad Request"}}"""))
        val done = CountDownLatch(1)
        var result: List<ApiClient.Playlist>? = null
        var error: String? = null
        ApiClient.playlistsList("123") { playlists, err -> result = playlists; error = err; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNull(result)
        assertEquals("HTTP 400", error)
    }

    @Test fun `invalid successful JSON returns error without crashing callback thread`() {
        server.enqueue(MockResponse().setBody("<html>not json</html>"))
        val done = CountDownLatch(1)
        var error: String? = null
        ApiClient.playlistsList("123") { _, err -> error = err; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertNotNull(error)
    }

    @Test fun `empty playlist succeeds without metadata request`() {
        server.enqueue(MockResponse().setBody("""{"result":{"tracks":[]}}"""))
        val done = CountDownLatch(1)
        var result: List<ApiClient.Track>? = null
        ApiClient.playlistTracks("987", "1000") { tracks, _ -> result = tracks; done.countDown() }
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result!!.isEmpty())
        assertEquals(1, server.requestCount)
    }
}
