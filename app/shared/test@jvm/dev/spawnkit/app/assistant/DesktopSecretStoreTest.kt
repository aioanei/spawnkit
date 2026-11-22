package dev.spawnkit.app.assistant

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Desktop secret storage. Windows encrypts with DPAPI for the signed-in user; macOS and Linux use the system keychain
 * tools, which these tests do not touch, so the file checks run on Windows only.
 */
class DesktopSecretStoreTest {
    private val windows = System.getProperty("os.name").startsWith("Windows")

    @Test
    fun valuesRoundTripAndAreNotStoredInPlainText() {
        if (!windows) return
        val dir = Files.createTempDirectory("spawnkit-secrets")
        val store = DesktopSecretStore(dir)

        store.write("test-key", "sk-test-123")

        assertTrue(store.persistent)
        assertEquals("sk-test-123", DesktopSecretStore(dir).read("test-key"), "A new store (a restart) reads the value back")
        val bytes = Files.readAllBytes(dir.resolve("test-key.bin"))
        assertFalse(String(bytes, Charsets.ISO_8859_1).contains("sk-test-123"), "The file holds no plain text")
    }

    @Test
    fun deleteRemovesTheValue() {
        if (!windows) return
        val store = DesktopSecretStore(Files.createTempDirectory("spawnkit-secrets"))
        store.write("test-key", "sk-test-123")

        store.delete("test-key")

        assertNull(store.read("test-key"))
    }

    @Test
    fun namesAreRestrictedToSafeCharacters() {
        val store = DesktopSecretStore(Files.createTempDirectory("spawnkit-secrets"))
        assertFailsWith<IllegalArgumentException> { store.write("../escape", "value") }
    }

    @Test
    fun memoryStoreSaysItForgetsOnRestart() {
        val store = MemorySecretStore()
        store.write("test-key", "value")

        assertEquals("value", store.read("test-key"))
        assertFalse(store.persistent)
        assertEquals("kept until you close SpawnKit", store.where)
    }
}
