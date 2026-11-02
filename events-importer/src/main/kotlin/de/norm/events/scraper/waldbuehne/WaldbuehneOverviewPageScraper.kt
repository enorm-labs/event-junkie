package de.norm.events.scraper.waldbuehne

import de.norm.events.common.deshout
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.decodeHtmlEntities
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.jsonLdEvents
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.schemaDate
import de.norm.events.scraper.schemaImageUrl
import de.norm.events.scraper.schemaName
import de.norm.events.scraper.schemaOffers
import de.norm.events.scraper.schemaSoldOut
import de.norm.events.scraper.schemaStatus
import de.norm.events.scraper.schemaTime
import de.norm.events.scraper.stringOrNull
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import tools.jackson.databind.JsonNode
import java.math.BigDecimal
import java.time.LocalTime

/**
 * Pure parser for the Waldbühne programme, read from the schema.org `MusicEvent` JSON-LD array that
 * `/programm-und-tickets/` embeds: every date of the season, with its tour line, image and price range.
 *
 * - A date whose clock is not set yet reads `T00:00:00`. No show here starts at midnight, so that clock is no start time.
 * - `description` is the tour line, with ` - <support>` appended when a support act is billed. The event page
 * names the support apart ([WaldbuehneEventPageScraper]), so the listing keeps the line as it is.
 * - `performer.name` repeats the event `name`, so the acts derive from the title as at any concert hall.
 * - An orchestra concert is left out until `docs/EVENT_SCOPE.md` §3.4 is decided ([ORCHESTRA]): the Berliner
 * Philharmoniker close their season here every June.
 */
class WaldbuehneOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val nodes = document.jsonLdEvents()
        logger.info { "Found ${nodes.size} Waldbühne JSON-LD event(s) on $baseUrl" }

        val parsed = nodes.mapSkippingFailures(logger, "Waldbühne event") { toScrapedEvent(it) }
        val (orchestra, ours) = parsed.partition { ORCHESTRA.containsMatchIn(it.title) }
        if (orchestra.isNotEmpty()) {
            logger.info { "Skipping ${orchestra.size} Waldbühne orchestra concert(s): ${orchestra.joinToString { it.sourceId }}" }
        }
        return ours
    }

    private fun toScrapedEvent(event: JsonNode): ScrapedEvent? {
        val cleanedTitle = event.schemaName()?.let(::cleanEventTitle) ?: return null
        val title = cleanedTitle.text
        val eventDate = event.schemaDate("startDate") ?: return null
        val url = event.stringOrNull("url")?.takeIf { it.startsWith("http") } ?: return null
        val subtitle = event.stringOrNull("description")?.let(::decodeHtmlEntities).blankToNull()
        val eventType = inferConcertVenueType(title, subtitle)
        val (presale, priceNote) = prices(event)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            typeIsFallback = eventType == EventType.CONCERT.name,
            eventDate = eventDate,
            doorsTime = event.schemaTime("doorTime"),
            startTime = event.schemaTime("startDate")?.takeUnless { it == LocalTime.MIDNIGHT },
            imageUrl = event.schemaImageUrl(),
            sourceUrl = url,
            sourceId = "${EventSource.WALDBUEHNE.sourceIdPrefix}${extractEventSlug(url, EVENT_PATH_PREFIX)}",
            pricePresale = presale,
            priceNote = priceNote,
            soldOut = event.schemaSoldOut() || cleanedTitle.soldOut,
            status = event.schemaStatus(),
            artists = castOf(subtitle) ?: buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    /**
     * The cheapest ticket, and the range in a note when the offer spans one. The `offers.url` is the
     * event page itself, so the ticket shop comes from the event page instead.
     */
    private fun prices(event: JsonNode): Pair<BigDecimal?, String?> {
        val offer = event.schemaOffers().firstOrNull() ?: return null to null
        val low = decimal(offer.stringOrNull("lowPrice") ?: offer.stringOrNull("price"))
        val high = decimal(offer.stringOrNull("highPrice"))
        val note = if (low != null && high != null && high > low) "$low – $high ${offer.stringOrNull("priceCurrency") ?: "EUR"}" else null
        return low to note
    }

    private fun decimal(value: String?): BigDecimal? = value.blankToNull()?.toBigDecimalOrNull()

    private companion object {
        const val EVENT_PATH_PREFIX = "/event/"

        /** An orchestra named in the title: "Berliner Philharmoniker: Saisonabschlusskonzert", "Rundfunk-Sinfonieorchester". */
        val ORCHESTRA = Regex("""\bphilharmoni(?:ker|c)\b|\w*(?:sinfonie|symphonie)orchester\b|\bsymphoniker\b""", RegexOption.IGNORE_CASE)
    }
}

/**
 * The acts a tour line bills when it is only names, the title then naming the show:
 * "DAS GIPFELTREFFEN" / "OLAF SCHUBERT, TORSTEN STRÄTER, JOHANN KÖNIG". Null for any other line.
 */
internal fun castOf(subtitle: String?): List<ScrapedArtist>? {
    val names = subtitle?.split(CAST_SEPARATOR)?.map { it.trim() } ?: return null
    if (names.size < MIN_CAST || !names.all { CAST_NAME.matches(it) }) return null
    return names.map { ScrapedArtist(deshout(it), ArtistRole.HEADLINER) }
}

private const val MIN_CAST = 2

private val CAST_SEPARATOR = Regex("""\s*,\s*""")

/** A person's name: two or three words of letters, no digit and no tour word. */
private val CAST_NAME = Regex("""\p{L}[\p{L}'.-]*(?:\s+\p{L}[\p{L}'.-]*){1,2}""")
