package com.victormuha.vplayer.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.player.service.PlaybackService
import com.victormuha.vplayer.ui.components.CircularProgress
import com.victormuha.vplayer.ui.components.RoundPage
import com.victormuha.vplayer.ui.components.RoundScreen
import com.victormuha.vplayer.ui.viewmodels.MainViewModel
import com.victormuha.vplayer.ui.viewmodels.PlaybackViewModel

private val Accent = Color(0xFFFFCC00)

@Composable
fun HomeScreen(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    val nav by mainViewModel.navigation.collectAsState()
    BackHandler(nav.screen != "home") { mainViewModel.goBack() }
    when (nav.screen) {
        "liked" -> LikedScreenContent(mainViewModel, playbackViewModel)
        "playlists" -> PlaylistsScreenContent(mainViewModel, playbackViewModel)
        "playlist" -> PlaylistDetailScreenContent(mainViewModel, playbackViewModel)
        "search" -> SearchScreenContent(mainViewModel, playbackViewModel)
        "player" -> PlayerScreenContent(mainViewModel, playbackViewModel)
        "player-actions" -> PlayerActions(mainViewModel, playbackViewModel)
        "queue" -> QueueScreenContent(mainViewModel, playbackViewModel)
        "settings" -> SettingsScreenContent(mainViewModel, playbackViewModel)
        else -> RoundPage("vPlayer") {
            item { MenuButton("Мне нравится") { mainViewModel.navigate("liked") } }
            item { MenuButton("Плейлисты") { mainViewModel.navigate("playlists") } }
            item { MenuButton("Поиск") { mainViewModel.navigate("search") } }
            item { MenuButton("Плеер") { mainViewModel.navigate("player") } }
            item { MenuButton("Настройки") { mainViewModel.navigate("settings") } }
        }
    }
}

