package de.norm.events.scraper.zosch

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Pure parser for the Zosch start page, where each `div.event` is one night.
 *
 * The header prints the date without a year (`21.10.`), so the date comes from the collapse target
 * `#e20261021`. The `.theme` span is the genre, the `.subtitle` the band's own style line.
 * The `.links` anchor is the band's website, not a ticket shop, so it is not read.
 */
class ZoschOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val nights = document.select(".eventContainer div.event")
        logger.info { "Found ${nights.size} night(s) on the Zosch page" }
        return nights.mapSkippingFailures(logger, "Zosch night") { parseNight(it, baseUrl) }
    }

    private fun parseNight(
        night: Element,
        baseUrl: String
    ): ScrapedEvent? {
        // The band name is bare text beside the `.theme` genre span.
        val title = night.selectFirst(".title > div")?.ownText().blankToNull()
        if (title.isNullOrBlank()) {
            logger.warn { "Zosch night has no title, skipping" }
            return null
        }
        val date = night.attrAt(".eventBody[id]", "id")?.let(::parseBodyId)
        if (date == null) {
            logger.warn { "Zosch night '$title' has no dated body id, skipping" }
            return null
        }
        val subtitle = night.textAt(".eventBody .subtitle")
        val eventType = mapEventType(night.textAt(".subHeader .category"), CATEGORY_TYPES)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = date,
            startTime = night.select(".subHeader span").firstNotNullOfOrNull { parseTime(it.text().removeSuffix(" Uhr")) },
            imageUrl = night.imgSrcAt(".imageWrapper img"),
            sourceUrl = baseUrl,
            sourceId = "${EventSource.ZOSCH.sourceIdPrefix}$date-${SlugGenerator.slugify(title)}",
            genre = night.textAt(".title .theme"),
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    private fun parseBodyId(id: String): LocalDate? =
        try {
            LocalDate.parse(id.removePrefix("e"), BODY_ID_DATE)
        } catch (_: DateTimeParseException) {
            null
        }

    private companion object {
        val BODY_ID_DATE: DateTimeFormatter = DateTimeFormatter.BASIC_ISO_DATE

        /** The site's three categories, `Musik`, `Lesung` and `Party`; the last two are in the shared table. */
        val CATEGORY_TYPES = mapOf("musik" to EventType.CONCERT.name)
    }
}
