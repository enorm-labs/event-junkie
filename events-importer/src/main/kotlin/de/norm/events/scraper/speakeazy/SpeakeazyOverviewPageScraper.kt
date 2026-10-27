package de.norm.events.scraper.speakeazy

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOn
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure parser for Speakeazy's Squarespace events page, which lists the upcoming nights and then the past ones.
 *
 * - **Each upcoming `article` holds the title, the ISO date, the start and end time and the image.** The blurb and the
 *   door price are on the event page only ([SpeakeazyEventPageScraper]).
 * - **A private party is listed too**, members only, and is dropped.
 * - **A programme name follows the act after a colon**, `Stock & Pankow : Annäherung an Bob Dylan`; only the act is billed.
 * - **The house's `Bassball` sessions bill a studio band and changing guests**, so they name no act.
 */
class SpeakeazyOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val articles = document.select("article.eventlist-event--upcoming")
        logger.info { "Found ${articles.size} upcoming event(s) on the Speakeazy listing" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip one malformed event without aborting the import
        return articles.mapNotNull { article ->
            try {
                parseArticle(article, sourceUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse a Speakeazy event, skipping" }
                null
            }
        }
    }

    private fun parseArticle(
        article: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val link = article.selectFirst("a.eventlist-title-link[href]") ?: error("No event link found")
        val cleanedTitle = cleanEventTitle(link.text())
        val title = cleanedTitle.text.replace(SPACED_COLON, ": ")
        if (title.isBlank()) {
            logger.warn { "Speakeazy event on $sourceUrl has no title, skipping" }
            return null
        }
        if (PRIVATE.containsMatchIn(title)) {
            logger.debug { "Skipping '$title': a private party" }
            return null
        }
        val eventDate = article.attrAt("time.event-date", "datetime")?.let(::parseIsoDate)
        if (eventDate == null) {
            logger.warn { "No parseable date for Speakeazy event '$title', skipping" }
            return null
        }
        val url = link.absUrl("href").ifEmpty { error("No absolute event URL for '$title' on $sourceUrl") }
        val eventType = inferConcertVenueType(title)
        val startTime = parseTime(article.textAt("time.event-time-localized-start"))
        val endTime = parseTime(article.textAt("time.event-time-localized-end"))

        return ScrapedEvent(
            title = title,
            eventType = eventType,
            eventDate = eventDate,
            startTime = startTime,
            endDate =
                endTime?.let {
                    article.attrAt("time.event-time-localized-end", "datetime")?.let(::parseIsoDate) ?: endOn(eventDate, startTime, it)
                },
            endTime = endTime,
            imageUrl = article.attrAt("img[data-src]", "data-src")?.takeIf { it.startsWith("http") },
            sourceUrl = url,
            sourceId = "${EventSource.SPEAKEAZY.sourceIdPrefix}${extractEventSlug(url)}",
            soldOut = cleanedTitle.soldOut,
            artists = if (HOUSE_SESSION.containsMatchIn(title)) emptyList() else buildArtistsForEventType(title.substringBefore(": "), null, eventType)
        )
    }

    private companion object {
        val PRIVATE = Regex("""\bprivate\s+party\b|\bprivatparty\b""", RegexOption.IGNORE_CASE)

        val SPACED_COLON = Regex("""\s+:\s+""")

        val HOUSE_SESSION = Regex("""\bbassball\b.*\bsession\b""", RegexOption.IGNORE_CASE)
    }
}
