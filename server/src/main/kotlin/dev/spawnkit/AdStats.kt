package dev.spawnkit

import dev.spawnkit.data.AdCounts
import dev.spawnkit.data.AdEventsSeen
import dev.spawnkit.data.Db
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
