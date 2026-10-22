package de.norm.events.scraper.torhausberlin

import de.norm.events.common.BERLIN
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.nextFlightPayload
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** A card's React key: the Teamup event id and the start as epoch milliseconds. */
private val CARD_KEY = Regex(""""TU(\d+):(\d+)"""")

private val CLOCK = Regex("""\d{1,2}:\d{2}""")

/** The end of a range that crosses midnight names its own day: `23.10.26, 18:00 – 24.10.26, 00:00`. */
private val END_DATE = Regex("""–\s*(\d{2}\.\d{2}\.\d{2}),""")

private val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yy")

/** The cooking collective's community dinner: a food row, not programme. */
private val FOOD_ROW = Regex("""\bK[üu]fa\b""", RegexOption.IGNORE_CASE)

private const val RADIO_BADGE = "Radio"

/**
 * Pure parser for the event cards on Torhaus Berlin's start page. The cards print the month and
 * the day but no year and link nowhere, so each card is paired, in order, with its React key in
 * the Next.js flight payload, which holds the Teamup event id and the exact start. The title of a
 * radio night repeats the show name before every DJ (`<show> w/ <show> w/ A, <show> w/ B`).
 */
class TorhausBerlinOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        // The page's intro is an `article` too and encloses the cards, so each card is the article nearest its clock line.
        val cards = document.select("p.font-mono").mapNotNull { it.closest("article") }
        val keys = CARD_KEY.findAll(nextFlightPayload(document)).map { it.groupValues[1] to it.groupValues[2].toLong() }.toList()
        if (cards.size != keys.size) {
            logger.warn { "Torhaus Berlin shows ${cards.size} event cards but its payload holds ${keys.size} keys, skipping the page" }
            return emptyList()
        }
        return cards.zip(keys).mapSkippingFailures(logger, "Torhaus Berlin event card") { (card, key) ->
            toScrapedEvent(card, key.first, Instant.ofEpochMilli(key.second).atZone(BERLIN), baseUrl)
        }
    }

    private fun toScrapedEvent(
        card: Element,
        teamupId: String,
        start: ZonedDateTime,
        baseUrl: String
    ): ScrapedEvent? {
        val rawTitle = card.textAt("p.line-clamp-4").blankToNull() ?: return skip(teamupId, "no title")
        if (FOOD_ROW.containsMatchIn(rawTitle)) return null
        val radio = card.select("span").any { it.text() == RADIO_BADGE }
        val (title, names) = splitLineup(rawTitle)
        val times = card.textAt("p.font-mono").orEmpty()
        val clocks = CLOCK.findAll(times).map { it.value }.toList()
        val startTime = clocks.firstOrNull()?.let { parseTime(it) }
        val endTime = clocks.drop(1).lastOrNull()?.let { parseTime(it) }
        val date = start.toLocalDate()
        return ScrapedEvent(
            title = title,
            eventType = if (radio) EventType.PARTY.name else inferUnmarkedTitleType(title),
            eventDate = date,
            startTime = startTime,
            endDate = endTime?.let { endDateOf(times) ?: endOn(date, startTime, it) },
            endTime = endTime,
            sourceUrl = baseUrl,
            sourceId = "${EventSource.TORHAUS_BERLIN.sourceIdPrefix}$teamupId-$date",
            artists = names.map { ScrapedArtist(name = it, role = if (radio) "DJ" else "HEADLINER") }
        )
    }

    /** The show name, and the acts after its first ` w/ ` with every repeat of `<show> w/ ` removed. */
    private fun splitLineup(rawTitle: String): Pair<String, List<String>> {
        val show = rawTitle.substringBefore(" w/ ").trim()
        if (show == rawTitle.trim()) return show to emptyList()
        val names =
            rawTitle
                .substringAfter(" w/ ")
                .replace("$show w/ ", "")
                .split(",")
                .mapNotNull { it.blankToNull() }
                .filterNot { isNonArtistName(it) }
                .distinct()
        return show to names
    }

    private fun endDateOf(times: String): LocalDate? =
        END_DATE.find(times)?.groupValues?.get(1)?.let {
            try {
                LocalDate.parse(it, SHORT_DATE)
            } catch (_: DateTimeParseException) {
                null
            }
        }

    private fun skip(
        teamupId: String,
        reason: String
    ): ScrapedEvent? {
        logger.warn { "Skipping Torhaus Berlin event $teamupId: $reason" }
        return null
    }
}
