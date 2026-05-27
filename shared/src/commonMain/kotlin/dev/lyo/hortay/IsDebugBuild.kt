package dev.lyo.hortay

/**
 * `true` when running a debuggable build. Android checks
 * [android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE]; iOS returns `false`
 * (no K/N build-config equivalent — wire via compiler arg if needed later).
 */
expect val isDebugBuild: Boolean