@Composable
private fun MenuButton(title: String, subtitle: String = "", onClick: () -> Unit) {
    Button(
        onClick, Modifier.fillMaxWidth().heightIn(min = 44.dp),
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 7.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF202020), contentColor = Color.White)
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            if (subtitle.isNotBlank()) {
                Text(subtitle, color = Color.LightGray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun LazyListScope.status(
    loading: Boolean, error: String?, empty: Boolean, onRetry: () -> Unit
) {
    if (loading) item("loading") { CircularProgressIndicator(Modifier.size(24.dp), color = Accent) }
    if (error != null) {
        item("error") {
            Text(error, fontSize = 11.sp, color = Color(0xFFFF8080), textAlign = TextAlign.Center)
        }
        item("retry") { MenuButton("Повторить", onClick = onRetry) }
    } else if (!loading && empty) {
        item("empty") { Text("Список пуст", color = Color.LightGray, fontSize = 12.sp) }
    }
}

@Composable
fun LoginScreen(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    val deviceCode by mainViewModel.deviceCode.collectAsState()
    val loading by mainViewModel.loading.collectAsState()
    val error by mainViewModel.error.collectAsState()
    var manual by remember { mutableStateOf(false) }
    var token by remember { mutableStateOf("") }
    BackHandler(manual || deviceCode != null) {
        manual = false
        mainViewModel.cancelDeviceFlow()
    }
    RoundPage("Яндекс.Музыка") {
        if (manual) {
            item {
                OutlinedTextField(
                    token, { token = it }, Modifier.fillMaxWidth(),
                    placeholder = { Text("OAuth-токен", fontSize = 11.sp) },
                    visualTransformation = PasswordVisualTransformation(), singleLine = true
                )
            }
            item { MenuButton("Войти") { if (token.isNotBlank()) mainViewModel.saveToken(token.trim()) } }
            item { MenuButton("Отмена") { manual = false } }
        } else if (deviceCode == null) {
            item { MenuButton("Войти по коду") { mainViewModel.startDeviceFlow() } }
            item { MenuButton("Вставить токен") { manual = true } }
        } else {
            item { Text("Откройте на телефоне:", color = Color.LightGray, fontSize = 11.sp) }
            item { Text(deviceCode!!.verificationUrl, color = Accent, fontSize = 12.sp, textAlign = TextAlign.Center) }
            item { Text(deviceCode!!.userCode, color = Accent, fontSize = 20.sp) }
            item { Text("Ожидание подтверждения", color = Color.LightGray, fontSize = 10.sp) }
            item { MenuButton("Отмена") { mainViewModel.cancelDeviceFlow() } }
        }
        if (loading) item { CircularProgressIndicator(Modifier.size(24.dp), color = Accent) }
        if (error != null) item { Text(error!!, color = Color(0xFFFF8080), fontSize = 11.sp) }
    }
}

@Composable
fun LikedScreenContent(main: MainViewModel, playback: PlaybackViewModel) {
    val tracks by main.likedTracks.collectAsState()
    val loading by main.loading.collectAsState()
    val error by main.error.collectAsState()
    val uid by main.uid.collectAsState()
    LaunchedEffect(uid) { main.loadLikedTracks() }
    TrackPage(
        "Мне нравится · ${tracks.size}", tracks, loading, error,
        { main.loadLikedTracks() }, { main.goBack() }
    ) { index ->
        playback.setQueue(tracks, index, uid ?: "")
        main.navigate("player")
    }
}

@Composable
fun PlaylistsScreenContent(main: MainViewModel, playback: PlaybackViewModel) {
    val playlists by main.playlists.collectAsState()
    val loading by main.loading.collectAsState()
    val error by main.error.collectAsState()
    val uid by main.uid.collectAsState()
    LaunchedEffect(uid) { main.loadPlaylists() }
    PlaylistPage(playlists, loading, error, { main.loadPlaylists() }, { main.goBack() }) { playlist ->
        main.navigate("playlist", playlist.kind, playlist.title, playlist.uid)
    }
}

/** Separate presentation function is exercised by round-screen layout regression tests. */
@Composable
internal fun PlaylistPage(
    playlists: List<ApiClient.Playlist>, loading: Boolean, error: String?,
    onRetry: () -> Unit, onBack: () -> Unit, onSelect: (ApiClient.Playlist) -> Unit
) {
    RoundPage("Плейлисты") {
        status(loading, error, playlists.isEmpty(), onRetry)
        items(playlists, key = { "${it.uid}:${it.kind}" }) { playlist ->
            MenuButton(playlist.title, "${playlist.trackCount} треков") { onSelect(playlist) }
        }
        item("refresh") { MenuButton("Обновить", onClick = onRetry) }
        item("back") { MenuButton("Назад", onClick = onBack) }
    }
}

@Composable
fun PlaylistDetailScreenContent(main: MainViewModel, playback: PlaybackViewModel) {
    val nav by main.navigation.collectAsState()
    val uid by main.uid.collectAsState()
    val owner = nav.playlistOwner ?: uid ?: ""
    val kind = nav.playlist ?: ""
    val allTracks by main.playlistTracks.collectAsState()
    val tracks = allTracks["$owner:$kind"] ?: emptyList()
    val loading by main.loading.collectAsState()
    val error by main.error.collectAsState()
    LaunchedEffect(owner, kind) { main.loadPlaylistTracks(kind, owner) }
    TrackPage(
        nav.playlistTitle, tracks, loading, error,
        { main.loadPlaylistTracks(kind, owner) }, { main.goBack() }
    ) { index ->
        playback.setQueue(tracks, index, uid ?: "")
        main.navigate("player")
    }
}

@Composable
private fun TrackPage(
    title: String, tracks: List<ApiClient.Track>, loading: Boolean, error: String?,
    onRetry: () -> Unit, onBack: () -> Unit, onSelect: (Int) -> Unit
) {
    RoundPage(title) {
        status(loading, error, tracks.isEmpty(), onRetry)
        items(tracks.size) { index ->
            val track = tracks[index]
            MenuButton(track.title.ifBlank { track.id }, track.artist) { onSelect(index) }
        }
        item("refresh") { MenuButton("Обновить", onClick = onRetry) }
        item("back") { MenuButton("Назад", onClick = onBack) }
    }
}

@Composable
fun SearchScreenContent(main: MainViewModel, playback: PlaybackViewModel) {
    var text by remember { mutableStateOf("") }
    val results by main.searchResults.collectAsState()
    val loading by main.loading.collectAsState()
    val error by main.error.collectAsState()
    val uid by main.uid.collectAsState()
    RoundPage("Поиск") {
        item {
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("Название трека", fontSize = 11.sp) }, singleLine = true
            )
        }
        item { MenuButton("Найти") { main.search(text) } }
        status(loading, error, results.isEmpty() && text.isNotBlank(), { main.search(text) })
        items(results.size) { index ->
            val track = results[index]
            MenuButton(track.title, track.artist) {
                playback.setQueue(results, index, uid ?: "")
                main.navigate("player")
            }
        }
        item { MenuButton("Назад") { main.goBack() } }
    }
}

