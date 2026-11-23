package dev.spawnkit.app.images

import androidx.compose.runtime.Composable

/** Importing pictures is not available on this platform yet; the builder hides the Import button. */
@Composable
actual fun rememberPlatformImagePicker(): ImagePicker? = null
