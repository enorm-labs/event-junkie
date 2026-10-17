package de.norm.events.scraper.volksbuehne

import de.norm.events.event.EventType
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.billsChildrensShow
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.hasFreeEntryPhrase
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClockPrefix
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

/**
 * Pure parser for the Volksbühne Spielplan, the home page's `section#performances`, keeping the
 * rows on [house]'s stages. Each `li.activity` carries a `time[datetime]` with its offset, the
 * stage, the title and its link, and optional lines: a format label (`Konzert`, `Lesung und
 * Gespräch`), the writer or director, a `mit …` line and a surtitle note (`mit engl. ÜT`).
 * Workshops and theatre tours drop (EVENT_SCOPE.md §3.2), and so do children's shows (§3.5).
 */
class VolksbuehneSpielplanScraper(
    private val house: VolksbuehneHouse
) {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        url: String
    ): List<ScrapedEvent> = document.select("section#performances li.activity").mapSkippingFailures(logger, "Volksbühne Spielplan row") { parseRow(it, url) }

    private fun parseRow(
        row: Element,
        url: String
    ): ScrapedEvent? {
        val stage = row.textAt(".activity__stage") ?: return null
        if (stage !in house.stages) {
            if (house == VolksbuehneHouse.ROSA_LUXEMBURG_PLATZ && !VolksbuehneHouse.isKnownStage(stage)) {
                logger.info { "Volksbühne row on stage '$stage' belongs to neither house, skipping it" }
            }
            return null
        }
        val title = row.textAt("h3.activity__title")
        val label = row.textAt(".activity__production-type-label")
        val addition = row.textAt(".activity__title_addition")
        if (title == null || isOutOfScope(title, label) || billsChildrensShow(title, addition)) return null
        val dateTime = row.attrAt(".activity__time time[datetime]", "datetime")
        val date = dateTime?.let { parseIsoDate(it.take(ISO_DATE_LENGTH)) }
        val page = row.selectFirst("h3.activity__title a[href]")?.absUrl("href").blankToNull()
        if (date == null || page == null) {
            logger.warn { "Volksbühne row without a date or a page on $url ($stage, $title, $dateTime), skipping it" }
            return null
        }
        val type = eventType(label)
        return ScrapedEvent(
            title = title,
            subtitle = listOfNotNull(row.textAt(".activity__writer"), addition, row.textAt(".activity__supertitle")).joinToString(" · ").blankToNull(),
            eventType = (type ?: EventType.SHOW).name,
            typeIsFallback = type == null,
            eventDate = date,
            startTime = parseClockPrefix(dateTime.substringAfter('T')),
            room = house.stages[stage],
            sourceUrl = page,
            sourceId = "${house.eventSource.sourceIdPrefix}${pageSlug(page)}",
            ticketUrl = row.attrAt("a.ticket-button[href]", "href"),
            soldOut = row.selectFirst(".ticket-status--sold-out") != null,
            free = hasFreeEntryPhrase(row.textAt(".ticket-note")),
            artists = artists(type, addition)
        )
    }

    /** The performers of a concert or a party, from its `mit A, B und C` line; a staged work's line names a cast or a host alike. */
    private fun artists(
        type: EventType?,
        addition: String?
    ): List<ScrapedArtist> {
        val role =
            when (type) {
                EventType.CONCERT -> "HEADLINER"
                EventType.PARTY -> "DJ"
                else -> return emptyList()
            }
        val names = WITH_LINE.find(addition.orEmpty())?.groupValues?.get(1) ?: return emptyList()
        return names
            .replace(LIVE_TAIL, "")
            .split(LIST_SEPARATOR)
            .mapNotNull { it.blankToNull() }
            .filterNot { '*' in it || isNonArtistName(it) }
            .map { ScrapedArtist(name = it, role = role) }
    }

    companion object {
        private const val ISO_DATE_LENGTH = 10

        /** `mit A, B und C`, or `mit DJ-Sets und Performances von A, B und C`: the names after the last `von`. */
        private val WITH_LINE = Regex("""^mit\s+(?:.*\bvon\s+)?(.+)$""", RegexOption.IGNORE_CASE)
        private val LIVE_TAIL = Regex("""\s+[-–]\s+live$""", RegexOption.IGNORE_CASE)
        private val LIST_SEPARATOR = Regex(""",\s*|\s+(?:und|&)\s+""")

        /** The format label's first keyword types the row: `Konzert und Künstler*innengespräch` is a concert, `Screening und Gespräch` a screening. */
        private val LABEL_TYPES: List<Pair<Regex, EventType>> =
            listOf(
                Regex("""konzert|concert""") to EventType.CONCERT,
                Regex("""party""") to EventType.PARTY,
                Regex("""screening|film|cinema""") to EventType.SCREENING,
                Regex("""lesung|gespräch|diskussion|dialog|buchpremiere|podcast|lecture|conversation""") to EventType.READING
            )

        /** The type a format label names, or null for no label or one that names none (a staged work's page then decides). */
        fun eventType(label: String?): EventType? {
            val text = label?.lowercase() ?: return null
            return LABEL_TYPES
                .mapNotNull { (pattern, type) -> pattern.find(text)?.let { it.range.first to type } }
                .minByOrNull { it.first }
                ?.second
        }

        /** A workshop or a guided tour of the theatre is taken part in, not watched (EVENT_SCOPE.md §3.2). */
        fun isOutOfScope(
            title: String,
            label: String?
        ): Boolean = label?.contains("workshop", ignoreCase = true) == true || title.startsWith("Theaterführung", ignoreCase = true)

        /**
         * The page path after its section, as the row's identity: `/produktionen/mononoke/20261024-1730/` is
         * `mononoke-20261024-1730`, one performance; a series page (`/reihen-und-projekte/<series>/<evening>/`) is one evening.
         */
        fun pageSlug(page: String): String =
            URI(page)
                .path
                .split('/')
                .filter { it.isNotBlank() }
                .drop(1)
                .joinToString("-")
    }
}
