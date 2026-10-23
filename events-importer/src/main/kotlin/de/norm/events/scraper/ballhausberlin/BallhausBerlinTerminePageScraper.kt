package de.norm.events.scraper.ballhausberlin

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.paragraphText
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.parseTime
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the Ballhaus Berlin `/de/termine/` page: one `li.termin` per date since 2019, server-rendered. The top
 * holds the image, a `data-olddatum` stamp in Berlin time, the title as a bare text node and an optional subtitle `div`.
 * The bottom holds the text and one `div` per field: `Einlass:`, `Beginn:`, `VVK:`, `AK:` and `Tickets bestellen bei:`.
 *
 * - **No category.** The house's own words type the night ([FORMATS]): its dance nights are parties, its cabaret
 *   companies shows, and the dance bands concerts.
 * - **Out of scope:** a `Geschlossene Gesellschaft` (a private party), the `Vintage Market` and the `Yes Choir !`
 *   sing-along (EVENT_SCOPE.md §3.2).
 * - `info folgen` (details to follow) as a subtitle says nothing and is dropped.
 * - A date has no page of its own, so every row links to the list.
 */
class BallhausBerlinTerminePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("li.termin[data-fullid]")
        val events = items.mapSkippingFailures(logger, "Ballhaus Berlin date") { toEvent(it, baseUrl) }
        logger.info { "Found ${items.size} Ballhaus Berlin date(s), ${events.size} public and in scope" }
        return events
    }

    private fun toEvent(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val top = item.selectFirst(".terminTopSchrift") ?: return null
        val stamp = top.selectFirst(".terminDatetime[data-olddatum]")?.attr("data-olddatum") ?: return null
        val start = LocalDateTime.parse(stamp, STAMP)
        val title = top.textNodes().firstNotNullOfOrNull { it.text().blankToNull() }?.let(::cleanEventTitle) ?: return null
        if (OUT_OF_SCOPE.containsMatchIn(title)) return null
        val subtitle =
            top
                .children()
                .firstOrNull { !it.hasClass("terminDatetime") && !it.hasClass("terminMehrzeile") }
                ?.text()
                ?.blankToNull()
                ?.takeUnless { PLACEHOLDER.matches(it) }
        val bottom = item.selectFirst(".terminBottom")
        val fields =
            bottom
                ?.children()
                ?.filter { it.tagName() == "div" }
                .orEmpty()
                .associate(::field)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = bottom?.select("> p")?.paragraphText(),
            eventType = inferVenueFormatType(title, subtitle, FORMATS),
            typeIsFallback = FORMATS.keys.none { it in "$title ${subtitle.orEmpty()}".lowercase() },
            eventDate = start.toLocalDate(),
            doorsTime = parseTime(fields["Einlass"]),
            startTime = parseTime(fields["Beginn"]) ?: start.toLocalTime(),
            imageUrl =
                item
                    .selectFirst("img.terminIconBild")
                    ?.absUrl("src")
                    ?.blankToNull()
                    ?.replace(" ", "%20"),
            sourceUrl = baseUrl,
            sourceId = "${EventSource.BALLHAUS_BERLIN.sourceIdPrefix}${item.attr("data-fullid").removePrefix("termin_")}",
            ticketUrl = bottom?.selectFirst("div:containsOwn(Tickets bestellen bei) a[href]")?.absUrl("href")?.blankToNull(),
            pricePresale = parsePriceValue(fields["VVK"]),
            priceBoxOffice = parsePriceValue(fields["AK"])
        )
    }

    /** A `Label: value` field. The text node after the label holds the value, which a link may replace. */
    private fun field(div: Element): Pair<String, String> {
        val text = div.text()
        return text.substringBefore(':').trim() to (
            div
                .childNodes()
                .filterIsInstance<TextNode>()
                .joinToString(" ") { it.text() }
                .substringAfter(':')
                .trim()
        )
    }

    private companion object {
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
        val OUT_OF_SCOPE = Regex("""^(?:Geschlossene Gesellschaft|Vintage Market|Yes Choir)""", RegexOption.IGNORE_CASE)
        val PLACEHOLDER = Regex("""info folgen""", RegexOption.IGNORE_CASE)

        /** Checked in order, so a dance band's name wins over `tanz`, and `party` over `show`. */
        val FORMATS =
            linkedMapOf(
                "beat club" to EventType.CONCERT.name,
                "paveier" to EventType.CONCERT.name,
                "party" to EventType.PARTY.name,
                "club night" to EventType.PARTY.name,
                "swing" to EventType.PARTY.name,
                "latino" to EventType.PARTY.name,
                "noire" to EventType.PARTY.name,
                "boheme sauvage" to EventType.PARTY.name,
                "tanz" to EventType.PARTY.name,
                "cabaret" to EventType.SHOW.name,
                "velvet creepers" to EventType.SHOW.name,
                "theater" to EventType.SHOW.name,
                "show" to EventType.SHOW.name
            )
    }
}
