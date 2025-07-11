package com.yooastan.mokamusicplayer

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.lifecycleScope
import com.yooastan.mokamusicplayer.ui.*
import com.yooastan.mokamusicplayer.ui.player.ExoPlayerManager
import com.yooastan.mokamusicplayer.ui.theme.MokaMusicPlayerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MokaMusicPlayerTheme {
                val context = LocalContext.current

                var fileUri by remember { mutableStateOf<Uri?>(null) }
                var isPlaying by remember { mutableStateOf(false) }
                var albumArtBitmap by remember { mutableStateOf<ImageBitmap?>(null) }
                var progress by remember { mutableStateOf(0f) }
                var currentScreen by remember { mutableStateOf("main") }

                val exoPlayerManager = remember { ExoPlayerManager(context) }

                val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
                    uri?.let {
                        fileUri = it
                        exoPlayerManager.initPlayer(it)
                        isPlaying = true
                        exoPlayerManager.play()

                        // Extract album art
                        val retriever = MediaMetadataRetriever()
                        retriever.setDataSource(context, it)
                        val art = retriever.embeddedPicture
                        albumArtBitmap = if (art != null) {
                            BitmapFactory.decodeByteArray(art, 0, art.size).asImageBitmap()
                        } else {
                            null
                        }
                        retriever.release()

                        // Start progress tracking
                        lifecycleScope.launch {
                            while (true) {
                                val player = exoPlayerManager.getPlayer()
                                if (player != null && player.isPlaying) {
                                    val position = player.currentPosition.toFloat()
                                    val duration = player.duration.toFloat().coerceAtLeast(1f)
                                    progress = position / duration
                                }
                                delay(500)
                            }
                        }
                    }
                }

                when (currentScreen) {
                    "main" -> MainScreen(
                        albumArt = albumArtBitmap,
                        trackTitle = fileUri?.lastPathSegment ?: "No File",
                        artist = "Unknown Artist",
                        album = "Unknown Album",
                        isPlaying = isPlaying,
                        onPlayPause = {
                            if (exoPlayerManager.isPlaying()) {
                                exoPlayerManager.pause()
                                isPlaying = false
                            } else {
                                exoPlayerManager.play()
                                isPlaying = true
                            }
                        },
                        onPrev = { /* Future */ },
                        onNext = { /* Future */ },
                        progress = progress,
                        onSeek = {
                            val player = exoPlayerManager.getPlayer()
                            if (player != null) {
                                val duration = player.duration
                                val newPosition = (duration * it).toLong()
                                player.seekTo(newPosition)
                            }
                        },
                        filePath = fileUri?.toString() ?: "No file selected",
                        onPickFile = { launcher.launch("*/*") },
                        onLibraryClick = { currentScreen = "library" },
                        onDspClick = { currentScreen = "dsp" },
                        onSettingsClick = { currentScreen = "settings" }
                    )
                    "dsp" -> DspScreen(onBack = { currentScreen = "main" })
                    "library" -> LibraryScreen(onBack = { currentScreen = "main" })
                    "settings" -> SettingsScreen(onBack = { currentScreen = "main" })
                }
            }
        }
    }
}
