package dev.lyo.hortay.data

import dev.lyo.hortay.tdlib.TdApi
import kotlinx.coroutines.flow.SharedFlow

/**
 * Narrow surface that repositories need from the TDLib daemon: a typed
 * request/response channel and a hot stream of updates.
 *
 * Both platforms implement this against their native TDLib bindings:
 *   - **Android:** [TdClient] (JNI) with a reflection-based adapter that
 *     converts between Java `org.drinkless.tdlib.TdApi` and the Kotlin
 *     [TdApi] at the boundary.
 *   - **iOS:** [dev.lyo.hortay.tdlib.TypedTdClient] via cinterop JSON.
 *
 * Repositories injected with `TdSender` stay in commonMain and are fully
 * shared across platforms.
 */
interface TdSender {
    suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T
    val updates: SharedFlow<TdApi.Update>
}
