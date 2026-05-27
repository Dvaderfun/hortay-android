package dev.lyo.hortay.ui.main

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lyo.hortay.data.DeepLink
import dev.lyo.hortay.data.DeepLinkRouter
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.LinkDialogState
import dev.lyo.hortay.ui.composables.dialogs.ExternalLinkConfirmDialog
import dev.lyo.hortay.ui.text.LocalHashtagTap
import dev.lyo.hortay.ui.text.LocalLinkConfirm
import dev.lyo.hortay.ui.text.parseHashtagWithScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Single source of truth for the in-app link plumbing both scaffolds need:
 *
 *   - [HortayUriHandler] installed as [LocalUriHandler] so every descendant
 *     `openUri(...)` (post body links, web preview cards, settings author rows,
 *     forward-source chips, AddChannelSheet affordances) routes through
 *     [HortayBackend.resolveLink] before falling out to the OS handler.
 *
 *   - [LocalLinkConfirm] hook for masked links (TDLib `Style.TextUrl`): the
 *     callback resolves the destination first; Telegram-internal targets jump
 *     in-app via the deep-link router with no friction, only genuine external
 *     destinations surface the [ExternalLinkConfirmDialog] showing the bolded
 *     host for anti-phishing review.
 *
 *   - The confirmation dialog itself, rendered from a process-wide
 *     [LinkDialogState] so a rotation mid-decision doesn't drop the prompt.
 *     See [LinkDialogState] KDoc for the lifecycle rationale.
 *
 * Both [MainScaffold] (TDLib mode) and [WebModeScaffold] (guest mode) used to
 * duplicate ~25 lines of this plumbing — extracting it here keeps the contract
 * in one place and means any future link-handling refinement lands once
 * instead of twice (the previous duplication had already drifted slightly
 * between the two sites).
 */
@Composable
fun LinkAwareScaffold(
    backend: HortayBackend,
    router: DeepLinkRouter,
    linkDialogs: LinkDialogState,
    scope: CoroutineScope,
    content: @Composable () -> Unit,
) {
    val systemUriHandler = LocalUriHandler.current
    val hortayUriHandler = remember(backend, router, scope, systemUriHandler) {
        HortayUriHandler(
            delegate = systemUriHandler,
            backend = backend,
            router = router,
            scope = scope,
        )
    }
    // Confirmation callback for masked-link spans (TDLib `Style.TextUrl`).
    // Suspending resolution happens on the long-lived app scope — composition
    // lifecycle is decoupled from the call so a mid-resolve rotation doesn't
    // lose the dialog. The result is written to [LinkDialogState.maskedLink],
    // a MutableStateFlow that survives configuration changes. Previously the
    // result was written to a `rememberSaveable` inside this composable —
    // the saveable bag was packed up before the suspending call returned on
    // rotation and the dialog disappeared.
    val confirmMaskedLink = remember(backend, router, linkDialogs, scope) {
        { url: String ->
            scope.launch {
                val link = backend.resolveLink(url)
                when (link) {
                    null, is DeepLink.External -> linkDialogs.showMaskedLink(url)
                    else -> router.submit(link)
                }
            }
            Unit
        }
    }
    // Hashtag tap → route through the same DeepLinkRouter both scaffolds
    // already observe, as a typed [DeepLink.HashtagSearch]. The default lambda
    // here serves surfaces with no surrounding channel context (Comments,
    // future settings / about screens). The PostBody composable installs a
    // scoped override via [CompositionLocalProvider] so feed-card taps carry
    // the post's channel handle.
    //
    // We always run the tag through [parseHashtagWithScope] first so the
    // canonical `#tag@channel` text-entity form (per TDLib's
    // `TextEntityTypeHashtag` docs: *"optionally containing a chat username
    // at the end"*) decomposes into (`#tag`, `channel`) — overriding any
    // scope captured by composition, since the entity is self-describing.
    val hashtagTap = remember(router) {
        { tag: String ->
            val (cleanTag, suffixHandle) = parseHashtagWithScope(tag)
            router.submit(
                DeepLink.HashtagSearch(
                    tag = cleanTag,
                    channelHandle = suffixHandle,
                    originalUrl = tag,
                ),
            )
            Unit
        }
    }
    val pendingMaskedLink by linkDialogs.maskedLink.collectAsStateWithLifecycle()
    CompositionLocalProvider(
        LocalUriHandler provides hortayUriHandler,
        LocalLinkConfirm provides confirmMaskedLink,
        LocalHashtagTap provides hashtagTap,
    ) {
        content()
        pendingMaskedLink?.let { url ->
            ExternalLinkConfirmDialog(
                url = url,
                onDismiss = { linkDialogs.dismissMaskedLink() },
            )
        }
    }
}
