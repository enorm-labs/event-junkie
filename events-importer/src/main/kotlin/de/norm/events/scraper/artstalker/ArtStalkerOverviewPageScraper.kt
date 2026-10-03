package de.norm.events.scraper.artstalker

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.knownGenresInStyleTail
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parsePriceValue
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

private val logger = KotlinLogging.logger {}

/**
 * Pure parser for the shop's listing, one `a[data-sync-id]` card per event in the
 * `[data-testid=event-list]` list: `time[datetime]` is the start, the `h2` the title, and the
 * "ab … €" line the cheapest online ticket, fees included. The type and the artists are read here,
 * from the title alone ([splitTagline]); the event page adds nothing that decides either.
 */
class ArtStalkerOverviewPageScraper {
    fun scrape(document: Document): List<ScrapedEvent> =
        document.select("[data-testid=event-list] a[data-sync-id]").mapNotNull { card ->
            @Suppress("TooGenericExceptionCaught") // One malformed card must not abort the listing
            try {
                parseCard(card)
            } catch (e: Exception) {
                logger.warn(e) { "Skipping an ART Stalker card that failed to parse: ${card.attr("href")}" }
                null
            }
        }

    private fun parseCard(card: Element): ScrapedEvent? {
        val url = card.absUrl("href")
        val id = SHOP_EVENT_ID.find(url)?.groupValues?.get(1)
        val rawTitle = card.textAt("h2")
        val dateTime = card.selectFirst("time[datetime]")?.attr("datetime")
        val date = dateTime?.let(::parseIsoDate)
        if (id == null || rawTitle.isNullOrBlank() || date == null) {
            logger.warn { "Skipping an ART Stalker card without an id, a title or a date: $url" }
            return null
        }
        val (title, subtitle) = splitTagline(rawTitle)
        val eventType = inferConcertVenueType(title, subtitle, VENUE_FORMATS)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            genre = knownGenresInStyleTail(subtitle),
            eventType = eventType,
            eventDate = date,
            startTime = parseClockPrefix(dateTime.substringAfter("T")),
            imageUrl = card.imgSrcAt("img"),
            sourceUrl = url,
            sourceId = "${EventSource.ART_STALKER.sourceIdPrefix}$id",
            ticketUrl = url,
            pricePresale = parsePriceValue(card.textAt(".c-list-item-event__event-min-price")),
            artists = buildArtistsForEventType(billedActs(title), subtitle, eventType)
        )
    }

    private companion object {
        /**
         * The house's own formats, which bill no act in the title: the weekly open session and the
         * music school's student showcase.
         */
        val VENUE_FORMATS =
            mapOf(
                "session" to EventType.OTHER.name,
                "musikschule" to EventType.OTHER.name,
                "open mic" to EventType.OTHER.name
            )
    }
}

/**
 * The venue bills `<name> - <tagline>`: `Dan Patlansky - Blues Rock`, `New Breed - Classic Rock -
 * Von Hendrix bis Foo Fighters`. The first space-padded hyphen splits the name from the tagline,
 * which becomes the subtitle; an en dash stays, since `Deer Anna – For the BirdsTour` is one name.
 */
internal fun splitTagline(raw: String): Pair<String, String?> {
    val trimmed = raw.trim()
    val cut = TAGLINE_SEPARATOR.find(trimmed) ?: return trimmed to null
    val head = trimmed.substring(0, cut.range.first).trim()
    val tail = trimmed.substring(cut.range.last + 1).trim()
    return if (head.isEmpty() || tail.isEmpty()) trimmed to null else head to tail
}

/**
 * The part of a title that names the acts. A one-word format label before them is no act
 * (`Doppelkonzert: Glam Jam + Mission BlueZ`), and a tribute night names the band after
 * "präsentiert von" (`BAD COMPANY- und FREE-Songs präsentiert von FREE COMPANY`).
 */
internal fun billedActs(title: String): String =
    PRESENTED_BY
        .find(title)
        ?.groupValues
        ?.get(1)
        ?.trim()
        ?: title.replaceFirst(FORMAT_LABEL, "").ifBlank { title }

/** The shop's event number, the last path segment: `…-am-3-10-2026/e2535642`. */
internal val SHOP_EVENT_ID = Regex("""/e(\d+)$""")

private val TAGLINE_SEPARATOR = Regex("""\s-\s""")
private val FORMAT_LABEL = Regex("""^\p{L}+:\s+""")
private val PRESENTED_BY = Regex("""\spräsentiert\s+von\s+(.+)$""", RegexOption.IGNORE_CASE)
