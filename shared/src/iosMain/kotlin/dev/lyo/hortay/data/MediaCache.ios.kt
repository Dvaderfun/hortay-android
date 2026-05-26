package dev.lyo.hortay.data

import dev.lyo.hortay.tdlib.TdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * iOS [MediaCache] — downloads TDLib files and tracks progress via
 * [TdApi.UpdateFile] updates. Simpler than the Android version (no stall
 * watchdog, no debounced cancel) but sufficient for rendering the feed.
 */
actual class MediaCache(
    private val td: TdSender,
    private val scope: CoroutineScope,
) {
    // No-arg constructor for guest-mode Koin (stub path)
    constructor() : this(
        td = object : TdSender {
            override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T =
                throw UnsupportedOperationException("stub")
            override val updates = MutableStateFlow<TdApi.Update>(
                TdApi.UpdateOption("stub", TdApi.OptionValueBoolean(false))
            )
        },
        scope = CoroutineScope(Dispatchers.Default),
    )

    private val slots = HashMap<Int, MutableStateFlow<MediaState>>()

    init {
        td.updates
            .filterIsInstance<TdApi.UpdateFile>()
            .onEach { update -> handleFileUpdate(update) }
            .launchIn(scope)
    }

    private fun handleFileUpdate(update: TdApi.UpdateFile) {
        val file = update.file ?: return
        val slot = slots[file.id] ?: return
        val local = file.local ?: return

        when {
            local.isDownloadingCompleted && local.path.isNotBlank() ->
                slot.value = MediaState.Ready(local.path)
            local.isDownloadingActive ->
                slot.value = MediaState.Downloading(
                    progress = if (file.expectedSize > 0)
                        local.downloadedSize.toFloat() / file.expectedSize.toFloat()
                    else 0f,
                    downloadedBytes = local.downloadedSize,
                    totalBytes = file.expectedSize,
                )
        }
    }

    actual fun observe(fileId: Int): StateFlow<MediaState> =
        getOrCreateSlot(fileId).asStateFlow()

    actual suspend fun ensure(fileId: Int, priority: DownloadPriority) {
        val slot = getOrCreateSlot(fileId)
        if (slot.value is MediaState.Ready) return
        runCatching {
            td.send(TdApi.DownloadFile(fileId, priority.tdValue, 0, 0, true))
        }.onSuccess { file ->
            val local = file.local
            if (local != null && local.isDownloadingCompleted && local.path.isNotBlank()) {
                slot.value = MediaState.Ready(local.path)
            }
        }
    }

    actual fun cancelDeferred(fileId: Int) {}
    actual fun cancelExplicit(fileId: Int) {
        scope.launch {
            runCatching { td.send(TdApi.CancelDownloadFile(fileId, false)) }
            slots[fileId]?.value = MediaState.Idle
        }
    }

    actual fun resync(fileId: Int) {
        scope.launch {
            runCatching {
                val file = td.send(TdApi.GetFile(fileId))
                val local = file.local
                if (local != null && local.isDownloadingCompleted && local.path.isNotBlank()) {
                    getOrCreateSlot(fileId).value = MediaState.Ready(local.path)
                }
            }
        }
    }

    actual suspend fun retry(fileId: Int, priority: DownloadPriority) {
        getOrCreateSlot(fileId).value = MediaState.Idle
        ensure(fileId, priority)
    }

    actual fun invalidate(fileId: Int, priority: DownloadPriority) {
        getOrCreateSlot(fileId).value = MediaState.Idle
    }

    private fun getOrCreateSlot(fileId: Int): MutableStateFlow<MediaState> =
        slots.getOrPut(fileId) { MutableStateFlow(MediaState.Idle) }
}
