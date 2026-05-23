package dev.lyo.hortay.ui.actions

import androidx.compose.ui.platform.UriHandler
import dev.lyo.hortay.PlatformClipboard
import dev.lyo.hortay.PlatformShare
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.TimelinePost

/**
 * Side-effects that bridge a post into the host system: open the original in
 * the Telegram client and use the system share sheet.
 *
 * Share-link generation prefers TDLib's offline `GetMessageLink` (via
 * [HortayBackend.canonicalShareUrl]) — Telegram knows the canonical format
 * for albums / topics / message-thread anchors better than any string
 * template we'd write here. Falls back to a hand-rolled URL when the backend
 * is null (guest mode) or returns null (e.g. restricted source chat with
 * `canGetLink = false`).
 */
object PostActions {

    /**
     * Resolved share URL: TDLib-minted when available, hand-rolled
     * otherwise. Always returns a non-empty string suitable for embedding
     * in share text / OS clipboard.
     */
    private suspend fun shareUrl(backend: HortayBackend?, post: TimelinePost): String =
        backend?.canonicalShareUrl(post) ?: fallbackShareUri(post, isGuest = backend == null)

    /**
     * Hand-rolled `https://t.me/...` fallback. Public channels get
     * `t.me/<handle>/<post>`; private channels get `t.me/c/<rawId>/<post>`
     * (which only resolves for users in that channel — Telegram's invariant,
     * same as the official "Copy link" action).
     */
    private fun fallbackShareUri(post: TimelinePost, isGuest: Boolean): String {
        val username = post.senderHandle?.removePrefix("@")
        val serverPostId = fallbackServerPostId(post, isGuest)
        return if (!username.isNullOrBlank()) {
            "https://t.me/$username/$serverPostId"
        } else {
            val rawChannelId = post.chatId.toString().removePrefix("-100")
            "https://t.me/c/$rawChannelId/$serverPostId"
        }
    }

    /**
     * Hand-rolled fallback for the post's server-visible message number.
     * TDLib internally bit-packs `(serverId shl 20)`; the visible number is
     * the high 44 bits. Guest-mode posts skip the shift because their id IS
     * the raw `t.me/<u>/<seq>` number — applying `ushr 20` would collapse
     * `seq=36046` to 0 and produce a `/0` URL.
     */
    private fun fallbackServerPostId(post: TimelinePost, isGuest: Boolean): Long =
        if (isGuest) post.id else post.id ushr 20

    /**
     * Open the post in the Telegram client via [uriHandler]
     * (`LocalUriHandler.current` at the call site). Uses the https://t.me
     * scheme so the OS chooser steps in if no Telegram client is installed
     * (the tg:// scheme would crash with ActivityNotFoundException).
     */
    suspend fun openInTelegram(
        uriHandler: UriHandler,
        backend: HortayBackend?,
        post: TimelinePost,
    ) {
        runCatching { uriHandler.openUri(shareUrl(backend, post)) }
    }

    /**
     * Copy the post body to the system clipboard. Returns true when there
     * was text to copy — the caller can layer a toast on top (Android 12L
     * and below; from 13+ the system shows its own confirmation; iOS shows
     * its own clipboard banner).
     */
    fun copyText(post: TimelinePost): Boolean {
        val text = post.content.captionPlain
        if (text.isBlank()) return false
        PlatformClipboard.writeText(label = post.senderName, text = text)
        return true
    }

    /** Open the system share sheet pre-filled with the post excerpt + URL. */
    suspend fun share(backend: HortayBackend?, post: TimelinePost) {
        val url = shareUrl(backend, post)
        PlatformShare.shareText(buildShareText(post, url))
    }

    private fun buildShareText(post: TimelinePost, url: String): String {
        val excerpt = post.content.captionPlain.take(200)
        return buildString {
            if (excerpt.isNotBlank()) {
                append(excerpt)
                if (post.content.captionPlain.length > 200) append("…")
                append("\n\n")
            }
            append("— ${post.senderName}\n$url")
        }
    }
}
