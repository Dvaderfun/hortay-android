package dev.lyo.hortay.data.archive

import androidx.compose.runtime.Immutable
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import dev.lyo.hortay.data.archive.db.ArchiveDatabase
import dev.lyo.hortay.data.web.WebForwardSource
import dev.lyo.hortay.data.web.WebMedia
import dev.lyo.hortay.data.web.WebPost
import dev.lyo.hortay.data.web.WebPreview
import dev.lyo.hortay.data.web.WebReaction
import dev.lyo.hortay.nowMs
import kotlinx.atomicfu.atomic
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toPersistentList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okio.BufferedSink
import okio.ByteString.Companion.toByteString

/**
 * Single-writer capture API for the post archive.
 *
 * Threading: `capture*` methods are `suspend` and use an internal mutex to serialize writes, so
 * calls from PostsRepository's CAS-loop dispatcher and from WebFeedSource's IO scope race safely.
 * Reads (`observe*`) use SQLDelight's `Query.asFlow()` and do not hold the mutex.
 *
 * TDLib-free by design: operates on the already-extracted [TdlibContentMeta], never raw TdApi, so
 * the whole repository lives in commonMain.
 */
class ArchiveRepository(
    private val db: ArchiveDatabase,
    private val settings: StateFlow<ArchiveSettings>,
    private val mediaStore: ArchivedMediaStore? = null,
    private val releaseScope: CoroutineScope? = null,
    private val clock: () -> Long = ::nowMs,
) {

    private val writeMutex = Mutex()
    private val writeCounter = atomic(0)
    private val capEvictionEvery = 100

    private val _events = MutableSharedFlow<ArchiveEvent>(extraBufferCapacity = 64)
    val events: Flow<ArchiveEvent> = _events

    /** Synchronous accessor for the `enabled` toggle. */
    fun isEnabled(): Boolean = settings.value.enabled

    /**
     * Capture the as-published baseline VERSION row — `seen_at_ms = originalDateMs`,
     * `edited_at_ms = null`. Idempotent via [selectFirstSeenForMessage]: skips when a VERSION row
     * already exists at-or-before [originalDateMs] (handles re-ingest + the edit-lands-first race).
     */
    suspend fun captureTdlibBaseline(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        meta: TdlibContentMeta,
        originalDateMs: Long,
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
        priorEditedAtMs: Long? = null,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        val blob = ContentBlobCodec.encode(meta)
        // Hash a normalized projection so unstable counters (poll voterCount, etc.) can't poison
        // dedup. See [ContentNormalizer].
        val hash = ContentBlobCodec.hash(ContentNormalizer.canonicalBytes(meta))
        writeMutex.withLock {
            val first = db.postSnapshotQueries.selectFirstSeenForMessage(
                chat.kind.name, chat.key, messageKey,
            ).executeAsOneOrNull()
            if (first != null && first.seen_at_ms <= originalDateMs) {
                meta.mediaRef?.localArchiveSha?.let { sha ->
                    runCatching { mediaStore?.releaseRef(sha) }
                }
                return
            }
            insertSnapshot(
                chat = chat, messageKey = messageKey, albumKey = albumKey,
                kind = SnapshotKind.VERSION, editedAtMs = priorEditedAtMs,
                contentKind = "tdlib", blob = blob, hash = hash,
                textPreview = meta.textPreview,
                minithumb = minithumb ?: meta.mediaRef?.minithumbBytes,
                deletedKeys = null, isComment = isComment,
                seenAtMs = originalDateMs,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
    }

    /**
     * Capture an admin-edit VERSION row — `seen_at_ms = clock()`, `edited_at_ms = editedAtMs`.
     * Idempotent on `content_hash` of the latest row.
     */
    suspend fun captureTdlibEdit(
        chat: ChatRef,
        messageKey: String,
        albumKey: String?,
        editedAtMs: Long,
        meta: TdlibContentMeta,
        minithumb: ByteArray? = null,
        isComment: Boolean = false,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        val blob = ContentBlobCodec.encode(meta)
        val hash = ContentBlobCodec.hash(ContentNormalizer.canonicalBytes(meta))
        writeMutex.withLock {
            val existing = db.postSnapshotQueries.latestForMessage(
                chat.kind.name, chat.key, messageKey,
            ).executeAsOneOrNull()
            if (existing?.content_hash == hash) {
                meta.mediaRef?.localArchiveSha?.let { sha ->
                    runCatching { mediaStore?.releaseRef(sha) }
                }
                return
            }
            insertSnapshot(
                chat = chat, messageKey = messageKey, albumKey = albumKey,
                kind = SnapshotKind.VERSION, editedAtMs = editedAtMs,
                contentKind = "tdlib", blob = blob, hash = hash,
                textPreview = meta.textPreview,
                minithumb = minithumb ?: meta.mediaRef?.minithumbBytes,
                deletedKeys = null, isComment = isComment,
                seenAtMs = clock(),
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
    }

    /**
     * Smart-grouped delete capture: recovers each key's `album_key` from VERSION history (cold-start
     * catch-up has no live `_posts` to read `mediaAlbumId` from) and writes one composite
     * [captureTdlibDelete] per album bucket.
     */
    suspend fun captureTdlibDeleteSmart(
        chat: ChatRef,
        messageKeys: List<String>,
        isComment: Boolean,
    ) {
        if (messageKeys.isEmpty()) return
        val byAlbum: Map<String?, List<String>> = writeMutex.withLock {
            messageKeys.groupBy { key ->
                db.postSnapshotQueries.selectAlbumKeyForMessage(
                    chat.kind.name, chat.key, key,
                ).executeAsOneOrNull()?.album_key
            }
        }
        for ((albumKey, keys) in byAlbum) {
            if (albumKey == null) {
                for (k in keys) {
                    captureTdlibDelete(chat, listOf(k), albumKey = null, isComment = isComment)
                }
            } else {
                captureTdlibDelete(chat, keys, albumKey = albumKey, isComment = isComment)
            }
        }
    }

    /** Capture a TDLib delete event (a DELETED marker row). Albums: pass all `messageKeys` together. */
    suspend fun captureTdlibDelete(
        chat: ChatRef,
        messageKeys: List<String>,
        albumKey: String?,
        isComment: Boolean,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureDeletes) return
        if (chat in s.excludedChats) return
        if (messageKeys.isEmpty()) return

        val anchorKey = messageKeys.first()
        val deletedJson = Json.encodeToString(
            ListSerializer(String.serializer()),
            messageKeys,
        )
        writeMutex.withLock {
            val markerBlob = byteArrayOf(0)
            val markerHash = ContentBlobCodec.hash(markerBlob + deletedJson.encodeToByteArray())
            if (isDuplicate(chat, anchorKey, markerHash)) return
            insertSnapshot(
                chat = chat, messageKey = anchorKey, albumKey = albumKey,
                kind = SnapshotKind.DELETED, editedAtMs = null,
                contentKind = "marker", blob = markerBlob, hash = markerHash,
                textPreview = "", minithumb = null,
                deletedKeys = deletedJson, isComment = isComment,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Deleted(chat, messageKeys))
    }

    /** Capture a guest-mode (t.me/s/) version snapshot. The previous [WebPost] is stored as JSON. */
    suspend fun captureWebVersion(
        chat: ChatRef,
        messageKey: String,
        previous: WebPost,
        seenAtOverrideMs: Long? = null,
    ) {
        val s = settings.first()
        if (!s.enabled || !s.captureEdits) return
        if (chat in s.excludedChats) return

        val json = Json.encodeToString(JsonObject.serializer(), webPostToJson(previous))
        val blob = json.encodeToByteArray()
        val hash = ContentBlobCodec.hash(blob)
        writeMutex.withLock {
            if (isDuplicate(chat, messageKey, hash)) return
            db.postSnapshotQueries.insert(
                source_kind = chat.kind.name,
                source_key = chat.key,
                message_key = messageKey,
                album_key = null,
                kind = SnapshotKind.VERSION.name,
                seen_at_ms = seenAtOverrideMs ?: clock(),
                edited_at_ms = null,
                content_kind = "web",
                content_blob = blob,
                content_hash = hash,
                text_preview = previous.textHtml.replace(Regex("<[^>]+>"), "").take(200),
                media_minithumb = null,
                deleted_msg_keys = null,
                is_comment = 0,
            )
            maybeEvictByCap()
        }
        _events.tryEmit(ArchiveEvent.Captured(chat, messageKey))
    }

    /** UPSERT the denormalised channel-index row. Call on every capture. */
    suspend fun upsertChannel(
        chat: ChatRef, title: String, handle: String?,
        photoMinithumb: ByteArray?, isVerified: Boolean,
    ) = writeMutex.withLock {
        val now = clock()
        db.transaction {
            db.archivedChannelQueries.upsertInsert(
                source_kind = chat.kind.name,
                source_key = chat.key,
                title = title, handle = handle,
                photo_minithumb = photoMinithumb,
                is_verified = if (isVerified) 1L else 0L,
                last_snapshot_at_ms = now,
            )
            db.archivedChannelQueries.upsertUpdate(
                title = title, handle = handle,
                photo_minithumb = photoMinithumb,
                is_verified = if (isVerified) 1L else 0L,
                last_snapshot_at_ms = now,
                source_kind = chat.kind.name, source_key = chat.key,
            )
        }
    }

    suspend fun clear() {
        writeMutex.withLock {
            db.transaction {
                db.postSnapshotQueries.clearAll()
                db.archivedChannelQueries.clearAll()
            }
        }
        runCatching { mediaStore?.clearAll() }
    }

    // --- Read API ---

    fun observeRevisions(chat: ChatRef, messageKey: String): Flow<ImmutableList<PostSnapshot>> =
        db.postSnapshotQueries
            .selectRevisions(chat.kind.name, chat.key, messageKey)
            .asFlow()
            .mapToList(Dispatchers.IO)
            .map { rows -> rows.map(::toDomain).toPersistentList() }

    fun observe(filter: ArchiveFilter): Flow<ImmutableList<PostSnapshot>> =
        db.postSnapshotQueries.selectAllForFilter(
            sourceKind = filter.chatKind?.name,
            kind = filter.kind?.name,
            isComment = filter.scope?.toIsCommentFlag(),
            query = filter.query?.let { "%$it%" },
        ).asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.map(::toDomain).toPersistentList() }

    fun observeChannelIndex(): Flow<ImmutableList<ArchivedChannelEntry>> =
        db.archivedChannelQueries.countByChannel()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows ->
                rows.map { r ->
                    ArchivedChannelEntry(
                        chat = ChatRef(SourceKind.valueOf(r.source_kind), r.source_key),
                        title = r.title, handle = r.handle,
                        photoMinithumb = r.photo_minithumb,
                        snapshotCount = r.snapshot_count.toInt(),
                        lastSnapshotAtMs = r.last_snapshot_at_ms,
                    )
                }.toPersistentList()
            }

    suspend fun purge(ids: List<Long>) {
        if (ids.isEmpty()) return
        val shasToRelease: List<String> = writeMutex.withLock {
            db.transactionWithResult {
                val blobs = db.postSnapshotQueries.selectBlobsByIds(ids).executeAsList()
                db.postSnapshotQueries.deleteByIds(ids)
                blobs.mapNotNull { blob ->
                    runCatching { ContentBlobCodec.decode(blob).mediaRef?.localArchiveSha }
                        .getOrNull()?.takeIf { it.isNotEmpty() }
                }
            }
        }
        for (sha in shasToRelease) {
            runCatching { mediaStore?.releaseRef(sha) }
        }
    }

    /**
     * Stream the entire archive as JSON into [sink]. okio-based so the in-memory cost stays bounded
     * by the largest single row (its base64 blob), not the total archive size. Strings are escaped
     * via [JsonPrimitive.toString]; blobs base64'd via okio. @return records written.
     */
    suspend fun exportTo(sink: BufferedSink): Int = writeMutex.withLock {
        var count = 0
        sink.writeUtf8("{\"version\":1,\"exportedAtMs\":${clock()},\"records\":[")
        val rows = db.postSnapshotQueries.selectAllForExport().executeAsList()
        rows.forEachIndexed { i, r ->
            if (i > 0) sink.writeUtf8(",")
            sink.writeUtf8("{")
            sink.writeUtf8("\"sourceKind\":${jsonStr(r.source_kind)},")
            sink.writeUtf8("\"sourceKey\":${jsonStr(r.source_key)},")
            sink.writeUtf8("\"messageKey\":${jsonStr(r.message_key)},")
            sink.writeUtf8("\"kind\":${jsonStr(r.kind)},")
            sink.writeUtf8("\"seenAtMs\":${r.seen_at_ms},")
            sink.writeUtf8("\"textPreview\":${jsonStr(r.text_preview)},")
            sink.writeUtf8("\"contentKind\":${jsonStr(r.content_kind)},")
            sink.writeUtf8("\"contentBlobBase64\":\"${r.content_blob.toByteString().base64()}\"")
            r.media_minithumb?.let {
                sink.writeUtf8(",\"minithumbBase64\":\"${it.toByteString().base64()}\"")
            }
            sink.writeUtf8("}")
            count++
        }
        sink.writeUtf8("]}")
        sink.flush()
        count
    }

    private fun jsonStr(s: String): String = JsonPrimitive(s).toString()

    suspend fun storageBytes(): Long = writeMutex.withLock {
        db.postSnapshotQueries.storageBytes().executeAsOne()
    }

    /**
     * Emits every TDLib-mode DELETED snapshot joined with the latest VERSION + channel metadata.
     * Used by PostsRepository to reconstruct ghost tombstone posts on cold start.
     */
    fun observeTdlibTombstones(): Flow<ImmutableList<TombstoneRecord>> =
        db.postSnapshotQueries.selectTombstonesJoined()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows -> rows.mapNotNull(::buildTombstoneFromJoinedRow).toPersistentList() }

    /** Emits a `(chatId, messageId) → revisionCount` map to seed [revisionCount] on cold start. */
    fun observeTdlibRevisionCounts(): Flow<Map<Pair<Long, Long>, Int>> =
        db.postSnapshotQueries.selectTdlibVersionCounts()
            .asFlow().mapToList(Dispatchers.IO)
            .map { rows ->
                val out = HashMap<Pair<Long, Long>, Int>(rows.size)
                rows.forEach { r ->
                    val chatId = r.source_key.toLongOrNull()
                    val msgId = r.message_key.toLongOrNull()
                    if (chatId != null && msgId != null && r.cnt >= 1L) {
                        out[chatId to msgId] = r.cnt.toInt()
                    }
                }
                out
            }

    private fun buildTombstoneFromJoinedRow(
        row: dev.lyo.hortay.data.archive.db.SelectTombstonesJoined,
    ): TombstoneRecord? {
        val chatId = row.d_source_key.toLongOrNull() ?: return null
        val deletedKeys = row.d_deleted_msg_keys
            ?.let { runCatching { Json.decodeFromString(ListSerializer(String.serializer()), it) }.getOrNull() }
            ?: listOf(row.d_message_key)
        val allMessageIds = deletedKeys.mapNotNull { it.toLongOrNull() }
        val primaryId = allMessageIds.firstOrNull() ?: return null

        val meta = runCatching { ContentBlobCodec.decode(row.v_content_blob) }.getOrNull()
            ?: return null

        return TombstoneRecord(
            chatId = chatId,
            primaryMessageId = primaryId,
            allMessageIds = allMessageIds,
            deletedAtMs = row.d_seen_at_ms,
            originalSeenAtMs = row.v_seen_at_ms,
            text = meta.text,
            channelTitle = row.c_title,
            channelHandle = row.c_handle,
            channelPhotoMinithumb = row.c_photo_minithumb,
            isVerified = row.c_is_verified == 1L,
        )
    }

    // --- Private helpers ---

    private fun isDuplicate(chat: ChatRef, messageKey: String, hash: String): Boolean {
        val last = db.postSnapshotQueries.latestForMessage(
            chat.kind.name, chat.key, messageKey,
        ).executeAsOneOrNull() ?: return false
        return last.content_hash == hash
    }

    private fun insertSnapshot(
        chat: ChatRef, messageKey: String, albumKey: String?,
        kind: SnapshotKind, editedAtMs: Long?, contentKind: String,
        blob: ByteArray, hash: String, textPreview: String,
        minithumb: ByteArray?, deletedKeys: String?, isComment: Boolean,
        seenAtMs: Long = clock(),
    ) {
        db.postSnapshotQueries.insert(
            source_kind = chat.kind.name,
            source_key = chat.key,
            message_key = messageKey,
            album_key = albumKey,
            kind = kind.name,
            seen_at_ms = seenAtMs,
            edited_at_ms = editedAtMs,
            content_kind = contentKind,
            content_blob = blob,
            content_hash = hash,
            text_preview = textPreview,
            media_minithumb = minithumb,
            deleted_msg_keys = deletedKeys,
            is_comment = if (isComment) 1L else 0L,
        )
    }

    private fun maybeEvictByCap() {
        val count = writeCounter.incrementAndGet()
        if (count % capEvictionEvery != 0) return
        val cap = settings.value.maxRecords
        if (cap == Int.MAX_VALUE) return
        val store = mediaStore
        val rs = releaseScope
        if (store == null || rs == null) {
            db.postSnapshotQueries.deleteByCap(cap.toLong())
            return
        }
        val blobs = db.transactionWithResult {
            val snapshot = db.postSnapshotQueries.selectBlobsByCap(cap.toLong()).executeAsList()
            db.postSnapshotQueries.deleteByCap(cap.toLong())
            snapshot
        }
        if (blobs.isEmpty()) return
        rs.launch {
            for (blob in blobs) {
                val sha = runCatching {
                    ContentBlobCodec.decode(blob).mediaRef?.localArchiveSha
                }.getOrNull()
                if (!sha.isNullOrEmpty()) {
                    runCatching { store.releaseRef(sha) }
                }
            }
        }
    }

    /** Empty stand-in for a row we can't decode — see [decodeContent]. */
    private fun unsupportedContent(): ArchivedContent.Tdlib = ArchivedContent.Tdlib(
        TdlibContentMeta(
            text = "", entitiesJson = "[]",
            mediaSummaryJson = null, pollJson = null,
            forwardJson = null, replyJson = null,
        ),
    )

    /** Decode a row's `content_blob` into an [ArchivedContent], degrading gracefully on failure. */
    private fun decodeContent(row: dev.lyo.hortay.data.archive.db.PostSnapshot): ArchivedContent =
        runCatching {
            when (row.content_kind) {
                "tdlib" -> ArchivedContent.Tdlib(ContentBlobCodec.decode(row.content_blob))
                "web" -> ArchivedContent.Web(
                    webPostFromJson(
                        Json.decodeFromString(
                            JsonObject.serializer(),
                            row.content_blob.decodeToString(),
                        ),
                    ),
                )
                else -> unsupportedContent()
            }
        }.getOrElse { unsupportedContent() }

    private fun toDomain(row: dev.lyo.hortay.data.archive.db.PostSnapshot): PostSnapshot {
        val content = decodeContent(row)
        val deletedKeys = row.deleted_msg_keys?.let {
            Json.decodeFromString(ListSerializer(String.serializer()), it)
        } ?: emptyList()
        return PostSnapshot(
            id = row.id,
            chat = ChatRef(SourceKind.valueOf(row.source_kind), row.source_key),
            messageKey = row.message_key,
            albumKey = row.album_key,
            kind = SnapshotKind.valueOf(row.kind),
            seenAtMs = row.seen_at_ms,
            editedAtMs = row.edited_at_ms,
            content = content,
            mediaMinithumb = row.media_minithumb,
            deletedMessageKeys = deletedKeys.toPersistentList(),
            isComment = row.is_comment == 1L,
        )
    }

    /** Serializes [WebPost] to a [JsonObject] without requiring [WebPost] to carry `@Serializable`. */
    private fun webPostToJson(post: WebPost): JsonObject = buildJsonObject {
        put("id", post.id)
        put("seq", post.seq)
        put("publishedAt", post.publishedAt)
        put("textHtml", post.textHtml)
        putJsonArray("media") {
            post.media.forEach { add(Json.encodeToJsonElement(WebMedia.serializer(), it)) }
        }
        post.webPreview?.let { put("webPreview", Json.encodeToJsonElement(WebPreview.serializer(), it)) }
        post.forwardedFrom?.let { put("forwardedFrom", Json.encodeToJsonElement(WebForwardSource.serializer(), it)) }
        post.views?.let { put("views", it) }
        putJsonArray("reactions") {
            post.reactions.forEach { add(Json.encodeToJsonElement(WebReaction.serializer(), it)) }
        }
    }

    /** Deserializes a [JsonObject] produced by [webPostToJson] back into a [WebPost]. */
    private fun webPostFromJson(obj: JsonObject): WebPost {
        val mediaList = obj["media"]?.let { el ->
            Json.decodeFromJsonElement(ListSerializer(WebMedia.serializer()), el)
        } ?: emptyList()
        val reactionList = obj["reactions"]?.let { el ->
            Json.decodeFromJsonElement(ListSerializer(WebReaction.serializer()), el)
        } ?: emptyList()
        return WebPost(
            id = (obj["id"] as? JsonPrimitive)?.content ?: "",
            seq = (obj["seq"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L,
            publishedAt = (obj["publishedAt"] as? JsonPrimitive)?.content ?: "",
            textHtml = (obj["textHtml"] as? JsonPrimitive)?.content ?: "",
            media = mediaList.toPersistentList(),
            webPreview = obj["webPreview"]?.let {
                Json.decodeFromJsonElement(WebPreview.serializer(), it)
            },
            forwardedFrom = obj["forwardedFrom"]?.let {
                Json.decodeFromJsonElement(WebForwardSource.serializer(), it)
            },
            views = (obj["views"] as? JsonPrimitive)?.content,
            reactions = reactionList.toPersistentList(),
        )
    }
}

sealed interface ArchiveEvent {
    data class Captured(val chat: ChatRef, val messageKey: String) : ArchiveEvent
    data class Deleted(val chat: ChatRef, val messageKeys: List<String>) : ArchiveEvent
}

@Immutable
data class ArchiveFilter(
    val chatKind: SourceKind? = null,
    val kind: SnapshotKind? = null,
    val scope: ArchiveScope? = null,
    val query: String? = null,
)

enum class ArchiveScope {
    POSTS, COMMENTS, ALL;
    fun toIsCommentFlag(): Long? = when (this) {
        POSTS -> 0L; COMMENTS -> 1L; ALL -> null
    }
}

@Immutable
data class ArchivedChannelEntry(
    val chat: ChatRef,
    val title: String,
    val handle: String?,
    val photoMinithumb: ByteArray?,
    val snapshotCount: Int,
    val lastSnapshotAtMs: Long,
)

/**
 * Denormalised record built from a DELETED row + latest VERSION + cached channel metadata. Enough
 * to reconstruct a minimal "ghost" feed card after TDLib forgets the message.
 */
@Immutable
data class TombstoneRecord(
    val chatId: Long,
    val primaryMessageId: Long,
    val allMessageIds: List<Long>,
    val deletedAtMs: Long,
    val originalSeenAtMs: Long,
    val text: String,
    val channelTitle: String,
    val channelHandle: String?,
    val channelPhotoMinithumb: ByteArray?,
    val isVerified: Boolean,
)
