package com.victormuha.vplayer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons

import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.victormuha.vplayer.api.ApiClient
import com.victormuha.vplayer.ui.viewmodels.MainViewModel
import com.victormuha.vplayer.ui.viewmodels.PlaybackViewModel
import com.victormuha.vplayer.player.service.PlaybackService
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Search

// ---------- Home ----------
@Composable
fun HomeScreen(
    mainViewModel: MainViewModel,
    playbackViewModel: PlaybackViewModel
) {
    val nav by mainViewModel.navigation.collectAsState()
    val navScreen = nav.screen
    val navPlaylist = nav.playlist
    val navTitle = nav.playlistTitle
    val token by mainViewModel.token.collectAsState()
    val uid by mainViewModel.uid.collectAsState()
    val loading by mainViewModel.loading.collectAsState()
    val error by mainViewModel.error.collectAsState()
    val deviceCode by mainViewModel.deviceCode.collectAsState()

    if (token == null) { LoginScreen(mainViewModel, playbackViewModel); return }
    if (uid == null) { LoginScreen(mainViewModel, playbackViewModel); return }

    RoundScreenScaffold(error = error, onDismissError = { mainViewModel.clearError() }) {
        when (navScreen) {
            "home" -> HomeContent(mainViewModel, playbackViewModel, navScreen)
            "liked" -> LikedScreenContent(mainViewModel, playbackViewModel)
            "playlists" -> PlaylistsScreenContent(mainViewModel, playbackViewModel)
            "playlist" -> PlaylistDetailScreenContent(mainViewModel, playbackViewModel, navPlaylist, navTitle)
            "search" -> SearchScreenContent(mainViewModel, playbackViewModel)
            "player" -> PlayerScreenContent(mainViewModel, playbackViewModel)
            "queue" -> QueueScreenContent(mainViewModel, playbackViewModel)
            "settings" -> SettingsScreenContent(mainViewModel, playbackViewModel)
            else -> HomeContent(mainViewModel, playbackViewModel, navScreen)
        }
    }
}

@Composable
fun RoundScreenScaffold(
    error: String?,
    onDismissError: () -> Unit,
    content: @Composable () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        content()
        if (error != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 20.dp)
                    .background(Color(0xFFCC0000), RoundedCornerShape(8.dp))
            ) {
                Text(
                    text = error.take(100),
                    color = Color.White,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

// ---------- Login ----------
@Composable
fun LoginScreen(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    val nav by mainViewModel.navigation.collectAsState()
    val token by mainViewModel.token.collectAsState()
    val deviceCode by mainViewModel.deviceCode.collectAsState()
    val loading by mainViewModel.loading.collectAsState()
    val error by mainViewModel.error.collectAsState()
    var manualToken by remember { mutableStateOf("") }
    var showManualTokenInput by remember { mutableStateOf(false) }

    // init() is called in MainActivity.onCreate — no need to re-init here

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showManualTokenInput) {
                Text("Введите токен", fontSize = 14.sp, color = Color.White)
                Spacer(Modifier.height(8.dp))
                TextField(
                    value = manualToken,
                    onValueChange = { manualToken = it },
                    modifier = Modifier.fillMaxWidth(0.7f),
                    placeholder = { Text("y0_...", fontSize = 11.sp) },
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    if (manualToken.isNotBlank()) {
                        mainViewModel.saveToken(manualToken.trim())
                        showManualTokenInput = false
                    }
                }) {
                    Text("Войти", fontSize = 14.sp)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { showManualTokenInput = false }) {
                    Text("Отмена", fontSize = 12.sp)
                }
            } else if (deviceCode == null) {
                Text("Вход в Яндекс.Музыку", fontSize = 14.sp, textAlign = TextAlign.Center, color = Color.White)
                Spacer(Modifier.height(16.dp))
                if (loading) {
                    CircularProgressIndicator()
                } else {
                    Button(onClick = { mainViewModel.startDeviceFlow() }) {
                        Text("Войти", fontSize = 14.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showManualTokenInput = true }) {
                        Text("Вставить токен", fontSize = 12.sp)
                    }
                }
            } else {
                Text("Откройте:", fontSize = 12.sp, color = Color.Gray)
                Text(deviceCode!!.verificationUrl, fontSize = 13.sp, color = Color(0xFFFFCC00))
                Spacer(Modifier.height(8.dp))
                Text("Код:", fontSize = 12.sp, color = Color.Gray)
                Text(deviceCode!!.userCode, fontSize = 20.sp, color = Color(0xFFFFCC00))
                Spacer(Modifier.height(12.dp))
                CircularProgressIndicator(Modifier.size(24.dp))
                Spacer(Modifier.height(8.dp))
                Button(onClick = { mainViewModel.cancelDeviceFlow() }) {
                    Text("Отмена", fontSize = 12.sp)
                }
            }
            if (error != null) {
                Spacer(Modifier.height(8.dp))
                Text(error!!.take(80), fontSize = 10.sp, color = Color(0xFFFF5252))
            }
        }
    }
}

