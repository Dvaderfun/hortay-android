package dev.lyo.hortay.ui.composables.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.lyo.hortay.PlatformClipboard
import dev.lyo.hortay.data.ChatId
import dev.lyo.hortay.data.HortayBackend
import dev.lyo.hortay.data.UserId
import dev.lyo.hortay.data.PersonalChannelLink
import dev.lyo.hortay.data.PresenceStatus
import dev.lyo.hortay.data.SenderVerification
import dev.lyo.hortay.data.UserProfile
import dev.lyo.hortay.nowMs
import dev.lyo.hortay.rememberToaster
import dev.lyo.hortay.ui.composables.sheets.formatThousandsKmp
import dev.lyo.hortay.ui.icons.Symbol
import dev.lyo.hortay.ui.media.TdAvatar
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.cd_premium_badge
import hortay.shared.generated.resources.cd_verified_badge
import hortay.shared.generated.resources.channels_subscribers
import hortay.shared.generated.resources.user_profile_about
import hortay.shared.generated.resources.user_profile_action_message
import hortay.shared.generated.resources.user_profile_bio
import hortay.shared.generated.resources.user_profile_birthday
import hortay.shared.generated.resources.user_profile_bot_about
import hortay.shared.generated.resources.user_profile_chip_bot
import hortay.shared.generated.resources.user_profile_chip_support
import hortay.shared.generated.resources.user_profile_groups_in_common
import hortay.shared.generated.resources.user_profile_handle_copied
import hortay.shared.generated.resources.user_profile_loading_name
import hortay.shared.generated.resources.user_profile_personal_channel
import hortay.shared.generated.resources.user_profile_status_just_now
import hortay.shared.generated.resources.user_profile_status_last_month
import hortay.shared.generated.resources.user_profile_status_last_week
import hortay.shared.generated.resources.user_profile_status_online
import hortay.shared.generated.resources.user_profile_status_recently
import hortay.shared.generated.resources.user_profile_status_was_days_ago
import hortay.shared.generated.resources.user_profile_status_was_hours_ago
import hortay.shared.generated.resources.user_profile_status_was_long_ago
import hortay.shared.generated.resources.user_profile_status_was_minutes_ago
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * User-profile bottom sheet. Renders the same surface for every author entry point
 * the reader app exposes:
 *
 *   - comment authors in the discussion thread;
 *   - personal-author posts in a channel (admin posting under their own identity);
 *   - the "forwarded from <user>" chip on top of a forwarded post;
 *   - in-text user mentions that carry a TdApi.TextEntityTypeMentionName.userId.
 *
 * Resolves [UserProfile] from [HortayBackend.userProfile] on entry; the sheet stays
 * visible during the loading window so the trigger feels instant — fields populate
 * inline as TDLib responds (mirrors the [dev.lyo.hortay.ui.channels.ChannelInfoSheet]
 * pattern by design, so users get one consistent affordance for both kinds of header).
 *
 * Visual structure (top → bottom):
 *   1. **Hero header** — 88dp avatar centred on a tonal disc, name + verification mark,
 *      `@handle` (tap to copy), presence line ("у мережі" / "нещодавно в мережі" / "у мережі Х тому").
 *   2. **Action chip** — Material 3 `FilledTonalButton`. "Написати" opens the official
 *      Telegram app on a `tg://user?id=…` deep link via [LocalUriHandler] (falls back to
 *      https://t.me/<handle> when the handle is set so the OS chooser can route to a
 *      browser if no Telegram client is installed).
 *   3. **Bio card** — surfaceContainerHigh tonal card, plain text. Bots: short/long description.
 *   4. **Personal channel** — tappable [ListItem] with channel avatar + title + handle +
 *      subscriber count. Tap drills into [dev.lyo.hortay.ui.main.MainScaffold]'s channel
 *      overlay via [onOpenChannel] — same path as a regular subscribed channel tap; sheet
 *      auto-dismisses so the focus shifts cleanly.
 *   5. **Meta rows** — birthdate (when visible), groups-in-common count.
 *
 * The hero verification glyph mirrors the feed `VerificationBadge`: blue check for
 * Verified, red SCAM pill, amber FAKE pill. Bots and support accounts surface their own
 * small chip below the name so the user is never surprised by "why does this user have
 * a `/help` command".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UserProfileSheet(
    userId: UserId,
    backend: HortayBackend,
    /** Optional name we already know from the trigger (sender row, mention, forward chip).
     *  Used as the visible label until the resolver lands so the hero is never blank. */
    seedName: String? = null,
    seedAvatarThumb: ByteArray? = null,
    seedAvatarFileId: Int? = null,
    onDismiss: () -> Unit,
    onOpenChannel: (chatId: ChatId) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var profile by remember(userId) { mutableStateOf<UserProfile?>(null) }

    LaunchedEffect(userId) {
        profile = backend.userProfile(userId)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Slightly larger top corner radius than the default to lean into M3 Expressive's
        // "more shape" stance — matches the channel-info / report sheets in this project.
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
        ) {
            ProfileHero(
                profile = profile,
                seedName = seedName,
                seedAvatarThumb = seedAvatarThumb,
                seedAvatarFileId = seedAvatarFileId,
            )
            Spacer(Modifier.height(20.dp))

            MessageAction(profile = profile, fallbackUserId = userId)

            profile?.bio?.let { bio ->
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(Res.string.user_profile_bio))
                BioCard(text = bio)
            }
            profile?.botDescription?.takeIf { profile?.bio == null }?.let { desc ->
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(Res.string.user_profile_bot_about))
                BioCard(text = desc)
            }

            profile?.personalChannel?.let { ch ->
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(Res.string.user_profile_personal_channel))
                PersonalChannelRow(
                    channel = ch,
                    onClick = {
                        onOpenChannel(ch.chatId)
                        onDismiss()
                    },
                )
            }

            val meta = profile?.let { profileMetaRows(it) }.orEmpty()
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionLabel(text = stringResource(Res.string.user_profile_about))
                meta.forEach { row ->
                    MetaRow(symbol = row.symbol, label = row.label, value = row.value)
                }
            }
        }
    }
}

