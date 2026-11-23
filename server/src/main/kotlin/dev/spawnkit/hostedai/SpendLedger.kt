package dev.spawnkit.hostedai

import dev.spawnkit.accounts.AttemptLimiter
import dev.spawnkit.ai.Usage
import dev.spawnkit.data.AiSpend
import dev.spawnkit.data.Db
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.insert
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** Hosted AI spending limits in millionths of a dollar (ADR-0011): abuse protection for a free service, not tiers. */
internal data class AiLimits(
    /** Everything the server may spend on AI in one calendar month (UTC). */
    val monthlyMicros: Long = 20_000_000,
    /** What one account may spend in one UTC day. */
    val dailyMicros: Long = 1_000_000,
    /** Model calls one account may make per hour. */
    val callsPerHour: Int = 120,
)

/** Model prices in millionths of a dollar per token. The defaults are the assistant model's $3 and $15 per million. */
internal data class ModelPrices(
    val inputMicrosPerToken: Long = 3,
    val outputMicrosPerToken: Long = 15,
) {
    /** What [usage] cost; prompt-cache writes cost 1.25 × the input price and reads 0.1 ×, rounded up. */
    fun cost(usage: Usage): Long =
        usage.inputTokens * inputMicrosPerToken +
            usage.outputTokens * outputMicrosPerToken +
            (usage.cacheWriteTokens * inputMicrosPerToken * 5 + 3) / 4 +
            (usage.cacheReadTokens * inputMicrosPerToken + 9) / 10
}

/** Spending set aside for one model call before it runs. */
internal class Reservation(
    val account: Long,
    val day: String,
    val micros: Long,
)

/** Whether a model call may run. */
internal sealed interface SpendDecision {
    /** It may; settle [reservation] with the real cost afterwards. */
    data class Allowed(
        val reservation: Reservation,
    ) : SpendDecision

    /** It may not, for the plain reason in [message]. */
    data class Refused(
        val message: String,
    ) : SpendDecision
}

/**
 * Keeps hosted AI spending in [AiSpend] and enforces [limits] (ADR-0011).
 *
 * Before a call, [reserve] checks the monthly cap and the account's daily limit and, in the same transaction, sets
 * aside the most the call can cost. Afterwards [settle] replaces the reservation with the cost the model reported. So
 * concurrent calls can never take the month past its cap: each one's worst case is counted before it runs. Both run
 * under one lock as well, since SQLite allows a single writer and this server is one process.
 *
 * The hourly call limit is kept in memory; a restart forgets it, which only relaxes it.
 */
internal class SpendLedger(
    private val db: Db,
    private val limits: AiLimits = AiLimits(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val calls = AttemptLimiter(limits.callsPerHour, HOUR, clock)

    /** Sets aside [maxMicros] for one call by [account], or refuses with the limit that was reached. */
    @Synchronized
    fun reserve(
        account: Long,
        maxMicros: Long,
    ): SpendDecision {
        val date = today()
        val day = date.toString()
        // A call that could cost more than a whole day's allowance can never run; saying so beats blaming today's use.
        if (maxMicros > limits.dailyMicros || maxMicros > limits.monthlyMicros) return SpendDecision.Refused(TOO_LONG)
        if (calls.blocked(account.toString())) return SpendDecision.Refused(HOURLY)
        return db.tx {
            if (monthTotal(date) + maxMicros > limits.monthlyMicros) return@tx SpendDecision.Refused(monthly(date))
            val row =
                AiSpend
                    .selectAll()
                    .where { (AiSpend.account eq account) and (AiSpend.day eq day) }
                    .singleOrNull()
            val spent = row?.get(AiSpend.microDollars) ?: 0
            if (spent + maxMicros > limits.dailyMicros) return@tx SpendDecision.Refused(DAILY)
            if (row == null) {
                AiSpend.insert {
                    it[AiSpend.account] = account
                    it[AiSpend.day] = day
                    it[microDollars] = maxMicros
                    it[AiSpend.calls] = 1
                }
            } else {
                AiSpend.update({ (AiSpend.account eq account) and (AiSpend.day eq day) }) {
                    it[microDollars] = spent + maxMicros
                    it[AiSpend.calls] = row[AiSpend.calls] + 1
                }
            }
            calls.record(account.toString())
            SpendDecision.Allowed(Reservation(account, day, maxMicros))
        }
    }

    /** Replaces [reservation] with what the call really cost: [actualMicros], or 0 when it failed. */
    @Synchronized
    fun settle(
        reservation: Reservation,
        actualMicros: Long,
    ) {
        db.tx {
            val where = (AiSpend.account eq reservation.account) and (AiSpend.day eq reservation.day)
            val spent =
                AiSpend
                    .selectAll()
                    .where { where }
                    .singleOrNull()
                    ?.get(AiSpend.microDollars) ?: return@tx
            AiSpend.update({ where }) { it[microDollars] = (spent - reservation.micros + actualMicros).coerceAtLeast(0) }
        }
    }

    /** Everything spent (and reserved) this month, across accounts. */
    fun monthMicros(): Long = db.tx { monthTotal(today()) }

    private fun JdbcTransaction.monthTotal(date: LocalDate): Long {
        val first = date.withDayOfMonth(1).toString()
        val last = date.withDayOfMonth(date.lengthOfMonth()).toString()
        return AiSpend
            .selectAll()
            .where { (AiSpend.day greaterEq first) and (AiSpend.day lessEq last) }
            .sumOf { it[AiSpend.microDollars] }
    }

    private fun today(): LocalDate = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()

    private fun monthly(date: LocalDate): String {
        val reset = date.withDayOfMonth(1).plusMonths(1)
        val name = reset.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        return "SpawnKit's free AI has reached its limit for this month. It starts again on ${reset.dayOfMonth} $name " +
            "${reset.year}. You can keep building by hand."
    }

    private companion object {
        const val HOUR = 60 * 60_000L
        const val DAILY = "You have used today's free AI allowance. It renews at midnight UTC. You can keep building by hand."
        const val TOO_LONG = "This conversation is too long for SpawnKit AI. Start a new request with a shorter description."
        const val HOURLY = "Too many AI requests in the last hour. Wait a little and try again."
    }
}
