package de.norm.events.scraper.thedoor

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

/**
 * Pure parser for The Door's Oxygen-built `/events/` page: one `.card-repeater-event` card per night, grouped by week,
 * each with a `02.10.2026` date, a start time, an `h2` title, a `LINEUP:` line and a flyer.
 *
 * **A placeholder night is skipped.** The grid fills every Friday and Saturday ahead of booking it, as `Saturday` with
 * the lineup `Coming Soon` and a generic cover. A night whose title is a bare weekday name and whose lineup names no act
 * once placeholders are dropped is such a slot. A weekday title with a real lineup, or a real title with `Coming Soon`,
 * is a night.
 */
class TheDoorEventsPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val (placeholders, cards) = document.select(".card-repeater-event").partition(::isPlaceholder)
        logger.info { "Found ${cards.size} night(s) on The Door's events page, skipped ${placeholders.size} placeholder(s)" }
        return cards.mapSkippingFailures(logger, "a The Door night") { parseCard(it, sourceUrl) }
    }

    private fun isPlaceholder(card: Element): Boolean = card.textAt("h2")?.lowercase() in WEEKDAY_NAMES && lineupOf(card).isEmpty()

    private fun parseCard(
        card: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val spans = card.select(".ct-span").map { it.text().trim() }
        val date = spans.firstOrNull { DATE.matches(it) }?.let(::parseGermanDate)
        val title = card.textAt("h2")?.let(::cleanEventTitle).blankToNull()
        if (date == null || title == null) {
            logger.warn { "The Door card without a date or a title ($spans), skipping" }
            return null
        }
        val lineup = lineupOf(card)
        return ScrapedEvent(
            title = title,
            eventType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name } ?: EventType.PARTY.name,
            eventDate = date,
            startTime = spans.firstOrNull { CLOCK.matches(it) }?.let(::parseTime),
            imageUrl = card.imgSrcAt("img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.THE_DOOR.sourceIdPrefix}$date",
            artists = lineup.map { ScrapedArtist(name = it, role = "DJ") }
        )
    }

    /** The comma-separated acts in the block after the `LINEUP:` label, placeholders such as `Coming Soon` dropped. */
    private fun lineupOf(card: Element): List<String> {
        val label = card.select(".ct-text-block").firstOrNull { it.text().trim().equals(LINEUP_LABEL, ignoreCase = true) }
        return label
            ?.nextElementSibling()
            ?.text()
            .orEmpty()
            .split(',')
            .mapNotNull { it.blankToNull() }
            .filterNot { it.lowercase() in COMING_SOON || isNonArtistName(it) }
    }

    private companion object {
        val DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{4}""")
        val CLOCK = Regex("""\d{1,2}:\d{2}""")
        const val LINEUP_LABEL = "LINEUP:"
        val COMING_SOON = setOf("coming soon", "soon", "announced soon", "tba soon")

        /** Weekday names in English and German, the titles a placeholder slot carries. */
        val WEEKDAY_NAMES: Set<String> =
            DayOfWeek.entries
                .flatMap { day -> listOf(Locale.ENGLISH, Locale.GERMAN).map { day.getDisplayName(TextStyle.FULL, it).lowercase(it) } }
                .toSet()
    }
}
