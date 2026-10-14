package de.norm.events.dataquality.classification

import de.norm.events.EVENTS_SCHEMA
import io.github.oshai.kotlinlogging.KotlinLogging
import io.micrometer.core.instrument.MeterRegistry
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.data.r2dbc.core.R2dbcEntityTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.util.concurrent.atomic.AtomicLong

/**
 * The tokens the classification check used this month, kept in `event_classification_usage` (#2996).
 *
 * A table, not Spring AI's `gen_ai.client.token.usage` meter: the meter starts at zero on every
 * restart, and translation's calls to the same model add to it. The gauge mirrors the stored total
 * and is set on every read and write, never by a query at scrape time.
 */
@Component
@ConditionalOnProperty(name = ["app.data-quality.classification.enabled"], havingValue = "true")
class ClassificationTokenLedger(
    private val template: R2dbcEntityTemplate,
    private val properties: ClassificationProperties,
    meterRegistry: MeterRegistry,
    private val clock: Clock = Clock.systemUTC()
) : ApplicationRunner {
    private val logger = KotlinLogging.logger {}
    private val monthTokens = AtomicLong(0)

    init {
        meterRegistry.gauge(TOKENS_GAUGE, monthTokens) { it.get().toDouble() }
    }

    val budget: Long get() = properties.monthlyTokenBudget

    /** Input plus output tokens of the current month (UTC). */
    suspend fun monthTotal(): Long =
        template.databaseClient
            .sql("SELECT input_tokens + output_tokens AS tokens FROM $EVENTS_SCHEMA.event_classification_usage WHERE month_start = :month")
            .bind("month", monthStart())
            .map { row, _ -> tokens(row.get("tokens", Number::class.java)) }
            .one()
            .awaitSingleOrNull()
            .let { it ?: 0 }
            .also(monthTokens::set)

    /** Adds one call's tokens to the current month, and returns the month's new total. */
    suspend fun record(usage: TokenUsage): Long =
        template.databaseClient
            .sql(
                """
                INSERT INTO $EVENTS_SCHEMA.event_classification_usage (month_start, input_tokens, output_tokens, calls)
                VALUES (:month, :input, :output, 1)
                ON CONFLICT (month_start) DO UPDATE SET
                    input_tokens = event_classification_usage.input_tokens + EXCLUDED.input_tokens,
                    output_tokens = event_classification_usage.output_tokens + EXCLUDED.output_tokens,
                    calls = event_classification_usage.calls + 1,
                    updated_at = now()
                RETURNING input_tokens + output_tokens AS tokens
                """.trimIndent()
            ).bind("month", monthStart())
            .bind("input", usage.input)
            .bind("output", usage.output)
            .map { row, _ -> tokens(row.get("tokens", Number::class.java)) }
            .one()
            .awaitSingle()
            .also(monthTokens::set)

    /** Sets the gauge at start, so a restart does not show zero until the next run. `runBlocking` is fine on the main thread. */
    @Suppress("TooGenericExceptionCaught") // Intentional: a measurement must not stop the application from starting
    override fun run(args: ApplicationArguments) {
        try {
            runBlocking { monthTotal() }
        } catch (e: Exception) {
            logger.warn(e) { "Could not read this month's classification tokens at start; the gauge reads zero until the next run" }
        }
    }

    private fun monthStart(): LocalDate = LocalDate.now(clock).withDayOfMonth(1)

    private fun tokens(value: Number?): Long = requireNotNull(value) { "Column 'tokens' is null" }.toLong()

    companion object {
        /** `importer_data_quality_classification_tokens`: input plus output tokens of the current month. */
        const val TOKENS_GAUGE = "importer.data_quality.classification.tokens"
    }
}
