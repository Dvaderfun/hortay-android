package dev.lyo.hortay.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide channel for user-facing transient messages (Snackbars).
 *
 * Why a bus instead of per-screen state: errors originate in repositories and ViewModels
 * but need to surface in whichever screen the user is currently looking at. Threading
 * a callback through every layer is noisy; a single bus subscribed at the scaffold
 * level decouples producers from consumers.
 *
 * Backpressure: [BufferOverflow.DROP_OLDEST] with a small buffer. Snackbars are
 * inherently transient and a flood of identical errors (e.g. flaky network spamming
 * "не вдалося оновити") would just queue jank if we suspended emitters. Dropping the
 * older message keeps the user informed about the *most recent* state, which is what
 * matters for a user-facing toast.
 *
 * Actions: a message may optionally carry an [Action]. The scaffold-level snackbar host
 * surfaces it as a Material 3 action button and dispatches it. Producers describe intent
 * declaratively (sealed type), not as `() -> Unit` — keeps the bus pure (no Context /
 * navigation-scope capture) and lets the dispatcher live where Context already lives.
 */
class UserMessageBus {

    private val _messages = MutableSharedFlow<UserMessage>(
        replay = 0,
        extraBufferCapacity = BUFFER,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val messages: Flow<UserMessage> = _messages.asSharedFlow()

    fun post(
        text: String,
        severity: Severity = Severity.Error,
        action: Action? = null,
    ) {
        _messages.tryEmit(UserMessage(text, severity, action))
    }

    enum class Severity { Info, Warning, Error }

    data class UserMessage(
        val text: String,
        val severity: Severity,
        val action: Action? = null,
    )

    sealed interface Action {
        val label: String
        data class OpenTelegram(override val label: String) : Action
        data class OpenUrl(override val label: String, val url: String) : Action
        data class SignIn(override val label: String) : Action
        class Run(override val label: String, val onClick: () -> Unit) : Action
    }

    private companion object {
        const val BUFFER = 8
    }
}
