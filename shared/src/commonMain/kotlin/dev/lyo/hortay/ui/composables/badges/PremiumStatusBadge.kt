package dev.lyo.hortay.ui.composables.badges

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.CustomEmojiInlineView
import dev.lyo.hortay.ui.theme.PremiumGold
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.cd_premium_badge
import org.jetbrains.compose.resources.stringResource

/**
 * Badge next to a user's display name: custom emoji status → gold star → nothing.
 * Mirrors Telegram's resolution: emoji status with valid expiration wins, else
 * plain Premium shows the gold star, else nothing renders.
 */
@Composable
fun PremiumStatusBadge(
    isPremium: Boolean,
    emojiStatusId: Long?,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val description = stringResource(Res.string.cd_premium_badge)
    when {
        emojiStatusId != null -> CustomEmojiInlineView(
            customEmojiId = emojiStatusId,
            contentDescription = description,
            tintColor = PremiumGold,
            modifier = modifier.size(size),
        )
        isPremium -> Symbol(
            name = "star",
            filled = true,
            contentDescription = description,
            tint = PremiumGold,
            size = size,
            modifier = modifier,
        )
    }
}
