package dev.lyo.hortay.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

/**
 * iOS app foreground signal, mirrored from UIApplication lifecycle notifications.
 * Twin of the `ProcessLifecycleOwner` observer inside Android's
 * [dev.lyo.hortay.data.TdLifecycleBridge]: the single source of truth that
 * [IosTdLifecycleBridge] and PostsRepository's snapshot-save collector read,
 * replacing the hardcoded `MutableStateFlow(true)` the iOS DI used to inject.
 *
 * `DidBecomeActive` / `DidEnterBackground` are the true active/background edges
 * (we deliberately ignore `WillResignActive`, which also fires on transient
 * interruptions like the notification shade or an incoming call). Observers are
 * delivered on the main queue; assigning a `MutableStateFlow.value` is
 * thread-safe. They live for the process lifetime — the app has no "destroyed"
 * state, so there is no symmetric removal; they die with the process.
 *
 * Seeded `true` because this object is constructed during the foreground UI
 * mount (`MainViewController` → `initKoin`); the first background edge corrects it.
 */
class IosAppLifecycle {

    private val _foreground = MutableStateFlow(true)
    val foreground: StateFlow<Boolean> = _foreground.asStateFlow()

    init {
        val center = NSNotificationCenter.defaultCenter
        val mainQueue = NSOperationQueue.mainQueue
        center.addObserverForName(UIApplicationDidBecomeActiveNotification, null, mainQueue) { _ ->
            _foreground.value = true
        }
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, mainQueue) { _ ->
            _foreground.value = false
        }
    }
}
