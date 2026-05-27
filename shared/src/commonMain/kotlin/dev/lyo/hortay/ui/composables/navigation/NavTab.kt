package dev.lyo.hortay.ui.composables.navigation

import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.nav_channels
import hortay.shared.generated.resources.nav_feed
import hortay.shared.generated.resources.nav_profile
import hortay.shared.generated.resources.nav_saved
import org.jetbrains.compose.resources.StringResource

/**
 * Bottom-nav tabs. [symbol] is the Material Symbols ligature name (rendered via the
 * [dev.lyo.hortay.ui.icons.Symbol] composable in [FloatingNavBar]). [labelRes] is
 * resolved at draw time with `stringResource` so the tab name follows the active
 * locale.
 */
enum class NavTab(val labelRes: StringResource, val symbol: String) {
    Feed(Res.string.nav_feed, "home"),
    Channels(Res.string.nav_channels, "dynamic_feed"),
    Saved(Res.string.nav_saved, "bookmark"),
    Profile(Res.string.nav_profile, "person"),
}