@Composable
private fun ProfileHero(
    profile: UserProfile?,
    seedName: String?,
    seedAvatarThumb: ByteArray?,
    seedAvatarFileId: Int?,
) {
    val resolvedName = profile?.displayName?.takeUnless { it.isBlank() }
        ?: seedName?.takeUnless { it.isBlank() }
        ?: stringResource(Res.string.user_profile_loading_name)
    val avatarThumb = profile?.avatarThumb ?: seedAvatarThumb
    val avatarFileId = profile?.avatarFileId ?: seedAvatarFileId

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Avatar disc with a soft tonal ring — anchors the eye and dresses up the small
        // initial-letter fallback so a fresh placeholder doesn't read as "broken".
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(
                    width = 2.dp,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = CircleShape,
                ),
            contentAlignment = Alignment.Center,
        ) {
            TdAvatar(
                name = resolvedName,
                thumb = avatarThumb,
                fileId = avatarFileId,
                size = 88.dp,
                textStyle = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = resolvedName,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            profile?.verification?.let {
                Spacer(Modifier.width(6.dp))
                VerificationGlyph(it)
            }
            if (profile?.isPremium == true) {
                Spacer(Modifier.width(6.dp))
                Symbol(
                    // `rocket_launch` stands in for Telegram Premium until a dedicated
                    // star glyph is bundled — Premium itself is closer to "boost" than
                    // a literal star in Telegram-Android marketing anyway.
                    name = "rocket_launch",
                    contentDescription = stringResource(Res.string.cd_premium_badge),
                    tint = MaterialTheme.colorScheme.tertiary,
                    size = 16.dp,
                )
            }
        }
        profile?.handle?.let { handle ->
            // Handle reads like a Telegram blue link, but the action it offers ISN'T
            // navigation — it's "copy to clipboard". Mirroring Telegram-Android: tap
            // the @username row in a profile card and the username lands on the
            // clipboard. Avoids the dedicated Copy button (read as visual clutter
            // when the @handle is already on screen one row above).
            val toast = rememberToaster()
            val copiedMessage = stringResource(Res.string.user_profile_handle_copied)
            Spacer(Modifier.height(2.dp))
            Text(
                text = handle,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        PlatformClipboard.writeText(label = handle, text = handle)
                        toast(copiedMessage)
                    }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        val statusLine = profile?.let { presenceLabel(it.status) }
        if (statusLine != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = statusLine,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Bot / Support chip stack — Telegram surfaces these as small badges next to the
        // name; we drop a row of tonal mini-chips so the user sees them at a glance.
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (profile?.isBot == true) {
                Chip(label = stringResource(Res.string.user_profile_chip_bot))
            }
            if (profile?.isSupport == true) {
                Chip(
                    label = stringResource(Res.string.user_profile_chip_support),
                    tint = MaterialTheme.colorScheme.secondaryContainer,
                )
            }
        }
    }
}

