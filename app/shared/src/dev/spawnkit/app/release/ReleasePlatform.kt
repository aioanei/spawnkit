package dev.spawnkit.app.release

import androidx.compose.runtime.Composable

/** Host services for the Release Manager. The Arcade address belongs to this operation, outside project data. */
interface ReleasePlatform {
    val available: Boolean

    /** Saves a standalone web ZIP. Returns false when the creator dismisses the system picker. */
    suspend fun exportWeb(
        suggestedName: String,
        packageJson: String,
    ): Boolean

    /** Publishes the encoded game and returns its public play URL. */
    suspend fun publish(
        arcadeUrl: String,
        packageJson: String,
    ): String
}

@Composable
expect fun rememberReleasePlatform(): ReleasePlatform

internal object UnsupportedReleasePlatform : ReleasePlatform {
    override val available = false

    override suspend fun exportWeb(
        suggestedName: String,
        packageJson: String,
    ): Boolean = unavailable()

    override suspend fun publish(
        arcadeUrl: String,
        packageJson: String,
    ): String = unavailable()

    private fun unavailable(): Nothing = error("Open the project in the Android or desktop builder to release it.")
}
