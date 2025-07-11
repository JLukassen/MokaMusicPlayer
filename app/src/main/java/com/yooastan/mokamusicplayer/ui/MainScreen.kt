package com.yooastan.mokamusicplayer.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.dp
import com.yooastan.mokamusicplayer.ui.components.AlbumArtBox
import com.yooastan.mokamusicplayer.ui.components.BottomNavigationBar
import com.yooastan.mokamusicplayer.ui.components.FilePathBox
import com.yooastan.mokamusicplayer.ui.components.PlaybackControlsRow
import com.yooastan.mokamusicplayer.ui.components.ProgressBar
import com.yooastan.mokamusicplayer.ui.components.TrackInfoBox

@Composable
fun MainScreen(
    albumArt: ImageBitmap?,
    trackTitle: String,
    artist: String,
    album: String,
    isPlaying: Boolean,
    hasPrevious: Boolean = true,
    hasNext: Boolean = true,
    onPlayPause: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    progress: Float,
    onSeek: (Float) -> Unit,
    filePath: String,
    onPickFile: () -> Unit = {},
    onLibraryClick: () -> Unit = {},
    onDspClick: () -> Unit = {},
    onSettingsClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Button(
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Pick File")
        }

        Spacer(modifier = Modifier.height(12.dp))

        AlbumArtBox(albumArt)

        Spacer(modifier = Modifier.height(12.dp))

        TrackInfoBox(trackTitle, artist, album)

        Spacer(modifier = Modifier.height(12.dp))

        PlaybackControlsRow(
            isPlaying = isPlaying,
            hasPrevious = hasPrevious,
            hasNext = hasNext,
            onPlayPause = onPlayPause,
            onPrev = onPrev,
            onNext = onNext,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(modifier = Modifier.height(12.dp))

        ProgressBar(progress, onSeek)

        Spacer(modifier = Modifier.height(8.dp))

        FilePathBox(filePath)

        Spacer(modifier = Modifier.weight(1f))

        BottomNavigationBar(
            onLibraryClick = onLibraryClick,
            onDspClick = onDspClick,
            onSettingsClick = onSettingsClick
        )
    }
}