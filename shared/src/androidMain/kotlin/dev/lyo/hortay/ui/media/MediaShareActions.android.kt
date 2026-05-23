package dev.lyo.hortay.ui.media

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.getSystemService
import coil3.SingletonImageLoader
import dev.lyo.hortay.data.AlbumItem
import dev.lyo.hortay.data.PlatformContextHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import hortay.shared.generated.resources.Res
import hortay.shared.generated.resources.media_share_error_clipboard_unavailable
import hortay.shared.generated.resources.media_share_error_insert_failed
import hortay.shared.generated.resources.media_share_error_intent_failed
import hortay.shared.generated.resources.media_share_error_mkdir
import hortay.shared.generated.resources.media_share_error_only_photos
import hortay.shared.generated.resources.media_share_error_source_missing

/**
 * Android-side [MediaShareActions]. See the interface doc for the contract.
 *
 * Routing:
 *   • Save → bytes are streamed through MediaStore's relative-path API into
 *     `Pictures/Hortay/...` (photos) or `Movies/Hortay/...` (videos). The
 *     Q+ path uses the `IS_PENDING` two-phase write so a half-streamed file
 *     never becomes visible to gallery apps mid-copy. Pre-Q falls back to
 *     `Environment.getExternalStoragePublicDirectory(...)` + `MediaScanner`.
 *
 *   • Copy → bytes stay where TDLib put them; we mint a temporary read URI
 *     through the app's [FileProvider] (authority
 *     `${applicationId}.fileprovider`) and put it into [ClipData]. Photo only:
 *     most chat apps and document editors accept image clipboard items,
 *     almost none accept video clipboard items, and a ClipData of a 200 MB
 *     video URI is a UX trap.
 *
 *   • Share → routes through `ACTION_SEND` with a FileProvider URI (bytes
 *     stay in place — fast for big videos).
 *
 * Telegram-Android places its save folder at `Pictures/Telegram` /
 * `Movies/Telegram`; we use `Pictures/Hortay` / `Movies/Hortay` so saved
 * media is recognisably ours in the gallery.
 */
object AndroidMediaShareActions : MediaShareActions {

    private const val TAG = "MediaShareActions"
    private const val SUBFOLDER = "Hortay"

    override fun isPersistable(localPath: String?): Boolean =
        !localPath.isNullOrBlank() && File(localPath).run { exists() && length() > 0L }

    override suspend fun saveToGallery(
        item: AlbumItem,
        localPath: String,
    ): MediaShareActions.Result = withContext(Dispatchers.IO) {
        val context = PlatformContextHolder.require()
        val src = File(localPath)
        if (!src.exists()) return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_source_missing)

