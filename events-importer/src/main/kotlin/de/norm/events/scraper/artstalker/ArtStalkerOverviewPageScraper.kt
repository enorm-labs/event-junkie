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
        val eventType = inferConcertVenueType(title, subtitle?.replace(NEGATED_CUE, ""), VENUE_FORMATS)
        val openStage = OPEN_STAGE.containsMatchIn(title)
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
            artists = if (openStage) emptyList() else buildArtistsForEventType(billedActs(title), subtitle, eventType)
        )
    }

    private companion object {
        /** The music school's student showcase, a house format that is no concert of a billed act. */
        val VENUE_FORMATS = mapOf("musikschule" to EventType.OTHER.name)

        /**
         * The weekly open session and an open mic: live music with no billed act, so a concert
         * whose title names nobody (#2992).
         */
        val OPEN_STAGE = Regex("""\bsession\b|\bopen\s+mic\b""", RegexOption.IGNORE_CASE)

        /** A cue the tagline denies, which types nothing: `Keine Karaoke Maschine. Eine echte Live-Band` (#3792). */
        val NEGATED_CUE = Regex("""\bkein(?:e|en|er)?\s+[\p{L}-]+""", RegexOption.IGNORE_CASE)
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
 * "präsentiert von" (`BAD COMPANY- und FREE-Songs präsentiert von FREE COMPANY`), a sing-along
 * after "mit der Band" (`Mitsing Konzert mit der Band The Rollin’ Horses`). A trailing `live`,
 * `live und in Farbe` or `and the Band` is billing, not a second act after `und` (#3749, #3792).
 */
internal fun billedActs(title: String): String {
    val acts =
        (PRESENTED_BY.find(title) ?: WITH_THE_BAND.find(title))
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?: title.replaceFirst(FORMAT_LABEL, "").ifBlank { title }
    return acts
        .replaceFirst(LIVE_TAIL, "")
        .replaceFirst(BAND_TAIL, "")
        .ifBlank { acts }
}

/** The shop's event number, the last path segment: `…-am-3-10-2026/e2535642`. */
internal val SHOP_EVENT_ID = Regex("""/e(\d+)$""")

private val TAGLINE_SEPARATOR = Regex("""\s-\s""")
private val FORMAT_LABEL = Regex("""^\p{L}+:\s+""")
private val LIVE_TAIL = Regex("""\s+live(?:\s+und\s+in\s+farbe)?$""", RegexOption.IGNORE_CASE)
private val PRESENTED_BY = Regex("""\spräsentiert\s+von\s+(.+)$""", RegexOption.IGNORE_CASE)
private val WITH_THE_BAND = Regex("""\smit\s+der\s+band\s+(.+)$""", RegexOption.IGNORE_CASE)
private val BAND_TAIL = Regex("""\s+(?:and\s+the|&(?:\s+the)?|und(?:\s+die)?|mit\s+der)\s+band$""", RegexOption.IGNORE_CASE)
