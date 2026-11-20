package dev.spawnkit.app.release

import androidx.compose.runtime.Composable

@Composable
actual fun rememberReleasePlatform(): ReleasePlatform = UnsupportedReleasePlatform
