package dev.lyo.hortay

import platform.Foundation.NSLog

actual object PlatformLog {
    actual fun v(tag: String, msg: String) { NSLog("V/$tag: $msg") }
    actual fun d(tag: String, msg: String) { NSLog("D/$tag: $msg") }
    actual fun i(tag: String, msg: String) { NSLog("I/$tag: $msg") }
    actual fun w(tag: String, msg: String, t: Throwable?) {
        NSLog("W/$tag: $msg" + (t?.let { " | ${it.message}" } ?: ""))
    }
    actual fun e(tag: String, msg: String, t: Throwable?) {
        NSLog("E/$tag: $msg" + (t?.let { " | ${it.message}" } ?: ""))
    }
}
