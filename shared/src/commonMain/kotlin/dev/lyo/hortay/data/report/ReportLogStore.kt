// CSAE-COMPLIANCE: Google Play Child Safety Standards
// Policy: https://support.google.com/googleplay/android-developer/answer/14747720
// Hortay published standards: BuildConfig.CHILD_SAFETY_POLICY_URL
// Architecture: delegation to Telegram moderation via TDLib reportChat dynamic flow

package dev.lyo.hortay.data.report

import dev.lyo.hortay.applicationFilesPath
import dev.lyo.hortay.ioDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.SYSTEM
import okio.buffer
import okio.use

@Serializable
data class ReportLogEntry(
    val timestamp: Long,
    /** "auth" for authenticated TDLib mode, "guest" for anonymous web mode. */
    val mode: String,
    val channelUsername: String?,
    val chatId: Long?,
    val messageId: Long?,
    /** "tdlib" | "deep_link" | "web" | "email" */
    val deliveryMethod: String,
    /** "ok" | "failed" | "delegated" */
    val deliveryStatus: String,
)

/**
 * Append-only JSONL audit log for child-safety reporting events.
 *
 * Storage: `<applicationFilesPath>/report_log.jsonl` — one JSON object per line.
 * Rotation: when the file exceeds [MAX_ENTRIES] records, rewrites with the
 * most-recent 200 lines. All I/O on the platform IO dispatcher; all mutations
 * serialised via [mutex] to avoid partial-line writes.
 *
 * Why JSONL instead of Room: project forbids Room (see ARCHITECTURE.md). JSONL is
 * the lightest format that is both append-only fast and human-readable for a
 * compliance audit, and 200 × ~200 B lines = ~40 KB max — trivially fits in a
 * single file read. Nothing here is queried or joined, only appended and
 * occasionally streamed.
 */
class ReportLogStore(
    fileName: String = DEFAULT_FILE_NAME,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
    private val path: Path = applicationFilesPath(fileName),
) {

    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun log(entry: ReportLogEntry): Unit = withContext(ioDispatcher) {
        mutex.withLock {
            ensureParent()
            fileSystem.appendingSink(path).buffer().use { sink ->
                sink.writeUtf8(json.encodeToString(entry))
                sink.writeUtf8("\n")
            }
            rotateIfNeeded()
        }
    }

    suspend fun snapshot(): List<ReportLogEntry> = withContext(ioDispatcher) {
        mutex.withLock {
            if (!fileSystem.exists(path)) return@withLock emptyList()
            readAllLines()
                .filter { it.isNotBlank() }
                .mapNotNull { line ->
                    runCatching { json.decodeFromString<ReportLogEntry>(line) }.getOrNull()
                }
        }
    }

    private fun ensureParent() {
        path.parent?.let { parent ->
            if (!fileSystem.exists(parent)) fileSystem.createDirectories(parent)
        }
    }

    private fun readAllLines(): List<String> =
        fileSystem.source(path).buffer().use { src ->
            buildList {
                while (true) {
                    val line = src.readUtf8Line() ?: break
                    add(line)
                }
            }
        }

    private fun rotateIfNeeded() {
        if (!fileSystem.exists(path)) return
        val lines = readAllLines().filter { it.isNotBlank() }
        if (lines.size > ROTATE_THRESHOLD) {
            val trimmed = lines.takeLast(MAX_ENTRIES)
            val tmpPath = path.parent!!.resolve(path.name + ".tmp")
            fileSystem.sink(tmpPath).buffer().use { sink: BufferedSink ->
                trimmed.forEach { line ->
                    sink.writeUtf8(line)
                    sink.writeUtf8("\n")
                }
            }
            fileSystem.atomicMove(tmpPath, path)
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        const val DEFAULT_FILE_NAME = "report_log.jsonl"
        private const val ROTATE_THRESHOLD = MAX_ENTRIES * 11 / 10
    }
}
