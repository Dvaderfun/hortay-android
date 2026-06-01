package dev.lyo.hortay.ui.archive.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.ui.icons.Symbol
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.post_edited_chip_count
import org.jetbrains.compose.resources.pluralStringResource

/**
 * Compact inline chip rendered after the timestamp on edited posts. One element communicates both
 * "this was edited" and "tap to see history". Pencil + "×N" in a single rounded secondaryContainer
 * pill; for a single edit the count is omitted (pencil-only reads correctly).
 */
@Composable
fun EditedChip(count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Symbol(
            name = "edit",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 12.dp,
        )
        if (count > 1) {
            Spacer(Modifier.width(2.dp))
            Text(
                text = pluralStringResource(Res.plurals.post_edited_chip_count, count, count),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}