// ---------- Home Content ----------
@Composable
fun HomeContent(
    mainViewModel: MainViewModel,
    playbackViewModel: PlaybackViewModel,
    navScreen: String
) {
    LaunchedEffect(Unit) {
        mainViewModel.loadPlaylists()
    }
    val playlists by mainViewModel.playlists.collectAsState()
    val loading by mainViewModel.loading.collectAsState()
    val uid by mainViewModel.uid.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 50.dp, bottom = 40.dp, start = 20.dp, end = 20.dp)
        ) {
            Text("vplayer", fontSize = 16.sp, color = Color(0xFFFFCC00))
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { mainViewModel.navigate("liked") }) {
                    Icon(Icons.Filled.Favorite, contentDescription = "Liked", tint = Color(0xFFFFCC00))
                }
                IconButton(onClick = { mainViewModel.navigate("playlists") }) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = "Playlists", tint = Color.White)
                }
                IconButton(onClick = { mainViewModel.navigate("search") }) {
                    Icon(Icons.Filled.Search, contentDescription = "Search", tint = Color.White)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (loading) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text("Мои плейлисты", fontSize = 12.sp, color = Color.Gray)
                    }
                    items(playlists) { pl ->
                        PlaylistRowItem(
                            title = pl.title,
                            subtitle = "${pl.trackCount} треков",
                            onClick = { mainViewModel.navigate("playlist", pl.kind, pl.title) }
                        )
                    }
                    item {
                        Text("Действия", fontSize = 12.sp, color = Color.Gray)
                    }
                    item {
                        PlaylistRowItem(
                            title = "Настройки",
                            subtitle = "",
                            onClick = { mainViewModel.navigate("settings") }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistRowItem(title: String, subtitle: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotEmpty()) {
                Text(subtitle, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
            }
        }
    }
}

// ---------- Liked ----------
@Composable
fun LikedScreenContent(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    LaunchedEffect(Unit) { mainViewModel.loadLikedTracks() }
    val tracks by mainViewModel.likedTracks.collectAsState()
    val loading by mainViewModel.loading.collectAsState()
    val uid by mainViewModel.uid.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 46.dp, start = 20.dp, end = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("Liked", fontSize = 14.sp, color = Color(0xFFFFCC00))
                Text("${tracks.size}", fontSize = 12.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(4.dp))
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (tracks.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                    Text("Пусто", fontSize = 14.sp, color = Color.Gray)
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 50.dp, bottom = 50.dp, start = 25.dp, end = 25.dp
                    )
                ) {
                    items(tracks.size) { idx ->
                        val t = tracks[idx]
                        TrackRowItem(
                            title = t.title,
                            artist = t.artist,
                            liked = t.liked,
                            onClick = {
                                playbackViewModel.setQueue(tracks, idx, uid ?: "")
                                mainViewModel.navigate("player")
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TrackRowItem(
    title: String,
    artist: String,
    liked: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = null,
                    tint = Color(0xFFFFCC00),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    title,
                    fontSize = 12.sp,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    contentDescription = "Like",
                    tint = if (liked) Color(0xFFFF5252) else Color.Gray,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(artist, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
        }
    }
}

// ---------- Playlists ----------
@Composable
fun PlaylistsScreenContent(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    LaunchedEffect(Unit) { mainViewModel.loadPlaylists() }
    val playlists by mainViewModel.playlists.collectAsState()
    val loading by mainViewModel.loading.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Playlists",
                fontSize = 14.sp,
                color = Color(0xFFFFCC00),
                modifier = Modifier.padding(top = 46.dp, start = 20.dp)
            )
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 50.dp, bottom = 50.dp, start = 25.dp, end = 25.dp
                    )
                ) {
                    items(playlists) { pl ->
                        PlaylistRowItem(
                            title = pl.title,
                            subtitle = "${pl.trackCount} треков",
                            onClick = { mainViewModel.navigate("playlist", pl.kind, pl.title) }
                        )
                    }
                }
            }
        }
    }
}

