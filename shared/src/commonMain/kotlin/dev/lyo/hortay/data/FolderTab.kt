package dev.lyo.hortay.data

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.StateFlow

/**
 * Projection of a Telegram chat folder for the tab bar. Title + id only — the
 * "is this folder useful to show?" predicate uses [FolderRule] / chat-id sets
 * exposed alongside.
 */
@Immutable
data class FolderInfo(
    val id: Int,
    val title: String,
    val iconName: String?,
)

/**
 * The slice of a fully-resolved `TdApi.ChatFolder` the timeline tab bar
 * actually reads. Currently a single field — whether the folder is
 * indistinguishable from the default "All" scope (`includeChannels=true`,
 * no pins / explicit includes / excludes / archive-mute-read narrowing).
 * When true the tab gets hidden so it doesn't duplicate the "Усі" chip.
 */
@Immutable
data class FolderRule(
    val includesAllChannels: Boolean,
)

/**
 * Platform-agnostic surface for the folder tab bar. iOS guest mode has no
 * folders concept; the backend returns null in that case and the bar
 * stays hidden.
 */
interface FoldersFacade {
    /** Resolved folder tabs in display order. Empty until `UpdateChatFolders` arrives. */
    val folders: StateFlow<List<FolderInfo>>

    /**
     * Per-folder rule projection — used by the tab visibility filter. Empty
     * while the eager `GetChatFolder × N` fan-out is in flight after a fresh
     * `UpdateChatFolders` emission; the UI treats "rule not yet resolved" as
     * "keep the tab" so the bar doesn't briefly drop chips.
     */
    val folderRules: StateFlow<Map<Int, FolderRule>>

    /**
     * Per-folder resolved chat-id membership. Mirrors what
     * `GetChats(ChatListFolder)` returns; kept in sync with
     * `UpdateChatAddedToList` / `UpdateChatRemovedFromList`.
     */
    val folderChatIds: StateFlow<Map<Int, Set<Long>>>

    /**
     * One-shot resolver for [folderId]'s chat-id membership. Primes the
     * cache when a freshly-selected folder tab's membership hasn't been
     * warmed yet.
     */
    suspend fun folderChatIds(folderId: Int): Set<Long>
}
