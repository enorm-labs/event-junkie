package de.norm.events.scraper.beateuwe

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.B2B_SEPARATOR
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.stripArtistSuffix
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Pure parser for the "Anstehende Veranstaltungen" list on Beate Uwe's Elementor homepage. A night
 * is an icon list (`FR, 09.10.26 OKT` beside a calendar icon, `23:00` beside a clock) followed by
 * a text widget: the title paragraph, linked to the night's Resident Advisor page when there is
 * one, then a `w/ A, B b2b C, D (live)` lineup or a line of prose. Elementor nests each night one
 * container deeper than the last, so a night is the pair in document order, not a shared parent.
 * The RA link is stored as the ticket link and never followed.
 */
class BeateUweOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val nights = pairNights(document)
        logger.info { "Found ${nights.size} night(s) on the Beate Uwe homepage" }
        return nights.mapSkippingFailures(logger, "a Beate Uwe night") { (dates, text) -> parseNight(dates, text, sourceUrl) }
    }

    /** Each date list with the first text widget after it, in document order. */
    private fun pairNights(document: Document): List<Pair<Element, Element>> {
        val nights = mutableListOf<Pair<Element, Element>>()
        var pending: Element? = null
        for (element in document.select("$DATE_LIST, $TEXT_WIDGET")) {
            val dates = pending
            when {
                element.`is`(DATE_LIST) -> {
                    pending = element
                }

                dates != null -> {
                    nights += dates to element
                    pending = null
                }
            }
        }
        return nights
    }

    private fun parseNight(
        dates: Element,
        text: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val dateText = dates.iconText("fa-calendar-alt")
        val date = parseDate(dateText)
        val paragraphs = text.select("p").mapNotNull { it.clean().blankToNull()?.let { line -> it to line } }
        val title = paragraphs.firstOrNull()?.second
        if (date == null || title == null) {
            logger.warn { "Beate Uwe night '$dateText' has no parseable date or no title, skipping" }
            return null
        }
        val second = paragraphs.getOrNull(1)?.second
        val lineup = second?.let { LINEUP_PREFIX.find(it) }?.let { second.substring(it.range.last + 1) }
        return ScrapedEvent(
            title = title,
            subtitle = second.takeIf { lineup == null },
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = date,
            startTime = parseTime(dates.iconText("fa-clock")),
            sourceUrl = sourceUrl,
            // The club holds one night a date and only some nights link anywhere, so the date is the identity.
            sourceId = "${EventSource.BEATE_UWE.sourceIdPrefix}$date",
            ticketUrl =
                paragraphs
                    .first()
                    .first
                    .selectFirst("a[href]")
                    ?.absUrl("href")
                    ?.blankToNull(),
            artists = lineup?.let(::parseLineup).orEmpty()
        )
    }

    /** `FR, 09.10.26 OKT`: the year is printed, two digits. */
    private fun parseDate(text: String?): LocalDate? {
        val (day, month, year) = DATE.find(text.orEmpty())?.destructured ?: return null
        val fullYear = year.toInt().let { if (it < CENTURY) it + MILLENNIUM else it }
        return try {
            LocalDate.of(fullYear, month.toInt(), day.toInt())
        } catch (_: DateTimeException) {
            null
        }
    }

    /** Comma-separated acts; a `b2b` pair bills both, `(live)` bills the act `LIVE`, and a `(… Set)` note comes off. */
    private fun parseLineup(text: String): List<ScrapedArtist> =
        text
            .split(OUTSIDE_PARENTHESES_COMMA)
            .flatMap { it.split(B2B_SEPARATOR) }
            .mapNotNull { act ->
                val name = stripArtistSuffix(act.replace(SET_NOTE, "").trim()).blankToNull()?.takeUnless(::isNonArtistName) ?: return@mapNotNull null
                ScrapedArtist(name = name, role = if (LIVE_MARK.containsMatchIn(act)) ArtistRole.LIVE else ArtistRole.DJ)
            }.distinctBy { it.name.lowercase() }

    private fun Element.iconText(icon: String): String? = selectFirst("li:has(i.$icon) .elementor-icon-list-text")?.clean()

    private fun Element.clean(): String = text().replace(WHITESPACE, " ").trim()

    private companion object {
        const val DATE_LIST = "ul.elementor-icon-list-items:has(i.fa-calendar-alt)"
        const val TEXT_WIDGET = "div.elementor-widget-text-editor"
        val DATE = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{2}|\d{4})\b""")
        val LINEUP_PREFIX = Regex("""^w/\s*""", RegexOption.IGNORE_CASE)
        val OUTSIDE_PARENTHESES_COMMA = Regex(""",(?![^()]*\))""")
        val SET_NOTE = Regex("""\s*\([^()]*\bset\)\s*$""", RegexOption.IGNORE_CASE)
        val LIVE_MARK = Regex("""\(\s*live\s*\)""", RegexOption.IGNORE_CASE)
        const val CENTURY = 100
        const val MILLENNIUM = 2000
    }
}
