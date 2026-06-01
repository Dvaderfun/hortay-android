package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.collections.immutable.ImmutableList
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Horizontal row of dots, one per revision, with timestamp labels. Filled dot = selected
 * revision; outlined = others. Tap selects.
 *
 * Label format adapts: when all revisions land on the same calendar day the label is `HH:mm`
 * (compact); when at least one revision crosses into a different day the label includes
 * `dd MMM` so the user can tell edits on different days apart. (kotlinx-datetime — month
 * abbreviation is English-only until a KMP date-format lib lands.)
 */
@Composable
fun RevisionTimeline(
    timestamps: ImmutableList<Long>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tz = TimeZone.currentSystemDefault()
    val includeDay = remember(timestamps) {
        timestamps.map { Instant.fromEpochMilliseconds(it).toLocalDateTime(tz).date }
            .distinct().size > 1
    }
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        timestamps.forEachIndexed { i, ts ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(if (i == selectedIndex) 16.dp else 12.dp)
                        .clip(CircleShape)
                        .background(
                            MaterialTheme.colorScheme.primary
                                .copy(alpha = if (i == selectedIndex) 1f else 0.35f),
                        )
                        .clickable { onSelect(i) },
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    formatLabel(Instant.fromEpochMilliseconds(ts).toLocalDateTime(tz), includeDay),
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}

private fun formatLabel(dt: LocalDateTime, includeDay: Boolean): String {
    val hh = dt.hour.toString().padStart(2, '0')
    val mm = dt.minute.toString().padStart(2, '0')
    val time = "$hh:$mm"
    if (!includeDay) return time
    val month = dt.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
    return "${dt.dayOfMonth} $month $time"
}
