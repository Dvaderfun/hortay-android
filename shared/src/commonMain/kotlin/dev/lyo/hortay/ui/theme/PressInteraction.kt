@file:Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")

package dev.lyo.hortay.ui.theme

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Dp

/**
 * Animated corner-radius selector driven by press + selected state. Pulled out
 * of Shape.kt because Shape.kt's polygonal-morph code references MaterialShapes
 * which CMP's iOS publication doesn't expose — but this helper only depends on
 * MotionScheme (Expressive annotation, accessible via INVISIBLE_REFERENCE
 * suppress), so it can live in commonMain and serve both targets.
 *
 * The animation runs on `MotionScheme.fastSpatialSpec` so the rounding visibly
 * bounces between states; spatial channel is the right physics for size/geometry
 * transitions (vs effects, which is for colour/opacity crossfades). Press takes
 * priority over selection — if the user presses an already-selected chip, they
 * see the squish, not the pill, until they release.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun rememberPressedSelectedCornerRadius(
    interactionSource: InteractionSource,
    selected: Boolean,
    rest: Dp,
    pressed: Dp,
    selectedRadius: Dp,
    label: String = "press-corner",
): State<Dp> {
    val isPressed by interactionSource.collectIsPressedAsState()
    return animateDpAsState(
        targetValue = when {
            isPressed -> pressed
            selected -> selectedRadius
            else -> rest
        },
        animationSpec = MaterialTheme.motionScheme.fastSpatialSpec(),
        label = label,
    )
}
