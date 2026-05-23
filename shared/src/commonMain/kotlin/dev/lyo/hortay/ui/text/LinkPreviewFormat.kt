package dev.lyo.hortay.ui.text

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import io.ktor.http.Url

/**
 * Format a URL for human-eye display: scheme + bold host + plain path/query/fragment,
 * truncated past [MAX_PRETTY_LEN] so a 2-line ellipsis row never blows up the
 * surrounding chrome on a long query string.
 *
 * The host is the security-relevant part of any URL — bolding it puts the user's
 * attention on what they're actually about to open (anti-phishing for masked-link
 * dialogs, glanceable host signal on the long-press action sheet).
 *
 * Falls back to the raw string (truncated) on any parse error — Ktor's Url
 * constructor is forgiving but a malformed input shouldn't crash the preview
 * composable.
 */
internal fun prettyLinkPreview(raw: String): AnnotatedString {
    val parsed = runCatching { Url(raw) }.getOrNull()
    val host = parsed?.host?.lowercase()?.removePrefix("www.")
    if (host.isNullOrBlank()) return AnnotatedString(raw.take(MAX_PRETTY_LEN))

    val scheme = parsed.protocol.name.takeIf { it.isNotBlank() }?.let { "$it://" }.orEmpty()
    val path = parsed.encodedPath
    val query = parsed.encodedQuery.let { if (it.isNotEmpty()) "?$it" else "" }
    val fragment = parsed.encodedFragment.let { if (it.isNotEmpty()) "#$it" else "" }
    val tail = (path + query + fragment).let {
        val budget = (MAX_PRETTY_LEN - scheme.length - host.length).coerceAtLeast(0)
        if (it.length > budget) it.take(budget) + "…" else it
    }

    return buildAnnotatedString {
        append(scheme)
        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(host) }
        append(tail)
    }
}

private const val MAX_PRETTY_LEN = 96
