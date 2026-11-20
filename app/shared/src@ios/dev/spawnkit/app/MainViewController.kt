package dev.spawnkit.app

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/** Entry point called from Swift (`MainViewControllerKt.MainViewController()`); the name is part of the iOS interop contract. */
@Suppress("ktlint:standard:function-naming", "FunctionName")
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
