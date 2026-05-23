package dev.lyo.hortay

import kotlinx.coroutines.CoroutineDispatcher

/**
 * Shared IO dispatcher. On JVM resolves to `Dispatchers.IO`; on Native to
 * `Dispatchers.Default` (Kotlin/Native has no separate IO pool — its default
 * dispatcher is multi-threaded since coroutines 1.9). Use this from common
 * code anywhere that wants to dispatch blocking I/O off the calling thread.
 */
expect val ioDispatcher: CoroutineDispatcher
