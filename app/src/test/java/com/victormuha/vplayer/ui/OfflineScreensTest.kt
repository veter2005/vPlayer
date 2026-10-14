package com.victormuha.vplayer.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.offline.*
import com.victormuha.vplayer.ui.screens.DownloadsPage
import com.victormuha.vplayer.ui.screens.HomePage
import com.victormuha.vplayer.ui.screens.TrackPage
import com.victormuha.vplayer.ui.screens.downloadControls
import com.victormuha.vplayer.ui.theme.VplayerTheme
import org.junit.Assert.assertTrue
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
class OfflineScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val tracks = (1..12).map { ApiClient.Track("$it", "9", "Трек $it", "Исполнитель") }

    @Test fun homeShowsOfflineBadgeAndHidesSearch() {
        compose.setContent { Viewport { HomePage(offline = true, downloadedPlaylists = 2) {} } }
        compose.onNodeWithTag("offline-badge").assertIsDisplayed()
        compose.onNodeWithText("Скачанное").assertIsDisplayed()
        compose.onNodeWithText("Поиск").assertDoesNotExist()
        insideSafeArea("offline-badge")
        screenshot("home-offline-466dp")
    }

    @Test fun downloadButtonStartsDownload() {
        var started = false
        compose.setContent {
            Viewport {
                TrackPage("Мне нравится · 12", tracks, false, null, {}, {},
                    header = { downloadControls(null, false, { started = true }, {}, {}) }) {}
            }
        }
        compose.onNodeWithText("⬇ Скачать для оффлайна").assertIsDisplayed().performClick()
        assertTrue(started)
        screenshot("liked-download-466dp")
    }

    @Test fun progressAndLocalMarksAreShown() {
        val entry = OfflinePlaylist("liked", "1", "liked", "Мне нравится", tracks.map { it.id }, SyncStatus.DOWNLOADING, 5, 12)
        var paused = false
        compose.setContent {
            Viewport {
                TrackPage("Мне нравится · 12", tracks, false, null, {}, {},
                    header = { downloadControls(entry, false, {}, { paused = true }, { error("must not delete") }) },
                    isLocal = { it.id.toInt() <= 5 }) {}
            }
        }
        compose.onNodeWithText("Загрузка 5/12").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("✓ Исполнитель", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        compose.onNodeWithText("Приостановить").assertIsDisplayed().performClick()
        assertTrue(paused)
        compose.onNodeWithText("Удалить загрузку").assertDoesNotExist()
        screenshot("liked-progress-466dp")
    }

    @Test fun offlineDoneHidesRefreshButKeepsDelete() {
        val entry = OfflinePlaylist("liked", "1", "liked", "Мне нравится", listOf("1"), SyncStatus.DONE, 12, 12)
        compose.setContent {
            Viewport {
                TrackPage("Мне нравится", tracks, false, null, {}, {},
                    header = { downloadControls(entry, true, {}, {}, {}) }) {}
            }
        }
        compose.onNodeWithText("✓ Скачано 12/12").assertIsDisplayed()
        compose.onNodeWithText("Нажмите, чтобы обновить").assertDoesNotExist()
        compose.onNodeWithText("Удалить загрузку").assertIsDisplayed()
    }

    @Test fun pausedShowsResumeAndDeleteNeedsConfirmation() {
        val entry = OfflinePlaylist("liked", "1", "liked", "Мне нравится", tracks.map { it.id }, SyncStatus.PAUSED, 5, 12)
        var resumed = false
        var removed = false
        compose.setContent {
            Viewport {
                TrackPage("Мне нравится · 12", tracks, false, null, {}, {},
                    header = { downloadControls(entry, false, { resumed = true }, {}, { removed = true }) },
                    isLocal = { it.id.toInt() <= 5 }) {}
            }
        }
        compose.onNodeWithText("Пауза · 5/12").assertIsDisplayed()
        insideSafeArea("round-list")
        screenshot("liked-paused-466dp")
        compose.onNodeWithText("▶ Продолжить загрузку").performClick()
        assertTrue(resumed)
        compose.onNodeWithText("Удалить загрузку").performClick()
        assertTrue("first tap only asks", !removed)
        compose.onNodeWithText("Точно удалить?").assertIsDisplayed().performClick()
        assertTrue(removed)
    }

    @Test fun downloadsLibraryListsAndScrollsToBack() {
        val lists = (1..10).associate { i ->
            "u:$i" to OfflinePlaylist("u:$i", "u", "$i", "Плейлист $i", listOf("$i"), SyncStatus.DONE, 8, 8)
        } + ("liked" to OfflinePlaylist("liked", "u", "liked", "Мне нравится", emptyList(), SyncStatus.PARTIAL, 3, 4))
        var opened: String? = null
        var back = false
        compose.setContent { Viewport(233f) { DownloadsPage(OfflineLibrary(playlists = lists), { k, _ -> opened = k }, { back = true }) } }
        compose.onNodeWithText("Мне нравится").assertIsDisplayed().performClick()
        assertTrue(opened == "liked")
        compose.onNodeWithTag("round-list").performScrollToIndex(12)
        compose.onNodeWithText("Назад").assertIsDisplayed().performClick()
        assertTrue(back)
        screenshot("downloads-233dp")
    }

    private fun insideSafeArea(tag: String) {
        val area = compose.onNodeWithTag("round-safe-area").getUnclippedBoundsInRoot()
        val b = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
        assertTrue(b.left >= area.left - 1.dp && b.right <= area.right + 1.dp)
    }

    @Composable private fun Viewport(diameter: Float = 466f, content: @Composable () -> Unit) {
        VplayerTheme(darkTheme = true) {
            Box(Modifier.requiredSize(diameter.dp).background(Color.Black).testTag("viewport")) { content() }
        }
    }

    private fun screenshot(name: String) {
        val file = File("build/reports/round-layout/$name.png")
        file.parentFile!!.mkdirs()
        val bounds = compose.onNodeWithTag("viewport").getUnclippedBoundsInRoot()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val density = compose.activity.resources.displayMetrics.density
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val cropped = android.graphics.Bitmap.createBitmap(
                bitmap, (bounds.left.value * density).toInt(), (bounds.top.value * density).toInt(),
                ((bounds.right - bounds.left).value * density).toInt(), ((bounds.bottom - bounds.top).value * density).toInt()
            )
            file.outputStream().use { cropped.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
