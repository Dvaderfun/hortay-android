package dev.lyo.hortay.ui.icons

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.sym_add
import hortay.shared.generated.resources.sym_arrow_back
import hortay.shared.generated.resources.sym_arrow_downward
import hortay.shared.generated.resources.sym_arrow_forward
import hortay.shared.generated.resources.sym_arrow_upward
import hortay.shared.generated.resources.sym_audio_file
import hortay.shared.generated.resources.sym_ballot
import hortay.shared.generated.resources.sym_bookmark
import hortay.shared.generated.resources.sym_bookmark_filled
import hortay.shared.generated.resources.sym_call
import hortay.shared.generated.resources.sym_call_end
import hortay.shared.generated.resources.sym_call_received
import hortay.shared.generated.resources.sym_campaign
import hortay.shared.generated.resources.sym_chat_bubble
import hortay.shared.generated.resources.sym_chat_bubble_filled
import hortay.shared.generated.resources.sym_check_box
import hortay.shared.generated.resources.sym_check_box_outline_blank
import hortay.shared.generated.resources.sym_chevron_right
import hortay.shared.generated.resources.sym_child_care
import hortay.shared.generated.resources.sym_close
import hortay.shared.generated.resources.sym_cloud_off
import hortay.shared.generated.resources.sym_content_copy
import hortay.shared.generated.resources.sym_data_saver_on
import hortay.shared.generated.resources.sym_delete
import hortay.shared.generated.resources.sym_delete_sweep
import hortay.shared.generated.resources.sym_description
import hortay.shared.generated.resources.sym_download
import hortay.shared.generated.resources.sym_download_for_offline
import hortay.shared.generated.resources.sym_dynamic_feed
import hortay.shared.generated.resources.sym_dynamic_feed_filled
import hortay.shared.generated.resources.sym_edit
import hortay.shared.generated.resources.sym_error
import hortay.shared.generated.resources.sym_flag
import hortay.shared.generated.resources.sym_forum
import hortay.shared.generated.resources.sym_forum_filled
import hortay.shared.generated.resources.sym_gif_box
import hortay.shared.generated.resources.sym_help
import hortay.shared.generated.resources.sym_hide_image
import hortay.shared.generated.resources.sym_home
import hortay.shared.generated.resources.sym_home_filled
import hortay.shared.generated.resources.sym_hourglass_empty
import hortay.shared.generated.resources.sym_how_to_vote
import hortay.shared.generated.resources.sym_image
import hortay.shared.generated.resources.sym_info
import hortay.shared.generated.resources.sym_ios_share
import hortay.shared.generated.resources.sym_lightbulb
import hortay.shared.generated.resources.sym_location_on
import hortay.shared.generated.resources.sym_lock
import hortay.shared.generated.resources.sym_login
import hortay.shared.generated.resources.sym_logout
import hortay.shared.generated.resources.sym_mic
import hortay.shared.generated.resources.sym_mic_off
import hortay.shared.generated.resources.sym_notifications_active
import hortay.shared.generated.resources.sym_notifications_active_filled
import hortay.shared.generated.resources.sym_notifications_off
import hortay.shared.generated.resources.sym_open_in_new
import hortay.shared.generated.resources.sym_pause
import hortay.shared.generated.resources.sym_person
import hortay.shared.generated.resources.sym_person_filled
import hortay.shared.generated.resources.sym_photo_camera
import hortay.shared.generated.resources.sym_pin
import hortay.shared.generated.resources.sym_play_arrow
import hortay.shared.generated.resources.sym_play_circle
import hortay.shared.generated.resources.sym_public
import hortay.shared.generated.resources.sym_push_pin
import hortay.shared.generated.resources.sym_push_pin_filled
import hortay.shared.generated.resources.sym_redeem
import hortay.shared.generated.resources.sym_refresh
import hortay.shared.generated.resources.sym_repeat
import hortay.shared.generated.resources.sym_rocket_launch
import hortay.shared.generated.resources.sym_rss_feed
import hortay.shared.generated.resources.sym_search
import hortay.shared.generated.resources.sym_search_off
import hortay.shared.generated.resources.sym_shield
import hortay.shared.generated.resources.sym_signal_cellular_alt
import hortay.shared.generated.resources.sym_smart_display
import hortay.shared.generated.resources.sym_smartphone
import hortay.shared.generated.resources.sym_storage
import hortay.shared.generated.resources.sym_sync
import hortay.shared.generated.resources.sym_timer
import hortay.shared.generated.resources.sym_translate
import hortay.shared.generated.resources.sym_verified
import hortay.shared.generated.resources.sym_video_call
import hortay.shared.generated.resources.sym_video_camera_front
import hortay.shared.generated.resources.sym_videocam_off
import hortay.shared.generated.resources.sym_visibility
import hortay.shared.generated.resources.sym_visibility_off
import hortay.shared.generated.resources.sym_volume_off
import hortay.shared.generated.resources.sym_volume_up
import hortay.shared.generated.resources.sym_wifi
import hortay.shared.generated.resources.sym_wifi_off
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

