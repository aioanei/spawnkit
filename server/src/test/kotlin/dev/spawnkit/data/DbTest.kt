package dev.spawnkit.data

import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** The accounts database opens with the current schema, keeps data across restarts and refuses newer schemas (C11a). */
class DbTest {
    private val file: Path = Files.createTempDirectory(Files.createDirectories(Path.of("build")), "db-").resolve("data/spawnkit.db")

    @Test
    fun aFreshDatabaseGetsTheCurrentSchema() {
        val db = Db.open(file)

        assertEquals(Db.SCHEMA_VERSION, db.version())
        assertEquals(0L, db.tx { Accounts.selectAll().count() })
    }

    @Test
    fun reopeningKeepsDataAndVersion() {
        Db.open(file).tx {
            Accounts.insert {
                it[displayName] = "Ada"
                it[createdAt] = 1L
            }
        }

        val reopened = Db.open(file)

        assertEquals(Db.SCHEMA_VERSION, reopened.version())
        assertEquals(listOf("Ada"), reopened.tx { Accounts.selectAll().map { it[Accounts.displayName] } })
    }

    @Test
    fun aNewerSchemaThanThisServerIsRefused() {
        Db.open(file).tx { SchemaVersion.update { it[version] = Db.SCHEMA_VERSION + 1 } }

        val problem = assertFailsWith<IllegalStateException> { Db.open(file) }

        assertContains(problem.message.orEmpty(), "newer")
    }
}
