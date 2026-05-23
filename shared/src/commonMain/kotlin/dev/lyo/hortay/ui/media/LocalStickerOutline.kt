package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Path

/**
 * Resolver for a sticker's silhouette vector path, keyed by TDLib sticker
 * fileId. Returns a parsed Compose [Path] in the sticker's native pixel
 * coordinate space, or null when no outline is known.
 *
 * The default is a no-op lambda — used by iOS where TDLib isn't present.
 * Android provides a real resolver via `LocalStickerOutline` at composition
 * root; the impl owns the LRU + negative cache + TDLib JNI call so this
 * surface stays a single-method slot.
 */
typealias StickerOutlineLoader = suspend (fileId: Int) -> Path?

val LocalStickerOutline = staticCompositionLocalOf<StickerOutlineLoader> {
    { null }
}
