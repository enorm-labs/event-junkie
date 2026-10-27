package de.norm.events.scraper.weekend

import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.genretag.isGenreLabel
import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.endOn
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.htmlParagraphText
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClock
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.splitBackToBack
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalTime

/**
 * Parser for Weekend's Squarespace `/upcoming` list, which holds the upcoming nights and then the past ones.
 *
 * - **Each upcoming `article` holds the whole record**: title, start and end with their dates, image and the blurb.
 * - **The times are 12-hour clocks** (`10:00 PM`) with a narrow no-break space before the meridiem.
 * - **The ticket link is an embedded Xceed iframe** in the blurb.
 * - **The DJs are a paragraph of their own**: a `🎶 LINE UP` or `🎶 CLUB – …` heading, or a `DJs:` line, names split at ` · `.
 *   A `Line-Up` that lists only styles (`Salsa · Bachata`) bills nobody.
 */
class WeekendUpcomingPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val articles = document.select("article.eventlist-event--upcoming")
        logger.info { "Found ${articles.size} upcoming event(s) on the Weekend listing" }
        return articles.mapSkippingFailures(logger, "Weekend event item") { parseArticle(it, sourceUrl) }
    }

    private fun parseArticle(
        article: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val link = article.selectFirst("a.eventlist-title-link[href]") ?: error("No event link found")
        val title = cleanEventTitle(link.text())
        if (title.isBlank()) {
            logger.warn { "Weekend event on $sourceUrl has no title, skipping" }
            return null
        }
        val eventDate = article.attrAt("time.event-date", "datetime")?.let(::parseIsoDate)
        if (eventDate == null) {
            logger.warn { "No parseable date for Weekend event '$title', skipping" }
            return null
        }
        val url = link.absUrl("href").ifEmpty { error("No absolute event URL for '$title' on $sourceUrl") }
        val times = article.select(".eventlist-meta-date time[class^=event-time-localized]")
        val startTime = times.firstOrNull()?.let(::clockOf)
        val end = times.takeIf { it.size > 1 }?.last()
        val endTime = end?.let(::clockOf)
        val text = article.selectFirst(".eventlist-description .sqs-html-content")

        return ScrapedEvent(
            title = title,
            description = htmlParagraphText(text?.html()),
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = startTime,
            endDate = endTime?.let { end.attr("datetime").let(::parseIsoDate) ?: endOn(eventDate, startTime, it) },
            endTime = endTime,
            imageUrl = article.attrAt("img[data-src]", "data-src")?.takeIf { it.startsWith("http") },
            sourceUrl = url,
            sourceId = "${EventSource.WEEKEND.sourceIdPrefix}${extractEventSlug(url, "/upcoming/")}",
            ticketUrl = article.attrAt(".eventlist-description iframe[src*=xceed.me]", "src"),
            artists = text?.let(::lineup).orEmpty()
        )
    }

    private fun clockOf(time: Element): LocalTime? = CLOCK.find(time.text())?.let { parseClock(it.groupValues[1], it.groupValues[2]) }

    /** The DJs of every line-up paragraph, in order, each once. */
    private fun lineup(text: Element): List<ScrapedArtist> =
        text
            .select("p")
            .map { it.textLines() }
            .flatMap(::lineupNames)
            .distinct()
            .map { ScrapedArtist(name = it, role = ArtistRole.DJ) }

    private fun lineupNames(lines: List<String>): List<String> {
        val first = lines.firstOrNull() ?: return emptyList()
        val inline = INLINE_DJS.find(first)
        val names =
            when {
                inline != null -> listOf(inline.groupValues[1])
                first.startsWith(LINEUP_EMOJI) || LINEUP_HEADING.matches(first) -> lines.drop(1).filterNot { it.endsWith(":") }
                else -> return emptyList()
            }.flatMap { it.split(NAME_SEPARATOR) }
                .flatMap(::actsOf)
                .filterNot(::isNonArtistName)
        return if (names.any(::isStyle)) emptyList() else names
    }

    /** A crew billed with its members, `The Michelins [Phonique b2b Misha Svirid]`, bills the members. */
    private fun actsOf(name: String): List<String> {
        val members = MEMBERS.find(name)?.groupValues?.get(1) ?: name
        return splitBackToBack(members.trim())
    }

    private fun isStyle(name: String): Boolean = normalizeGenre(name).any(::isGenreLabel)

    private companion object {
        /** `10:00 PM`: Squarespace puts U+202F before the meridiem, which `\s` does not match. */
        val CLOCK = Regex("""(\d{1,2}:\d{2})[\s  ]*([AP]M)?""", RegexOption.IGNORE_CASE)

        const val LINEUP_EMOJI = "🎶"

        val LINEUP_HEADING = Regex("""(?:DJ\s+)?LINE[\s-]?UP:?""", RegexOption.IGNORE_CASE)

        val INLINE_DJS = Regex("""^DJs?:\s*(.+)$""", RegexOption.IGNORE_CASE)

        val NAME_SEPARATOR = Regex("""\s+·\s+""")

        val MEMBERS = Regex("""\[([^\[\]]+)]\s*$""")
    }
}
