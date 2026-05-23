package dev.lyo.hortay

import android.util.Log

actual object PlatformLog {
    actual fun v(tag: String, msg: String) { Log.v(tag, msg) }
    actual fun d(tag: String, msg: String) { Log.d(tag, msg) }
    actual fun i(tag: String, msg: String) { Log.i(tag, msg) }
    actual fun w(tag: String, msg: String, t: Throwable?) { if (t != null) Log.w(tag, msg, t) else Log.w(tag, msg) }
    actual fun e(tag: String, msg: String, t: Throwable?) { if (t != null) Log.e(tag, msg, t) else Log.e(tag, msg) }
}