/**
 * App-wide icon entry point.
 *
 * Resolves a Material Symbols snake_case name to a bundled `res/drawable/sym_*.xml`
 * vector drawable. Source axis pinned to **Rounded · weight 500 · grade 0 · 24 dp**
 * — Google's canonical pairing for M3 Expressive consumer apps with bold display
 * typography (Plus Jakarta Sans Bold here). Weight 400 reads thin against
 * `displaySmall` 32 sp ExtraBold; weight 500 balances visually without crossing
 * into "loud" weight 600+.
 *
 * Some icons ship a `_filled` companion drawable (e.g. `sym_home_filled.xml`,
 * `sym_bookmark_filled.xml`) for the `filled = true` axis — used to express
 * selected / active state in nav bars and toggles, the M3 Expressive convention.
 * When a drawable lacks a filled twin the parameter is silently a no-op so call
 * sites can pass `filled = isSelected` without branching.
 *
 * To add a new symbol:
 *   1. Visit https://fonts.google.com/icons, pick the icon, choose "Rounded".
 *   2. Set Weight=500, Optical size=24px, Fill=0, Grade=0.
 *   3. Click Android → save as `res/drawable/sym_<name>.xml`.
 *   4. Add a `"<name>" -> Res.drawable.sym_<name>` line below.
 */
