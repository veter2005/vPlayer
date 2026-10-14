package com.victormuha.vplayer.offline

import android.os.Looper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadServiceTest {
    @Test fun startsInForegroundImmediatelyAndStopsWhenQueueEmpty() {
        val controller = Robolectric.buildService(DownloadService::class.java).create().startCommand(0, 1)
        val shadow = shadowOf(controller.get())
        assertNotNull("startForeground within onStartCommand", shadow.lastForegroundNotification)
        val deadline = System.currentTimeMillis() + 10_000
        while (!shadow.isStoppedBySelf && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        assertTrue("service stops itself when nothing is queued", shadow.isStoppedBySelf)
        controller.destroy()
    }
}
