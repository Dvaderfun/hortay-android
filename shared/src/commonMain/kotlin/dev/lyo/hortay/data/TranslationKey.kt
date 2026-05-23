package dev.lyo.hortay.data

import kotlinx.coroutines.flow.StateFlow

/**
 * Cache key for a single message translation.
 *
 * Tuple is **(chatId, messageId, language)** rather than just (chatId, messageId): the
 * system locale can change at runtime (Configuration change recreates Activities but
 * not the process) and serving an old-language translation after the user switches
 * device language would be stale and confusing. The per-language key also lets a
 * future "translate to specific language" UI reuse the same cache.
 */
data class TranslationKey(val chatId: Long, val messageId: Long, val language: String)

/**
 * Platform-agnostic surface for the in-memory translation cache. Backed by
 * `TranslationsStore` on Android (TDLib `TranslateMessageText`); iOS guest mode
 * has no translation backend, so the facade is null on iOS for now.
 */
interface TranslationsFacade {
    val translations: StateFlow<Map<TranslationKey, FormattedText>>

    fun currentTargetLanguage(): String
    fun isTranslated(chatId: Long, messageId: Long): Boolean
    fun translation(chatId: Long, messageId: Long): FormattedText?
    suspend fun translate(chatId: Long, messageId: Long): Boolean
    fun clear(chatId: Long, messageId: Long)
}
