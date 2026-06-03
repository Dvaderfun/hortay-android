package dev.lyo.hortay.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import dev.lyo.hortay.tdlib.TdApi

/**
 * Per-network auto-download policy backed by TDLib's native `autoDownloadSettings`
 * (`setAutoDownloadSettings` per [TdApi.NetworkType]) with a DataStore JSON cache
 * for the cold-start window before `authStage` reaches [AuthStage.Ready].
 *
 * **Why the split.** TDLib owns auto-download settings server-side and syncs them
 * across the user's devices via `autoDownloadSettings`. The previous shape stored
 * the policy only in DataStore JSON — a parallel store TDLib didn't see — so a
 * setting changed in the official Telegram client never reached Hortay, and vice
 * versa. Routing writes through `SetAutoDownloadSettings` fixes that.
 *
 * **Why DataStore stays as a cache.** TDLib exposes no `GetAutoDownloadSettings`
 * for the *current* per-network values — only `GetAutoDownloadSettingsPresets`
 * (default low/medium/high). We persist last-written values locally so the UI
 * has something to paint between process start and `AuthStage.Ready`, and so
 * [MediaAutoDownloader]'s prefetch decisions don't fall back to the hard-coded
 * defaults during that window for returning users. Once auth is ready, every
 * mutation also fires `SetAutoDownloadSettings` for the relevant network so the
 * setting travels with the account.
 *
 * **First-run seeding.** When DataStore has no record yet, the store waits for
 * `AuthStage.Ready` and calls `GetAutoDownloadSettingsPresets` once. The
 * `low`/`medium`/`high` triple seeds Roaming/Mobile/Wi-Fi respectively, matching
 * TDLib's own intended preset mapping. Failures fall through to the in-process
 * [AutoDownloadSettings.DEFAULT] values, which are tuned to the same shape.
 */
