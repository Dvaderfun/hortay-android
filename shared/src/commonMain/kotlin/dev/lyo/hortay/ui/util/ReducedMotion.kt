package dev.lyo.hortay.ui.util

import androidx.compose.runtime.Composable
import dev.lyo.hortay.systemAnimatorDurationScale

/**
 * True when the user has disabled animations system-wide (Android Settings → Accessibility
 * "Remove animations" / Developer options "Animator duration scale = Off"; iOS Reduce
 * Motion), which drives [systemAnimatorDurationScale] to `0f`.
 *
 * Gate for CONTENT / gesture animations (spoiler dispersal, pinch-zoom springs,
 * drag-dismiss settle) so motion-sensitive users get an instant snap to the end state
 * instead of bouncy physics or a multi-hundred-ms reveal. Infinite-loop decorations
 * (shimmer, spinners) are intentionally NOT gated here — they read as "still loading"
 * rather than "motion".
 *
 * Reads the scale on every recomposition; cheap (a single platform getter) and the value
 * only changes when the user toggles the OS setting, so no need to remember it.
 */
@Composable
fun rememberReducedMotion(): Boolean = systemAnimatorDurationScale() == 0f
