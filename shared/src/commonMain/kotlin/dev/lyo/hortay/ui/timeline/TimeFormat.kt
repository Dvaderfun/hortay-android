package dev.lyo.hortay.ui.timeline

import androidx.compose.runtime.Composable
import dev.lyo.hortay.nowMs
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.time_days_short
import hortay.shared.generated.resources.time_hours_short
import hortay.shared.generated.resources.time_just_now
import hortay.shared.generated.resources.time_minutes_short
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import org.jetbrains.compose.resources.stringResource

/**
 * Single source of truth for the "X min/hour/day ago" timestamp used on every message
 * surface (feed post header, comment header). Kept in `ui.timeline` because PostCard
 * is the canonical owner of the message-header design language; CommentsScreen reuses
 * by import.
 */
@Composable
internal fun formatRelative(epochMs: Long): String {
    val diffMin = (nowMs() - epochMs) / 60_000
    return when {
        diffMin < 1 -> stringResource(Res.string.time_just_now)
        diffMin < 60 -> stringResource(Res.string.time_minutes_short, diffMin.toInt())
        diffMin < 60 * 24 -> stringResource(Res.string.time_hours_short, (diffMin / 60).toInt())
        diffMin < 60 * 24 * 7 -> stringResource(Res.string.time_days_short, (diffMin / (60 * 24)).toInt())
        else -> formatAbsoluteDate(epochMs)
    }
}

@OptIn(kotlin.time.ExperimentalTime::class)
private fun formatAbsoluteDate(epochMs: Long): String {
    val ldt: LocalDateTime = Instant.fromEpochMilliseconds(epochMs).toLocalDateTime(TimeZone.currentSystemDefault())
    val month = ldt.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "$month ${ldt.day}, ${ldt.year}"
}