class AutoDownloadStore(
    context: Context,
    private val td: TdSender,
    authStage: StateFlow<AuthStage>,
    scope: CoroutineScope,
) : AutoDownloadFacade {

    private val dataStore = context.applicationContext.autoDownloadDataStore
    private val json = Json {
        ignoreUnknownKeys = true
        // Serializing with defaults present keeps the on-disk JSON self-describing —
        // useful when poking the file from `adb shell` to debug a user-reported issue.
        encodeDefaults = true
    }

    /**
     * Hot, cached view of the user's policy. StateFlow so consumers
     * ([MediaAutoDownloader], UI) read the latest known value synchronously
     * without waiting on a DataStore disk hop. Started [SharingStarted.Eagerly]
     * to seed the cache as soon as the graph is built — the prefetcher reads
     * it on every real-time post arrival.
     */
    override val settings: StateFlow<AutoDownloadSettings> = dataStore.data
        .map { prefs -> prefs[KEY_SETTINGS]?.decodeOrNull() ?: AutoDownloadSettings.DEFAULT }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, AutoDownloadSettings.DEFAULT)

    init {
        // Seed first-run defaults from TDLib's `getAutoDownloadSettingsPresets` once
        // auth reaches Ready. If DataStore already has a record we leave it alone —
        // the user's prior choices win over TDLib presets, and on every subsequent
        // mutation we mirror to TDLib anyway.
        scope.launch {
            authStage.filter { it is AuthStage.Ready }.first()
            seedFromPresetsIfMissing()
        }
    }

    /**
     * Persist the new settings to DataStore and mirror each changed per-network
     * bucket to TDLib via [TdApi.SetAutoDownloadSettings]. Mirroring is best-effort
     * — a TDLib failure (e.g. transient FLOOD_WAIT) doesn't roll back the local
     * write; the next user toggle retries, and the local copy keeps the UI honest
     * meanwhile.
     */
    override suspend fun update(transform: (AutoDownloadSettings) -> AutoDownloadSettings) {
        val (before, after) = persistTransform(transform)
        if (before == after) return
        mirrorChangedNetworks(before, after)
    }

    override suspend fun resetAll() {
        update { AutoDownloadSettings.DEFAULT }
    }

    private suspend fun persistTransform(
        transform: (AutoDownloadSettings) -> AutoDownloadSettings,
    ): Pair<AutoDownloadSettings, AutoDownloadSettings> {
        var captured: Pair<AutoDownloadSettings, AutoDownloadSettings>? = null
        dataStore.edit { prefs ->
            val current = prefs[KEY_SETTINGS]?.decodeOrNull() ?: AutoDownloadSettings.DEFAULT
            val next = transform(current)
            prefs[KEY_SETTINGS] = json.encodeToString(AutoDownloadSettings.serializer(), next)
            captured = current to next
        }
        return captured ?: (AutoDownloadSettings.DEFAULT to AutoDownloadSettings.DEFAULT)
    }

    private suspend fun mirrorChangedNetworks(
        before: AutoDownloadSettings,
        after: AutoDownloadSettings,
    ) {
        if (before.onWifi != after.onWifi) {
            pushToTdlib(after.onWifi, TdApi.NetworkTypeWiFi())
        }
        if (before.onMobile != after.onMobile) {
            pushToTdlib(after.onMobile, TdApi.NetworkTypeMobile())
        }
        if (before.onRoaming != after.onRoaming) {
            pushToTdlib(after.onRoaming, TdApi.NetworkTypeMobileRoaming())
        }
    }

    private suspend fun pushToTdlib(policy: AutoDownloadPolicy, type: TdApi.NetworkType) {
        runCatching {
            td.send(TdApi.SetAutoDownloadSettings(policy.toTdSettings(), type))
        }
    }

    private suspend fun seedFromPresetsIfMissing() {
        // Read raw JSON directly: `settings.value` may still be at the eager
        // DEFAULT seed if the DataStore upstream hasn't pushed its first
        // emission yet, and using that would mistakenly upload defaults to
        // TDLib for a returning user.
        val raw = dataStore.data.map { it[KEY_SETTINGS] }.first()
        if (raw != null) {
            // Returning user — push the cached values up to TDLib so the server's
            // copy reflects local state. Covers the case where the user edited
            // Hortay while offline (or before this refactor landed and the local
            // store was the only source) and then signed in. Best-effort; if any
            // call fails it'll retry on the next user mutation.
            val current = raw.decodeOrNull() ?: AutoDownloadSettings.DEFAULT
            pushToTdlib(current.onWifi, TdApi.NetworkTypeWiFi())
            pushToTdlib(current.onMobile, TdApi.NetworkTypeMobile())
            pushToTdlib(current.onRoaming, TdApi.NetworkTypeMobileRoaming())
            return
        }
        val presets = runCatching { td.send(TdApi.GetAutoDownloadSettingsPresets()) }.getOrNull()
            ?: return
        val seeded = AutoDownloadSettings(
            onWifi = presets.high.toAutoDownloadPolicy(AutoDownloadPolicy.DEFAULT_WIFI),
            onMobile = presets.medium.toAutoDownloadPolicy(AutoDownloadPolicy.DEFAULT_MOBILE),
            onRoaming = presets.low.toAutoDownloadPolicy(AutoDownloadPolicy.DEFAULT_ROAMING),
        )
        // Use the same persist path so a concurrent user toggle (unlikely but
        // possible — settings UI mounted while seeding runs) wins via DataStore's
        // serialised `edit`. We don't mirror back to TDLib here: presets are
        // already TDLib's own values, so a `SetAutoDownloadSettings` would be a
        // no-op round-trip.
        dataStore.edit { prefs ->
            if (prefs[KEY_SETTINGS] == null) {
                prefs[KEY_SETTINGS] = json.encodeToString(AutoDownloadSettings.serializer(), seeded)
            }
        }
    }

    private fun String.decodeOrNull(): AutoDownloadSettings? =
        runCatching { json.decodeFromString(AutoDownloadSettings.serializer(), this) }.getOrNull()

    private companion object {
        // Schema evolution lives on [AutoDownloadPolicy] / [AutoDownloadSettings]
        // via field defaults + ignoreUnknownKeys. This key has no version suffix
        // because we don't intend a multi-version migration scheme; if the JSON
        // shape ever needs a hard break (e.g. enum field with no default), the
        // path is to bump this key name and write a one-shot migration in
        // [AutoDownloadStore.settings].
        val KEY_SETTINGS = stringPreferencesKey("auto_download_settings")
    }
}

