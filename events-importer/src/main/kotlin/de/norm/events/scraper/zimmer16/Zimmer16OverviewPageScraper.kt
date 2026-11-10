package de.norm.events.scraper.zimmer16

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cssUrlValue
import de.norm.events.scraper.dateOrNull
import de.norm.events.scraper.extractSupportFromSubtitle
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.lastPathSegment
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure parser for the ZIMMER 16 homepage and its children's programme page, whose YesTicket WordPress plugin renders
 * the events as cards: `a[href*=yesticket.org/event/]` around a `div.ytp-event-card` with the day, the month and the year.
 *
 * A title ends in the audience, `(Erwachsene)` or `(Kinder)`, which goes. A `(Kinder)` card is marked `for-children`
 * (EVENT_SCOPE.md §3.5) and is a show unless its title says `Konzert`. The site names no category, so an adult night is
 * a concert unless its title names the house's other formats, the reading stage or a revue.
 *
 * There is no line-up field either, and a title puts the act in a different place each time, so a concert card bills
 * only its `(support: …)` act. [Zimmer16EventPageScraper] bills the headliner from the event page's text
 * ([billedFromPage]). An improv night is comedy and a night that calls itself a `show` is a show. Another format's
 * card, a reading, a revue or a children's show, bills a bare name or a `+` co-bill, and nobody for a tagline or an `&`.
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
                HOUSE_FORMATS.entries.firstOrNull { (word, _) -> word in title.lowercase() }?.value
                    ?: inferConcertVenueType(title, null, HOUSE_KEYWORDS)
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
            sourceId = "${EventSource.ZIMMER_16.sourceIdPrefix}${lastPathSegment(url)}",
            artists =
                when {
                    eventType == EventType.CONCERT.name -> {
                        extractSupportFromSubtitle(subtitle).map { ScrapedArtist(name = it, role = ArtistRole.SUPPORT) }
                    }

                    TAGLINE.containsMatchIn(title) -> {
                        emptyList()
                    }

                    else -> {
                        buildArtistsForEventType(title, subtitle, eventType, unpackWithFrame = true)
                    }
                }
        ).forChildrenIf(childrens)
    }

    private fun cardDate(card: Element): LocalDate? {
        val month = parseGermanMonthAbbreviation(card.textAt(".ytp-event-card-month"))
        val day = card.textAt(".ytp-event-card-day")?.toIntOrNull()
        val year = card.textAt(".ytp-event-card-year")?.toIntOrNull()
        if (month == null || day == null || year == null) return null
        return dateOrNull { LocalDate.of(year, month, day) }
    }

    private companion object {
        val AUDIENCE = Regex("""\s*\((?:Erwachsene|Kinder)\)\s*$""")
        val CHILDREN = Regex("""\(Kinder\)\s*$""")
        val CONCERT_WORD = Regex("""konzert""", RegexOption.IGNORE_CASE)
        val SUPPORT = Regex("""\s*\(support:\s*([^)]+)\)""", RegexOption.IGNORE_CASE)
        val TAGLINE = Regex("""[–—:"„“”]| - |&|\+\+""")

        /** The open stage: live music with no billed act, so a concert that names nobody (#2992). */
        const val OPEN_STAGE = "offene bühne"

        /** The house's own words for its formats beside concerts. */
        val HOUSE_FORMATS =
            linkedMapOf(
                "lesebühne" to EventType.READING.name,
                "revue" to EventType.SHOW.name,
                OPEN_STAGE to EventType.CONCERT.name
            )

        /** Whole words for the formats a substring would over-match: `show` is not `Showcase`. Improv is comedy, as at BKA. */
        val HOUSE_KEYWORDS = mapOf("improtheater" to EventType.COMEDY.name, "show" to EventType.SHOW.name)
    }
}
