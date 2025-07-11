package com.yooastan.mokamusicplayer.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier

@Composable
fun SliderSetting(label: String, value: Float, min: Float, max: Float) {
    var sliderValue by remember { mutableStateOf(value) }
    Column {
        Text("$label: ${"%.2f".format(sliderValue)}")
        Slider(
            value = sliderValue,
            onValueChange = { sliderValue = it },
            valueRange = min..max,
            modifier = Modifier.fillMaxWidth()
        )
    }
}