package com.retro99.catalogue.ui.downloads

import androidx.compose.runtime.*
import com.retro99.catalogue.domain.*
import com.retro99.catalogue.ui.add.catalogueDeviceName
import com.retro99.catalogue.ui.publication.catalogueSize
import com.retro99.translations.StringRes
import com.retro99.user.api.UserRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import org.jetbrains.compose.resources.getString
import org.koin.compose.koinInject
import org.koin.core.annotation.*
import resources.translations.*

sealed interface DownloadAnnouncement {
    data class Progress(val title: String, val percent: Int) : DownloadAnnouncement
    data class Waiting(val title: String) : DownloadAnnouncement
    data class Downloading(val title: String) : DownloadAnnouncement
    data class Done(val title: String) : DownloadAnnouncement
    data class Failed(val row: CatalogueAcquisition) : DownloadAnnouncement
    data class Cancelled(val title: String) : DownloadAnnouncement
}

/** Tracks transitions, not recompositions. Invisible progress is consumed, never replayed. */
class DownloadAnnouncementTracker {
    private var previous = emptyMap<String, CatalogueAcquisition>()
    private var initialized = false
    fun reset() { previous = emptyMap(); initialized = false }
    fun update(rows: List<CatalogueAcquisition>, visible: Set<String>, downloadsVisible: Boolean): List<DownloadAnnouncement> {
        val events = buildList {
            rows.forEach { row ->
                val before = previous[row.requestId]
                val shown = downloadsVisible || "${row.sourceId}\u0000${row.publicationKey}" in visible || "${row.sourceId}\u0000${row.detailIdentity}" in visible
                val changed = before?.state != row.state
                when {
                    row.state == AcquisitionState.Done && changed && initialized -> add(DownloadAnnouncement.Done(row.title))
                    (row.state is AcquisitionState.Failed || row.state == AcquisitionState.Interrupted) && changed && initialized -> add(DownloadAnnouncement.Failed(row))
                    row.state == AcquisitionState.Waiting && changed && shown && initialized -> add(DownloadAnnouncement.Waiting(row.title))
                    row.state == AcquisitionState.Downloading && shown -> {
                        val total = row.expectedSizeBytes?.takeIf { it > 0 }
                        if (total == null) {
                            if (changed || before?.expectedSizeBytes?.let { it > 0 } == true) add(DownloadAnnouncement.Downloading(row.title))
                        } else {
                            val bucket = ((row.bytesSoFar.toDouble() / total * 100).toInt() / 25).coerceIn(0, 4)
                            val oldBucket = before?.expectedSizeBytes?.takeIf { it > 0 }?.let { ((before.bytesSoFar.toDouble() / it * 100).toInt() / 25).coerceIn(0, 4) } ?: 0
                            if (bucket in 1..3 && bucket > oldBucket) add(DownloadAnnouncement.Progress(row.title, bucket * 25))
                        }
                    }
                }
            }
        }
        previous = rows.associateBy { it.requestId }; initialized = true
        return events
    }
}

/**
 * Starts watching the queue on first use, and only then asks for [scope]: constructing it needs
 * no main dispatcher, so it can be made by the dependency graph on a host without one.
 */
class CatalogueDownloadAnnouncer(
    private val queue: CatalogueAcquisitionManager,
    private val users: UserRegistry,
    private val scope: () -> CoroutineScope,
) {
    private data class Visibility(val keys: Set<String>, val all: Boolean)
    private val owners = mutableMapOf<Any, Visibility>()
    private val channel = Channel<DownloadAnnouncement>(Channel.UNLIMITED)
    val events get() = started.let { channel.receiveAsFlow() }
    private val tracker = DownloadAnnouncementTracker()
    private val started by lazy { start() }
    private fun start() = scope().launch {
        users.observeActiveProfile().map { it?.id }.distinctUntilChanged().collectLatest { profile ->
            tracker.reset(); owners.clear()
            while (channel.tryReceive().isSuccess) { /* Never announce an old profile's title. */ }
            if (profile != null) queue.observeAcquisitions().collect { rows ->
                tracker.update(rows, owners.values.flatMap { it.keys }.toSet(), owners.values.any { it.all }).forEach { channel.send(it) }
            }
        }
    }
    fun visible(owner: Any, sourceId: String, keys: Collection<String>, all: Boolean = false) {
        started
        owners[owner] = Visibility(keys.mapTo(mutableSetOf()) { "$sourceId\u0000$it" }, all)
    }
    fun hidden(owner: Any) { owners.remove(owner) }
    fun cancelled(title: String) { started; channel.trySend(DownloadAnnouncement.Cancelled(title)) }
}

/** Installed once in the app shell, so completion/failure is announced beyond catalogue routes. */
@Composable
fun CatalogueDownloadAnnouncements() {
    val announcer: CatalogueDownloadAnnouncer = koinInject()
    val send = catalogueAnnouncementSender()
    val deviceName = catalogueDeviceName()
    LaunchedEffect(announcer, send, deviceName) {
        announcer.events.collect { event ->
            val text = when (event) {
                is DownloadAnnouncement.Progress -> getString(StringRes.catalogue_announce_progress, event.title, event.percent)
                is DownloadAnnouncement.Waiting -> getString(StringRes.catalogue_announce_waiting, event.title)
                is DownloadAnnouncement.Downloading -> getString(StringRes.catalogue_download_notice, event.title)
                is DownloadAnnouncement.Done -> getString(StringRes.catalogue_announce_done, event.title)
                is DownloadAnnouncement.Cancelled -> getString(StringRes.catalogue_announce_cancelled, event.title)
                is DownloadAnnouncement.Failed -> {
                    val row = event.row
                    val unknown = getString(StringRes.catalogue_file_size_unknown)
                    val size = row.expectedSizeBytes?.let(::catalogueSize) ?: unknown
                    val reason = when (row.state.failureReason) {
                        AcquisitionFailureReason.Connection -> getString(StringRes.catalogue_failure_connection).substringAfter(" · ")
                        AcquisitionFailureReason.TooLarge -> getString(StringRes.catalogue_failure_too_large, size, catalogueSize(CatalogueAcquisitionLimits.MAX_FILE_BYTES))
                        AcquisitionFailureReason.Storage -> getString(StringRes.catalogue_failure_storage, deviceName, row.neededBytes?.let(::catalogueSize) ?: unknown)
                        AcquisitionFailureReason.Invalid -> getString(StringRes.catalogue_failure_invalid)
                        AcquisitionFailureReason.Protected -> getString(StringRes.catalogue_failure_protected)
                        AcquisitionFailureReason.Refused -> getString(StringRes.catalogue_failure_refused, row.catalogueName)
                        AcquisitionFailureReason.SignIn -> getString(StringRes.catalogue_failure_sign_in, row.catalogueName)
                        null -> getString(StringRes.catalogue_failure_interrupted)
                    }
                    getString(StringRes.catalogue_announce_failed, row.title, reason)
                }
            }
            send(text)
        }
    }
}

@Composable
internal expect fun catalogueAnnouncementSender(): (String) -> Unit
