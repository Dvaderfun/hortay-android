package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import java.text.DateFormat
import java.util.Date
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.time_days_short
import hortay.shared.generated.resources.time_hours_short
import hortay.shared.generated.resources.time_just_now
import hortay.shared.generated.resources.time_minutes_short
import org.jetbrains.compose.resources.stringResource

/**
 * Single source of truth for the "X min/hour/day ago" timestamp used on every message
 * surface (feed post header, comment header). Kept in `ui.timeline` because PostCard
 * is the canonical owner of the message-header design language; CommentsScreen reuses
 * by import — mirrors the [label] / [symbolName] split in ReplyKindResources.kt.
 */
@Composable
internal fun formatRelative(epochMs: Long): String {
    val diffMin = (System.currentTimeMillis() - epochMs) / 60_000
    return when {
        diffMin < 1 -> stringResource(Res.string.time_just_now)
        diffMin < 60 -> stringResource(Res.string.time_minutes_short, diffMin.toInt())
        diffMin < 60 * 24 -> stringResource(Res.string.time_hours_short, (diffMin / 60).toInt())
        diffMin < 60 * 24 * 7 -> stringResource(Res.string.time_days_short, (diffMin / (60 * 24)).toInt())
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(epochMs))
    }
}
