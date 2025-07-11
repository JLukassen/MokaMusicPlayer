package com.yooastan.mokamusicplayer.ui.components

import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth


@Composable
fun ProgressBar(progress: Float, onSeek: (Float) -> Unit) {
    Slider(
        value = progress,
        onValueChange = { onSeek(it) },
        modifier = Modifier.fillMaxWidth()
    )
}