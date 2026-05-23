package dev.lyo.hortay.data.posts

/**
 * Result of `HortayBackend.resolvePublicHandle` (Android: PostsRepository).
 * Discriminator for the three meaningful outcomes the UI handles:
 *
 *   - [Channel] — handle resolved to a channel-style supergroup chat the app
 *     can open in-place.
 *   - [User] — handle is a 1:1 user (or bot). Carries TDLib `userId` so
 *     callers can open the in-app `UserProfileSheet` without re-resolving.
 *   - [Unsupported] — handle resolved to a non-channel kind (regular group,
 *     basic supergroup, …). Caller surfaces a kind-keyed snackbar.
 *   - [NotFound] — handle is invalid / banned / never existed.
 */
sealed interface PublicHandleResult {
    data class Channel(val chatId: Long) : PublicHandleResult
    data class User(val userId: Long) : PublicHandleResult
    data class Unsupported(val kind: PublicHandleKind) : PublicHandleResult
    data object NotFound : PublicHandleResult
}

/**
 * Discriminator carried by [PublicHandleResult.Unsupported]. Users are
 * routed via [PublicHandleResult.User] directly, so this enum only covers
 * things Hortay surfaces to the OS / a snackbar (groups, supergroups,
 * unrecognised entities).
 */
enum class PublicHandleKind { Group, Unknown }
