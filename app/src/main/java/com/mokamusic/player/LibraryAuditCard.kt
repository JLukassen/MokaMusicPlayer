package com.mokamusic.player

import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
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
 * Explicit deletion of verified copies or suspected duplicates (never automatic).
 * Suspected matches carry a separate confirmation, since MP3/FLAC versions may differ.
 * Every candidate group must retain one track; Android always requests deletion consent.
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
    var selectedIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var pendingDelete by remember { mutableStateOf<List<MusicTrack>?>(null) }
    var acknowledgeUnverified by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val deleteLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { response ->
        if (response.resultCode == Activity.RESULT_OK) {
            report = null
            review = null
            selectedIds = emptySet()
            notice = "Android approved deletion. Refreshing library."
            onLibraryChanged()
        } else {
            notice = "Deletion cancelled; music was not removed by Moka."
        }
    }

    LaunchedEffect(tracks) {
        review = null
        report = null
        selectedIds = emptySet()
        pendingDelete = null
    }

    pendingDelete?.let { targets ->
        val verifiedIds = review?.exact.orEmpty().flatMap { it.tracks }.map { it.id }.toSet()
        val unverified = targets.filter { it.id !in verifiedIds }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete ${targets.size} selected file(s)?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text("Review the precise files to remove. Other copies are preserved. " +
                        "Deletion may be permanent; Android will ask for final permission.")
                    if (unverified.isNotEmpty()) {
                        Text(
                            "${unverified.size} selected track(s) are SUSPECTED matches only. " +
                                "They are NOT confirmed identical. Different encodings, masters " +
                                "and recordings may contain unique audio; keep your preferred copy.",
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Checkbox(
                                checked = acknowledgeUnverified,
                                onCheckedChange = { acknowledgeUnverified = it }
                            )
                            Text(
                                "I reviewed these suspected tracks and want to remove my " +
                                    "selected files despite the risk.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(7.dp)
                    ) {
                        targets.forEach { track ->
                            Text("${track.artist} — ${track.title}", fontWeight = FontWeight.SemiBold)
                            Text("${track.relativePath.orEmpty()}${track.displayName}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val groups = review?.let { inspected ->
                        inspected.exact.map { group -> group.tracks.map { it.id } } +
                            inspected.possible.map { group -> group.map { it.id } }
                    }.orEmpty()
                    val confirmedIds = targets.map { it.id }.toSet()
                    val missingWarning = unverified.isNotEmpty() && !acknowledgeUnverified
                    pendingDelete = null
                    if (confirmedIds != selectedIds ||
                        !DuplicateSelectionPolicy.valid(groups, confirmedIds)) {
                        notice = "Selection changed. Run the deep scan and choose files again."
                    } else if (missingWarning) {
                        notice = "Confirm the warning for suspected matches before deleting."
                    } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                        notice = "Deletion requires Android 11 or later in this beta."
                    } else {
                        runCatching {
                            val request = MediaStore.createDeleteRequest(
                                context.contentResolver, targets.map { it.uri }
                            )
                            deleteLauncher.launch(
                                IntentSenderRequest.Builder(request.intentSender).build()
                            )
                        }.onFailure { error ->
                            notice = "Unable to request deletion: ${error.message}"
                        }
                    }
                }, enabled = unverified.isEmpty() || acknowledgeUnverified) {
                    Text("Continue to Android confirmation")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
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
                selectedIds = emptySet()
                acknowledgeUnverified = false
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
                val groups = result.exact.map { group -> group.tracks.map { it.id } } +
                    result.possible.map { group -> group.map { it.id } }
                val verifiedIds = result.exact.flatMap { it.tracks }.map { it.id }.toSet()
                val selectedTracks = (result.exact.flatMap { it.tracks } +
                    result.possible.flatten()).distinctBy { it.uri.toString() }
                    .filter { it.id in selectedIds }
                val unverifiedCount = selectedTracks.count { it.id !in verifiedIds }
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
                            val checked = track.id in selectedIds
                            val allowed = !busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                (checked || DuplicateSelectionPolicy.canSelect(
                                    groups, selectedIds, track.id
                                ))
                            Row(
                                modifier = Modifier.fillMaxWidth().selectable(
                                    selected = checked,
                                    enabled = allowed,
                                    onClick = {
                                        selectedIds = if (checked) selectedIds - track.id
                                            else selectedIds + track.id
                                    }
                                ),
                                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = null,
                                    enabled = allowed
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("${track.artist} — ${track.title}")
                                    Text("${track.relativePath.orEmpty()}${track.displayName}",
                                        style = MaterialTheme.typography.bodySmall)
                                    Text("${track.formatLabel} · ${track.sizeBytes} bytes",
                                        style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                        Text(
                            "Select copies to delete. At least one is kept in this group.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        HorizontalDivider()
                    }
                    if (result.possible.isNotEmpty()) {
                        Text("${result.possible.size} possible match groups (NOT verified)",
                            fontWeight = FontWeight.SemiBold)
                        Text(
                            "These tracks may NOT be the same recording. Review the formats, " +
                                "paths and filenames yourself before choosing any files to remove.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                        result.possible.take(30).forEachIndexed { index, group ->
                            Text("Suspected group ${index + 1}",
                                fontWeight = FontWeight.SemiBold)
                            group.forEach { track ->
                                val checked = track.id in selectedIds
                                val allowed = !busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                    (checked || DuplicateSelectionPolicy.canSelect(
                                        groups, selectedIds, track.id
                                    ))
                                Row(
                                    modifier = Modifier.fillMaxWidth().selectable(
                                        selected = checked,
                                        enabled = allowed,
                                        onClick = {
                                            selectedIds = if (checked) selectedIds - track.id
                                                else selectedIds + track.id
                                        }
                                    ),
                                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        onCheckedChange = null,
                                        enabled = allowed
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("${track.artist} — ${track.title}")
                                        Text("${track.relativePath.orEmpty()}${track.displayName}",
                                            style = MaterialTheme.typography.bodySmall)
                                        Text("${track.formatLabel} · ${track.sizeBytes} bytes",
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                            HorizontalDivider()
                        }
                        if (result.possible.size > 30) Text("Showing first 30 suspected groups")
                    }
                }
                if (result.exact.isNotEmpty() || result.possible.isNotEmpty()) {
                    Text("${selectedIds.size} selected for deletion (max " +
                        "${DuplicateSelectionPolicy.MAX_FILES_PER_REQUEST} per request)",
                        fontWeight = FontWeight.SemiBold)
                    if (unverifiedCount > 0) {
                        Text(
                            "${unverifiedCount} selected file(s) are only suspected matches. " +
                                "Deletion needs an extra explicit warning confirmation.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            enabled = !busy && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                DuplicateSelectionPolicy.valid(groups, selectedIds),
                            onClick = {
                                acknowledgeUnverified = false
                                pendingDelete = selectedTracks.toList()
                            }
                        ) { Text("Review & delete ${selectedIds.size} selected") }
                        OutlinedButton(
                            enabled = selectedIds.isNotEmpty(),
                            onClick = { selectedIds = emptySet() }
                        ) { Text("Clear selection") }
                    }
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
                    Text("Deletion is disabled on Android 10 and earlier in this beta.")
                Text(
                    "Verified copies and suspected matches can both be selected manually. " +
                        "Suspected matches are NOT proven duplicates. No tracks are preselected; " +
                        "Android requests deletion permission for your chosen batch.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
