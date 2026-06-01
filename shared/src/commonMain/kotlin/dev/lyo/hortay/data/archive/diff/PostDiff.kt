package dev.lyo.hortay.data.archive.diff

import io.github.petertrr.diffutils.diff
import io.github.petertrr.diffutils.patch.DeltaType
import kotlinx.collections.immutable.toPersistentList

/**
 * Adaptive diff for archived post revisions.
 *
 * Telegram posts are often single-paragraph: a pure line-level diff degenerates to
 * "everything deleted, everything inserted." This picks granularity by post shape:
 *
 *   - ≥3 lines  → LINE-level
 *   - else ≥3 sentences → SENTENCE-level
 *   - else            → WORD-level
 *
 * Entities (bold / link / etc.) are preserved at the revision level, not at the diff
 * segment level — the renderer paints diff styling (background + lineThrough) on top of
 * the regular formatted-text render.
 *
 * Uses `io.github.petertrr:kotlin-multiplatform-diff` (the KMP port of java-diff-utils) so
 * the diff runs on every target including iOS — the original `io.github.java-diff-utils` is
 * JVM-only.
 */
object PostDiff {
    enum class Granularity { LINE, SENTENCE, WORD }

    private val sentenceSplit = Regex("(?<=[.!?…])\\s+")
    private val wordSplit = Regex("\\s+")

    fun compute(old: String, new: String): PostDiffResult {
        val granularity = pickGranularity(old)
        val oldTokens = tokenize(old, granularity)
        val newTokens = tokenize(new, granularity)
        val patch = diff(oldTokens, newTokens)
        val sep = separator(granularity)
        val out = mutableListOf<PostDiffSegment>()
        var oldIdx = 0
        for (delta in patch.deltas) {
            for (i in oldIdx until delta.source.position) {
                out.add(PostDiffSegment.Unchanged(oldTokens[i] + sep))
            }
            when (delta.type) {
                DeltaType.DELETE -> delta.source.lines.forEach {
                    out.add(PostDiffSegment.Deleted(it + sep))
                }
                DeltaType.INSERT -> delta.target.lines.forEach {
                    out.add(PostDiffSegment.Inserted(it + sep))
                }
                DeltaType.CHANGE -> {
                    delta.source.lines.forEach {
                        out.add(PostDiffSegment.Deleted(it + sep))
                    }
                    delta.target.lines.forEach {
                        out.add(PostDiffSegment.Inserted(it + sep))
                    }
                }
                DeltaType.EQUAL -> {}
            }
            oldIdx = delta.source.position + delta.source.size()
        }
        for (i in oldIdx until oldTokens.size) {
            out.add(PostDiffSegment.Unchanged(oldTokens[i] + sep))
        }
        return PostDiffResult(granularity, out.toPersistentList())
    }

    private fun pickGranularity(text: String): Granularity {
        if (text.split('\n').size >= 3) return Granularity.LINE
        if (text.split(sentenceSplit).size >= 3) return Granularity.SENTENCE
        return Granularity.WORD
    }

    private fun tokenize(text: String, g: Granularity): List<String> = when (g) {
        Granularity.LINE -> text.split('\n')
        Granularity.SENTENCE -> text.split(sentenceSplit)
        Granularity.WORD -> text.split(wordSplit)
    }

    private fun separator(g: Granularity): String = when (g) {
        Granularity.LINE -> "\n"
        Granularity.SENTENCE -> " "
        Granularity.WORD -> " "
    }
}
