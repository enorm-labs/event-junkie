package de.norm.events.scraper.parkaue

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.childrensShowPhrase
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser
import java.time.LocalDateTime

/**
 * Pure parser for a Theater an der Parkaue month page. One `div.performance` per date, with schema.org microdata: the
 * start in `meta[itemprop=startDate]`, an end in `19:00 - 20:30`, the stage in `data-location`, the age as `(6+)`, and
 * the format in the headline's subtitle (`Familienkonzert`, `Literatur, Talk, Lesebühne …`, `Konferenz`).
 *
 * An age under 10 marks the date `for-children`; a 10+ play stays in the default lists (EVENT_SCOPE.md §3.5). A family
 * concert names no age, so `childrensShowPhrase` reads its subtitle. A conference is a participation format (§3.2) and
 * is dropped, and so is a classroom piece, which plays at a school and has no start time here. The key is the number
 * in the date's own link, one per performance, so it survives a changed time. A bookable date links the ticket shop,
 * and a sold-out date links nothing.
 */
class ParkaueMonthPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val performances = document.select("div.performance[data-day-token]")
        logger.info { "Found ${performances.size} Theater an der Parkaue performance(s) on $baseUrl" }
        return performances.mapSkippingFailures(logger, "Theater an der Parkaue performance") { parse(it) }
    }

    private fun parse(performance: Element): ScrapedEvent? {
        val url = performance.selectFirst("h3.performance__headline a[href]")?.absUrl("href")?.blankToNull() ?: return null
        val id = PERFORMANCE_ID.find(url)?.groupValues?.get(1) ?: return null
        val title = cleanText(performance.attr("data-title")) ?: return null
        val start = startOf(performance) ?: return null // a classroom piece
        val subtitle = performance.textAt(".headline__subtitle")?.let(::cleanText)
        if (subtitle != null && CONFERENCE.containsMatchIn(subtitle)) return null
        val eventType = typeOf(subtitle)
        val classes = performance.classNames()
        val endTime = END_TIME.find(performance.textAt(".performance__time").orEmpty())?.let { parseTime(it.groupValues[1]) }

        return ScrapedEvent(
            title = title,
            subtitle = subtitle ?: performance.selectFirst(".performance__author")?.let { authorLine(it) },
            eventType = eventType.name,
            eventDate = start.toLocalDate(),
            startTime = start.toLocalTime(),
            endDate = endTime?.let { start.toLocalDate() },
            endTime = endTime,
            room = cleanText(performance.attr("data-location"))?.takeUnless { it == HOUSE_NAME },
            imageUrl = performance.selectFirst(".performance__image img[src]")?.absUrl("src")?.blankToNull(),
            sourceUrl = url,
            sourceId = "${EventSource.PARKAUE.sourceIdPrefix}$id",
            ticketUrl = performance.hrefAt(TICKET_LINK),
            soldOut = "performance--is-soldout" in classes,
            status = if ("performance--is-canceled" in classes) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name,
            artists = if (eventType == EventType.CONCERT) listOf(ScrapedArtist(name = title, role = ArtistRole.HEADLINER)) else emptyList()
        ).forChildrenIf(childrensAge(performance) ?: childrensShowPhrase(title, subtitle))
    }

    private fun startOf(performance: Element): LocalDateTime? =
        performance
            .selectFirst("meta[itemprop=startDate]")
            ?.attr("content")
            ?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

    private fun typeOf(subtitle: String?): EventType =
        when {
            subtitle == null -> EventType.SHOW
            CONCERT.containsMatchIn(subtitle) -> EventType.CONCERT
            READING.containsMatchIn(subtitle) -> EventType.READING
            else -> EventType.SHOW
        }

    /** The age line, `(6+)`, when it is under ten; a 10+ play is not marked (EVENT_SCOPE.md §3.5). */
    private fun childrensAge(performance: Element): String? =
        performance
            .textAt(".performance__agerecommendation")
            ?.let { AGE.find(it) }
            ?.takeIf { it.groupValues[1].toInt() < CHILDRENS_AGE_LIMIT }
            ?.value

    /** `von Philipp Löhle<br>nach Carlo Collodi` as one line. */
    private fun authorLine(author: Element): String? =
        author
            .html()
            .split(LINE_BREAK)
            .mapNotNull { cleanText(Parser.unescapeEntities(it.replace(TAG, ""), false)) }
            .joinToString(", ")
            .blankToNull()

    private companion object {
        const val HOUSE_NAME = "Theater an der Parkaue"
        const val CHILDRENS_AGE_LIMIT = 10

        // The sold-out date's waiting-list mail link sits in the same column.
        const val TICKET_LINK = ".performance__col--actions a[href^=https://tickets.parkaue.de/]"

        val PERFORMANCE_ID = Regex("""/(\d+)/?$""")
        val AGE = Regex("""\(?(\d{1,2})\+\)?""")
        val END_TIME = Regex("""-\s*(\d{1,2}:\d{2})""")
        val CONCERT = Regex("""konzert""", RegexOption.IGNORE_CASE)
        val READING = Regex("""lesung|lesebühne|\btalk\b|gespräch""", RegexOption.IGNORE_CASE)
        val CONFERENCE = Regex("""\b(?:konferenz|fachtag)\b""", RegexOption.IGNORE_CASE)
        val LINE_BREAK = Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE)
        val TAG = Regex("""<[^>]+>""")
    }
}

/** Text without the soft hyphens the site sets inside long words (`Tisch­tennis­platten­politik`). */
private fun cleanText(text: String?): String? = text?.replace(SOFT_HYPHEN, "")?.replace(WHITESPACE, " ")?.blankToNull()

private const val SOFT_HYPHEN = "­"
