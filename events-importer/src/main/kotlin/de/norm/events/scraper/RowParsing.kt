package de.norm.events.scraper

import io.github.oshai.kotlinlogging.KLogger

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
