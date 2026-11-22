package dev.spawnkit.app.assistant

import androidx.compose.runtime.Composable

/**
 * Device storage for secrets such as the creator's AI model key. Values never enter projects, exports, logs or agent
 * tools. Names are short identifiers (`[a-z0-9-]`).
 */
interface SecretStore {
    /** Whether values survive closing SpawnKit. */
    val persistent: Boolean

    /** Where values are kept, in the creator's words, for example "Android Keystore". */
    val where: String

    fun read(name: String): String?

    fun write(
        name: String,
        value: String,
    )

    fun delete(name: String)
}

/** Keeps values only while SpawnKit runs, on platforms without secure storage yet. */
class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override val persistent = false
    override val where = "kept until you close SpawnKit"

    override fun read(name: String): String? = values[checkedName(name)]

    override fun write(
        name: String,
        value: String,
    ) {
        values[checkedName(name)] = value
    }

    override fun delete(name: String) {
        values.remove(checkedName(name))
    }
}

/** The platform's secret store. */
@Composable
expect fun rememberSecretStore(): SecretStore

private val SecretName = Regex("[a-z0-9-]{1,64}")

/** Returns [name] if it is a valid secret name. */
internal fun checkedName(name: String): String {
    require(SecretName.matches(name)) { "Secret names use lowercase letters, digits and dashes" }
    return name
}