// ---------- Playlist Detail ----------
@Composable
fun PlaylistDetailScreenContent(
    mainViewModel: MainViewModel,
    playbackViewModel: PlaybackViewModel,
    playlistKind: String?,
    playlistTitle: String
) {
    val uid by mainViewModel.uid.collectAsState()
    val loading by mainViewModel.loading.collectAsState()
    val playlistTracks by mainViewModel.playlistTracks.collectAsState()
    val tracks = playlistKind?.let { playlistTracks[it] } ?: emptyList()

    LaunchedEffect(playlistKind) {
        playlistKind?.let { mainViewModel.loadPlaylistTracks(it) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                playlistTitle,
                fontSize = 14.sp,
                color = Color(0xFFFFCC00),
                modifier = Modifier.padding(top = 46.dp, start = 20.dp),
                maxLines = 1
            )
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 50.dp, bottom = 50.dp, start = 25.dp, end = 25.dp
                    )
                ) {
                    items(tracks.size) { idx ->
                        val t = tracks[idx]
                        TrackRowItem(
                            title = t.title,
                            artist = t.artist,
                            liked = t.liked,
                            onClick = {
                                playbackViewModel.setQueue(tracks, idx, uid ?: "")
                                mainViewModel.navigate("player")
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------- Search ----------
@Composable
fun SearchScreenContent(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    var searchText by remember { mutableStateOf("") }
    val results by mainViewModel.searchResults.collectAsState()
    val loading by mainViewModel.loading.collectAsState()
    val uid by mainViewModel.uid.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Search",
                fontSize = 14.sp,
                color = Color(0xFFFFCC00),
                modifier = Modifier.padding(top = 46.dp, start = 20.dp)
            )
            Spacer(Modifier.height(4.dp))
            TextField(
                value = searchText,
                onValueChange = {
                    searchText = it
                    mainViewModel.search(it)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp),
                placeholder = { Text("Название трека...", fontSize = 12.sp) },
                singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            if (loading) {
                Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        top = 50.dp, bottom = 50.dp, start = 25.dp, end = 25.dp
                    )
                ) {
                    items(results.size) { idx ->
                        val t = results[idx]
                        TrackRowItem(
                            title = t.title,
                            artist = t.artist,
                            liked = t.liked,
                            onClick = {
                                playbackViewModel.setQueue(results, idx, uid ?: "")
                                mainViewModel.navigate("player")
                            }
                        )
                    }
                }
            }
        }
    }
}

