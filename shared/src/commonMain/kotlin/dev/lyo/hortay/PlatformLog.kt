package dev.lyo.hortay

/**
 * Tiny KMP-shared logger. On Android the actual implementation routes to
 * `android.util.Log`; on iOS it prints to NSLog via stdout. Centralising
 * `Log.w` / `Log.e` callers behind this surface lets web-pipeline files
 * (which only use logging as a side channel for one-off debug context)
 * live in commonMain without each one carrying its own expect/actual.
 */
expect object PlatformLog {
    fun v(tag: String, msg: String)
    fun d(tag: String, msg: String)
    fun i(tag: String, msg: String)
    fun w(tag: String, msg: String, t: Throwable? = null)
    fun e(tag: String, msg: String, t: Throwable? = null)
}
