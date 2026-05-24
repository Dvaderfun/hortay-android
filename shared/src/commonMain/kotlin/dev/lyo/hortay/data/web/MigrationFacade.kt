package dev.lyo.hortay.data.web

import kotlinx.coroutines.flow.StateFlow

/**
 * Per-channel migration progress reported by the coordinator while the user's
 * guest-mode subscription set is being walked. `processed` increments on each
 * `JoinChat` round-trip; `lastUsername` is purely diagnostic — surfaced in the
 * UI so the user sees which channel is currently being joined.
 */
data class MigrationProgress(
    val total: Int,
    val processed: Int,
    val lastUsername: String?,
)

/**
 * Platform-agnostic surface for the guest → TDLib subscription migration flow.
 * Android backs it with `MigrationCoordinator`; iOS guest mode never reaches
 * the post-sign-in proposal (TDLib isn't running there), so the backend hands
 * back null.
 */
interface MigrationFacade {
    /** Non-null when a proposal is awaiting user response. */
    val pendingProposal: StateFlow<List<String>?>

    /** Live progress as the coordinator iterates through accepted handles. */
    val progress: StateFlow<MigrationProgress?>

    /** User accepted [usernames] — start joining. Suspends until done. */
    suspend fun confirm(usernames: List<String>)

    /** User skipped the proposal. */
    suspend fun dismiss()
}
