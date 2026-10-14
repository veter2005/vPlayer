package com.victormuha.vplayer.player

import android.content.Intent
import androidx.media3.session.MediaSessionService
import com.victormuha.vplayer.player.service.PlaybackService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PlaybackServiceTest {
    @Test fun localBindAndMediaSessionBindAreBothServed() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        val local = service.onBind(Intent(PlaybackService.ACTION_LOCAL_BIND))
        assertTrue("UI gets the local binder", local is PlaybackService.PlaybackBinder)
        val session = service.onBind(Intent(MediaSessionService.SERVICE_INTERFACE))
        assertNotNull("Media3 controllers still get the session binder", session)
        assertFalse(session is PlaybackService.PlaybackBinder)
        controller.destroy()
    }

    @Test fun setQueueStartsServiceSoUnbindDoesNotKillPlayback() {
        val controller = Robolectric.buildService(PlaybackService::class.java).create()
        val service = controller.get()
        service.setQueue(listOf(PlaybackService.TrackItem("1:2", "T", "A", 1000)), 0, "u")
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val started = shadowOf(service).nextStartedService
        assertNotNull("service must call startService on itself", started)
        assertEquals(PlaybackService::class.java.name, started.component!!.className)
        controller.destroy()
    }
}
