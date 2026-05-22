package dev.lyo.hortay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * iOS entry point. Called from Swift in `:iosApp`/iosApp/ContentView.swift as
 *
 * ```swift
 * struct ComposeView: UIViewControllerRepresentable {
 *     func makeUIViewController(context: Context) -> UIViewController =
 *         MainViewControllerKt.MainViewController()
 *     func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
 * }
 * ```
 *
 * Currently a placeholder. Real implementation lands after Phase A (library
 * swaps to KMP) + Phase C (move CMP-ready UI files to commonMain). When those
 * complete, this becomes:
 *
 * ```kotlin
 * fun MainViewController(): UIViewController = ComposeUIViewController {
 *     HortayTheme {
 *         val graph = remember { IosAppGraph() }
 *         WebModeScaffold(graph)
 *     }
 * }
 * ```
 *
 * v1 iOS launches straight into guest-mode (web-only, no TDLib) — TDLib is
 * Android-only until cinterop port lands.
 */
@Suppress("FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController {
    Placeholder()
}

@Composable
private fun Placeholder() {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(
            modifier = Modifier.fillMaxSize().padding(32.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "Hortay iOS\n\nKMP/CMP scaffold ready.\nPhase A library swaps + Phase C code moves pending.",
                color = Color.Unspecified,
            )
        }
    }
}
