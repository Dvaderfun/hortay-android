package dev.lyo.hortay.ui.main

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalUriHandler
import dev.lyo.hortay.data.web.GuestModeStore
import dev.lyo.hortay.data.UserMessageBus

val LocalUserMessageBus = compositionLocalOf<UserMessageBus?> { null }

/**
 * Subscribes to [UserMessageBus.messages] and surfaces each on [hostState].
 * Action buttons dispatch via CMP's [LocalUriHandler] (works cross-platform)
 * or [GuestModeStore] for SignIn.
 */
@Composable
fun UserMessageSnackbarRelay(
    userMessages: UserMessageBus,
    guestMode: GuestModeStore,
    hostState: SnackbarHostState,
) {
    val uriHandler = LocalUriHandler.current
    LaunchedEffect(Unit) {
        userMessages.messages.collect { msg ->
            val result = hostState.showSnackbar(
                message = msg.text,
                actionLabel = msg.action?.label,
                duration = if (msg.action != null) SnackbarDuration.Long else SnackbarDuration.Short,
            )
            val action = msg.action
            if (result == SnackbarResult.ActionPerformed && action != null) {
                when (action) {
                    is UserMessageBus.Action.OpenTelegram ->
                        runCatching { uriHandler.openUri("https://t.me/") }
                    is UserMessageBus.Action.OpenUrl ->
                        runCatching { uriHandler.openUri(action.url) }
                    is UserMessageBus.Action.SignIn ->
                        guestMode.setGuest(false)
                    is UserMessageBus.Action.Run ->
                        action.onClick()
                }
            }
        }
    }
}