@Composable
fun PlayerScreenContent(main: MainViewModel, playback: PlaybackViewModel) {
    val state by playback.state.collectAsState()
    when (val current = state) {
        is PlaybackService.PlayerState.Ready -> PlayerPanel(
            current, { playback.prev() }, { playback.togglePlayPause() }, { playback.next() },
            { main.navigate("player-actions") }, { main.navigate("home") }
        )
        is PlaybackService.PlayerState.Loading -> RoundPage("Загрузка") {
            item { Text(current.title, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center) }
            item { CircularProgressIndicator(Modifier.size(24.dp), color = Accent) }
            item { MenuButton("Следующий") { playback.next() } }
            item { MenuButton("Домой") { main.navigate("home") } }
        }
        is PlaybackService.PlayerState.Error -> RoundPage("Ошибка воспроизведения") {
            item { Text(current.message, color = Color(0xFFFF8080), fontSize = 11.sp) }
            item { MenuButton("Следующий") { playback.next() } }
            item { MenuButton("Домой") { main.navigate("home") } }
        }
        PlaybackService.PlayerState.Idle -> RoundPage("Плеер") {
            item { Text("Выберите трек", color = Color.LightGray, fontSize = 12.sp) }
            item { MenuButton("Мне нравится") { main.navigate("liked") } }
            item { MenuButton("Домой") { main.navigate("home") } }
        }
    }
}

