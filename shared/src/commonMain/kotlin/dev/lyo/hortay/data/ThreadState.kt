package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList

/**
 * Materialised state of a discussion thread for the comments overlay.
 *
 * Emitted by `HortayBackend.observeThread`. The screen renders one of three
 * shapes:
 *   - [Loading] — initial subscription state and any intervening reset
 *     (chat-id swap, logout, recovery from a transient error).
 *   - [Ready] — the thread resolved; [rows] is the ordered tree-flattened
 *     comment list, [threadChatId] is the discussion supergroup the screen
 *     should `viewMessages` against for read-acks.
 *   - [Error] — the thread can't be opened (post has no discussion, channel
 *     migrated, TDLib rejected the lookup). [message] is the localised
 *     surface copy.
 */
@Immutable
sealed interface ThreadState {
    data object Loading : ThreadState
    data class Ready(val rows: ImmutableList<ThreadRow>, val threadChatId: Long) : ThreadState
    data class Error(val message: String) : ThreadState
}
