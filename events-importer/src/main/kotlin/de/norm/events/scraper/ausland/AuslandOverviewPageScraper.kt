package de.norm.events.scraper.ausland

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.isPlaceholderName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLinesAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.util.Locale

/**
 * Pure parser for ausland's TYPO3 news listing, `/program/all`: one `section.event` per event with the date
 * (`Thursday, 15 October, 2026 - 10:00`), the title, the venue's own line-up field (`p.artistinfo`, one act
 * per line) and the series it belongs to.
 *
 * The site names no category. A title with one of the house's other formats (a workshop, a residency, an
 * installation) types it; any other night is a concert, which is what the house mostly programmes.
 */
class AuslandOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("section.event.listitem")
        logger.info { "Found ${items.size} event item(s) on the ausland listing" }
        return items.mapSkippingFailures(logger, "ausland event item") { parseItem(it, baseUrl) }
    }

    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = item.textAt("h2") ?: return null
        val href = item.selectFirst("a[itemprop=url][href*=/event/]")?.attr("href") ?: return null
        val url = resolveUrl(baseUrl, href)
        val dateLine = item.textAt("p.date")
        val date = parseListingDate(dateLine)
        if (date == null) {
            logger.warn { "ausland event '$title' has no readable date '$dateLine', skipping" }
            return null
        }
        val format = VENUE_FORMATS.entries.firstOrNull { (word, _) -> word in title.lowercase() }?.value
        return ScrapedEvent(
            title = title,
            subtitle = item.textAt("p.addinfo.series"),
            eventType = format ?: inferConcertVenueType(title),
            typeIsFallback = format == null,
            eventDate = date,
            startTime = parseTime(dateLine?.substringAfter(" - ", "")),
            imageUrl = item.selectFirst("img[itemprop=image][src]")?.absUrl("src").blankToNull(),
            sourceUrl = url,
            sourceId = auslandSourceId(url),
            artists = lineup(item)
        )
    }

    private fun lineup(item: Element): List<ScrapedArtist> =
        item
            .textLinesAt("p.artistinfo")
            .filterNot { isPlaceholderName(it) || isNonArtistName(it) }
            .distinct()
            .map { ScrapedArtist(name = it) }

    private companion object {
        val LISTING_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, dd MMMM, yyyy", Locale.ENGLISH)

        /** The house's words for its formats beside concerts, checked before the shared title cues. */
        val VENUE_FORMATS =
            linkedMapOf(
                "workshop" to EventType.OTHER.name,
                "residency" to EventType.OTHER.name,
                "meetup" to EventType.OTHER.name,
                "record club" to EventType.OTHER.name,
                "research day" to EventType.OTHER.name,
                "installation" to EventType.EXHIBITION.name,
                "sound art project" to EventType.EXHIBITION.name
            )

        fun parseListingDate(line: String?): LocalDate? =
            line?.substringBefore(" - ")?.trim()?.let {
                try {
                    LocalDate.parse(it, LISTING_DATE)
                } catch (_: DateTimeParseException) {
                    null
                }
            }
    }
}

/** The event's slug under `/event/`, the same on the listing and on the event page. */
internal fun auslandSourceId(url: String): String = "${EventSource.AUSLAND.sourceIdPrefix}${URI(url).path.removePrefix("/event/").trimEnd('/')}"
