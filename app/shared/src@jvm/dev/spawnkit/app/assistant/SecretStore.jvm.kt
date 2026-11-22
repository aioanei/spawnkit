package dev.spawnkit.app.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.sun.jna.platform.win32.Crypt32Util
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

@Composable
actual fun rememberSecretStore(): SecretStore = remember { DesktopSecretStore(DesktopSecretStore.defaultDirectory()) }

/**
 * Desktop secret storage:
 * - Windows: DPAPI encryption for the signed-in user, in files under [directory].
 * - macOS: the login keychain through the `security` tool.
 * - Linux: the Secret Service through `secret-tool` when it is installed.
 * - Otherwise values are kept in memory only, and [persistent] is false.
 */
class DesktopSecretStore(
    private val directory: Path,
) : SecretStore {
    private val os = System.getProperty("os.name").lowercase()
    private val backend: Backend =
        when {
            os.startsWith("windows") -> Dpapi()
            os.contains("mac") -> MacKeychain()
            onPath("secret-tool") -> SecretService()
            else -> Memory()
        }

    override val persistent: Boolean get() = backend !is Memory
    override val where: String get() = backend.where

    override fun read(name: String): String? = backend.read(checkedName(name))

    override fun write(
        name: String,
        value: String,
    ) = backend.write(checkedName(name), value)

    override fun delete(name: String) = backend.delete(checkedName(name))

    private interface Backend {
        val where: String

        fun read(name: String): String?

        fun write(
            name: String,
            value: String,
        )

        fun delete(name: String)
    }

    private inner class Dpapi : Backend {
        override val where = "Windows (encrypted for your user account)"

        private fun file(name: String) = directory.resolve("$name.bin")

        override fun read(name: String): String? {
            val file = file(name)
            if (!Files.exists(file)) return null
            return runCatching { String(Crypt32Util.cryptUnprotectData(Files.readAllBytes(file)), Charsets.UTF_8) }.getOrNull()
        }

        override fun write(
            name: String,
            value: String,
        ) {
            Files.createDirectories(directory)
            Files.write(file(name), Crypt32Util.cryptProtectData(value.toByteArray(Charsets.UTF_8)))
        }

        override fun delete(name: String) {
            Files.deleteIfExists(file(name))
        }
    }

    private class MacKeychain : Backend {
        override val where = "your macOS keychain"

        override fun read(name: String): String? = run("security", "find-generic-password", "-s", SERVICE, "-a", name, "-w")?.trimEnd('\n')

        override fun write(
            name: String,
            value: String,
        ) {
            checkNotNull(run("security", "add-generic-password", "-U", "-s", SERVICE, "-a", name, "-w", value)) {
                "The key could not be saved in the keychain"
            }
        }

        override fun delete(name: String) {
            run("security", "delete-generic-password", "-s", SERVICE, "-a", name)
        }
    }

    private class SecretService : Backend {
        override val where = "your system keyring"

        override fun read(name: String): String? = run("secret-tool", "lookup", "service", SERVICE, "account", name)?.trimEnd('\n')

        override fun write(
            name: String,
            value: String,
        ) {
            checkNotNull(run("secret-tool", "store", "--label=SpawnKit $name", "service", SERVICE, "account", name, input = value)) {
                "The key could not be saved in the keyring"
            }
        }

        override fun delete(name: String) {
            run("secret-tool", "clear", "service", SERVICE, "account", name)
        }
    }

    private class Memory : Backend {
        private val store = MemorySecretStore()
        override val where = store.where

        override fun read(name: String) = store.read(name)

        override fun write(
            name: String,
            value: String,
        ) = store.write(name, value)

        override fun delete(name: String) = store.delete(name)
    }

    companion object {
        private const val SERVICE = "SpawnKit"

        /** `%APPDATA%\SpawnKit\secrets` on Windows, otherwise `~/.spawnkit/secrets` (used by the Windows backend only). */
        fun defaultDirectory(): Path =
            System.getenv("APPDATA")?.let { Paths.get(it, "SpawnKit", "secrets") }
                ?: Paths.get(System.getProperty("user.home"), ".spawnkit", "secrets")

        private fun onPath(tool: String): Boolean =
            System.getenv("PATH").orEmpty().split(java.io.File.pathSeparator).any { Files.isExecutable(Paths.get(it, tool)) }

        /** Runs a tool and returns its output, or `null` when it fails. */
        private fun run(
            vararg command: String,
            input: String? = null,
        ): String? =
            runCatching {
                val process = ProcessBuilder(*command).redirectErrorStream(false).start()
                process.outputStream.use { out -> input?.let { out.write(it.toByteArray(Charsets.UTF_8)) } }
                val output = process.inputStream.bufferedReader().readText()
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroy()
                    return null
                }
                output.takeIf { process.exitValue() == 0 }
            }.getOrNull()
    }
}