/**
 * Sole action chip in the sheet. Was a two-button row (Message + Copy handle) — the
 * Copy half got folded into the `@handle` row above (Telegram-Android idiom: tap the
 * handle to copy). Single FilledTonalButton at full width is the MD3E pattern for a
 * "one primary action" header — Telegram-X's user-info card uses the same shape.
 *
 * Routes through [LocalUriHandler] which is KMP — Android delegates to the OS
 * chooser (browser fallback if no Telegram client installed); iOS hands off to
 * `UIApplication.openURL`. Prefers `https://t.me/<handle>` when a handle exists
 * so the https scheme survives "no client installed"; falls back to
 * `tg://user?id=<id>` for handle-less users (works in the official Telegram
 * client, no-op on bare browsers).
 */
@Composable
private fun MessageAction(profile: UserProfile?, fallbackUserId: UserId) {
    val uriHandler = LocalUriHandler.current
    val handle = profile?.handle?.removePrefix("@")?.takeUnless { it.isBlank() }
    val targetUri = if (handle != null) "https://t.me/$handle" else "tg://user?id=${fallbackUserId.value}"
    FilledTonalButton(
        onClick = { runCatching { uriHandler.openUri(targetUri) } },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Symbol(
            // No bundled `sym_send`; the `ios_share` glyph (arrow-out-of-box) carries
            // the same "send / outbound" semantic visually and is already in the pack.
            name = "ios_share",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            size = 18.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.user_profile_action_message))
    }
}

