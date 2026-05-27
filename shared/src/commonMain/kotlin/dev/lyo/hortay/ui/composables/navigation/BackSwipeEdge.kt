package dev.lyo.hortay.ui.composables.navigation

/**
 * Mirror of `androidx.activity.BackEventCompat.EDGE_*` for commonMain
 * consumers. Predictive-back screens use these to anchor the swipe-pivot
 * transform to whichever side the user started from.
 *
 * iOS has no equivalent gesture surface yet; the constants exist so screens
 * compile on both targets, and the iOS placeholder always passes
 * [BackSwipeEdge.Left].
 */
object BackSwipeEdge {
    const val Left: Int = 0
    const val Right: Int = 1
}