@Composable
fun Symbol(
    name: String,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
    tint: Color = LocalContentColor.current,
    size: Dp = 24.dp,
    filled: Boolean = false,
) {
    Icon(
        painter = painterResource(symbolDrawable(name, filled)),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

private fun symbolDrawable(name: String, filled: Boolean = false): DrawableResource {
    if (filled) {
        when (name) {
            "home" -> return Res.drawable.sym_home_filled
            "bookmark" -> return Res.drawable.sym_bookmark_filled
            "person" -> return Res.drawable.sym_person_filled
            "forum" -> return Res.drawable.sym_forum_filled
            "dynamic_feed" -> return Res.drawable.sym_dynamic_feed_filled
            "chat_bubble" -> return Res.drawable.sym_chat_bubble_filled
            "push_pin" -> return Res.drawable.sym_push_pin_filled
            "notifications_active" -> return Res.drawable.sym_notifications_active_filled
            // No filled variant bundled — fall through to the outline below.
        }
    }
    return when (name) {
        "add" -> Res.drawable.sym_add
        "arrow_back" -> Res.drawable.sym_arrow_back
        "arrow_downward" -> Res.drawable.sym_arrow_downward
        "arrow_forward" -> Res.drawable.sym_arrow_forward
        "arrow_upward" -> Res.drawable.sym_arrow_upward
        "audio_file" -> Res.drawable.sym_audio_file
        "ballot" -> Res.drawable.sym_ballot
        "bookmark" -> Res.drawable.sym_bookmark
        "call" -> Res.drawable.sym_call
        "call_end" -> Res.drawable.sym_call_end
        "call_received" -> Res.drawable.sym_call_received
        "campaign" -> Res.drawable.sym_campaign
        // `card_giftcard` / `place` / `poll` were renamed in Material Symbols —
        // keep legacy aliases so older call sites resolve to the modern glyph.
        "card_giftcard" -> Res.drawable.sym_redeem
        "chat_bubble" -> Res.drawable.sym_chat_bubble
        "check_box" -> Res.drawable.sym_check_box
        "check_box_outline_blank" -> Res.drawable.sym_check_box_outline_blank
        "chevron_right" -> Res.drawable.sym_chevron_right
        "child_care" -> Res.drawable.sym_child_care
        "close" -> Res.drawable.sym_close
        "cloud_off" -> Res.drawable.sym_cloud_off
        "content_copy" -> Res.drawable.sym_content_copy
        "data_saver_on" -> Res.drawable.sym_data_saver_on
        "delete" -> Res.drawable.sym_delete
        "delete_sweep" -> Res.drawable.sym_delete_sweep
        "description" -> Res.drawable.sym_description
        "download" -> Res.drawable.sym_download
        "download_for_offline" -> Res.drawable.sym_download_for_offline
        "dynamic_feed" -> Res.drawable.sym_dynamic_feed
        "edit" -> Res.drawable.sym_edit
        "error" -> Res.drawable.sym_error
        "flag" -> Res.drawable.sym_flag
        "forum" -> Res.drawable.sym_forum
        "gif_box" -> Res.drawable.sym_gif_box
        "hide_image" -> Res.drawable.sym_hide_image
        "home" -> Res.drawable.sym_home
        "hourglass_empty" -> Res.drawable.sym_hourglass_empty
        "how_to_vote" -> Res.drawable.sym_how_to_vote
        "image" -> Res.drawable.sym_image
        "info" -> Res.drawable.sym_info
        "ios_share" -> Res.drawable.sym_ios_share
        "lightbulb" -> Res.drawable.sym_lightbulb
        "location_on" -> Res.drawable.sym_location_on
        "lock" -> Res.drawable.sym_lock
        "login" -> Res.drawable.sym_login
        "logout" -> Res.drawable.sym_logout
        "mic" -> Res.drawable.sym_mic
        "mic_off" -> Res.drawable.sym_mic_off
        "notifications_active" -> Res.drawable.sym_notifications_active
        "notifications_off" -> Res.drawable.sym_notifications_off
        "open_in_new" -> Res.drawable.sym_open_in_new
        "pause" -> Res.drawable.sym_pause
        "person" -> Res.drawable.sym_person
        "photo_camera" -> Res.drawable.sym_photo_camera
        "pin" -> Res.drawable.sym_pin
        "place" -> Res.drawable.sym_location_on
        "play_arrow" -> Res.drawable.sym_play_arrow
        "play_circle" -> Res.drawable.sym_play_circle
        "poll" -> Res.drawable.sym_ballot
        "public" -> Res.drawable.sym_public
        "push_pin" -> Res.drawable.sym_push_pin
        "redeem" -> Res.drawable.sym_redeem
        "refresh" -> Res.drawable.sym_refresh
        "repeat" -> Res.drawable.sym_repeat
        "rocket_launch" -> Res.drawable.sym_rocket_launch
        "rss_feed" -> Res.drawable.sym_rss_feed
        "search" -> Res.drawable.sym_search
        "search_off" -> Res.drawable.sym_search_off
        "shield" -> Res.drawable.sym_shield
        "signal_cellular_alt" -> Res.drawable.sym_signal_cellular_alt
        "smart_display" -> Res.drawable.sym_smart_display
        "smartphone" -> Res.drawable.sym_smartphone
        "storage" -> Res.drawable.sym_storage
        "sync" -> Res.drawable.sym_sync
        "timer" -> Res.drawable.sym_timer
        "translate" -> Res.drawable.sym_translate
        "verified" -> Res.drawable.sym_verified
        "video_call" -> Res.drawable.sym_video_call
        "video_camera_front" -> Res.drawable.sym_video_camera_front
        "videocam_off" -> Res.drawable.sym_videocam_off
        "visibility" -> Res.drawable.sym_visibility
        "visibility_off" -> Res.drawable.sym_visibility_off
        "volume_off" -> Res.drawable.sym_volume_off
        "volume_up" -> Res.drawable.sym_volume_up
        "wifi" -> Res.drawable.sym_wifi
        "wifi_off" -> Res.drawable.sym_wifi_off
        else -> Res.drawable.sym_help
    }
}
