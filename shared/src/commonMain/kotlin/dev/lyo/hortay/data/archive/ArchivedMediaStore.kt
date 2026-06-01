package dev.lyo.hortay.data.archive

import dev.lyo.hortay.tdlib.TdApi

/**
 * Reference-counted permanent storage for archived media files.
 *
 * TDLib's file cache is LRU; a snapshot pointing at TDLib's `local.path` would lose its media
 * silently on eviction (and deleted-message media is unrecoverable through TDLib — tdlib/td#3493).
 * The implementation copies the bytes once into app-private storage, keyed by SHA-256 of contents,
 * so two captures of the same image share one file. Refcounts are released on snapshot eviction
 * (see [ArchiveSweep]).
 *
 * Interface (commonMain) so [ArchiveSweep] / [ArchiveRepository] depend on it without pulling in
 * the platform file-IO; the concrete copy/hash impl lives in androidMain. iOS uses a no-op until
 * Phase II brings real TDLib there.
 */
interface ArchivedMediaStore {
    /** Copy [file]'s on-disk bytes into archive storage, returning the SHA key (or null when the
     *  file isn't downloaded / available). Increments the refcount on an existing match. */
    suspend fun copyIfAvailable(file: TdApi.File?): String?

    /** Absolute path of the archived file for [sha], or null when not present. */
    suspend fun pathFor(sha: String): String?

    /** Decrement the refcount for [sha]; deletes the row + underlying file when it reaches 0. */
    suspend fun releaseRef(sha: String)

    /** Delete every archived media file (called on archive clear / logout). */
    suspend fun clearAll()

    /** Total bytes of archived media on disk. */
    suspend fun storageBytes(): Long
}
