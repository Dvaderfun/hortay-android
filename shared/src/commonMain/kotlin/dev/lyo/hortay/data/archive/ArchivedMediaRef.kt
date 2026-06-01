@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import kotlinx.serialization.Serializable
import kotlinx.serialization.protobuf.ProtoNumber

/**
 * Snapshot of a single media attachment captured alongside a [TdlibContentMeta] VERSION.
 *
 * Two redundant identity systems live here on purpose (per tdlib/td#1025):
 *  - **`uniqueId`** — content-addressed; stable across launches and users. The persistent
 *    primary key for diffing "is this the same physical file across two revisions?".
 *  - **`remoteId`** — TDLib's reusable identifier; can be fed to `GetRemoteFile` →
 *    `DownloadFile`. Mutates over time; captured value is a snapshot.
 *  - **`localArchiveSha`** — Tier 2: when the file was on disk at capture we copy it into the
 *    archive's permanent storage, indexed by SHA-256. Survives TDLib LRU eviction, post
 *    deletion, even logout. Payload lives in `ArchivedMediaFile`.
 *  - **`minithumbBytes`** — Telegram's inline ~40px JPEG preview. Renders instantly; the only
 *    universally-available fallback.
 *
 * ProtoBuf field numbers are pinned so a future field addition stays backward compatible.
 */
@Immutable
@Serializable
data class ArchivedMediaRef(
    @ProtoNumber(1) val type: String,
    @ProtoNumber(2) val width: Int = 0,
    @ProtoNumber(3) val height: Int = 0,
    @ProtoNumber(4) val durationMs: Long = 0L,
    @ProtoNumber(5) val sizeBytes: Long = 0L,
    @ProtoNumber(6) val mimeType: String? = null,
    @ProtoNumber(7) val fileName: String? = null,
    @ProtoNumber(8) val remoteId: String = "",
    @ProtoNumber(9) val uniqueId: String = "",
    @ProtoNumber(10) val minithumbBytes: ByteArray? = null,
    @ProtoNumber(11) val localArchiveSha: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ArchivedMediaRef) return false
        return type == other.type &&
            width == other.width && height == other.height &&
            durationMs == other.durationMs && sizeBytes == other.sizeBytes &&
            mimeType == other.mimeType && fileName == other.fileName &&
            remoteId == other.remoteId && uniqueId == other.uniqueId &&
            (minithumbBytes ?: ByteArray(0)).contentEquals(other.minithumbBytes ?: ByteArray(0)) &&
            localArchiveSha == other.localArchiveSha
    }

    override fun hashCode(): Int {
        var r = type.hashCode()
        r = 31 * r + width; r = 31 * r + height
        r = 31 * r + durationMs.hashCode(); r = 31 * r + sizeBytes.hashCode()
        r = 31 * r + (mimeType?.hashCode() ?: 0)
        r = 31 * r + (fileName?.hashCode() ?: 0)
        r = 31 * r + remoteId.hashCode(); r = 31 * r + uniqueId.hashCode()
        r = 31 * r + (minithumbBytes?.contentHashCode() ?: 0)
        r = 31 * r + (localArchiveSha?.hashCode() ?: 0)
        return r
    }
}
