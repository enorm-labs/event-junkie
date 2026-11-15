package de.norm.events.scraper.fez

import de.norm.events.common.BERLIN
import de.norm.events.event.ArtistRole
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.FOUR_DIGIT_CLOCK
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.forChildren
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanDayMonth
import de.norm.events.scraper.parseGermanWeekday
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.format.DateTimeFormatter

/**
 * Pure parser for the FEZ stage schedule. One `div.termin` per performance: weekday, day and month name, the title
 * linked to the ticket shop, a `.time-line` with the time and the room, a `.cat-line` category (absent on some), and a
 * `.subTitle` of `<performer> | empfohlen von 2-6 J.`. FEZ plays only for children, so every row is `for-children` with
 * the age line as its phrase (EVENT_SCOPE.md §3.5).
 *
 * The performer is the act on a concert. On a show it is the company, so it stays the subtitle. The key is the ticket
 * shop's `EventID` (the production) with the date and time; an `AppointmentID` in some links changes per date.
 */
class FezSpielplanPageScraper(
    /** Clock for the year inference, in the venue's time zone; override in tests. */
    private val clock: Clock = Clock.system(BERLIN)
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.termin")
        logger.info { "Found ${items.size} FEZ performance(s) on $baseUrl" }
        return items.mapSkippingFailures(logger, "FEZ performance") { parseItem(it, baseUrl) }
    }

    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val title = item.textAt("a.titleLink h2")?.replace(WHITESPACE, " ") ?: return null
        val day = item.textAt(".date-day")?.removeSuffix(".") ?: return null
        val monthDay = parseGermanDayMonth(day, item.textAt(".date-month") ?: return null) ?: return null
        val eventDate = inferYearForWeekday(monthDay, parseGermanWeekday(item.textAt(".date-weekday")), clock)

        val timeLine = item.selectFirst(".time-line")
        val timeText =
            timeLine
                ?.ownText()
                ?.replace(WHITESPACE, " ")
                ?.trim()
                .orEmpty()
        val time = CLOCK_TIME.find(timeText)
        val startTime = time?.let { parseTime(it.value) }
        val room = time?.let { timeText.removeRange(it.range) }?.trim()?.blankToNull()

        val ticketUrl = item.selectFirst("a.titleLink")?.absUrl("href")?.blankToNull()
        val production = ticketUrl?.let { EVENT_ID.find(it)?.groupValues?.get(1) } ?: return null

        val parts =
            item
                .textAt(".subTitle")
                ?.split('|')
                ?.mapNotNull { it.blankToNull() }
                .orEmpty()
        val age = parts.firstOrNull { AGE.containsMatchIn(it) }
        val performer = parts.firstOrNull { it != age }
        val category = item.textAt(".cat-line")
        val concert = category != null && CONCERT.containsMatchIn(category)

        return ScrapedEvent(
            title = title,
            subtitle = performer,
            eventType = if (concert) EventType.CONCERT.name else EventType.SHOW.name,
            eventDate = eventDate,
            startTime = startTime,
            room = room,
            imageUrl = item.selectFirst(".teaserimg img[src]")?.absUrl("src")?.blankToNull(),
            sourceUrl = baseUrl,
            sourceId =
                "${EventSource.FEZ.sourceIdPrefix}$production-${eventDate.format(DateTimeFormatter.BASIC_ISO_DATE)}" +
                    "-${startTime?.format(FOUR_DIGIT_CLOCK) ?: "0000"}",
            ticketUrl = ticketUrl,
            soldOut = timeLine?.selectFirst(".ausverkauft") != null,
            artists = if (concert) acts(performer) else emptyList()
        ).forChildren(age ?: CHILDRENS_STAGE)
    }

    /** `Honigkuchenpferde` or `Robert Metcalf & Band` is one act; only a comma list (`A, B & C`) names several. */
    private fun acts(performer: String?): List<ScrapedArtist> {
        val names =
            when {
                performer == null -> emptyList()
                ',' in performer -> performer.split(ACT_SEPARATOR).mapNotNull { it.blankToNull() }
                else -> listOf(performer)
            }
        return names
            .filterNot { isNonArtistName(it) || it.equals(OWN_PRODUCTION, ignoreCase = true) }
            .map { ScrapedArtist(name = it, role = ArtistRole.HEADLINER) }
    }

    private companion object {
        const val CHILDRENS_STAGE = "Astrid-Lindgren-Bühne"
        const val OWN_PRODUCTION = "Eigenproduktion"

        val CLOCK_TIME = Regex("""\b\d{1,2}:\d{2}\b""")
        val EVENT_ID = Regex("""[?&]EventID=(\d+)""", RegexOption.IGNORE_CASE)
        val AGE = Regex("""empfohlen|\bJ\.$|Mindestalter|\bab\s+\d""", RegexOption.IGNORE_CASE)
        val CONCERT = Regex("""konzert|musik(?!theater)""", RegexOption.IGNORE_CASE)
        val ACT_SEPARATOR = Regex(""",\s*|\s+(?:&|und)\s+""")
    }
}
