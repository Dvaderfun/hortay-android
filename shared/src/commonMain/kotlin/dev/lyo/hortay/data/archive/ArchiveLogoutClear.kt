package dev.lyo.hortay.data.archive

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch

/**
 * Wires the post archive into the logout fan-out (hard rule: every session-scoped state holder
 * clears on logout). On `loggedOut` it clears the archive DB + media files and drops the
 * per-account excluded-chat set. The master enable toggle survives (a user preference).
 *
 * Bound eagerly (`createdAtStart`) so the collector attaches before the first logout can fire.
 */
class ArchiveLogoutClear(
    private val repo: ArchiveRepository,
    private val settingsStore: ArchiveSettingsStore,
    loggedOut: SharedFlow<Unit>,
    scope: CoroutineScope,
) {
    init {
        scope.launch {
            loggedOut.collect {
                repo.clear()
                settingsStore.resetForLogout()
            }
        }
    }
}
