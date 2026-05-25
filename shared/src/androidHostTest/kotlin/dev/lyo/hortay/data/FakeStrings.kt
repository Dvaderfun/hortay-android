package dev.lyo.hortay.data

import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * Test stub for [StringResolver]. Returns deterministic placeholder strings without
 * touching the CMP resource loader, so JVM-only unit tests can construct repositories
 * that accept a [StringResolver] in their constructor.
 *
 * Tests that assert on user-facing message content should not use this fake — instead,
 * they should pass a per-test stub that returns the expected string for the IDs they
 * care about. The current consumers only check structural state (Error vs Ready), so
 * a single shared fake is enough.
 */
internal object FakeStrings : StringResolver {
    override fun getString(res: StringResource): String = "str:${res.key}"
    override fun getString(res: StringResource, vararg args: Any): String =
        "str:${res.key}(${args.joinToString(",")})"
    override fun getQuantityString(res: PluralStringResource, count: Int, vararg args: Any): String =
        "plural:${res.key}($count;${args.joinToString(",")})"
}
