package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * When true, [TdMediaImage] renders in "passive" mode: no download spinner, no retry affordance,
 * no progress chrome — just the Ready image + inline minithumb (or a decorative "unavailable"
 * glyph). Provided by the deleted-post tombstone card path (post archive), where the underlying
 * file will never download because the message is gone server-side, so the normal loading/retry
 * UI would lie. Default `false` everywhere else.
 */
val LocalMediaPassive = staticCompositionLocalOf { false }
