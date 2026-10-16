package de.norm.events.scraper.colosseum

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.WixEventsWarmupData
import de.norm.events.scraper.parseWixSchedule
import de.norm.events.scraper.parseWixTicketPrice
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.wixParagraphs
import de.norm.events.scraper.wixTicketPriceRange
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import java.time.LocalTime

/**
 * Pure parser for one Colosseum event page (`/details-registrierung/<slug>`), read for its times
 * and its ticket price.
 *
 * **The page states two Einlass/Beginn pairs, and the field they sit in tells them apart.** The
 * payload's `about` is a cloned block the house never rewrites: all 18 live events carry the same
 * `Einlass: 19 Uhr / Beginn: 20 Uhr` there, over a Dustin O'Halloran biography. The rich-content
 * `longDescription` carries the event's own lines, and all 18 have them. Reading the field rather
 * than the formatting is what gets the hard rows right: the Gene Krupa Show states `Einlass: 17
 * Uhr` without minutes, and Peter Sandberg states the boilerplate's own clocks as its own text
 * (#1684).
 *
 * **The listing's time is the Einlass more often than the Beginn** — 10 of 18 against 8 — so it
 * cannot be relabelled without this page, and the pair read here replaces it whole.
 *
 * **The price is the ticket's own, the figure the page labels `Preis`** (#1954): face value plus
 * the presale fee, as other venues publish it. The listing's `lowestTicketPrice` is the checkout
 * total, with Wix's service fee and, on some events, VAT added on top — 21,72 € for a 19,80 €
 * ticket. An event sold through an outside shop has no `tickets`, and keeps the listing's figure.
 * Several tiers give the lowest as the price and the range as the note.
 *
 * **The event's own text also names its type**, where the listing gives none: a comedian, a
 * concert or a composer ([typeOf]). The description is still refused, for the reason in
 * [ColosseumWebsiteImporter]'s KDoc.
 *
 * @see ColosseumWebsiteImporter for the fetch orchestration.
 */
class ColosseumDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses the doors and start times of one event page, or null when the payload cannot be read.
     *
     * The returned event carries the times and the page's own title and date; everything else is
     * the listing's, which [ColosseumWebsiteImporter] merges back in.
     */
    fun scrape(
        document: Document,
        url: String
    ): ScrapedEvent? {
        val state = WixEventsWarmupData.pageState(document, EventSource.COLOSSEUM)
        val event = state?.let { WixEventsWarmupData.eventOf(it, EventSource.COLOSSEUM) } ?: return null
        val prices = state.path("tickets").mapNotNull { parseWixTicketPrice(it.path("price")) }
        val schedule = parseWixSchedule(event.path("scheduling").path("config"))
        val text = wixParagraphs(event.path("longDescription"))
        val lines = timeLines(text)
        val start = lines[BEGINN] ?: schedule.startTime
        // An Einlass equal to the start is the listing's own time read back, not a door time.
        val doors = lines[EINLASS]?.takeIf { it != start }
        if (lines.isEmpty()) {
            logger.debug { "Colosseum event page states no Einlass or Beginn time: $url" }
        }
        return ScrapedEvent(
            title = event.stringOrNull("title").orEmpty(),
            eventDate = schedule.date ?: UNRESOLVED_EVENT_DATE,
            eventType = typeOf(text),
            doorsTime = doors,
            startTime = start,
            pricePresale = prices.minOrNull(),
            priceNote = wixTicketPriceRange(prices),
            sourceUrl = url,
            sourceId = "${EventSource.COLOSSEUM.sourceIdPrefix}${event.stringOrNull("slug").orEmpty()}"
        )
    }

    /**
     * The clocks the event's own text states, by their label.
     *
     * A label with nothing after it yields no entry, and the listing's time stands. No live event
     * writes one today; the house edits these lines by hand, so the case is covered rather than
     * assumed away.
     */
    private fun timeLines(paragraphs: List<String>): Map<String, LocalTime> =
        paragraphs
            .mapNotNull { text -> TIME_LINE.find(text) }
            .associate { match ->
                val (label, hour, minute) = match.destructured
                label.lowercase() to LocalTime.of(hour.toInt(), minute.ifEmpty { "0" }.toInt())
            }

    /**
     * The type the event's own text names, or null: a comedian or stand-up first, then a concert,
     * a composer or a pianist. The listing has no category, so [ColosseumWebsiteImporter] uses it
     * only where the title and the strapline give no cue (#2992).
     */
    private fun typeOf(paragraphs: List<String>): String? {
        val text = paragraphs.joinToString(" ")
        return when {
            COMEDY_TEXT.containsMatchIn(text) -> EventType.COMEDY.name
            CONCERT_TEXT.containsMatchIn(text) -> EventType.CONCERT.name
            else -> null
        }
    }

    private companion object {
        const val EINLASS = "einlass"
        const val BEGINN = "beginn"

        /** `Stand-up-Comedy`, `Comedians`, `Kabarett`: the act is a comedian. */
        val COMEDY_TEXT = Regex("""stand-?up|comedian|comedy|kabarett""", RegexOption.IGNORE_CASE)

        /** `Konzert`, `concert`, `composer and pianist`: the night is music. */
        val CONCERT_TEXT = Regex("""konzert|concert|composer|komponist|pianist""", RegexOption.IGNORE_CASE)

        /** `Einlass: 18:30 Uhr`, and `Beginn: 20 Uhr` — the house writes the minutes only sometimes. */
        val TIME_LINE = Regex("""^\s*(Einlass|Beginn)\s*:?\s*(\d{1,2})(?::(\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)
    }
}
