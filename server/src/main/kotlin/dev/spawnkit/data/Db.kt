package dev.spawnkit.data

import org.jetbrains.exposed.v1.core.ReferenceOption
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.SchemaUtils
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.jdbc.update
import java.nio.file.Files
import java.nio.file.Path

/** The schema version stored in the database; one row. */
internal object SchemaVersion : Table("schema_version") {
    val version = integer("version")
}

/** A SpawnKit account (ADR-0010). Accounts carry a plan so paid plans can be added later (ADR-0011). */
internal object Accounts : Table("accounts") {
    val id = long("id").autoIncrement()
    val displayName = varchar("display_name", 60)
    val email = varchar("email", 254).nullable()
    val verified = bool("verified").default(false)
    val plan = varchar("plan", 20).default("free")
    val createdAt = long("created_at")
    override val primaryKey = PrimaryKey(id)
}

/** A way to sign in to an account: `email` (with a password hash), `google` or later `apple`. */
internal object Identities : Table("identities") {
    val id = long("id").autoIncrement()
    val account = long("account_id").references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val provider = varchar("provider", 20)
    val subject = varchar("subject", 254)
    val passwordHash = varchar("password_hash", 256).nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        uniqueIndex(provider, subject)
    }
}

/** Verification and reset codes, stored only as hashes. */
internal object EmailCodes : Table("email_codes") {
    val id = long("id").autoIncrement()
    val email = varchar("email", 254)
    val purpose = varchar("purpose", 16)
    val codeHash = varchar("code_hash", 64)
    val expiresAt = long("expires_at")
    val tries = integer("tries").default(0)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, email, purpose)
    }
}

/** Signed-in sessions, keyed by the SHA-256 hash of the token; the token itself is never stored. */
internal object Sessions : Table("sessions") {
    val tokenHash = varchar("token_hash", 64)
    val account = long("account_id").references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val createdAt = long("created_at")
    val expiresAt = long("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

/** Published Arcade games. Games published before accounts, or whose owner was deleted, have no owner. */
internal object Games : Table("games") {
    val slug = varchar("slug", 36)
    val title = varchar("title", 200)
    val owner = long("owner_id").references(Accounts.id, onDelete = ReferenceOption.SET_NULL).nullable()
    val publishedAt = long("published_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(slug)
}

/** Projects synced to an account (C11b), with a revision for conflict detection. */
internal object Projects : Table("projects") {
    val id = long("id").autoIncrement()
    val owner = long("owner_id").references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val name = varchar("name", 100)
    val revision = long("revision")
    val document = text("document")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Games a player liked. */
internal object Likes : Table("likes") {
    val account = long("account_id").references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val game = varchar("game_slug", 36).references(Games.slug, onDelete = ReferenceOption.CASCADE)
    override val primaryKey = PrimaryKey(account, game)
}

/** Games a signed-in player opened, for "My played games". */
internal object Plays : Table("plays") {
    val account = long("account_id").references(Accounts.id, onDelete = ReferenceOption.CASCADE)
    val game = varchar("game_slug", 36).references(Games.slug, onDelete = ReferenceOption.CASCADE)
    val lastPlayed = long("last_played")
    override val primaryKey = PrimaryKey(account, game)
}

/**
 * Hosted AI spending per account and UTC day, in millionths of a dollar (ADR-0011). Rows hold no personal data and
 * deliberately outlive their account, so deleting an account never lowers the month's total under the cap.
 */
internal object AiSpend : Table("ai_spend") {
    val account = long("account_id")
    val day = varchar("day", 10)
    val microDollars = long("micro_dollars").default(0)
    val calls = integer("calls").default(0)
    override val primaryKey = PrimaryKey(account, day)
}

/** Test ad events already counted, by play session and number, so each is counted once (C12). No personal data. */
internal object AdEventsSeen : Table("ad_events_seen") {
    val game = varchar("game_slug", 36).references(Games.slug, onDelete = ReferenceOption.CASCADE)
    val session = varchar("session", 64)
    val number = integer("number")
    override val primaryKey = PrimaryKey(game, session, number)
}

/** Test ad views and clicks per game and UTC day (C12). */
internal object AdCounts : Table("ad_counts") {
    val game = varchar("game_slug", 36).references(Games.slug, onDelete = ReferenceOption.CASCADE)
    val day = varchar("day", 10)
    val views = long("views").default(0)
    val clicks = long("clicks").default(0)
    override val primaryKey = PrimaryKey(game, day)
}

/**
 * The server's SQLite database (ADR-0010). [open] creates it on first use and applies the migrations in order, in one
 * transaction. A database written by a newer server is refused rather than changed.
 */
internal class Db private constructor(
    private val database: Database,
) {
    /** Runs [block] in a transaction. */
    fun <T> tx(block: JdbcTransaction.() -> T): T = transaction(database) { block() }

    /** The schema version stored in the database. */
    fun version(): Int = tx { SchemaVersion.selectAll().single()[SchemaVersion.version] }

    companion object {
        /** The schema this server writes. Add a migration below when it changes. */
        const val SCHEMA_VERSION: Int = 2

        private val migrations: Map<Int, JdbcTransaction.() -> Unit> =
            mapOf(
                1 to { SchemaUtils.create(Accounts, Identities, EmailCodes, Sessions, Games, Projects, Likes, Plays, AiSpend) },
                2 to { SchemaUtils.create(AdEventsSeen, AdCounts) },
            )

        /** Opens (creating if needed) the database file at [file]. */
        fun open(file: Path): Db {
            file.toAbsolutePath().parent?.let { Files.createDirectories(it) }
            val database =
                Database.connect(
                    "jdbc:sqlite:${file.toAbsolutePath()}",
                    driver = "org.sqlite.JDBC",
                    setupConnection = { connection ->
                        connection.createStatement().use {
                            it.execute("PRAGMA foreign_keys = ON")
                            it.execute("PRAGMA busy_timeout = 5000")
                        }
                    },
                )
            val db = Db(database)
            db.tx { migrate() }
            return db
        }

        private fun JdbcTransaction.migrate() {
            SchemaUtils.create(SchemaVersion)
            val current = SchemaVersion.selectAll().singleOrNull()?.get(SchemaVersion.version)
            check(current == null || current <= SCHEMA_VERSION) {
                "This database was written by a newer SpawnKit server (schema $current; this server knows $SCHEMA_VERSION). Update the server."
            }
            val from = current ?: 0
            for (version in from + 1..SCHEMA_VERSION) migrations.getValue(version).invoke(this)
            if (current == null) {
                SchemaVersion.insert { it[SchemaVersion.version] = SCHEMA_VERSION }
            } else if (current < SCHEMA_VERSION) {
                SchemaVersion.update { it[SchemaVersion.version] = SCHEMA_VERSION }
            }
        }
    }
}
