package de.norm.events.scraper.atrane

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.stringOrNull
import de.norm.events.scraper.withBilingualDescriptionSplit
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure parser for A-Trane's `/programm/` page, an EventON calendar with one month block per month.
 *
 * Each `.eventon_list_event` card carries a schema.org `Event` JSON-LD block for the name, URL,
 * date, image and blurb, and markup for what the block leaves out: the style tags and the ticket
 * prices. The `startDate` is unpadded with a fixed `+2:00` offset (`2026-11-1T20:00+2:00`), so only
 * its wall-clock digits are read. The name is `<br>`-separated lines under an "A-TRANE PRÄSENTIERT"
 * credit. Featured cards repeat a night, so a night is kept once. Every night is a concert, and
 * its style tags, which the club writes freely, are joined into the genre.
 */
class ATraneProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(document: Document): List<ScrapedEvent> {
        val cards = document.select(".eventon_list_event[data-event_id]")
        logger.info { "Found ${cards.size} event card(s) on the A-Trane programme" }

        return cards
            .mapSkippingFailures(logger, "an A-Trane event card") { card ->
                parseCard(card)
            }.distinctBy { it.sourceId }
    }

    @Suppress("ReturnCount") // One guard clause per kind of card that is not a concert here
    private fun parseCard(card: Element): ScrapedEvent? {
        val event = card.jsonLdEvents().firstOrNull() ?: error("No JSON-LD block found")
        val url = event.stringOrNull("url") ?: error("No event URL found")
        val lines = nameLines(event.schemaName() ?: error("No event name found"))
        if (lines.any { CLOSED.containsMatchIn(it) }) return null
        // A show the club presents in another house is that house's event.
        if (lines.any { ELSEWHERE.containsMatchIn(it) }) return null

        val (title, subtitle) = titleAndSubtitle(lines) ?: error("No act found in '${lines.joinToString(" / ")}'")
        val start =
            parseStart(event.stringOrNull("startDate")) ?: return null.also {
                logger.warn { "No parseable start for A-Trane event '$title', skipping" }
            }
        val tags = card.select(".evoet_eventtypes [data-filter^=event_type]").map { it.attr("data-v").trim() }
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = htmlParagraphText(event.stringOrNull("description")),
            eventType = EventType.CONCERT.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            imageUrl = event.schemaImageUrl(),
            sourceUrl = url,
            sourceId = "${EventSource.A_TRANE.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH)}",
            genre = tags.filter { it.isNotBlank() && !NOT_A_STYLE.containsMatchIn(it) }.joinToString(", ").ifEmpty { null },
            pricePresale = fullPrice(card),
            free = tags.any { FREE_ENTRY.containsMatchIn(it) },
            status = event.schemaStatus(),
            // The sidemen line splits names on hyphens that double-barrelled names also carry.
            artists = buildArtistsForEventType(actName(title), null, EventType.CONCERT.name)
        ).withBilingualDescriptionSplit()
    }

    /** The name's lines, entities decoded and non-breaking spaces flattened, blank lines dropped. */
    private fun nameLines(name: String): List<String> =
        name
            .split(LINE_BREAK)
            .map { it.replace(WHITESPACE, " ").trim() }
            .filter { it.isNotEmpty() }

    /**
     * The act and the lines under it. A festival night names its act after "HEUTE … MIT:" below a
     * banner line; a weekly session leaves that label empty, and an empty label is dropped.
     */
    private fun titleAndSubtitle(lines: List<String>): Pair<String, String?>? {
        val body = lines.filterNot { PRESENTER.containsMatchIn(it) }
        val acts = body.map { it.replace(TONIGHT_WITH, "").trim() }
        val actIndex = body.indexOfFirst { TONIGHT_WITH.containsMatchIn(it) && it.replace(TONIGHT_WITH, "").isNotBlank() }
        val start = if (actIndex >= 0) actIndex else 0
        val rest = acts.drop(start).filter { it.isNotEmpty() }
        val title = rest.firstOrNull() ?: return null
        return title to rest.drop(1).joinToString(" · ").ifEmpty { null }
    }

    /** The act without the album or programme title it names in quotes; an act that is only a quoted name loses the quotes. */
    private fun actName(title: String): String = title.replace(WORK_TITLE, "").trim().ifEmpty { title.trim { it in QUOTES } }

    /** The wall-clock start of an unpadded `startDate`; the offset is ignored. */
    private fun parseStart(value: String?): LocalDateTime? =
        START.find(value.orEmpty())?.let {
            try {
                LocalDateTime.parse(it.value, START_FORMAT)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    /** The cheapest ticket that is not a student rate; a night without the shop's price list has none. */
    private fun fullPrice(card: Element) =
        card
            .select(".evovo_price_option label")
            .filterNot { CONCESSION.containsMatchIn(it.ownText()) }
            .mapNotNull { parsePriceValue(it.selectFirst(".value")?.text()) }
            .minOrNull()

    private companion object {
        const val EVENT_PATH = "/Events-Directory/"

        val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val WHITESPACE = Regex("""[\s\u00A0]+""")
        const val QUOTES = "«»\" "
        val WORK_TITLE = Regex("""\s*[«"][^»"]*[»"]\s*$""")
        val START = Regex("""^\d{4}-\d{1,2}-\d{1,2}T\d{1,2}:\d{2}""")
        val START_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("y-M-d'T'H:mm")

        val CLOSED = Regex("""geschlossen|closed""", RegexOption.IGNORE_CASE)
        val ELSEWHERE = Regex("""(?:präsentiert|presents)\s+(?:im|in|at)\s+(?!a-trane)""", RegexOption.IGNORE_CASE)
        val PRESENTER = Regex("""^a-trane\s+(?:präsentiert|presents)""", RegexOption.IGNORE_CASE)
        val TONIGHT_WITH = Regex("""^(?:tag\s*\d+\s+)?heute\b.*?\bmit:""", RegexOption.IGNORE_CASE)

        val NOT_A_STYLE = Regex("""live concert|eintritt frei""", RegexOption.IGNORE_CASE)
        val FREE_ENTRY = Regex("""eintritt frei|no cover""", RegexOption.IGNORE_CASE)
        val CONCESSION = Regex("""student""", RegexOption.IGNORE_CASE)
    }
}
