package dev.lyo.hortay.data.archive

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.protobuf.ProtoBuf
import okio.Buffer
import okio.ByteString.Companion.toByteString

/**
 * Encodes and decodes [TdlibContentMeta] to/from a compact on-disk binary blob.
 *
 * ## Format
 * A 4-byte magic header (`TDLB`) + 4-byte version + ProtoBuf-encoded [TdlibContentMeta]. The
 * magic + version prefix lets the archive DB detect format drift on read and surface a graceful
 * "snapshot format unsupported" rather than a deserialization crash.
 *
 * Framing + SHA-256 go through okio ([Buffer] / [okio.ByteString]) rather than `java.io.*` /
 * `java.security.MessageDigest` so the codec compiles for every KMP target (the original used
 * JVM-only APIs). ProtoBuf is `kotlinx-serialization-protobuf` (KMP-native).
 *
 * ## Why no raw TdApi.MessageContent
 * `TdApi.MessageContent` is a generated class with no binary serialization API. The
 * [TdlibContentMeta] fields carry all the information needed for archive diffing and search;
 * TDLib's own local DB remains the source of truth for live content.
 */
@OptIn(ExperimentalSerializationApi::class)
object ContentBlobCodec {

    private const val MAGIC: Int = 0x54444C42 // "TDLB"
    private const val VERSION: Int = 1

    /**
     * Encodes [meta] into a versioned binary blob. Deterministic: encoding the same
     * [TdlibContentMeta] always produces identical bytes.
     */
    fun encode(meta: TdlibContentMeta): ByteArray {
        val protoBytes = ProtoBuf.encodeToByteArray(TdlibContentMeta.serializer(), meta)
        val buf = Buffer()
        buf.writeInt(MAGIC)   // okio writeInt is big-endian, matching DataOutputStream
        buf.writeInt(VERSION)
        buf.write(protoBytes)
        return buf.readByteArray()
    }

    /**
     * Decodes a blob produced by [encode].
     * @throws IllegalArgumentException on magic/version mismatch.
     */
    fun decode(blob: ByteArray): TdlibContentMeta {
        val buf = Buffer().apply { write(blob) }
        val magic = buf.readInt()
        require(magic == MAGIC) { "Not a content blob (magic 0x${magic.toString(16)})" }
        val version = buf.readInt()
        require(version == VERSION) { "Unsupported blob version $version" }
        val protoBytes = buf.readByteArray()
        return ProtoBuf.decodeFromByteArray(TdlibContentMeta.serializer(), protoBytes)
    }

    /**
     * Returns the SHA-256 hex digest of [blob]. Used by the archive repository to detect content
     * changes (unchanged blobs → no new snapshot row).
     */
    fun hash(blob: ByteArray): String = blob.toByteString().sha256().hex()
}
