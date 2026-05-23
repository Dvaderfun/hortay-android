package dev.lyo.hortay.data

import kotlinx.serialization.Serializable

/**
 * Per-network media auto-download policy. Mirrors Telegram's "Data and Storage" model:
 * three independent profiles keyed by the active network class, each profile a small
 * tuple of toggles + a video size cap.
 *
 * Every primary-constructor field carries a default value. This is the
 * forward-compatibility contract for [AutoDownloadStore]'s persisted JSON: when a
 * future build adds a new toggle / cap, old user JSON (missing that field) decodes
 * cleanly into the constructor's default instead of raising
 * [kotlinx.serialization.MissingFieldException].
 */
@Serializable
data class AutoDownloadPolicy(
    val photos: Boolean = false,
    val videos: Boolean = false,
    val videoMaxBytes: Long = 0L,
    val animations: Boolean = false,
) {
    companion object {
        const val DEFAULT_VIDEO_MAX_WIFI: Long = 50L * 1024 * 1024
        const val DEFAULT_VIDEO_MAX_MOBILE: Long = 10L * 1024 * 1024

        val DEFAULT_WIFI = AutoDownloadPolicy(
            photos = true,
            videos = true,
            videoMaxBytes = DEFAULT_VIDEO_MAX_WIFI,
            animations = true,
        )
        val DEFAULT_MOBILE = AutoDownloadPolicy(
            photos = true,
            videos = true,
            videoMaxBytes = DEFAULT_VIDEO_MAX_MOBILE,
            animations = true,
        )
        val DEFAULT_ROAMING = AutoDownloadPolicy(
            photos = false,
            videos = false,
            videoMaxBytes = 0L,
            animations = false,
        )

        /** Slider snap points in bytes — Telegram-Android's bucket set. */
        val VIDEO_SIZE_STEPS: List<Long> = listOf(
            1L, 2L, 5L, 10L, 30L, 50L, 100L, 200L, 300L, 500L,
        ).map { it * 1024 * 1024 }
    }
}

@Serializable
data class AutoDownloadSettings(
    val onWifi: AutoDownloadPolicy = AutoDownloadPolicy.DEFAULT_WIFI,
    val onMobile: AutoDownloadPolicy = AutoDownloadPolicy.DEFAULT_MOBILE,
    val onRoaming: AutoDownloadPolicy = AutoDownloadPolicy.DEFAULT_ROAMING,
) {
    companion object {
        val DEFAULT = AutoDownloadSettings()
    }
}

enum class AutoDownloadCategory { Wifi, Mobile, Roaming }

fun AutoDownloadSettings.policy(category: AutoDownloadCategory): AutoDownloadPolicy = when (category) {
    AutoDownloadCategory.Wifi -> onWifi
    AutoDownloadCategory.Mobile -> onMobile
    AutoDownloadCategory.Roaming -> onRoaming
}

fun AutoDownloadSettings.withPolicy(
    category: AutoDownloadCategory,
    policy: AutoDownloadPolicy,
): AutoDownloadSettings = when (category) {
    AutoDownloadCategory.Wifi -> copy(onWifi = policy)
    AutoDownloadCategory.Mobile -> copy(onMobile = policy)
    AutoDownloadCategory.Roaming -> copy(onRoaming = policy)
}

fun AutoDownloadCategory.defaultPolicy(): AutoDownloadPolicy = when (this) {
    AutoDownloadCategory.Wifi -> AutoDownloadPolicy.DEFAULT_WIFI
    AutoDownloadCategory.Mobile -> AutoDownloadPolicy.DEFAULT_MOBILE
    AutoDownloadCategory.Roaming -> AutoDownloadPolicy.DEFAULT_ROAMING
}

/**
 * Common interface for the per-network policy store. Android: [AutoDownloadStore]
 * implements this, mirroring writes through TDLib's `SetAutoDownloadSettings`.
 * iOS: stub returning DEFAULT and ignoring writes (no TDLib in guest mode).
 */
interface AutoDownloadFacade {
    val settings: kotlinx.coroutines.flow.StateFlow<AutoDownloadSettings>
    suspend fun update(transform: (AutoDownloadSettings) -> AutoDownloadSettings)
    suspend fun resetAll()
}
