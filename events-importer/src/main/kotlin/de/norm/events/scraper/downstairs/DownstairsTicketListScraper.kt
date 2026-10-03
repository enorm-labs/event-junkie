package de.norm.events.scraper.downstairs

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure HTML parser for one page of the Downstairs Comedy Club's ticket list, the `tickets` Turbo
 * frame of its tickettoaster shop (`/catalog/tickets?limit=100&page=N`).
 *
 * Each `div[id^=tickets_ticket_]` is one performance: a date tile, a `18:00 Uhr` chip, the title,
 * the venue line and a `ds-status--soldout` or `--available` badge. The tile prints no year, but
 * the ticket link's slug ends in the full date (`…-am-03-10-2026`). The id after `tickets_ticket_`
 * is the performance's identity.
 *
 * The club's own nights (Downstairs Allstars, the Grünes Licht open mic) name no act; a guest's
 * `<Name> - <Programme>` names one. A workshop is a class, not a show, and is skipped; its
 * closing show is kept.
 */
class DownstairsTicketListScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div[id^=tickets_ticket_]")
        logger.info { "Found ${items.size} Downstairs ticket(s) on $baseUrl" }

        @Suppress("TooGenericExceptionCaught") // Intentional: one malformed row must not abort the page
        return items.mapNotNull { item ->
            try {
                parseItem(item)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse Downstairs ticket, skipping" }
                null
            }
        }
    }

    /** Whether the frame offers a further page: a `page` field in its "100 weitere Ergebnisse" form. */
    fun nextPage(document: Document): Int? = document.selectFirst("#tickets_pagy input[name=page]")?.attr("value")?.toIntOrNull()

    @Suppress("ReturnCount") // Guard clauses for the required fields are clearer than nesting
    private fun parseItem(item: Element): ScrapedEvent? {
        val id = item.id().removePrefix("tickets_ticket_").takeIf { it.isNotBlank() } ?: return null
        val url = item.selectFirst("[data-clickable-href-value]")?.absUrl("data-clickable-href-value")?.takeIf { it.isNotEmpty() } ?: return null
        val date = SLUG_DATE.find(url)?.destructured?.let { (day, month, year) -> LocalDate.of(year.toInt(), month.toInt(), day.toInt()) } ?: return null
        val title = item.textAt(".ds-event-row__title")?.replace(WHITESPACE, " ")?.trim() ?: return null
        if (WORKSHOP.containsMatchIn(title) && !SHOW.containsMatchIn(title)) return null
        return ScrapedEvent(
            title = title,
            eventType = EventType.COMEDY.name,
            eventDate = date,
            startTime = parseTime(item.textAt(".ds-timechip")?.removeSuffix("Uhr")?.trim()),
            sourceUrl = url,
            sourceId = "${EventSource.DOWNSTAIRS.sourceIdPrefix}$id",
            ticketUrl = url,
            soldOut = item.selectFirst(".ds-status--soldout") != null,
            artists = if (HOUSE_NIGHT.containsMatchIn(title)) emptyList() else buildArtistsForEventType(title, null, EventType.COMEDY.name)
        )
    }

    private companion object {
        val SLUG_DATE = Regex("""-am-(\d{2})-(\d{2})-(\d{4})$""")
        val WORKSHOP = Regex("""workshop""", RegexOption.IGNORE_CASE)
        val SHOW = Regex("""show""", RegexOption.IGNORE_CASE)
        val HOUSE_NIGHT = Regex("""^\W*downstairs\b""", RegexOption.IGNORE_CASE)
    }
}
