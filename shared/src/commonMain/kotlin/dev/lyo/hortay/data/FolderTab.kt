package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.PersistentSet
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-agnostic projection of a Telegram chat folder — the slice TimelineScreen
 * needs to render the tab bar and filter the feed by scope.
 *
 * Build path on Android: [ChatFoldersRepository] joins `TdApi.ChatFolderInfo`
 * (title / icon) with `TdApi.ChatFolder` (rule evaluation) and
 * `folderChatIds(folderId)` (resolved membership) and projects each entry into
 * this DTO. iOS guest mode has no folders surface, so the facade returns an
 * empty list.
 *
 * [includesAllChannels] is the precomputed "this folder is indistinguishable from
 * the All scope" predicate (see `ChatFoldersRepository.isEquivalentToAll`).
 * Surfaced as a DTO field rather than a method so the UI can hide redundant
 * tabs without re-evaluating the rule on every recomposition.
 */
@Immutable
data class FolderTab(
    val id: Int,
    val title: String,
    val iconName: String?,
    val chatIds: PersistentSet<Long>,
    val includesAllChannels: Boolean,
)

/**
 * Platform-agnostic surface for the folder tab bar. iOS guest mode exposes a
 * stub that publishes an empty list and a no-op resolver.
 */
interface FoldersFacade {
    /** Resolved folder tabs in display order. Empty until TDLib emits `UpdateChatFolders`. */
    val tabs: StateFlow<List<FolderTab>>

    /**
     * Resolve the chat-id set that belongs to [folderId] right now. Backed by
     * `LoadChats(ChatListFolder)` + `GetChats(ChatListFolder)` on Android.
     * Returns empty on iOS guest mode.
     */
    suspend fun folderChatIds(folderId: Int): Set<Long>
}
