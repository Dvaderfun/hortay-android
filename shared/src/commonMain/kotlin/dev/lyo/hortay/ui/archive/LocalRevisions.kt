package dev.lyo.hortay.ui.archive

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.TimelinePost

/**
 * Feed-level opener for the post revision sheet. A tapped `EditedChip` / `DeletedBadge`
 * invokes this with the post; the scaffold provides an implementation that loads the
 * message's snapshots from the archive and mounts [PostRevisionSheet]. Default no-op so
 * surfaces without archive wiring (previews, guest mode, tests) compile + render inertly.
 */
val LocalOpenRevisions = staticCompositionLocalOf<(TimelinePost) -> Unit> { {} }
