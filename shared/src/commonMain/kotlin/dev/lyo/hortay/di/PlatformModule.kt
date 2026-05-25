package dev.lyo.hortay.di

import org.koin.core.module.Module

/**
 * Per-platform module — each `actual` provides OS-specific actuals that can't
 * live in commonMain (Android: `Context`, `Activity`-scoped pickers; iOS:
 * `UIViewController`-host-aware bridges). Returning a `Module` from an
 * `expect fun` (rather than declaring an `expect class`) is the
 * Koin-recommended KMP shape — keeps platform types out of commonMain.
 */
expect fun platformModule(): Module