        val isVideo = item !is AlbumItem.Photo
        val mime = item.guessMimeType()
        val displayName = buildDisplayName(mime, isVideo)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, src, displayName, mime, isVideo)
            } else {
                saveViaLegacyPath(context, src, displayName, mime, isVideo)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "saveToGallery failed", t)
            MediaShareActions.Result.Failure(
                reasonResId = Res.string.media_share_error_insert_failed,
                debugDetail = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    override suspend fun copyToClipboard(
        item: AlbumItem,
        localPath: String,
    ): MediaShareActions.Result = withContext(Dispatchers.IO) {
        val context = PlatformContextHolder.require()
        if (item !is AlbumItem.Photo) return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_only_photos)
        val src = File(localPath)
        if (!src.exists()) return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_source_missing)
        val mime = item.guessMimeType()

        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                src,
            )
            val clip = ClipData.newUri(context.contentResolver, "Hortay image", uri).apply {
                description.extras = android.os.PersistableBundle().apply {
                    putString("mimeType", mime)
                }
            }
            val cm = context.getSystemService<ClipboardManager>()
                ?: return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_clipboard_unavailable)
            cm.setPrimaryClip(clip)
            // Without the read-grant flag a paster on Q+ receives a SecurityException
            // when resolving the URI. ClipData.newUri does NOT auto-grant on its own.
            context.grantUriPermission(
                "android",
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            MediaShareActions.Result.Success
        } catch (t: Throwable) {
            Log.w(TAG, "copyToClipboard failed", t)
            MediaShareActions.Result.Failure(
                reasonResId = Res.string.media_share_error_clipboard_unavailable,
                debugDetail = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    override suspend fun shareMedia(
        item: AlbumItem,
        localPath: String,
    ): MediaShareActions.Result = withContext(Dispatchers.IO) {
        val context = PlatformContextHolder.require()
        val src = File(localPath)
        if (!src.exists()) return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_source_missing)
        val mime = item.guessMimeType()

        try {
            val uri = FileProvider.getUriForFile(
                context,
                context.packageName + ".fileprovider",
                src,
            )
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            val chooser = Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            if (send.resolveActivity(context.packageManager) == null) {
                return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_intent_failed)
            }
            context.startActivity(chooser)
            MediaShareActions.Result.Success
        } catch (t: Throwable) {
            Log.w(TAG, "shareMedia failed", t)
            MediaShareActions.Result.Failure(
                reasonResId = Res.string.media_share_error_intent_failed,
                debugDetail = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    override suspend fun shareUrl(url: String): MediaShareActions.Result = withContext(Dispatchers.IO) {
        val context = PlatformContextHolder.require()
        if (url.isBlank()) return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_source_missing)
        try {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
            }
            if (send.resolveActivity(context.packageManager) == null) {
                return@withContext MediaShareActions.Result.Failure(Res.string.media_share_error_intent_failed)
            }
            val chooser = Intent.createChooser(send, null).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
            MediaShareActions.Result.Success
        } catch (t: Throwable) {
            Log.w(TAG, "shareUrl failed", t)
            MediaShareActions.Result.Failure(
                reasonResId = Res.string.media_share_error_intent_failed,
                debugDetail = t.message ?: t.javaClass.simpleName,
            )
        }
    }

    /**
     * Snapshot is opened then immediately closed — Coil's disk cache uses
     * journaled writes (Okio FileSystem snapshots survive past close), so
     * the returned path remains valid until cache eviction.
     */
    override suspend fun coilCachePath(url: String): String? = withContext(Dispatchers.IO) {
        val context = PlatformContextHolder.require()
        if (url.isBlank()) return@withContext null
        val cache = SingletonImageLoader.get(context).diskCache ?: return@withContext null
        val snapshot = cache.openSnapshot(url) ?: return@withContext null
        try {
            snapshot.data.toString()
        } finally {
            snapshot.close()
        }
    }

    private fun saveViaMediaStore(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
        isVideo: Boolean,
    ): MediaShareActions.Result {
        val resolver = context.contentResolver
        val collection = if (isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relative = (if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES) +
            "/" + SUBFOLDER

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
            put(MediaStore.MediaColumns.SIZE, src.length())
        }

        val uri = resolver.insert(collection, values)
            ?: return MediaShareActions.Result.Failure(Res.string.media_share_error_insert_failed)
        try {
            resolver.openOutputStream(uri, "w")?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            } ?: error("openOutputStream returned null")

            val finalise = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            resolver.update(uri, finalise, null, null)
            return MediaShareActions.Result.Success
        } catch (t: Throwable) {
            resolver.delete(uri, null, null)
            throw t
        }
    }

    @Suppress("DEPRECATION")
    private fun saveViaLegacyPath(
        context: Context,
        src: File,
        displayName: String,
        mime: String,
        isVideo: Boolean,
    ): MediaShareActions.Result {
        val rootName = if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
        val dir = File(Environment.getExternalStoragePublicDirectory(rootName), SUBFOLDER)
        if (!dir.exists() && !dir.mkdirs()) {
            return MediaShareActions.Result.Failure(
                reasonResId = Res.string.media_share_error_mkdir,
                args = listOf(dir.toString()),
            )
        }
        val dest = File(dir, displayName)
        src.inputStream().use { input ->
            dest.outputStream().use { out -> input.copyTo(out) }
        }
        android.media.MediaScannerConnection.scanFile(
            context,
            arrayOf(dest.absolutePath),
            arrayOf(mime),
            null,
        )
        return MediaShareActions.Result.Success
    }

    /** "Hortay_20260511_142233.jpg" — sortable in any file manager. */
    private fun buildDisplayName(mime: String, isVideo: Boolean): String {
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val ext = extensionFor(mime, isVideo)
        return "Hortay_$ts.$ext"
    }

    private fun extensionFor(mime: String, isVideo: Boolean): String =
        when (mime.lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/quicktime" -> "mov"
            else -> if (isVideo) "mp4" else "jpg"
        }

    /**
     * Best-effort MIME for the gallery / clipboard payload. TDLib does not
     * propagate `mime_type` through to the [AlbumItem] graph; for photos this
     * is always JPEG (Telegram re-encodes uploads server-side) and for videos
     * / animations overwhelmingly MP4.
     */
    private fun AlbumItem.guessMimeType(): String = when (this) {
        is AlbumItem.Photo -> "image/jpeg"
        is AlbumItem.Video -> "video/mp4"
        is AlbumItem.Animation -> "video/mp4"
    }
}
