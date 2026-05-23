package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentMap
import kotlinx.coroutines.flow.StateFlow

/**
 * Platform-bridged custom-emoji resolver. Android wraps TDLib's
 * `GetCustomEmojiStickers` with batching + caching. iOS is a stub — guest
 * mode resolves custom emoji through the web bridge directly (see
 * `WebCustomEmojiResolver` in commonMain), so the iOS UI never queries
 * this surface and the in-memory map stays empty.
 *
 * Public surface intentionally minimal — the bridge's write paths
 * (`populate`, `clear`) stay androidMain-only on the concrete class.
 */
expect class CustomEmojiRepository {
    /**
     * Live store of resolved stickers keyed by `customEmojiId`. Updates
     * push immediately as TDLib batches land; subscribers can do cheap
     * structural comparisons because the map is persistent.
     */
    val stickers: StateFlow<PersistentMap<Long, CustomEmojiSticker>>

    /**
     * Request resolution for [ids]. Idempotent and batched — the
     * implementation debounces (~50ms) so a screen-full of posts coalesces
     * into a single round-trip. Already-resolved ids are no-ops.
     */
    fun request(ids: Iterable<Long>)
}
