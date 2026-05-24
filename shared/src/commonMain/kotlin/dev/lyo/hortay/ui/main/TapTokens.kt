package dev.lyo.hortay.ui.main

import kotlinx.atomicfu.atomic

/**
 * Atomic per-process counter that mints monotonically-increasing tokens.
 *
 * Replaces the JVM-only `System.nanoTime()` the original Android scaffolds
 * used to disambiguate home-tap re-clicks and report-flow open events. Used
 * across [dev.lyo.hortay.ui.main.MainScaffold] and
 * [dev.lyo.hortay.ui.web.WebModeScaffold] for the same purpose: a fresh
 * token forces downstream `LaunchedEffect(token)` reactions to fire even
 * when the user re-triggers the same gesture in quick succession.
 *
 * KMP-safe via atomicfu; no synchronisation cost on the read path because
 * each call increments and returns a unique Long.
 */
private val tapTokenCounter = atomic(1L)

internal fun nextTapToken(): Long = tapTokenCounter.incrementAndGet()
