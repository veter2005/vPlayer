package com.victormuha.vplayer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.player.service.PlaybackService
import com.victormuha.vplayer.ui.components.safeSquareSide
import com.victormuha.vplayer.ui.screens.PlaylistPage
import com.victormuha.vplayer.ui.screens.PlayerPanel
import com.victormuha.vplayer.ui.theme.VplayerTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w466dp-h466dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoundLayoutTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun playerFits466Dp() = playerFits(466f)
    @Test fun playerFits233Dp() = playerFits(233f)
    @Test
    @Config(sdk = [28], qualifiers = "w233dp-h233dp-xhdpi")
    fun playerFits466PixelsAt320Dpi() = playerFits(233f)

    private fun playerFits(diameter: Float) {
        var toggled = false
        compose.setContent {
            Viewport(diameter) {
                PlayerPanel(
                    PlaybackService.PlayerState.Ready(
                        "Длинное название композиции, которое не должно выходить за круг",
                        "Исполнитель с длинным именем", true, 60000, 240000,
                        false, PlaybackService.RepeatMode.OFF
                    ),
                    {}, { toggled = true }, {}, {}, {}
                )
            }
        }
        val area = compose.onNodeWithTag("round-safe-area").getUnclippedBoundsInRoot()
        for (tag in listOf("previous", "play-pause", "next", "home", "more")) {
            val node = compose.onNodeWithTag(tag).assertIsDisplayed()
            val bounds = node.getUnclippedBoundsInRoot()
            assertTrue("$tag left", bounds.left >= area.left - 1.dp)
            assertTrue("$tag right", bounds.right <= area.right + 1.dp)
            assertTrue("$tag top", bounds.top >= area.top - 1.dp)
            assertTrue("$tag bottom", bounds.bottom <= area.bottom + 1.dp)
        }
        compose.onNodeWithTag("play-pause").performClick()
        assertTrue(toggled)
        screenshot("player-${diameter.toInt()}dp-${compose.activity.resources.displayMetrics.densityDpi}dpi")
    }

    @Test fun playlistScrollReachesLastItemAndBackOnSmallScreen() {
        var selected = false
        var back = false
        compose.setContent {
            Viewport(233f) {
                PlaylistPage(
                    (1..20).map { ApiClient.Playlist("$it", "Плейлист номер $it", 30, "123") },
                    false, null, {}, { back = true }, { selected = true }
                )
            }
        }
        compose.onNodeWithTag("round-list").performScrollToIndex(20)
        compose.onNodeWithText("Плейлист номер 20").assertIsDisplayed().performClick()
        assertTrue(selected)
        compose.onNodeWithTag("round-list").performScrollToIndex(22)
        compose.onNodeWithText("Назад").assertIsDisplayed().performClick()
        assertTrue(back)
        screenshot("playlist-233dp")
    }

    @Test fun geometryCornersRemainInsideCircleAtMultipleDensities() {
        for (diameter in listOf(200f, 233f, 300f, 466f)) {
            val side = safeSquareSide(diameter)
            val cornerDistance = kotlin.math.sqrt(2f) * side / 2f
            assertTrue(cornerDistance <= diameter / 2f - 5.99f)
        }
    }

    @Composable private fun Viewport(diameter: Float, content: @Composable () -> Unit) {
        VplayerTheme(darkTheme = true) {
            Box(Modifier.requiredSize(diameter.dp).background(Color.Black).testTag("viewport")) {
                content()
            }
        }
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/round-layout/$name.png")
        file.parentFile!!.mkdirs()
        val bounds = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        // Robolectric does not service PixelCopy callbacks used by captureToImage.
        // Draw the real Android decor view to a software Canvas instead.
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val density = compose.activity.resources.displayMetrics.density
            val bitmap = android.graphics.Bitmap.createBitmap(
                view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888
            )
            view.draw(android.graphics.Canvas(bitmap))
            val cropped = android.graphics.Bitmap.createBitmap(
                bitmap, (bounds.left.value * density).toInt(), (bounds.top.value * density).toInt(),
                ((bounds.right - bounds.left).value * density).toInt(),
                ((bounds.bottom - bounds.top).value * density).toInt()
            )
            if (name.startsWith("player-")) {
                // Catch a full-screen opaque container accidentally painting over the ring.
                val visibleRing = (0 until (cropped.height * 0.08f).toInt()).any { y ->
                    android.graphics.Color.red(cropped.getPixel(cropped.width / 2, y)) > 100
                }
                assertTrue("Progress ring must be visible above player content", visibleRing)
            }
            file.outputStream().use {
                cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