/**
 * Translate our compact per-network policy to TDLib's wire shape. The TDLib record
 * carries fields we don't surface in the UI (call-quality, story preload, upload
 * bitrate); we set them to neutral defaults so a Hortay write doesn't clobber a
 * user's preferences set in the official Telegram client for those orthogonal knobs.
 *
 * Neutral defaults:
 *   • `maxPhotoFileSize` — TDLib's documented maximum is 10 MB; we use that so the
 *     photos toggle gates auto-download by *boolean*, not by size (any feed photo
 *     is well under 10 MB).
 *   • `maxOtherFileSize` — covers documents/audio/voice notes which Hortay doesn't
 *     prefetch; 0 (disabled) keeps Hortay's policy from accidentally enabling
 *     prefetch for content types we don't render in the feed.
 *   • `preloadLargeVideos` / `preloadNextAudio` / `preloadStories` — false because
 *     Hortay doesn't implement them; setting true would only confuse other clients.
 *   • `useLessDataForCalls` — false; Hortay doesn't make calls. Other clients keep
 *     their own value because we only write on user-driven mutation, not on read.
 *   • `videoUploadBitrate` — 0, TDLib treats this as "use server default".
 */
private fun AutoDownloadPolicy.toTdSettings(): TdApi.AutoDownloadSettings =
    TdApi.AutoDownloadSettings(
        /* isAutoDownloadEnabled = */ photos || videos || animations,
        /* maxPhotoFileSize = */ if (photos) MAX_PHOTO_BYTES else 0,
        /* maxVideoFileSize = */ if (videos) videoMaxBytes else 0L,
        /* maxOtherFileSize = */ 0L,
        /* videoUploadBitrate = */ 0,
        /* preloadLargeVideos = */ false,
        /* preloadNextAudio = */ false,
        /* preloadStories = */ false,
        /* useLessDataForCalls = */ false,
    )

/**
 * Inverse of [toTdSettings] for preset seeding. Animations don't have a dedicated
 * field in TDLib's `autoDownloadSettings` (GIFs ride on `maxOtherFileSize` server-
 * side); we infer from `isAutoDownloadEnabled` so the seeded value at least matches
 * the preset's overall posture. The [fallback] argument supplies our Hortay-side
 * defaults when a TDLib preset field doesn't cleanly map (e.g. preset has
 * `maxVideoFileSize = 0` but `isAutoDownloadEnabled = true`).
 */
private fun TdApi.AutoDownloadSettings.toAutoDownloadPolicy(
    fallback: AutoDownloadPolicy,
): AutoDownloadPolicy = AutoDownloadPolicy(
    photos = isAutoDownloadEnabled && maxPhotoFileSize > 0,
    videos = isAutoDownloadEnabled && maxVideoFileSize > 0,
    videoMaxBytes = if (maxVideoFileSize > 0) maxVideoFileSize else fallback.videoMaxBytes,
    animations = isAutoDownloadEnabled,
)

// 10 MB — TDLib's documented `maxPhotoFileSize` upper bound. Any feed photo is
// well under this in practice; using the cap means the photos *toggle* is the
// only knob the user sees, while still passing a well-formed value to TDLib.
private const val MAX_PHOTO_BYTES: Int = 10 * 1024 * 1024

private val Context.autoDownloadDataStore by preferencesDataStore(name = "auto_download")
