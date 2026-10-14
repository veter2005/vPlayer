package com.victormuha.vplayer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.victormuha.vplayer.player.service.PlaybackService
import com.victormuha.vplayer.ui.screens.HomeScreen
import com.victormuha.vplayer.ui.screens.LoginScreen
import com.victormuha.vplayer.ui.theme.VplayerTheme
import com.victormuha.vplayer.ui.viewmodels.MainViewModel
import com.victormuha.vplayer.ui.viewmodels.PlaybackViewModel

private fun Context.bindServiceAutoCreate(intent: Intent, connection: ServiceConnection) {
    bindService(intent, connection, Context.BIND_AUTO_CREATE)
}

class MainActivity : ComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels()
    private val playbackViewModel: PlaybackViewModel by viewModels()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val sb = binder as? PlaybackService.PlaybackBinder ?: return
            playbackViewModel.onServiceConnected(sb.service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            playbackViewModel.onServiceDisconnected()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        // Init ViewModel with application context (loads saved token, device_id, account)
        mainViewModel.init(applicationContext)

        setContent {
            VplayerTheme(darkTheme = true) {
                MainApp(mainViewModel, playbackViewModel)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        bindServiceAutoCreate(
            Intent(this, PlaybackService::class.java),
            serviceConnection
        )
    }

    override fun onStop() {
        super.onStop()
        unbindService(serviceConnection)
        playbackViewModel.onServiceDisconnected()
    }
}

@Composable
fun MainApp(
    mainViewModel: MainViewModel,
    playbackViewModel: PlaybackViewModel
) {
    val nav by mainViewModel.navigation.collectAsState()
    val token by mainViewModel.token.collectAsState()

    // Route based on nav + token. LoginScreen handles auth; HomeScreen handles all sub-screens.
    if (nav.screen == "login" || token == null) {
        LoginScreen(mainViewModel, playbackViewModel)
    } else {
        HomeScreen(mainViewModel, playbackViewModel)
    }
}
