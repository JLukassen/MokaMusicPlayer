package com.yooastan.mokamusicplayer.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yooastan.mokamusicplayer.ui.components.SliderSetting

@Composable
fun DspScreen(onBack: () -> Unit = {}) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("DSP Control Panel", style = MaterialTheme.typography.titleLarge)

        Spacer(modifier = Modifier.height(16.dp))

        // 🟢 Output Control
        Text("Output Control", style = MaterialTheme.typography.titleMedium)
        SliderSetting(label = "Limiter Threshold", value = -17f, min = -40f, max = 0f)
        SliderSetting(label = "Limiter Release", value = 90f, min = 0f, max = 200f)
        SliderSetting(label = "Post Gain", value = 0f, min = -10f, max = 10f)

        Spacer(modifier = Modifier.height(24.dp))

        // 🟠 Multimodal EQ
        Text("Multimodal EQ (Placeholder)", style = MaterialTheme.typography.titleMedium)
        Text("Future: Add graphic or parametric EQ sliders here.")

        Spacer(modifier = Modifier.height(24.dp))

        // 🟣 Convolver
        Text("Convolver (Placeholder)", style = MaterialTheme.typography.titleMedium)
        Text("Future: Add IR file loader and waveform editor here.")

        Spacer(modifier = Modifier.height(24.dp))

        // 🔵 Live Programmable DSP
        Text("Live Programmable DSP (Placeholder)", style = MaterialTheme.typography.titleMedium)
        Text("Future: Add scripting UI and parameter editors here.")

        Spacer(modifier = Modifier.weight(1f))

        Button(onClick = { /* TODO: Apply DSP settings */ }, modifier = Modifier.fillMaxWidth()) {
            Text("Apply DSP Settings")
        }
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) {
            Text("Back")
        }
    }
}