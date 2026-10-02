package de.norm.events.scraper

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/**
 * Runs [block] and returns its result beside the `WARN` lines [dateCheckedAgainstWeekday] logged.
 * Every scraper that checks a printed weekday logs through that one function, so its tests
 * capture that function's logger rather than their own.
 */
fun <T> withWeekdayWarnings(block: () -> T): Pair<T, List<ILoggingEvent>> {
    val logger = LoggerFactory.getLogger(WEEKDAY_LOGGER) as Logger
    val appender = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(appender)
    return try {
        block() to appender.list.filter { it.level == Level.WARN }
    } finally {
        logger.detachAppender(appender)
        appender.stop()
    }
}

/** The logger kotlin-logging names after `WeekdayDateExtensions.kt`. */
private const val WEEKDAY_LOGGER = "de.norm.events.scraper.WeekdayDateExtensions"
