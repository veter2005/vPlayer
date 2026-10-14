package com.victormuha.vplayer.player

import com.victormuha.vplayer.api.Api
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.player.service.QueueLogic
import com.victormuha.vplayer.player.service.TrackSource
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class QueueLogicTest {
    @Test fun localFileWinsEvenOnline() {
        val f = File("x.mp3")
        assertEquals(TrackSource.Local(f), QueueLogic.chooseSource(f, offline = false))
        assertEquals(TrackSource.Stream, QueueLogic.chooseSource(null, offline = false))
        assertEquals(TrackSource.Unavailable, QueueLogic.chooseSource(null, offline = true))
    }

    @Test fun stepRespectsBoundariesAndWrap() {
        assertEquals(1, QueueLogic.step(0, 3, true, false))
        assertNull(QueueLogic.step(2, 3, true, false))
        assertEquals(0, QueueLogic.step(2, 3, true, true))
        assertEquals(2, QueueLogic.step(0, 3, false, true))
        assertNull(QueueLogic.step(0, 0, true, true))
    }

    @Test fun offlineSkipsToNextDownloadedTrack() {
        val downloaded = setOf(1, 4)
        assertEquals(4, QueueLogic.findPlayable(5, 2, true, false) { it in downloaded })
        assertEquals(1, QueueLogic.findPlayable(5, 2, true, true) { it in downloaded - 4 })
        assertNull(QueueLogic.findPlayable(5, 2, true, false) { it in setOf(1) })
        assertNull(QueueLogic.findPlayable(5, 0, true, true) { false })
    }

    @Test fun shuffleKeepsCurrentFirstAndAllItems() {
        val order = QueueLogic.order(10, true, 7, kotlin.random.Random(1))
        assertEquals(7, order.first())
        assertEquals((0 until 10).toSet(), order.toSet())
        assertEquals((0 until 4).toList(), QueueLogic.order(4, false, 2))
    }

    @Test fun variantSelectionByQuality() {
        val v = listOf(
            ApiClient.DownloadVariant("mp3", 320, false, "u320", false),
            ApiClient.DownloadVariant("mp3", 192, false, "u192", false),
            ApiClient.DownloadVariant("mp3", 64, false, "u64", false),
            ApiClient.DownloadVariant("mp3", 320, true, "preview", false),
            ApiClient.DownloadVariant("aac", 256, false, "aac", false)
        )
        assertEquals("u320", Api.chooseVariant(v, economy = false)!!.downloadInfoUrl)
        assertEquals("u192", Api.chooseVariant(v, economy = true)!!.downloadInfoUrl)
        assertNull(Api.chooseVariant(v.filter { it.preview || it.codec != "mp3" }, false))
    }
}
