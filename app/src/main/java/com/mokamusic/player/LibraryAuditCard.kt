package com.mokamusic.player

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mokamusic.player.data.*
import com.mokamusic.player.model.MusicTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Duplicate removal is only offered after a full SHA-256 match.
 * Each selection requires two distinct actions: Moka's review dialog and Android's
 * system MediaStore deletion consent. No automatic or bulk deletion.
 */
@Composable
internal fun LibraryAuditCard(
    tracks: List<MusicTrack>,
    onLibraryChanged: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<LibraryAuditReport?>(null) }
    var expanded by remember { mutableStateOf<AuditCategory?>(null) }
    var review by remember { mutableStateOf<DuplicateReview?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingDelete by remember { mutableStateOf<MusicTrack?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { response ->
        if (response.resultCode == Activity.RESULT_OK) {
            report = null
            review = null
            notice = "Android approved deletion. Refreshing library."
            onLibraryChanged()
        } else {
            notice = "Deletion cancelled; music was not removed by Moka."
        }
    }

    LaunchedEffect(tracks) {
        review = null
        report = null
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this verified duplicate?") },
            text = {
                Text(
                    "Remove only this copy?\\n\\n" +
                        "${target.artist} — ${target.title}\\n" +
                        "${target.relativePath.orEmpty()}${target.displayName}\\n\\n" +
                        "Confirm the file again in Android's system dialog. " +
                        "This may permanently remove it; it is not an automatic cleanup."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        notice = "Deletion requires Android 11 or later in this beta."
                    } else {
                        runCatching {
                            val permission = MediaStore.createDeleteRequest(
                                context.contentResolver, listOf(target.uri)
                            )
                            deleteLauncher.launch(IntentSenderRequest.Builder(permission.intentSender).build())
                        }.onFailure { error ->
                            notice = "Unable to request deletion: ${error.message}"
                        }
                    }
                }) { Text("Continue to Android confirmation") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Keep file") }
            }
        )
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Text("Library cleanup · review & verified duplicates",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(
                "Quick audit reviews metadata. Deep scan also compares normalized filenames, " +
                    "title/artist, approximate duration and file size, then hashes matching-size " +
                    "files with SHA-256. Different encodings are only suggestions for review.",
                style = MaterialTheme.typography.bodySmall
            )
            Button(enabled = !busy && tracks.isNotEmpty(), onClick = {
                busy = true
                scope.launch {
                    report = withContext(Dispatchers.Default) {
                        LibraryAuditor.analyze(tracks.toList())
                    }
                    expanded = null
                    busy = false
                }
            }) { Text(if (busy) "Working…" else "Quick audit ${tracks.size} tracks") }

            Button(enabled = !busy && tracks.isNotEmpty(), onClick = {
                busy = true
                review = null
                notice = null
                progress = null
                scope.launch {
                    try {
                        review = DuplicateVerifier.scan(context, tracks.toList()) { complete, total ->
                            withContext(Dispatchers.Main) { progress = complete to total }
                        }
                    } catch (error: Exception) {
                        notice = "Deep scan failed: ${error.message}"
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Scanning…" else "Deep duplicate scan (read-only)") }
            progress?.let { (done, total) ->
                Text("Checked ${done}/${total} same-size files",
                    style = MaterialTheme.typography.bodySmall)
                if (busy && total > 0) LinearProgressIndicator(
                    progress = { done.toFloat() / total.toFloat() },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            notice?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

            report?.let { found ->
                for (category in AuditCategory.entries) {
                    val count = found.count(category)
                    OutlinedButton(modifier = Modifier.fillMaxWidth(),
                        onClick = { expanded = if (expanded == category) null else category }) {
                        Text("${category.label}: ${count}")
                    }
                    if (expanded == category) {
                        found.examples(category).forEach { issue ->
                            Text("${issue.track.artist} — ${issue.track.title}",
                                fontWeight = FontWeight.SemiBold)
                            Text(issue.description, style = MaterialTheme.typography.bodySmall)
                            HorizontalDivider()
                        }
                        if (count > 12) Text("Showing first 12 of ${count}",
                            style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            review?.let { result ->
                val confirmed = result.exact.sumOf { it.tracks.size - 1 }
                Text("${result.exact.size} exact duplicate groups · ${confirmed} extra copies",
                    fontWeight = FontWeight.Bold)
                Text(
                    "${result.hashedFiles} files hashed; ${result.unreadableFiles} could not be verified. " +
                        "Metadata-only matches are NOT verified duplicates.",
                    style = MaterialTheme.typography.bodySmall
                )
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    result.exact.forEachIndexed { index, group ->
                        Text("Verified group ${index + 1}: ${group.tracks.size} identical files",
                            fontWeight = FontWeight.SemiBold)
                        Text("SHA-256 ${group.sha256.take(16)}…", style = MaterialTheme.typography.bodySmall)
                        group.tracks.forEach { track ->
                            Text("${track.artist} — ${track.title}")
                            Text("${track.relativePath.orEmpty()}${track.displayName}",
                                style = MaterialTheme.typography.bodySmall)
                            OutlinedButton(
                                enabled = !busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R,
                                onClick = { pendingDelete = track }
                            ) { Text("Review deleting this copy") }
                        }
                        HorizontalDivider()
                    }
                    if (result.possible.isNotEmpty()) {
                        Text("${result.possible.size} possible matches (not verified)",
                            fontWeight = FontWeight.SemiBold)
                        result.possible.take(30).forEach { group ->
                            Text(group.joinToString(" / ") { it.displayName },
                                style = MaterialTheme.typography.bodySmall)
                        }
                        if (result.possible.size > 30) Text("Showing first 30 possible groups")
                    }
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                    Text("Deletion is disabled on Android 10 and earlier in this beta.")
                Text(
                    "Only files proven identical byte-for-byte can be selected for deletion. " +
                        "The system asks for permission for each file. Never delete your only copy.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
