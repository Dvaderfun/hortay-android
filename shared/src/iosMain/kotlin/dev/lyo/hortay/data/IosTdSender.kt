package dev.lyo.hortay.data

import dev.lyo.hortay.tdlib.TdApi
import dev.lyo.hortay.tdlib.TypedTdClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.shareIn

/**
 * iOS [TdSender] implementation. Delegates to [TypedTdClient] which already
 * speaks Kotlin [TdApi] via JSON ↔ tdjson cinterop. The only adaptation is
 * narrowing the updates flow to [TdApi.Update] (TypedTdClient emits all
 * [TdApi.Object] including non-Update responses that lost their `@extra`).
 */
internal class IosTdSender(
    private val client: TypedTdClient,
    scope: CoroutineScope,
) : TdSender {

    override suspend fun <T : TdApi.Object> send(query: TdApi.Function<T>): T =
        client.send(query)

    override val updates: SharedFlow<TdApi.Update> = client.updates
        .mapNotNull { it as? TdApi.Update }
        .shareIn(scope, SharingStarted.Eagerly, replay = 0)
}
