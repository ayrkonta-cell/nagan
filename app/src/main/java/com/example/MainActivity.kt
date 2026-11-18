package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.data.local.LocalPlaylistRepository
import com.example.data.local.MusicDatabase
import com.example.data.remote.JioSaavnClient
import com.example.data.remote.MultiSourceMusicRepository
import com.example.data.remote.SoundCloudClient
import com.example.player.MusicPlayerController
import com.example.player.YouTubeStreamResolver
import com.example.ui.MainViewModel
import com.example.ui.screens.MusicAppScreen
import com.example.ui.theme.TzlilimTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TzlilimTheme(darkTheme = true) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MusicAppRoot()
                }
            }
        }
    }
}

@Composable
private fun MusicAppRoot() {
    val context = LocalContext.current
    val soundCloudClient = remember { SoundCloudClient() }
    val jioSaavnClient = remember { JioSaavnClient() }
    val playerController = remember(soundCloudClient, jioSaavnClient) {
        MusicPlayerController(
            context = context.applicationContext,
            soundCloudClient = soundCloudClient,
            youTubeStreamResolver = YouTubeStreamResolver(
                soundCloudClient = soundCloudClient,
                jioSaavnClient = jioSaavnClient
            )
        )
    }

    DisposableEffect(playerController) {
        onDispose {
            playerController.release()
        }
    }

    val viewModel: MainViewModel = viewModel(
        factory = viewModelFactory {
            initializer {
                val app = checkNotNull(this[APPLICATION_KEY]) {
                    "APPLICATION_KEY missing from CreationExtras"
                }
                val database = MusicDatabase.getInstance(app)
                MainViewModel(
                    playlistRepository = LocalPlaylistRepository(database.playlistDao()),
                    musicRepository = MultiSourceMusicRepository(
                        soundCloudClient = soundCloudClient,
                        jioSaavnClient = jioSaavnClient
                    ),
                    playerController = playerController,
                    defaultPlaylistName = app.getString(R.string.default_playlist_name),
                    defaultPlaylistDesc = app.getString(R.string.default_playlist_desc)
                )
            }
        }
    )

    MusicAppScreen(viewModel = viewModel)
}