@Composable
private fun BioCard(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PersonalChannelRow(
    channel: PersonalChannelLink,
    onClick: () -> Unit,
) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        leadingContent = {
            TdAvatar(
                name = channel.title.ifBlank { "?" },
                thumb = channel.avatarThumb,
                fileId = channel.avatarFileId,
                size = 44.dp,
                textStyle = MaterialTheme.typography.titleSmall,
            )
        },
        headlineContent = {
            Text(
                text = channel.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        supportingContent = {
            val handle = channel.handle
            val subs = channel.subscribers
            val parts = listOfNotNull(
                handle,
                subs?.let { stringResource(Res.string.channels_subscribers, formatThousandsKmp(it)) },
            )
            if (parts.isNotEmpty()) {
                Text(
                    text = parts.joinToString(" · "),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        trailingContent = {
            Symbol(
                name = "chevron_right",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                size = 20.dp,
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MetaRow(symbol: String, label: String, value: String) {
    ListItem(
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth(),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Symbol(
                    name = symbol,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    size = 18.dp,
                )
            }
        },
        headlineContent = {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
        },
        trailingContent = {
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
    )
}

@Composable
private fun VerificationGlyph(v: SenderVerification) {
    when (v) {
        SenderVerification.Verified -> Symbol(
            name = "verified",
            contentDescription = stringResource(Res.string.cd_verified_badge),
            tint = MaterialTheme.colorScheme.primary,
            size = 18.dp,
        )
        SenderVerification.Scam -> Chip(
            label = "SCAM",
            tint = MaterialTheme.colorScheme.errorContainer,
            textColor = MaterialTheme.colorScheme.onErrorContainer,
        )
        SenderVerification.Fake -> Chip(
            label = "FAKE",
            tint = MaterialTheme.colorScheme.tertiaryContainer,
            textColor = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

@Composable
private fun Chip(
    label: String,
    tint: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(tint)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
            color = textColor,
        )
    }
}

private data class MetaRowSpec(val symbol: String, val label: String, val value: String)

@Composable
private fun profileMetaRows(profile: UserProfile): List<MetaRowSpec> = buildList {
    val birthLabel = formatBirthdate(profile)
    if (birthLabel != null) {
        add(
            MetaRowSpec(
                // `redeem` (gift box) is the closest semantic glyph in the bundled pack —
                // birthdays are a "gift" affordance in Telegram-Android.
                symbol = "redeem",
                label = stringResource(Res.string.user_profile_birthday),
                value = birthLabel,
            ),
        )
    }
    if (profile.groupsInCommon > 0) {
        add(
            MetaRowSpec(
                symbol = "person",
                label = stringResource(Res.string.user_profile_groups_in_common),
                value = formatThousandsKmp(profile.groupsInCommon),
            ),
        )
    }
}

private fun formatBirthdate(profile: UserProfile): String? {
    val month = profile.birthMonth ?: return null
    val day = profile.birthDay ?: return null
    // KMP-locale-safe: month-as-number label until a CMP month-name API ships.
    // Telegram's own profile cards in Ukrainian render "12 листопада" but the
    // commonMain platform doesn't ship `java.text.DateFormatSymbols`; the
    // numeric fallback ("12.11") is still unambiguous and consistent across
    // every locale until we can wire `kotlinx-datetime` localised names.
    val year = profile.birthYear
    val mm = month.toString().padStart(2, '0')
    val dd = day.toString().padStart(2, '0')
    return if (year != null) "$dd.$mm.$year" else "$dd.$mm"
}

@Composable
private fun presenceLabel(status: PresenceStatus): String? = when (status) {
    PresenceStatus.Online -> stringResource(Res.string.user_profile_status_online)
    PresenceStatus.Recently -> stringResource(Res.string.user_profile_status_recently)
    PresenceStatus.LastWeek -> stringResource(Res.string.user_profile_status_last_week)
    PresenceStatus.LastMonth -> stringResource(Res.string.user_profile_status_last_month)
    is PresenceStatus.Offline -> formatOfflineLabel(status.wasOnlineSeconds)
    PresenceStatus.Empty -> null
}

@Composable
private fun formatOfflineLabel(wasOnlineSec: Long): String {
    val now = nowMs() / 1000L
    val deltaSec = (now - wasOnlineSec).coerceAtLeast(0L)
    val delta = deltaSec.seconds
    return when {
        delta < 1.minutes -> stringResource(Res.string.user_profile_status_just_now)
        delta < 1.hours -> {
            val mins = delta.inWholeMinutes.toInt().coerceAtLeast(1)
            pluralStringResource(Res.plurals.user_profile_status_was_minutes_ago, mins, mins)
        }
        delta < 1.days -> {
            val hours = delta.inWholeHours.toInt().coerceAtLeast(1)
            pluralStringResource(Res.plurals.user_profile_status_was_hours_ago, hours, hours)
        }
        delta < 7.days -> {
            val days = delta.inWholeDays.toInt().coerceAtLeast(1)
            pluralStringResource(Res.plurals.user_profile_status_was_days_ago, days, days)
        }
        else -> stringResource(Res.string.user_profile_status_was_long_ago)
    }
}
