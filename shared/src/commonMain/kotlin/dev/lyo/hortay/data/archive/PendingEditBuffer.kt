package dev.lyo.hortay.data.archive

import dev.lyo.hortay.nowMs
import dev.lyo.hortay.tdlib.TdApi
import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized

/**
 * Pairs `UpdateMessageContent` (UMC) with `UpdateMessageEdited` (UME) so the archive captures a
 * new VERSION row **only** for genuine admin edits.
 *
 * Admin edits emit BOTH `updateMessageEdited(editDate > 0)` AND a separate `updateMessageContent`
 * with the new payload; order between the two is not guaranteed. Non-edit content mutations (poll
 * voter ticks, live-location updates, paid-media reveals, self-destruct expiry, fact-checks) emit
 * ONLY UMC. Tying capture to bare UMC produced "phantom edit" rows on every poll vote; tying it to
 * UME alone misses the fresh content (TDLib doesn't carry it on UME).
 *
 * This buffer holds incoming UMC payloads for up to [TTL_MS] and either commits the buffered
 * content when a paired UME arrives ([commitOnEdited]) or drops the entry on TTL expiry. It also
 * handles UME arriving FIRST — [commitOnEdited] returns `null` and the caller falls back to
 * `GetMessage`.
 *
 * Thread-safety: a single atomicfu lock guards the map (KMP replacement for the original's
 * `ConcurrentHashMap` / `AtomicInteger`). `nowMs` is injected for testability.
 */
class PendingEditBuffer(private val now: () -> Long = ::nowMs) {

    private data class Entry(val content: TdApi.MessageContent, val expiresAtMs: Long)

    private val lock = SynchronizedObject()
    private val pending = HashMap<Pair<Long, Long>, Entry>()
    private var stashCount = 0

    /**
     * Stash an incoming `UpdateMessageContent`. If a paired `UpdateMessageEdited` arrives within
     * [TTL_MS], [commitOnEdited] returns this content; otherwise the entry expires silently. Every
     * [STASH_PRUNE_EVERY] stash folds in a prune so the map can't grow unboundedly.
     */
    fun stash(chatId: Long, messageId: Long, content: TdApi.MessageContent) = synchronized(lock) {
        pending[chatId to messageId] = Entry(content, now() + TTL_MS)
        if (++stashCount % STASH_PRUNE_EVERY == 0) pruneLocked()
    }

    /**
     * Called when a `UpdateMessageEdited` with `editDate > 0` arrives.
     * @return buffered fresh content when UMC arrived first; `null` when UME arrived first.
     */
    fun commitOnEdited(chatId: Long, messageId: Long): TdApi.MessageContent? = synchronized(lock) {
        pruneLocked()
        pending.remove(chatId to messageId)?.content
    }

    /** Drop entries that aged past [TTL_MS] without a paired UME. */
    fun pruneExpired() = synchronized(lock) { pruneLocked() }

    private fun pruneLocked() {
        val n = now()
        pending.entries.removeAll { it.value.expiresAtMs < n }
    }

    /** Clear everything (called on logout). */
    fun clear() = synchronized(lock) { pending.clear() }

    /** Test-only size. */
    internal fun size(): Int = synchronized(lock) { pending.size }

    companion object {
        /** Time a UMC stays buffered waiting for its paired UME (worst-case observed gap + margin). */
        const val TTL_MS: Long = 1500L

        /** Stash-counter modulus that triggers an opportunistic prune. */
        const val STASH_PRUNE_EVERY = 32
    }
}
