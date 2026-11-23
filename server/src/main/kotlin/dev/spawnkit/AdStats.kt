package dev.spawnkit

import dev.spawnkit.data.AdCounts
import dev.spawnkit.data.AdEventsSeen
import dev.spawnkit.data.Db
import dev.spawnkit.data.PurchaseCounts
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.ZoneOffset

/** One reported ad event: its number within a play session, its placement and whether it was a view or a click. */
internal data class AdEventReport(
    val number: Int,
    val placement: String,
    val click: Boolean,
)

/** Test purchases of one product of one game on one UTC day. */
internal data class PurchaseDay(
    val day: String,
    val product: String,
    val count: Long,
)

/** Views and clicks of one game on one UTC day. */
internal data class AdDay(
    val day: String,
    val views: Long,
    val clicks: Long,
)

/**
 * Test ad counts from Arcade plays (C12). Events carry a random play-session ID and a number, never personal data;
 * each (session, number) is counted once, so retries and duplicates do not inflate the counts.
 */
internal class AdStats(
    private val db: Db,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Counts the new events among [events] of play [session] for game [slug]; returns how many were new. */
    fun record(
        slug: String,
        session: String,
        events: List<AdEventReport>,
    ): Int =
        db.tx {
            val day =
                Instant
                    .ofEpochMilli(clock())
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .toString()
            var accepted = 0
            for (event in events.distinctBy { it.number }) {
                val seen =
                    AdEventsSeen
                        .selectAll()
                        .where {
                            (AdEventsSeen.game eq slug) and (AdEventsSeen.session eq session) and (AdEventsSeen.number eq event.number)
                        }.any()
                if (seen) continue
                AdEventsSeen.insert {
                    it[game] = slug
                    it[AdEventsSeen.session] = session
                    it[number] = event.number
                }
                val row =
                    AdCounts
                        .selectAll()
                        .where { (AdCounts.game eq slug) and (AdCounts.day eq day) }
                        .singleOrNull()
                if (row == null) {
                    AdCounts.insert {
                        it[game] = slug
                        it[AdCounts.day] = day
                        it[views] = if (event.click) 0 else 1
                        it[clicks] = if (event.click) 1 else 0
                    }
                } else {
                    AdCounts.update({ (AdCounts.game eq slug) and (AdCounts.day eq day) }) {
                        if (event.click) it[clicks] = row[AdCounts.clicks] + 1 else it[views] = row[AdCounts.views] + 1
                    }
                }
                accepted++
            }
            accepted
        }

    /**
     * Counts the new test purchases among [purchases] (event number and product) of play [session] for game [slug].
     * Purchases share event numbers with ad events, so each is counted once. Returns how many were new.
     */
    fun recordPurchases(
        slug: String,
        session: String,
        purchases: List<Pair<Int, String>>,
    ): Int =
        db.tx {
            val day = today()
            var accepted = 0
            for ((number, product) in purchases.distinctBy { it.first }) {
                val seen =
                    AdEventsSeen
                        .selectAll()
                        .where { (AdEventsSeen.game eq slug) and (AdEventsSeen.session eq session) and (AdEventsSeen.number eq number) }
                        .any()
                if (seen) continue
                AdEventsSeen.insert {
                    it[game] = slug
                    it[AdEventsSeen.session] = session
                    it[AdEventsSeen.number] = number
                }
                val match = (PurchaseCounts.game eq slug) and (PurchaseCounts.day eq day) and (PurchaseCounts.product eq product)
                val row = PurchaseCounts.selectAll().where { match }.singleOrNull()
                if (row == null) {
                    PurchaseCounts.insert {
                        it[game] = slug
                        it[PurchaseCounts.day] = day
                        it[PurchaseCounts.product] = product
                        it[count] = 1
                    }
                } else {
                    PurchaseCounts.update({ match }) { it[count] = row[PurchaseCounts.count] + 1 }
                }
                accepted++
            }
            accepted
        }

    /** Test purchases of [slug] per UTC day and product, newest day first. */
    fun purchases(slug: String): List<PurchaseDay> =
        db.tx {
            PurchaseCounts
                .selectAll()
                .where { PurchaseCounts.game eq slug }
                .orderBy(PurchaseCounts.day to SortOrder.DESC, PurchaseCounts.product to SortOrder.ASC)
                .map { PurchaseDay(it[PurchaseCounts.day], it[PurchaseCounts.product], it[PurchaseCounts.count]) }
        }

    private fun today(): String =
        Instant
            .ofEpochMilli(clock())
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .toString()

    /** Views and clicks of [slug] per UTC day, newest first. */
    fun daily(slug: String): List<AdDay> =
        db.tx {
            AdCounts
                .selectAll()
                .where { AdCounts.game eq slug }
                .orderBy(AdCounts.day to SortOrder.DESC)
                .map { AdDay(it[AdCounts.day], it[AdCounts.views], it[AdCounts.clicks]) }
        }
}
