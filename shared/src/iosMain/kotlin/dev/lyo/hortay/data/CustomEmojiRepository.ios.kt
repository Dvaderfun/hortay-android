package dev.lyo.hortay.data

import kotlinx.collections.immutable.PersistentMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS stub for [CustomEmojiRepository]. Guest mode resolves custom emoji
 * through the web bridge (`WebCustomEmojiResolver`, already commonMain) so
 * the iOS UI never invokes this surface. Phase I replaces this with a real
 * impl once TDLib is cross-compiled.
 */
actual class CustomEmojiRepository {
    actual val stickers: StateFlow<PersistentMap<Long, CustomEmojiSticker>> =
        MutableStateFlow(persistentMapOf<Long, CustomEmojiSticker>()).asStateFlow()

    actual fun request(ids: Iterable<Long>) {}
}
