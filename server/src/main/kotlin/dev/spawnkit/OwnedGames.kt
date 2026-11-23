package dev.spawnkit

import dev.spawnkit.accounts.Accounts
import dev.spawnkit.data.Db
import dev.spawnkit.data.Games
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update

/** Accounts and game ownership, given to the Arcade when the server has a database. */
internal class ArcadeOwners(
    val accounts: Accounts,
    val games: OwnedGames,
    val ads: AdStats? = null,
)

/**
 * Who owns each Arcade game (ADR-0010). Game files stay in [ArcadeStore]; this index adds the owner. Games published
 * before accounts, without signing in, or by a deleted account have no owner: they stay playable, and nobody can
 * change them through the API.
 */
internal class OwnedGames(
    private val db: Db,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Adds [games] that the index does not know yet, unowned. Returns how many were added. */
    fun importUnowned(games: List<PublishedGame>): Int =
        db.tx {
            val known = Games.selectAll().map { it[Games.slug] }.toSet()
            val missing = games.filter { it.slug !in known }
            for (game in missing) insertRow(game, owner = null)
            missing.size
        }

    /** Records a newly published [game], owned by [owner] when signed in. */
    fun record(
        game: PublishedGame,
        owner: Long?,
    ) {
        db.tx { insertRow(game, owner) }
    }

    /** The owner of [slug], or `null` when it is unowned or unknown. */
    fun owner(slug: String): Long? =
        db.tx {
            Games
                .selectAll()
                .where { Games.slug eq slug }
                .singleOrNull()
                ?.get(Games.owner)
        }

    /** Games owned by [account], newest change first. */
    fun ownedBy(account: Long): List<PublishedGame> =
        db.tx {
            Games
                .selectAll()
                .where { Games.owner eq account }
                .orderBy(Games.updatedAt to SortOrder.DESC, Games.slug to SortOrder.ASC)
                .map { PublishedGame(it[Games.slug], it[Games.title]) }
        }

    /** Notes that [slug] was republished as [title]. */
    fun republished(
        slug: String,
        title: String,
    ) {
        db.tx {
            Games.update({ Games.slug eq slug }) {
                it[Games.title] = title.take(MAX_TITLE)
                it[updatedAt] = clock()
            }
        }
    }

    /** Forgets [slug] after it was unpublished. */
    fun removed(slug: String) {
        db.tx { Games.deleteWhere { Games.slug eq slug } }
    }

    private fun insertRow(
        game: PublishedGame,
        owner: Long?,
    ) {
        val now = clock()
        Games.insert {
            it[slug] = game.slug
            it[title] = game.title.take(MAX_TITLE)
            it[Games.owner] = owner
            it[publishedAt] = now
            it[updatedAt] = now
        }
    }

    private companion object {
        const val MAX_TITLE = 200
    }
}
