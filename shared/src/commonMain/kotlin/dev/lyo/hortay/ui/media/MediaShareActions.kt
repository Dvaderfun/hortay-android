package dev.lyo.hortay.ui.media

import androidx.compose.runtime.staticCompositionLocalOf
import dev.lyo.hortay.data.AlbumItem
import org.jetbrains.compose.resources.StringResource
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.media_share_error_source_missing

/**
 * "Save" / "Copy" / "Share" actions for the fullscreen media viewer.
 *
 * Android backs this with MediaStore (Save), FileProvider + ClipData (Copy),
 * and `ACTION_SEND` (Share). iOS guest mode currently no-ops every method —
 * the viewer chrome is hidden on iOS until PHPhotoLibrary / `UIPasteboard` /
 * `UIActivityViewController` plumbing lands in Phase II.
 */
interface MediaShareActions {

    sealed interface Result {
        data object Success : Result
        /**
         * Localised failure payload. [reasonResId] is the user-facing string,
         * [args] feed any `%1$s` / `%2$d` format-args. [debugDetail] is an
         * English diagnostic string surfaced to logcat / crash reports only —
         * never to UI.
         */
        data class Failure(
            val reasonResId: StringResource,
            val args: List<Any> = emptyList(),
            val debugDetail: String? = null,
        ) : Result
    }

    /**
     * True when [Result] of a save/copy is meaningful for the given item — i.e.
     * the file is actually local. Callers gate their button enabled state on
     * this so the affordance does not appear on a Failed / Downloading slot.
     */
    fun isPersistable(localPath: String?): Boolean

    /** Save the bytes at [localPath] into the public gallery. */
    suspend fun saveToGallery(item: AlbumItem, localPath: String): Result

    /** Photo-only. Puts a clipboard item carrying the image bytes. */
    suspend fun copyToClipboard(item: AlbumItem, localPath: String): Result

    /** Hand the file at [localPath] to the system share sheet. */
    suspend fun shareMedia(item: AlbumItem, localPath: String): Result

    /**
     * Guest-mode fallback: send the source CDN URL as `text/plain`. Used by
     * the viewer's Share button when neither TDLib nor Coil has a local copy.
     */
    suspend fun shareUrl(url: String): Result

    /**
     * Resolve [url] in Coil's disk cache → absolute file path on disk, or
     * null if Coil never loaded or evicted it.
     */
    suspend fun coilCachePath(url: String): String?
}

/**
 * Default impl is a no-op so previews + iOS guest mode compile without
 * platform wiring. `MainActivity` / `MainViewController` provides the real
 * impl at the top of the composition.
 */
val LocalMediaShareActions = staticCompositionLocalOf<MediaShareActions> {
    NoopMediaShareActions
}

private object NoopMediaShareActions : MediaShareActions {
    override fun isPersistable(localPath: String?): Boolean = false
    override suspend fun saveToGallery(item: AlbumItem, localPath: String): MediaShareActions.Result =
        MediaShareActions.Result.Failure(reasonResId = noopResId())
    override suspend fun copyToClipboard(item: AlbumItem, localPath: String): MediaShareActions.Result =
        MediaShareActions.Result.Failure(reasonResId = noopResId())
    override suspend fun shareMedia(item: AlbumItem, localPath: String): MediaShareActions.Result =
        MediaShareActions.Result.Failure(reasonResId = noopResId())
    override suspend fun shareUrl(url: String): MediaShareActions.Result =
        MediaShareActions.Result.Failure(reasonResId = noopResId())
    override suspend fun coilCachePath(url: String): String? = null

    private fun noopResId(): StringResource =
        Res.string.media_share_error_source_missing
}

/**
 * Resolve the local path to copy/save for [item]. For [AlbumItem.Photo] this
 * is the fullscreen variant the viewer is painting; for [AlbumItem.Video] the
 * playback file the user is actually watching (picked quality, falls back to
 * default); for [AlbumItem.Animation] the playback file. Web-mode placeholders
 * (fileId == 0) and items without a Ready slot return null.
 */
fun AlbumItem.viewerFileId(activeQuality: Int? = null): Int? = when (this) {
    is AlbumItem.Photo -> fullscreen.fileId.takeIf { it != null && it != 0 }
    is AlbumItem.Video -> activeQuality ?: qualities.defaultPick.fileId.takeIf { it != 0 }
    is AlbumItem.Animation -> playbackFileId.takeIf { it != 0 }
}

/** True when the viewer should expose Save/Copy chrome for this item. */
fun AlbumItem.canBePersisted(): Boolean = viewerFileId() != null
