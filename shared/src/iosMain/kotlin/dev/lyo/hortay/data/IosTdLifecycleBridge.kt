package dev.lyo.hortay.data

import dev.lyo.hortay.data.posts.PostsRepository
import dev.lyo.hortay.tdlib.TdApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * iOS twin of Android's [TdLifecycleBridge]. Two jobs:
 *
 *  1. **Presence + network signal.** On the `Ready && foreground && !hideOnline`
 *     edge it sends `SetOption("online", true)` + `SetNetworkType` + the
 *     read-only client options; on the inverse edge `online=false`. Per TDLib
 *     (`tdlib/td#3144`) the `online` option is presence only — it maps to
 *     `account.updateStatus` and does NOT gate content sync. Content updates
 *     ride `SetNetworkType` + per-chat `OpenChat` (the latter via
 *     [ChatPresence], already wired through the shared PostsRepository). The
 *     read-only options mirror Android and must be re-sent on every go-online
 *     because TDLib clears non-persistent options across a closed→reopened
 *     session.
 *
 *  2. **Resume refresh.** iOS suspends backgrounded apps and tears down the
 *     MTProto socket; nothing else on iOS re-triggers a feed refresh on return.
 *     On every foreground re-entry while signed in this re-runs
 *     [PostsRepository.refreshIfStale] so the feed catches up instead of sitting
 *     on whatever it last had (or on skeletons if the suspension killed the
 *     cold-start load).
 *
 * Network classification is coarse (`NetworkTypeOther`) — iOS has no
 * auto-download consumer yet (`HortayBackend.autoDownload == null`); refine via
 * `NWPathMonitor` when one lands. `NetworkTypeNone` is never pushed (matches
 * Android): forcing it on background would block any future push-wake sync.
 */
class IosTdLifecycleBridge(
    private val td: TdSender,
    private val authStage: StateFlow<AuthStage>,
    private val foreground: StateFlow<Boolean>,
    private val hideOnlineStatus: Flow<Boolean>,
    private val posts: PostsRepository,
    private val scope: CoroutineScope,
) {

    fun bind() {
        combine(authStage, foreground, hideOnlineStatus) { auth, fg, hide ->
            auth == AuthStage.Ready && fg && !hide
        }
            .distinctUntilChanged()
            .onEach { active -> if (active) goOnline() else goOffline() }
            .launchIn(scope)

        foreground
            .drop(1)
            .filter { it && authStage.value == AuthStage.Ready }
            .onEach {
                runCatching { posts.refreshIfStale() }.warnUnlessCancelled(TAG, "resume-refresh")
            }
            .launchIn(scope)
    }

    private suspend fun goOnline() {
        runCatching { td.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(true))) }
            .warnUnlessCancelled(TAG, "online=true")
        runCatching { td.send(TdApi.SetNetworkType(TdApi.NetworkTypeOther())) }
            .warnUnlessCancelled(TAG, "networkType")
        applyReadOnlyClientOptions()
    }

    private suspend fun applyReadOnlyClientOptions() {
        runCatching { td.send(TdApi.SetOption("disable_top_chats", TdApi.OptionValueBoolean(true))) }
            .warnUnlessCancelled(TAG, "disable_top_chats")
        runCatching { td.send(TdApi.SetOption("notification_group_count_max", TdApi.OptionValueInteger(0L))) }
            .warnUnlessCancelled(TAG, "notification_group_count_max")
    }

    private suspend fun goOffline() {
        runCatching { td.send(TdApi.SetOption("online", TdApi.OptionValueBoolean(false))) }
            .warnUnlessCancelled(TAG, "online=false")
        // Intentionally do NOT push NetworkTypeNone — see class doc.
    }

    private companion object {
        const val TAG = "IosTdLifecycleBridge"
    }
}
