package de.norm.events.scraper.paloma

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.time.MonthDay
import java.util.Locale

/**
 * Pure parser for Paloma's hand-coded `programm.php`. Each night is a block headed by a bold
 * `FREITAG 16/10 22:00` line, without a year, followed by a table of one or more party names
 * (bold cells) and the DJs under each (`td.kotti_online_td`). The year comes from the weekday.
 * The title joins the night's party names; every DJ of every party is billed.
 *
 * The PHP upper-cases names byte-wise, so accented letters stay lower case (`RENé LöWE`). The
 * parser upper-cases them again, and the artist normalizer de-shouts the result.
 */
class PalomaProgrammPageScraper(
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val headings = document.select("b").filter { HEADING.matches(it.text().clean()) }
        logger.info { "Found ${headings.size} night(s) on the Paloma programme" }
        return headings.mapSkippingFailures(logger, "a Paloma night") { parseNight(it, sourceUrl) }
    }

    private fun parseNight(
        heading: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val text = heading.text().clean()
        val match = HEADING.matchEntire(text) ?: return null
        val (weekday, day, month) = match.destructured
        val date = parseDate(day, month, weekday) ?: return null
        val block = heading.parent()?.parent() ?: error("No block around '$text'")
        val parties = block.namesIn("td > b")
        val djs = block.namesIn("td.kotti_online_td").filterNot(::isNonArtistName)
        val title = parties.joinToString(TITLE_SEPARATOR).ifEmpty { djs.joinToString(", ") }.blankToNull()
        if (title == null) {
            logger.warn { "Paloma night '$text' names no party and no DJ, skipping" }
            return null
        }
        return ScrapedEvent(
            title = title,
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = date,
            startTime = parseTime(match.groupValues[TIME_GROUP]),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.PALOMA.sourceIdPrefix}$date",
            artists = djs.map { ScrapedArtist(name = it, role = "DJ") }
        )
    }

    private fun parseDate(
        day: String,
        month: String,
        weekday: String
    ): LocalDate? =
        try {
            inferYearForWeekday(MonthDay.of(month.toInt(), day.toInt()), parseGermanWeekday(weekday), clock)
        } catch (_: DateTimeException) {
            null
        }

    /** The upper-cased, distinct, non-blank texts of the cells [selector] matches. */
    private fun Element.namesIn(selector: String): List<String> =
        select(selector)
            .mapNotNull {
                it
                    .text()
                    .clean()
                    .uppercase(Locale.GERMAN)
                    .blankToNull()
            }.distinct()

    private fun String.clean(): String = replace(WHITESPACE, " ").trim()

    private companion object {
        /** `FREITAG 16/10 22:00`. */
        val HEADING = Regex("""^(\p{L}+)\s+(\d{1,2})/(\d{1,2})\s+(\d{1,2}:\d{2})$""")
        const val TIME_GROUP = 4
        const val TITLE_SEPARATOR = " / "
    }
}
