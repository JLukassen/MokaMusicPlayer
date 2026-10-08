package com.mokamusic.player

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mokamusic.player.data.*
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun LibraryAuditCard(tracks: List<MusicTrack>) {
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<LibraryAuditReport?>(null) }
    var expanded by remember { mutableStateOf<AuditCategory?>(null) }
    var busy by remember { mutableStateOf(false) }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Library cleanup · review only", style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold)
            Text("Detect possible duplicates, missing tags and suspicious entries. " +
                "No deletion or tag editing is performed.", style = MaterialTheme.typography.bodySmall)
            Button(enabled = !busy && tracks.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    report = withContext(Dispatchers.Default) { LibraryAuditor.analyze(tracks.toList()) }
                    expanded = null
                    busy = false
                }
            }) { Text(if (busy) "Checking…" else "Audit ${tracks.size} tracks") }
            report?.let { found ->
                for (category in AuditCategory.entries) {
                    val count = found.count(category)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(),
                        onClick = { expanded = if (expanded == category) null else category }) {
                        Text("${category.label}: $count")
                    }
                    if (expanded == category) {
                        found.examples(category).forEach { issue ->
                            Text("${issue.track.artist} — ${issue.track.title}",
                                fontWeight = FontWeight.SemiBold)
                            Text(issue.description, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                        if (count > 12) Text("Showing first 12 of $count",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
                Text("Matching metadata is not proof of identical audio. " +
                    "Cover art and file accessibility aren't checked here.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
