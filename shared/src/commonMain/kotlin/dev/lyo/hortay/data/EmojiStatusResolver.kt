package dev.lyo.hortay.data

import dev.lyo.hortay.nowMs
import dev.lyo.hortay.tdlib.TdApi

/**
 * Resolve [TdApi.EmojiStatus] into the custom-emoji id for the inline badge.
 * Returns null when absent or expired. Both regular and NFT/upgraded-gift
 * statuses collapse to one custom-emoji id (gift uses `modelCustomEmojiId`).
 */
fun resolveEmojiStatusId(status: TdApi.EmojiStatus?): Long? {
    if (status == null) return null
    val exp = status.expirationDate
    if (exp != 0 && exp.toLong() * 1000L <= nowMs()) return null
    return when (val type = status.type) {
        is TdApi.EmojiStatusTypeCustomEmoji -> type.customEmojiId
        is TdApi.EmojiStatusTypeUpgradedGift -> type.modelCustomEmojiId
        else -> null
    }
}
