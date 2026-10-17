package de.norm.events.scraper.hafenbar

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.knownGenresInStyleTail
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.splitSegmentOnConjunctions
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for one Hafenbar weekly party page, an Avada layout that announces only the next
 * night. Two columns carry it: `.<weekday>_ankuendigung2` holds the paragraph with the DJs in
 * `<strong>`, and `.<weekday>_ankuendigung3` the heading `Freitag, 16.10.2026` and a style line.
 * The date comes from the heading, else from the paragraph, which may print a two-digit year.
 * A page without the announcement yields nothing.
 */
class HafenbarPartyPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val announcement = document.selectFirst("[class*=_ankuendigung2]")
        val dateBox = document.selectFirst("[class*=_ankuendigung3]")
        if (announcement == null || dateBox == null) {
            logger.warn { "Hafenbar page $sourceUrl carries no next-night announcement" }
            return emptyList()
        }
        return listOfNotNull(parseNight(document, announcement, dateBox, sourceUrl))
    }

    private fun parseNight(
        document: Document,
        announcement: Element,
        dateBox: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val texts = listOf(dateBox.textAt("h2"), announcement.text())
        val date = texts.firstNotNullOfOrNull(::parseDate)
        val title = seriesTitle(document)
        if (date == null || title == null) {
            logger.warn { "Hafenbar page $sourceUrl announces no readable date or title, skipping" }
            return null
        }
        val subtitle = styleLine(dateBox)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            description = announcement.select("p").joinToString("\n\n") { it.text() }.blankToNull(),
            eventType = EventType.PARTY.name,
            eventDate = date,
            startTime = texts.firstNotNullOfOrNull(::parseStart),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.HAFENBAR.sourceIdPrefix}${URI(sourceUrl).path.trim('/')}-$date",
            genre = if (title.contains(SCHLAGER, ignoreCase = true)) SCHLAGER else knownGenresInStyleTail(subtitle),
            artists = djs(announcement).map { ScrapedArtist(name = it, role = "DJ") }
        )
    }

    /** The page's first heading, `Schlagerparty am Freitag`, without its weekday tail. */
    private fun seriesTitle(document: Document): String? =
        document
            .textAt(".post-content h1")
            ?.replace(WEEKDAY_TAIL, "")
            ?.replace('–', '-')
            .blankToNull()

    /** The line under the date heading without its opening time: `deutsche Hits aus 5 Jahrzehnten`. */
    private fun styleLine(dateBox: Element): String? =
        dateBox
            .textAt(".wpb_text_column")
            ?.replace(LEADING_START, "")
            ?.replace(SPACE_BEFORE_COMMA, ",")
            .blankToNull()

    /**
     * The acts in the paragraph's bold runs that name a DJ. A billing like `DJ Legende DJ PUNYESH`
     * keeps the last `DJ` name, since the words before it praise the act.
     */
    private fun djs(announcement: Element): List<String> =
        announcement
            .select("strong")
            .map { it.text() }
            .filter { DJ.containsMatchIn(it) }
            .flatMap(::splitSegmentOnConjunctions)
            .mapNotNull { act ->
                act
                    .substring(
                        DJ
                            .findAll(act)
                            .last()
                            .range.first
                    ).trim(' ', '!', '.', ',')
                    .blankToNull()
            }.filterNot(::isNonArtistName)
            .distinct()

    private fun parseDate(text: String?): LocalDate? {
        val match = text?.let { DATE.find(it) } ?: return null
        return if (match.groupValues[YEAR_GROUP].length == 2) parseGermanShortDate(match.value) else parseGermanDate(match.value)
    }

    private fun parseStart(text: String?): LocalTime? {
        val match = text?.let { START.find(it) } ?: return null
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2].ifEmpty { "0" }.toInt()
        return if (hour < HOURS_PER_DAY && minute < MINUTES_PER_HOUR) LocalTime.of(hour, minute) else null
    }

    private fun Element.textAt(selector: String): String? = selectFirst(selector)?.text().blankToNull()

    private companion object {
        const val SCHLAGER = "Schlager"
        const val YEAR_GROUP = 3
        const val HOURS_PER_DAY = 24
        const val MINUTES_PER_HOUR = 60
        val DATE = Regex("""\b(\d{1,2})\.(\d{1,2})\.(\d{4}|\d{2})\b""")
        val START = Regex("""\bab\s+(\d{1,2})(?:[:.](\d{2}))?\s*Uhr""", RegexOption.IGNORE_CASE)
        val LEADING_START = Regex("""^ab\s+\d{1,2}(?:[:.]\d{2})?\s*Uhr[\s,]*""", RegexOption.IGNORE_CASE)
        val WEEKDAY_TAIL = Regex("""\s+am\s+\p{L}+$""")
        val SPACE_BEFORE_COMMA = Regex("""\s+,""")
        val DJ = Regex("""\bDJs?\b""")
    }
}
