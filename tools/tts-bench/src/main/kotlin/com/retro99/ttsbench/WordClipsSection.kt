package com.retro99.ttsbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Isolated-word clips for the single-word listening check: plain vs trailing period, per engine. */
@Composable
fun WordClipsSection(lab: WordLab, player: SamplePlayer) {
    val scope = rememberCoroutineScope()
    val playing by player.playing.collectAsState()
    var engine by remember { mutableStateOf(WordEngine.SUPERTONIC) }
    val clips = remember { mutableStateMapOf<WordEngine, List<WordClip>>() }
    var busy by remember { mutableStateOf(false) }

    Text("Word clips", style = MaterialTheme.typography.titleSmall)
    Text(
        "Isolated words per engine, plain and with a trailing period (the clipped-tail check).",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WordEngine.entries.forEach { candidate ->
            if (candidate == engine) {
                Button(onClick = { engine = candidate }) { Text(candidate.id) }
            } else {
                OutlinedButton(onClick = { engine = candidate }) { Text(candidate.id) }
            }
        }
    }
    Button(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                player.stop()
                clips[engine] = lab.prepareWordClips(engine)
                busy = false
            }
        },
    ) { Text(if (busy) "Working…" else "Prepare word clips · ${engine.id}") }

    clips[engine].orEmpty().groupBy { clip -> clip.word }.forEach { (word, variants) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                word,
                modifier = Modifier.width(110.dp),
                style = MaterialTheme.typography.bodySmall,
            )
            variants.forEach { clip ->
                val key = "${engine.id}/${clip.file.name}"
                OutlinedButton(onClick = { player.toggle(key, clip.file) }) {
                    Text(if (playing == key) "■ ${clip.text}" else "▶ ${clip.text}")
                }
            }
        }
    }
}
