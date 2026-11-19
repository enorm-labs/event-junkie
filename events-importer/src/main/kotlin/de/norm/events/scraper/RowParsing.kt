package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.Level

/**
 * [parse] applied to every row, dropping a row that yields null or throws. A row that throws is
 * logged on the caller's [logger] as `Failed to parse <what>, skipping`, so one malformed row never
 * costs the page and the line keeps the scraper's logger name.
 */
inline fun <T, R : Any> Iterable<T>.mapSkippingFailures(
    logger: KLogger,
    what: String,
    parse: (T) -> R?
): List<R> = mapSkippingFailures(logger, { what }, parse)

/** [mapSkippingFailures], naming the failed row in the line through [what]. */
@Suppress("TooGenericExceptionCaught") // Intentional: one malformed row must not cost the page
inline fun <T, R : Any> Iterable<T>.mapSkippingFailures(
    logger: KLogger,
    crossinline what: (T) -> String,
    parse: (T) -> R?
): List<R> =
    mapNotNull { row ->
        try {
            parse(row)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse ${what(row)}, skipping" }
            null
        }
    }

/** [mapSkippingFailures] for a row that yields several events, or none. */
@Suppress("TooGenericExceptionCaught") // Intentional: one malformed row must not cost the page
inline fun <T, R> Iterable<T>.flatMapSkippingFailures(
    logger: KLogger,
    what: String,
    parse: (T) -> List<R>
): List<R> =
    flatMap { row ->
        try {
            parse(row)
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse $what, skipping" }
            emptyList()
        }
    }

/**
 * A listing's rows through [mapSkippingFailures], after one INFO line counting them: `Found <n> <what>(s)`.
 * The source comes from the run's MDC, and [url], when given, goes in the payload rather than the text.
 */
inline fun <T, R : Any> Iterable<T>.parseListing(
    logger: KLogger,
    what: String,
    url: String? = null,
    parse: (T) -> R?
): List<R> {
    val rows = this as? Collection<T> ?: toList()
    logger.at(Level.INFO) {
        message = "Found ${rows.size} ${countedNoun(what)}(s)"
        payload = url?.let { mapOf(LogFields.URL to it) }
    }
    return rows.mapSkippingFailures(logger, what, parse)
}

/** [what] without the article its failure line reads it with: `a Paloma night` is counted as `Paloma night(s)`. */
@PublishedApi
internal fun countedNoun(what: String): String = what.removePrefix("an ").removePrefix("a ")
