package com.retro99.ttsbench

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

object BenchHolder {
    @Volatile
    private var controller: BenchController? = null

    fun get(context: Context): BenchController = controller ?: synchronized(this) {
        controller ?: BenchController(context).also { created -> controller = created }
    }
}

/**
 * Start a run over adb, then read the results from logcat (tag TtsBench) or the CSV:
 *
 *     adb shell am start -n com.retro99.ttsbench/.MainActivity --es mode quick
 *
 * Modes: quick, full, kokoro, all, samples.
 */
class MainActivity : ComponentActivity() {

    private val controller by lazy { BenchHolder.get(this) }
    private val scope = MainScope()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        handleIntent(intent)
        setContent {
            MaterialTheme {
                BenchScreen(controller)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val requested = intent?.getStringExtra("mode")
        if (requested != null) {
            val mode = BenchMode.entries.firstOrNull { entry -> entry.extra == requested } ?: return
            controller.start(mode)
            return
        }
        val wordClips = intent?.getStringExtra("wordclips") ?: return
        val engines = when (wordClips) {
            "all" -> WordEngine.entries
            else -> WordEngine.entries.filter { engine -> engine.id == wordClips }
        }
        if (engines.isEmpty()) return
        scope.launch {
            engines.forEach { engine -> controller.wordLab.prepareWordClips(engine) }
        }
    }
}

@Composable
private fun BenchScreen(controller: BenchController) {
    val state by controller.state.collectAsState()
    val device = remember { DeviceInfo.read() }
    Column(
        modifier = Modifier
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("TTS Bench", style = MaterialTheme.typography.headlineSmall)
        Text(device.summary(), style = MaterialTheme.typography.bodySmall)

        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BenchMode.entries.forEach { mode ->
                Button(enabled = !state.running, onClick = { controller.start(mode) }) {
                    Text(mode.label)
                }
            }
            OutlinedButton(enabled = state.running, onClick = controller::stop) { Text("Stop") }
        }
        Text(state.status, style = MaterialTheme.typography.titleSmall)
        state.csvPath?.let { path ->
            Text("CSV: $path", style = MaterialTheme.typography.bodySmall)
        }

        SamplesSection(controller.samples, controller.player)
        ListeningSection(controller.lab, controller.player)
        WordClipsSection(controller.wordLab, controller.player)
        SummaryTable(state.summaries)

        Text("Log", style = MaterialTheme.typography.titleSmall)
        Text(
            state.log.joinToString("\n"),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun SummaryTable(summaries: List<Summary>) {
    if (summaries.isEmpty()) return
    Text("Median results", style = MaterialTheme.typography.titleSmall)
    Text(
        "model      thr steps passage   gen ms  audio ms   r",
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
    )
    summaries.forEach { row ->
        Text(
            "%-10s %3d %5d %-8s %7d %9d %5.2f".format(
                Locale.US,
                row.model,
                row.threads,
                row.steps,
                row.passage,
                row.medianGenerationMs,
                row.medianAudioMs,
                row.medianRatio,
            ),
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
        )
    }
}

@Composable
private fun ListeningSection(lab: ListeningLab, player: SamplePlayer) {
    val scope = rememberCoroutineScope()
    var stepClips by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var blindOrder by remember { mutableStateOf<List<Clip>>(emptyList()) }
    var revealed by remember { mutableStateOf(false) }
    var speedClips by remember { mutableStateOf<List<Pair<Clip, Clip>>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }

    Text("Listening checks", style = MaterialTheme.typography.titleSmall)

    Text("Steps: same sentence at 8, 6 and 4 steps", style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            enabled = !busy,
            onClick = {
                scope.launch {
                    busy = true
                    stepClips = lab.prepareStepClips()
                    blindOrder = stepClips.shuffled()
                    revealed = false
                    busy = false
                }
            },
        ) { Text(if (busy) "Working…" else "Prepare step clips") }
    }
    if (blindOrder.isNotEmpty()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            blindOrder.forEachIndexed { index, clip ->
                OutlinedButton(onClick = { player.toggle("blind-$index", clip.file) }) {
                    Text(if (revealed) clip.label else "${'A' + index}")
                }
            }
            OutlinedButton(onClick = { revealed = !revealed }) { Text("Reveal") }
        }
    }

    Text(
        "Speed: model-native audio vs 1.0x audio stretched by the player",
        style = MaterialTheme.typography.bodySmall,
    )
    Button(
        enabled = !busy,
        onClick = {
            scope.launch {
                busy = true
                speedClips = lab.prepareSpeedClips()
                busy = false
            }
        },
    ) { Text("Prepare speed clips") }
    speedClips.forEach { (native, stretched) ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(onClick = { player.toggle(native.label, native.file) }) {
                Text(native.label)
            }
            OutlinedButton(
                onClick = {
                    player.toggle(stretched.label, stretched.file, stretched.playbackSpeed)
                },
            ) {
                Text(stretched.label)
            }
        }
    }
}
