package dev.lyo.hortay.ui.settings

import android.app.Activity
import android.os.Build
import dev.lyo.hortay.data.LocaleStore

/**
 * Android-side [LanguagePicker]. Reads / writes through [LocaleStore], which
 * bridges Hortay's Compose-only, ComponentActivity-based setup to Android's
 * per-app language picker.
 *
 * On API 33+ the platform `LocaleManager` is the source of truth — the
 * system Settings → Apps → Hortay → Language picker writes here too, and the
 * system recreates the activity stack on change. On API 26-32 the choice is
 * persisted in a small SharedPrefs file and the activity is recreated
 * explicitly so `MainActivity.attachBaseContext` can wrap the base context
 * with the new `java.util.Locale` before resources resolve.
 */
class AndroidLanguagePicker(private val activity: Activity) : LanguagePicker {
    override fun current(): String? = LocaleStore.read(activity)

    override fun apply(tag: String?) {
        LocaleStore.write(activity, tag)
        // On API 33+ the platform LocaleManager recreates the activity stack itself;
        // on older API levels we have to do it so attachBaseContext re-wraps with
        // the new locale.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            activity.recreate()
        }
    }
}
