package dev.spawnkit.app.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/** No secure storage on this platform yet: keys are kept only while SpawnKit runs, and the assistant says so. */
@Composable
actual fun rememberSecretStore(): SecretStore = remember { MemorySecretStore() }
