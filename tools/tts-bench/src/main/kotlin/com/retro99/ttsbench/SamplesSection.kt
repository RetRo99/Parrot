package com.retro99.ttsbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Saved runs: pick one, then tap rows to hear them, one at a time. */
@Composable
fun SamplesSection(store: SampleStore, player: SamplePlayer) {
    val version by store.version.collectAsState()
    val playing by player.playing.collectAsState()
    var runs by remember { mutableStateOf<List<SampleRun>>(emptyList()) }
    var pickedName by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(version) {
        runs = withContext(Dispatchers.IO) { store.listRuns() }
    }
    val selected = runs.firstOrNull { run -> run.stamp == pickedName } ?: runs.firstOrNull()

    Text("Samples", style = MaterialTheme.typography.titleSmall)
    if (selected == null) {
        Text("No saved runs yet. Start a Samples run.", style = MaterialTheme.typography.bodySmall)
        return
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedButton(onClick = { menuOpen = true }) { Text(runTitle(selected)) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            runs.forEach { run ->
                DropdownMenuItem(
                    text = { Text(runTitle(run)) },
                    onClick = {
                        player.stop()
                        pickedName = run.stamp
                        menuOpen = false
                    },
                )
            }
        }
        OutlinedButton(onClick = { confirmDelete = true }) { Text("Delete run") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete run?") },
            text = { Text("${runTitle(selected)} and its WAV files will be removed.") },
            confirmButton = {
                Button(
                    onClick = {
                        player.stop()
                        store.delete(selected)
                        pickedName = null
                        confirmDelete = false
                    },
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    val groups = selected.rows.groupBy { row -> row.passage }
    val passageOrder = PASSAGES.map { passage -> passage.name } + KIND_LISTENING
    passageOrder.filter { name -> name in groups }.forEach { name ->
        val rows = groups.getValue(name)
        Text(
            "${name.replaceFirstChar { char -> char.uppercase() }}: ${rows.first().passageText}",
            style = MaterialTheme.typography.bodySmall,
        )
        rowOrder(rows).forEach { row ->
            val key = "${selected.stamp}/${row.file}@${row.speed}/${row.label}"
            SampleRowItem(
                row = row,
                playing = playing == key,
                onClick = { player.toggle(key, selected.dir.resolve(row.file), row.speed) },
            )
        }
    }
}

/** Supertonic 8, 6, 4 steps then Kokoro, threads ascending; listening keeps its own order. */
private fun rowOrder(rows: List<SampleRow>): List<SampleRow> =
    if (rows.first().kind == KIND_LISTENING) {
        rows
    } else {
        rows.sortedWith(
            compareBy<SampleRow>({ row -> row.model != ModelKind.SUPERTONIC.id })
                .thenByDescending { row -> row.steps }
                .thenBy { row -> row.threads },
        )
    }

private fun runTitle(run: SampleRun): String = "${run.stamp} · ${run.mode} · ${run.rows.size}"

@Composable
private fun SampleRowItem(row: SampleRow, playing: Boolean, onClick: () -> Unit) {
    val background = if (playing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(background)
            .clickable(onClick = onClick)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (playing) "■" else "▶", modifier = Modifier.width(20.dp))
        Column {
            Text(row.displayLabel(), style = MaterialTheme.typography.bodyMedium)
            Text(
                "%.1f s audio · %.1f s to generate · r %.2f".format(
                    Locale.US,
                    row.audioMs / row.speed / MS_PER_SECOND,
                    row.generationMs / MS_PER_SECOND,
                    row.ratio,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private const val MS_PER_SECOND = 1000.0
