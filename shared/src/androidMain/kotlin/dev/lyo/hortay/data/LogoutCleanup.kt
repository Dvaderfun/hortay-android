package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.data.report.ReportDialogState
import dev.lyo.hortay.data.web.MigrationStore
import dev.lyo.hortay.ui.media.StickerOutlineStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Drops every piece of per-account state on TDLib logout. Extracted from the
 * old `AppGraph.runLogoutCleanup` so the fan-out is discoverable + testable
 * outside the graph wiring.
 *
 * TDLib's database is being torn down (LogOut → AuthorizationStateClosing →
 * Closed) and account A's file ids / chat ids / message ids are about to
 * become invalid. Wipe every per-account in-memory cache + the on-disk
 * timeline snapshot before TDLib's `spawnClient()` rebuilds a fresh native
 * session, so account B (or the empty AuthScreen if the user doesn't sign
 * in again) doesn't briefly render account A's last feed.
 *
 * `MigrationStore` is reset specifically: a per-app proposal-shown flag
 * would otherwise suppress the migration offer when the user signs in to a
 * *different* Telegram account on the same device, even though that account
 * has never seen the offer.
 *
 * Each clear is wrapped in `runCatching` so a partial wipe is still better
 * than aborting the cleanup midway and leaving half the caches stale.
 *
 * `PostsRepository.clear()` already wipes the on-disk snapshot internally
 * (it owns the snapshotStore reference and the semantic ownership), so this
 * orchestrator never touches `TimelineSnapshotStore` directly.
 */
class LogoutCleanup(
    private val tdClient: TdClient,
    private val appScope: CoroutineScope,
    private val postsRepository: PostsRepository,
    private val messageMapper: MessageMapper,
    private val commentsRepository: CommentsRepository,
    private val mediaCache: MediaCache,
    private val customEmoji: CustomEmojiRepository,
    private val stickerOutline: StickerOutlineStore,
    private val chatFoldersRepository: ChatFoldersRepository,
    private val translations: TranslationsStore,
    private val migrationStore: MigrationStore,
    private val reportDialogs: ReportDialogState,
    private val nav: NavStack,
) {
    private var bound = false

    fun bind() {
        if (bound) return
        bound = true
        appScope.launch {
            tdClient.loggedOut.collect { runCleanup() }
        }
    }

    private suspend fun runCleanup() {
        runCatching { postsRepository.clear() }
        runCatching { ChatPresence.clear() }
        runCatching { messageMapper.clear() }
        runCatching { commentsRepository.clear() }
        runCatching { mediaCache.clear() }
        runCatching { customEmoji.clear() }
        runCatching { stickerOutline.clear() }
        runCatching { chatFoldersRepository.clear() }
        runCatching { translations.clear() }
        runCatching { migrationStore.reset() }
        runCatching { reportDialogs.close() }
        runCatching { nav.clear() }
    }
}
