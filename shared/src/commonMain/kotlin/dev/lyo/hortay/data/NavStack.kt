package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.navigation3.runtime.NavKey
import kotlinx.atomicfu.atomic

/**
 * Process-wide polymorphic back-stack for the drill-overlay surfaces.
 *
 * Replaces three previously-disjoint navigation states:
 *   1. `channelStack: List<Long>` — channel-drill overlay
 *   2. `commentsForPost` / `pendingCommentsKey` — comments ModalBottomSheet
 *   3. `pendingScrollTarget` — orthogonal deep-link scroll target
 *
 * Each push is a fresh layer; no dedup on repeated chatIds. Permits unlimited
 * nesting: channel → comments → channel → comments → … (the Telegram-Android
 * pattern, where every drill is its own back-stack entry).
 *
 * `entryId` is a stable per-instance id used as the contentKey for the per-entry
 * `SaveableStateProvider` and `viewModel(key)` so each entry has an isolated
 * saveable state bag and a dedicated ViewModel — pushing the same channel
 * twice produces two distinct screens with their own scroll positions and
 * view-models. Without the explicit id, nav3 would key host-entry identity off
 * `NavKey.equals/hashCode` and collapse two `Channel(123L)` pushes into one.
 *
 * Lifetime: held as a Koin singleton via [dev.lyo.hortay.di.coreModule];
 * cleared on logout by [dev.lyo.hortay.data.LogoutCleanup]. Not saveable across
 * process death — same boundary [LinkDialogState] and
 * [dev.lyo.hortay.data.report.ReportDialogState] draw: a killed process
 * represents user abandonment of the drill path, not pending intent.
 *
 * Implements [NavKey] (Navigation 3 marker interface) so each variant can be
 * pushed directly onto `nav3`'s [androidx.navigation3.runtime.NavBackStack].
 */
@Immutable
sealed interface NavTarget : NavKey {

    val entryId: String

    /**
     * Single-channel drill. `scrollToMessageId` lets a deep-link
     * `t.me/<chan>/<msg>` push the channel pre-targeted at a specific post;
     * the screen consumes it once and scrolls on first composition.
     *
     * No push-side preload flag: the destination's own
     * [SCREEN_MOUNT_GRACE_MS] anti-flicker grace decides whether a
     * skeleton paints, based on whether [ChannelViewModel] has resolved
     * to Ready by then. The MainScaffold's `pushChannel` awaits
     * [PostsRepository.loadChannelHistory] with a short timeout before
     * actually mounting [Channel], so warm/local-cache opens land
     * populated and only genuinely slow loads cross the threshold to a
     * skeleton.
     */
    @Immutable
    data class Channel(
        val chatId: Long,
        val scrollToMessageId: Long? = null,
        override val entryId: String = nextNavEntryId(),
    ) : NavTarget

    /**
     * Comments thread anchored at the given feed post. The anchor carries
     * `chatId` + `id` (the message id used as discussion-thread root by
     * TDLib `GetMessageThread`).
     */
    @Immutable
    data class Comments(
        val anchor: TimelinePost,
        override val entryId: String = nextNavEntryId(),
    ) : NavTarget

    /**
     * Guest-mode single-channel drill. Identified by t.me/s/ handle rather
     * than a TDLib chatId — guest mode has no TDLib session to mint chatIds.
     * Only consumed by [dev.lyo.hortay.ui.web.WebModeScaffold]; auth-mode
     * [dev.lyo.hortay.ui.main.MainScaffold] never sees this variant because
     * the two scaffolds never compose simultaneously
     * ([dev.lyo.hortay.MainActivity] routes between them).
     */
    @Immutable
    data class WebChannel(
        val username: String,
        override val entryId: String = nextNavEntryId(),
    ) : NavTarget
}

private val navEntrySeq = atomic(0L)

/**
 * Process-monotonic id for a freshly-pushed [NavTarget]. Replaces the
 * previous `UUID.randomUUID()` — KMP doesn't ship `java.util.UUID`, and a
 * counter is enough here (entries are scoped to a single app instance,
 * never serialised across processes).
 */
private fun nextNavEntryId(): String = "nav-${navEntrySeq.incrementAndGet()}"

/**
 * Koin-singleton wrapper around Navigation 3's
 * [androidx.navigation3.runtime.NavBackStack] (a `SnapshotStateList<NavKey>`).
 *
 * Why a singleton and not `rememberNavBackStack`:
 *  1. [dev.lyo.hortay.data.DeepLinkRouter] submits before Compose mounts
 *     ([dev.lyo.hortay.MainActivity.onCreate]) — composition-scoped state
 *     wouldn't exist yet.
 *  2. The same instance survives the MainScaffold ↔ WebModeScaffold swap on
 *     guest-mode toggles mid-session.
 *  3. Cleared on logout via [LogoutCleanup] (DI singleton).
 *
 * The backing [entries] list is the actual `NavBackStack` — pass it directly
 * to `NavDisplay(backStack = nav.entries, …)`. Mutations go through this
 * class's [push] / [pop] / [clear] so the eager-singleton contract (Koin
 * `createdAtStart`) ordering is preserved.
 */
class NavStack {

    val entries: SnapshotStateList<NavTarget> = mutableStateListOf()

    val top: NavTarget? get() = entries.lastOrNull()

    /**
     * Compose-observable top-of-stack — reads cause recomposition when the
     * top entry changes. Used by `MainScaffold` for the predictive-back
     * handler's `enabled` flag.
     */
    fun topAsState() = derivedStateOf { entries.lastOrNull() }

    fun push(entry: NavTarget) {
        entries.add(entry)
    }

    /** Pop the top entry. Returns it, or null if the stack is already empty. */
    fun pop(): NavTarget? {
        if (entries.isEmpty()) return null
        return entries.removeAt(entries.lastIndex)
    }

    fun clear() {
        entries.clear()
    }
}
