package dev.lyo.hortay.data.archive

import android.content.Context
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.tdlib.TdApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Android (file-IO) implementation of [ArchivedMediaStore]. Reference-counted permanent storage for
 * archived media files copied out of TDLib's LRU cache on capture.
 *
 * Storage key: SHA-256 of the file's bytes — two captures of the same image share one file. A
 * single-writer mutex serialises copy/ref-bump/delete so refcount can't drift under concurrent
 * capture+sweep. Files live under [Context.filesDir]/archive_media/.
 */
class AndroidArchivedMediaStore(
    private val context: Context,
    private val db: ArchiveDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ArchivedMediaStore {

    private val mutex = Mutex()

    private val rootDir: File by lazy {
        File(context.filesDir, "archive_media").apply { mkdirs() }
    }

    override suspend fun copyIfAvailable(file: TdApi.File?): String? = withContext(Dispatchers.IO) {
        if (file?.local == null) return@withContext null
        val path = file.local.path
        if (path.isNullOrEmpty() || !file.local.isDownloadingCompleted) return@withContext null
        val src = File(path)
        if (!src.exists()) return@withContext null

        val sha = sha256OfFile(src) ?: return@withContext null
        mutex.withLock {
            val existing = db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()
            if (existing != null) {
                db.archivedMediaFileQueries.incrementRefCount(sha)
                return@withLock sha
            }
            val dst = File(rootDir, "$sha.bin")
            if (!dst.exists()) {
                runCatching { src.copyTo(dst, overwrite = false) }
                    .getOrElse { return@withLock null }
            }
            db.archivedMediaFileQueries.insert(
                sha = sha,
                path = dst.absolutePath,
                size_bytes = dst.length(),
                mime_type = null,
                created_at_ms = clock(),
                ref_count = 1L,
            )
            sha
        }
    }

    override suspend fun pathFor(sha: String): String? = withContext(Dispatchers.IO) {
        db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()?.path
    }

    override suspend fun releaseRef(sha: String) {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                db.archivedMediaFileQueries.decrementRefCount(sha)
                val row = db.archivedMediaFileQueries.selectBySha(sha).executeAsOneOrNull()
                    ?: return@withLock
                if (row.ref_count <= 0L) {
                    runCatching { File(row.path).delete() }
                    db.archivedMediaFileQueries.deleteBySha(sha)
                }
            }
        }
    }

    override suspend fun clearAll() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { rootDir.listFiles()?.forEach { it.delete() } }
                db.archivedMediaFileQueries.clearAll()
            }
        }
    }

    override suspend fun storageBytes(): Long = withContext(Dispatchers.IO) {
        db.archivedMediaFileQueries.storageBytes().executeAsOne()
    }

    private fun sha256OfFile(f: File): String? = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { b -> ((b.toInt() and 0xFF) + 0x100).toString(16).substring(1) }
    }.getOrNull()

    private companion object {
        const val BUFFER_SIZE = 8192
    }
}