@Composable
internal fun PlayerPanel(
    state: PlaybackService.PlayerState.Ready, onPrev: () -> Unit, onToggle: () -> Unit,
    onNext: () -> Unit, onMore: () -> Unit, onHome: () -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color.Black), Alignment.Center) {
        val diameter = minOf(maxWidth, maxHeight)
        val scale = (diameter.value / 233f).coerceIn(0.85f, 2f)
        val progress = if (state.durationMs > 0) state.positionMs.toFloat() / state.durationMs else 0f
        // Ring is an overlay outside the inscribed safe square, not a 200dp column element.
        CircularProgress(progress, Modifier.size(diameter * 0.93f).testTag("progress-ring"))
        RoundScreen(background = Color.Transparent) {
            Column(
                Modifier.fillMaxSize(), verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    state.title, color = Color.White, fontSize = 14.sp * scale, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    state.artist, color = Color.LightGray, fontSize = 11.sp * scale, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center
                )
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceEvenly, Alignment.CenterVertically) {
                    PlayerButton(Icons.Default.SkipPrevious, "Предыдущий", "previous", onPrev, scale = scale)
                    PlayerButton(
                        if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        "Воспроизвести / пауза", "play-pause", onToggle, highlighted = true, scale = scale
                    )
                    PlayerButton(Icons.Default.SkipNext, "Следующий", "next", onNext, scale = scale)
                }
                Text(
                    "${formatTime(state.positionMs)} / ${formatTime(state.durationMs)}",
                    color = Color.LightGray, fontSize = 11.sp * scale
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    TextButton(onHome, Modifier.height(32.dp * scale).testTag("home"), contentPadding = PaddingValues(0.dp)) {
                        Text("Домой", fontSize = 11.sp * scale, color = Accent)
                    }
                    TextButton(onMore, Modifier.height(32.dp * scale).testTag("more"), contentPadding = PaddingValues(0.dp)) {
                        Text("Ещё", fontSize = 11.sp * scale, color = Accent)
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerButton(
    icon: ImageVector, label: String, tag: String, onClick: () -> Unit,
    highlighted: Boolean = false, scale: Float = 1f
) {
    IconButton(
        onClick, Modifier.size(44.dp * scale).testTag(tag).background(
            if (highlighted) Accent else Color(0xFF202020), CircleShape
        )
    ) {
        Icon(icon, label, Modifier.size(26.dp * scale), tint = if (highlighted) Color.Black else Color.White)
    }
}

@Composable
private fun PlayerActions(main: MainViewModel, playback: PlaybackViewModel) {
    val state by playback.state.collectAsState()
    val queue by playback.queue.collectAsState()
    val liked by playback.liked.collectAsState()
    val shuffle by playback.shuffle.collectAsState()
    val repeat by playback.repeat.collectAsState()
    val volume by playback.volume.collectAsState()
    val uid by main.uid.collectAsState()
    val track = queue.items.getOrNull(queue.currentIndex)
    RoundPage("Управление") {
        if (state is PlaybackService.PlayerState.Ready) {
            val ready = state as PlaybackService.PlayerState.Ready
            item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Перемотка", color = Color.LightGray, fontSize = 12.sp)
                    var fraction by remember(ready.title) { mutableStateOf<Float?>(null) }
                    Slider(
                        value = fraction ?: (ready.positionMs.toFloat() / ready.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f),
                        onValueChange = { fraction = it },
                        onValueChangeFinished = {
                            fraction?.let { playback.seekTo((it * ready.durationMs).toLong()) }
                            fraction = null
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
        if (track != null && uid != null) item {
            MenuButton(if (liked) "Убрать лайк" else "Мне нравится") { playback.toggleLike(track.trackId, uid!!) }
        }
        item { MenuButton("Очередь") { main.navigate("queue") } }
        item { MenuButton(if (shuffle) "Перемешать: вкл." else "Перемешать: выкл.") { playback.setShuffle(!shuffle) } }
        item {
            MenuButton("Повтор: " + when (repeat) {
                PlaybackService.RepeatMode.OFF -> "выкл."
                PlaybackService.RepeatMode.ALL -> "все"
                PlaybackService.RepeatMode.ONE -> "один"
            }) {
                playback.setRepeatMode(when (repeat) {
                    PlaybackService.RepeatMode.OFF -> PlaybackService.RepeatMode.ALL
                    PlaybackService.RepeatMode.ALL -> PlaybackService.RepeatMode.ONE
                    PlaybackService.RepeatMode.ONE -> PlaybackService.RepeatMode.OFF
                })
            }
        }
        item { VolumeControl(volume, { playback.setVolume(it) }) }
        item { MenuButton("Назад к плееру") { main.navigate("player") } }
    }
}

@Composable
fun QueueScreenContent(main: MainViewModel, playback: PlaybackViewModel) {
    val queue by playback.queue.collectAsState()
    RoundPage("Очередь") {
        items(queue.items.size) { index ->
            val track = queue.items[index]
            MenuButton((if (index == queue.currentIndex) "▶ " else "") + track.title, track.artist) {
                playback.playIndex(index)
                main.navigate("player")
            }
        }
        item { MenuButton("Назад") { main.navigate("player") } }
    }
}

@Composable
private fun VolumeControl(volume: Float, onChange: (Float) -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Громкость ${(volume * 100).toInt()}%", color = Color.LightGray, fontSize = 12.sp)
        Row {
            IconButton({ onChange((volume - 0.1f).coerceAtLeast(0f)) }) { Text("−", color = Accent, fontSize = 22.sp) }
            IconButton({ onChange((volume + 0.1f).coerceAtMost(1f)) }) { Text("+", color = Accent, fontSize = 22.sp) }
        }
    }
}

@Composable
fun SettingsScreenContent(main: MainViewModel, playback: PlaybackViewModel) {
    val volume by playback.volume.collectAsState()
    val config = LocalConfiguration.current
    val density = LocalDensity.current.density
    RoundPage("Настройки") {
        item { VolumeControl(volume, { playback.setVolume(it) }) }
        item {
            Text(
                "v0.2.1\n${config.screenWidthDp} × ${config.screenHeightDp} dp\n" +
                    "Плотность ${"%.2f".format(density)}",
                color = Color.Gray, fontSize = 10.sp, textAlign = TextAlign.Center
            )
        }
        item { MenuButton("Выйти из аккаунта") { playback.stop(); main.logout() } }
        item { MenuButton("Назад") { main.goBack() } }
    }
}

fun formatTime(ms: Long): String {
    val seconds = ms.coerceAtLeast(0) / 1000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
