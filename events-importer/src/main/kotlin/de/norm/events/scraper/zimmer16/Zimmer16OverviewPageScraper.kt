package de.norm.events.scraper.zimmer16

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cssUrlValue
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate

/**
 * Pure parser for the ZIMMER 16 homepage and its children's programme page, whose YesTicket WordPress plugin renders
 * the events as cards: `a[href*=yesticket.org/event/]` around a `div.ytp-event-card` with the day, the month and the year.
 *
 * A title ends in the audience, `(Erwachsene)` or `(Kinder)`, which goes. A `(Kinder)` card is marked `for-children`
 * (EVENT_SCOPE.md §3.5) and is a show unless its title says `Konzert`. The site names no category, so an adult night is
 * a concert unless its title names the house's other formats, the reading stage or a revue.
 *
 * There is no line-up field either. A title is billed as its act only when it is a bare name, a `+` or `, ` co-bill,
 * or `<programme> mit <act>`. A `<record> Release Tour` part names the record, not the act (#3164). A tagline,
 * a programme name or an `&` (`Calum Baird – Scottish Singer-Songwriter Live`, `Kaléko - Friederike
 * Ziegler`, `Bossa Nova Duo Kommerell & Komoll`) puts the act in a different place each time, so it names nobody.
 * Nor does the weekly `Offene Bühne`, whose stage is open to anyone.
 */
class Zimmer16OverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val cards = document.select("a[href*=yesticket.org/event/]:has(div.ytp-event-card)")
        logger.info { "Found ${cards.size} YesTicket card(s) on the ZIMMER 16 homepage" }
        return cards.mapSkippingFailures(logger, "ZIMMER 16 card") { parseCard(it, baseUrl) }
    }

    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val rawTitle = card.textAt(".ytp-event-card-title") ?: return null
        val url = card.attr("href").ifBlank { return null }.let { resolveUrl(baseUrl, it) }
        val date = cardDate(card)
        if (date == null) {
            logger.warn { "ZIMMER 16 card '$rawTitle' has no readable date, skipping" }
            return null
        }
        val support = SUPPORT.find(rawTitle)
        val childrens = CHILDREN.find(rawTitle)?.value?.trim()
        val title = rawTitle.replace(AUDIENCE, "").replace(SUPPORT, "").trim()
        val subtitle = support?.let { "Support: ${it.groupValues[1].trim()}" }
        val eventType =
            if (childrens != null) {
                if (CONCERT_WORD.containsMatchIn(title)) EventType.CONCERT.name else EventType.SHOW.name
            } else {
                HOUSE_FORMATS.entries.firstOrNull { (word, _) -> word in title.lowercase() }?.value ?: inferConcertVenueType(title)
            }
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            typeIsFallback = true,
            eventDate = date,
            imageUrl = card.selectFirst(".ytp-event-card-image[style]")?.attr("style")?.let { cssUrlValue(it, "background-image") },
            sourceUrl = url,
            ticketUrl = url,
            sourceId = "${EventSource.ZIMMER_16.sourceIdPrefix}${url.trimEnd('/').substringAfterLast('/')}",
            artists = if (TAGLINE.containsMatchIn(title) || OPEN_STAGE in title.lowercase()) emptyList() else artistsOf(title, subtitle, eventType)
        ).forChildrenIf(childrens)
    }

    private fun artistsOf(
        title: String,
        subtitle: String?,
        eventType: String
    ): List<ScrapedArtist> {
        val billing = title.split(", ").filterNot { RELEASE_TOUR.containsMatchIn(it) }.joinToString(" + ")
        return if (billing.isBlank()) emptyList() else buildArtistsForEventType(billing, subtitle, eventType, unpackWithFrame = true)
    }

    private fun cardDate(card: Element): LocalDate? {
        val month = parseGermanMonthAbbreviation(card.textAt(".ytp-event-card-month"))
        val day = card.textAt(".ytp-event-card-day")?.toIntOrNull()
        val year = card.textAt(".ytp-event-card-year")?.toIntOrNull()
        if (month == null || day == null || year == null) return null
        return try {
            LocalDate.of(year, month, day)
        } catch (_: DateTimeException) {
            null
        }
    }

    private companion object {
        val AUDIENCE = Regex("""\s*\((?:Erwachsene|Kinder)\)\s*$""")
        val CHILDREN = Regex("""\(Kinder\)\s*$""")
        val CONCERT_WORD = Regex("""konzert""", RegexOption.IGNORE_CASE)
        val SUPPORT = Regex("""\s*\(support:\s*([^)]+)\)""", RegexOption.IGNORE_CASE)
        val TAGLINE = Regex("""[–—:"„“”]| - |&|\+\+""")
        val RELEASE_TOUR = Regex("""\sRelease[- ]Tour$""", RegexOption.IGNORE_CASE)

        /** The open stage: live music with no billed act, so a concert that names nobody (#2992). */
        const val OPEN_STAGE = "offene bühne"

        /** The house's own words for its formats beside concerts. */
        val HOUSE_FORMATS =
            linkedMapOf(
                "lesebühne" to EventType.READING.name,
                "revue" to EventType.SHOW.name,
                OPEN_STAGE to EventType.CONCERT.name
            )
    }
}