// ---------- Player ----------
@Composable
fun PlayerScreenContent(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    val state by playbackViewModel.state.collectAsState()
    val shuffle by playbackViewModel.shuffle.collectAsState()
    val repeat by playbackViewModel.repeat.collectAsState()
    val liked by playbackViewModel.liked.collectAsState()
    val uid by mainViewModel.uid.collectAsState()
    val queue by playbackViewModel.queue.collectAsState()


    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        when (val s = state) {
            is PlaybackService.PlayerState.Idle -> {
                Text("Ожидание", fontSize = 14.sp, color = Color.Gray)
            }
            is PlaybackService.PlayerState.Loading -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.title, fontSize = 14.sp, color = Color.White, maxLines = 1)
                    Text(s.artist, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
                    Spacer(Modifier.height(12.dp))
                    CircularProgressIndicator()
                }
            }
            is PlaybackService.PlayerState.Ready -> {
                val uidLocal = uid
                val queueLocal = queue
                val currentTrackId = queueLocal.items.getOrNull(queueLocal.currentIndex)?.trackId
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.title, fontSize = 14.sp, color = Color.White, maxLines = 1)
                    Text(s.artist, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
                    Spacer(Modifier.height(12.dp))
                    // Progress ring
                    Box(contentAlignment = Alignment.Center) {
                        val progress = if (s.durationMs > 0) s.positionMs.toFloat() / s.durationMs else 0f
                        CircularProgressInd(progress = progress)
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = Color(0xFFFFCC00),
                            modifier = Modifier.size(48.dp)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${formatTime(s.positionMs)} / ${formatTime(s.durationMs)}",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(4.dp))
                    // Seek slider
                    if (s.durationMs > 0) {
                        Slider(
                            value = s.positionMs.toFloat() / s.durationMs.toFloat(),
                            onValueChange = { fraction ->
                                playbackViewModel.seekTo((fraction * s.durationMs).toLong())
                            },
                            modifier = Modifier.fillMaxWidth(0.7f),
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFFCC00),
                                activeTrackColor = Color(0xFFFFCC00),
                                inactiveTrackColor = Color(0x33FFFFFF)
                            )
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    // Like / dislike / queue row
                    if (uidLocal != null && currentTrackId != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconButton(onClick = {
                                playbackViewModel.toggleLike(currentTrackId, uidLocal)
                            }) {
                                Icon(
                                    if (liked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    "like",
                                    tint = if (liked) Color(0xFFFF5252) else Color.Gray
                                )
                            }
                            IconButton(onClick = { mainViewModel.navigate("queue") }) {
                                Icon(Icons.Filled.QueueMusic, "queue", tint = Color.White)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        IconButton(onClick = { playbackViewModel.prev() }) {
                            Icon(Icons.Filled.SkipPrevious, "prev", tint = Color.White)
                        }
                        IconButton(onClick = { playbackViewModel.togglePlayPause() }) {
                            Icon(
                                if (s.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                "play/pause",
                                tint = Color(0xFFFFCC00),
                                modifier = Modifier.size(32.dp)
                            )
                        }
                        IconButton(onClick = { playbackViewModel.next() }) {
                            Icon(Icons.Filled.SkipNext, "next", tint = Color.White)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { playbackViewModel.setShuffle(!shuffle) }) {
                            Icon(
                                Icons.Filled.Shuffle, "shuffle",
                                tint = if (shuffle) Color(0xFFFFCC00) else Color.Gray
                            )
                        }
                        IconButton(onClick = { playbackViewModel.setRepeatMode(
                            if (repeat == PlaybackService.RepeatMode.OFF) PlaybackService.RepeatMode.ALL
                            else if (repeat == PlaybackService.RepeatMode.ALL) PlaybackService.RepeatMode.ONE
                            else PlaybackService.RepeatMode.OFF
                        ) }) {
                            Icon(
                                Icons.Filled.Repeat, "repeat",
                                tint = if (repeat != PlaybackService.RepeatMode.OFF) Color(0xFFFFCC00) else Color.Gray
                            )
                        }
                    }
                }
            }
            is PlaybackService.PlayerState.Error -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(s.message, fontSize = 12.sp, color = Color(0xFFFF5252))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { mainViewModel.goBack() }) { Text("Назад", fontSize = 12.sp) }
                }
            }
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}

@Composable
fun CircularProgressInd(progress: Float) {
    com.victormuha.vplayer.ui.components.CircularProgress(progress = progress)
}

// ---------- Queue ----------
@Composable
fun QueueScreenContent(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    val queue by playbackViewModel.queue.collectAsState()

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(
                "Queue",
                fontSize = 14.sp,
                color = Color(0xFFFFCC00),
                modifier = Modifier.padding(top = 46.dp, start = 20.dp)
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    top = 50.dp, bottom = 50.dp, start = 25.dp, end = 25.dp
                )
            ) {
                items(queue.items.size) { idx ->
                    val t = queue.items[idx]
                    TrackRowItem(
                        title = t.title,
                        artist = t.artist,
                        liked = false,
                        onClick = {
                            playbackViewModel.playIndex(idx)
                            mainViewModel.navigate("player")
                        }
                    )
                }
            }
        }
    }
}

// ---------- Settings ----------
@Composable
fun SettingsScreenContent(mainViewModel: MainViewModel, playbackViewModel: PlaybackViewModel) {
    val volume by playbackViewModel.volume.collectAsState()

    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Настройки", fontSize = 14.sp, color = Color(0xFFFFCC00))
            Spacer(Modifier.height(16.dp))
            Text("Volume: ${"%.0f".format(volume * 100)}%", fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { playbackViewModel.setVolume((volume - 0.1f).coerceAtLeast(0f)) }) {
                    Text("-", fontSize = 16.sp)
                }
                Button(onClick = { playbackViewModel.setVolume((volume + 0.1f).coerceAtMost(1f)) }) {
                    Text("+", fontSize = 16.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = { playbackViewModel.stop(); mainViewModel.logout() }) {
                Text("Выйти", fontSize = 12.sp)
            }
        }
    }
}
