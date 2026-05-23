package dev.lyo.hortay.data

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getPluralString as cmpGetPluralString
import org.jetbrains.compose.resources.getString as cmpGetString

/**
 * Synchronous string lookup for non-Composable code (mappers, repositories, error
 * formatters). Wraps CMP's suspend `getString`/`getPluralString` — the underlying
 * reads from `composeResources` are effectively sync on every platform we ship
 * (Android: asset stream from APK; iOS: bundled file in main bundle). Blocking
 * the calling thread for a microsecond inside a data-layer coroutine is fine.
 *
 * Tests substitute [FakeStringResolver] (or any custom impl) so they never touch
 * the Compose resource loader.
 */
interface StringResolver {
    fun getString(res: StringResource): String
    fun getString(res: StringResource, vararg args: Any): String
    fun getQuantityString(res: PluralStringResource, count: Int, vararg args: Any): String
}

class ComposeResourcesStringResolver : StringResolver {
    override fun getString(res: StringResource): String =
        runBlocking { cmpGetString(res) }

    override fun getString(res: StringResource, vararg args: Any): String =
        runBlocking { cmpGetString(res, *args) }

    override fun getQuantityString(res: PluralStringResource, count: Int, vararg args: Any): String =
        runBlocking { cmpGetPluralString(res, count, *args) }
}
