package com.retro99.parrot.fixtures

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.retro99.base.ui.compose.*
import com.retro99.dictionary.*
import com.retro99.reader.ui.navigator.PageRect
import com.retro99.reader.ui.navigator.PageText
import com.retro99.reader.ui.reader.saved.*
import com.retro99.saved.ui.dictionary.DictionaryEntrySheet
import kotlinx.coroutines.*
import java.io.File

/** Screenshots and measured lookup against the real pack, without touching the user's data. */
class DictionaryFixtureActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = File(filesDir, "packs")
        val directory = File(root, "dictionary-en/oewn-2025-1").apply { mkdirs() }
        val pack = File(directory, "english.sqlite")
        if (!pack.exists()) assets.open("english.sqlite").use { input -> pack.outputStream().use(input::copyTo) }
        File(root, "dictionary-en/.active").writeText("oewn-2025-1")
        val dictionary = DictionaryService(object : DictionaryPlatform {
            override val packRoot = root.absolutePath
            override fun availableBytes() = filesDir.usableSpace
            override fun open(path: String): SqlDriver = AndroidSqliteDriver(DictionaryDatabase.Schema, this@DictionaryFixtureActivity, path)
        })
        val mode = when (intent.getStringExtra("theme")) { "night" -> EmberMode.Night; "eink" -> EmberMode.Eink; else -> EmberMode.Day }
        val screen = intent.getStringExtra("screen") ?: "strip"
        val word = intent.getStringExtra("word") ?: "empty"
        setContent {
            ParrotTheme(mode) {
                val entry by produceState<DictionaryEntry?>(null) {
                    dictionary.acquire()
                    val coldStart = SystemClock.elapsedRealtimeNanos()
                    value = dictionary.lookup(word)
                    val coldMs = (SystemClock.elapsedRealtimeNanos() - coldStart) / 1_000_000.0
                    val timings = mutableListOf<Double>()
                    repeat(100) { index ->
                        val start = SystemClock.elapsedRealtimeNanos()
                        dictionary.lookup(listOf("empty", "running", "mice", "went", "can't", "Tomas")[index % 6])
                        timings += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    }
                    timings.sort()
                    Log.i("DictionaryFixture", "lookup cold_ms=$coldMs median_ms=${timings[50]} p95_ms=${timings[95]} result=${value?.headword}")
                    try { awaitCancellation() } finally { withContext(NonCancellable) { dictionary.release() } }
                }
                var sheet by remember { mutableStateOf(screen == "sheet") }
                var saved by remember { mutableStateOf(false) }
                Box(Modifier.fillMaxSize().background(Ember.colors.bg).systemBarsPadding()) {
                    Text("The landing was empty when she came down the hill, the ropes coiled and the bell-rope tied off for the night.\n\n“He has gone up to the village,” Tomas said. “He will not take anyone across before morning.”\n\nMara set her bag on the planks. Out on the water the first of the storm lanterns flickered, then held.\n\n“Then we wait,” she said, “and you can tell me what was in the letter.”",
                        color = Ember.colors.ink, fontFamily = literataFamily(), fontSize = 18.sp, lineHeight = 30.sp,
                        modifier = Modifier.padding(24.dp, 30.dp))
                    entry?.let { result ->
                        if (!sheet) SelectionToolbar(
                            ReaderTextSelection("chapter8.xhtml", "application/xhtml+xml", PageText(quote = word, rect = PageRect(170.0, 55.0, 235.0, 80.0))),
                            definition = when (screen) {
                                "missing" -> DefinitionState.NotFound("Tomas")
                                "download" -> DefinitionState.Pack(DictionaryPackState())
                                else -> DefinitionState.Found(result)
                            }, pageTopDp = 0f, bottomObstructionDp = 32f, topObstructionDp = 0f,
                            isDarkPage = mode == EmberMode.Night,
                            onSaved = { if (it == SavedAction.OpenDictionary) sheet = true }, modifier = Modifier.fillMaxSize())
                        if (sheet) DictionaryEntrySheet(result, { sheet = false }, onCopy = {}, onHighlight = {}, onSave = { saved = true }, saved = saved)
                    }
                }
            }
        }
    }
}
