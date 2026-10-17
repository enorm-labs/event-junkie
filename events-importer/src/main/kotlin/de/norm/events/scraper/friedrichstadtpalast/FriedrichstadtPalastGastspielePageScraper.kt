package de.norm.events.scraper.friedrichstadtpalast

import de.norm.events.event.EventType
import de.norm.events.genretag.genreFamily
import de.norm.events.genretag.isGenreLabel
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textLines
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for the Friedrichstadt-Palast's `/gastspiele/` page. Each guest show is one
 * `section.content-with-photo`: a bold title paragraph behind an `<a name="…">` anchor, prose, and
 * one ticket button per performance, `Tickets 28.12.2026, 16:00 Uhr`. Only the button is read for
 * the date and time. A button whose text does not hold both is skipped with a warning, and the
 * prose is never searched for a date.
 */
class FriedrichstadtPalastGastspielePageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val shows = document.select("section.content-with-photo")
        logger.info { "Found ${shows.size} guest show(s) on the Friedrichstadt-Palast page" }
        return shows.mapSkippingFailures(logger, "a Friedrichstadt-Palast guest show") { parseShow(it, baseUrl) }.flatten()
    }

    private fun parseShow(
        section: Element,
        baseUrl: String
    ): List<ScrapedEvent>? {
        val text = section.selectFirst(".text") ?: return null
        val anchor = text.selectFirst("a[name]")?.attr("name").blankToNull()
        val heading = text.selectFirst("p:has(a[name]) strong") ?: text.selectFirst("p strong")
        val (title, subtitle) = splitHeading(heading?.text().orEmpty())
        if (title == null || anchor == null) {
            logger.warn { "Friedrichstadt-Palast guest show '${heading?.text()}' has no title or no anchor, skipping" }
            return null
        }
        val performances = text.select("a.button").mapNotNull { performance(it, title) }
        if (performances.isEmpty()) logger.warn { "Friedrichstadt-Palast guest show '$title' has no readable performance, skipping" }
        val paragraphs = text.select("p").filterNot { it.selectFirst("a[name]") != null }
        val eventType = eventType(title, subtitle)
        val repeatedDates = performances.groupBy { it.date }.filterValues { it.size > 1 }.keys
        return performances.map { performance ->
            val timeSuffix = if (performance.date in repeatedDates) "-" + performance.time.toString().replace(":", "") else ""
            ScrapedEvent(
                title = title,
                subtitle = subtitle,
                description = description(paragraphs),
                eventType = eventType,
                eventDate = performance.date,
                startTime = performance.time,
                imageUrl = section.selectFirst(".image img[src]")?.absUrl("src").blankToNull(),
                sourceUrl = resolveUrl(baseUrl, "#$anchor"),
                sourceId = "${EventSource.FRIEDRICHSTADTPALAST.sourceIdPrefix}$anchor-${performance.date}$timeSuffix",
                ticketUrl = performance.ticketUrl,
                soldOut = SOLD_OUT.containsMatchIn(performance.label),
                status = parseEventStatus(performance.label),
                promoters = listOfNotNull(promoter(paragraphs)),
                artists = artists(title, subtitle, eventType)
            )
        }
    }

    /** The prose, up to each paragraph's `Veranstalter:` line, which opens the promoter's address and contact block. */
    private fun description(paragraphs: List<Element>): String? =
        paragraphs
            .flatMap { paragraph -> paragraph.textLines().takeWhile { !it.startsWith(PROMOTER_LABEL) } }
            .joinToString("\n")
            .blankToNull()

    private fun performance(
        button: Element,
        title: String
    ): Performance? {
        val label = button.text()
        val match = TICKET_DATE.find(label)
        val date = match?.let { parseDate(it.destructured.component1(), it.destructured.component2(), it.destructured.component3()) }
        val time = match?.let { parseClock(it.groupValues[HOUR_GROUP], it.groupValues[MINUTE_GROUP]) }
        if (date == null || time == null) {
            logger.warn { "Friedrichstadt-Palast ticket button '$label' for '$title' holds no readable date and time, skipping" }
            return null
        }
        return Performance(date, time, button.absUrl("href").blankToNull(), label)
    }

    /** A format the house names in the heading, else a concert unless a shared title cue says otherwise. */
    private fun eventType(
        title: String,
        subtitle: String?
    ): String {
        val heading = listOfNotNull(title, subtitle).joinToString(" ")
        return VENUE_FORMATS.entries.firstOrNull { (cue, _) -> cue.containsMatchIn(heading) }?.value
            ?: inferConcertVenueType(title, subtitle)
    }

    /**
     * The act from the heading. A heading that is only a genre (`CHANSON`, a tribute evening) names no act,
     * and a conductor credit (`… DIRECTED BY ULI PLETTENDORFF`) is not part of the orchestra's name.
     */
    private fun artists(
        title: String,
        subtitle: String?,
        eventType: String
    ): List<ScrapedArtist> {
        if (!title.contains(' ') && (isGenreLabel(title) || genreFamily(title.lowercase()) != null)) return emptyList()
        return buildArtistsForEventType(title.replace(CONDUCTOR_CREDIT, ""), subtitle, eventType)
    }

    /** The organiser's name from the `Veranstalter: …` line of the credits. */
    private fun promoter(paragraphs: List<Element>): String? =
        paragraphs
            .asSequence()
            .flatMap { it.textLines() }
            .firstOrNull { it.startsWith(PROMOTER_LABEL) }
            ?.removePrefix(PROMOTER_LABEL)
            .blankToNull()

    private data class Performance(
        val date: LocalDate,
        val time: LocalTime,
        val ticketUrl: String?,
        val label: String
    )

    private companion object {
        /** `Tickets 28.12.2026, 16:00 Uhr`. */
        val TICKET_DATE = Regex("""(\d{1,2})\.(\d{1,2})\.(\d{4}),?\s+(\d{1,2})[:.](\d{2})""")
        const val HOUR_GROUP = 4
        const val MINUTE_GROUP = 5
        const val PROMOTER_LABEL = "Veranstalter:"
        val SOLD_OUT = Regex("""ausverkauft""", RegexOption.IGNORE_CASE)

        /** ` – ` between a name and its programme: `FRANK SCHÖBEL – 65jähriges Bühnenjubiläum`. */
        val HEADING_SEPARATOR = Regex("""\s+[–—-]\s+""")

        /** A tour year closing a heading: `Suzi Quatro & Band – 2027`, `Ulrich Tukur & Die Rhythmus Boys 2027`. */
        val TRAILING_YEAR = Regex("""\s*[–—-]?\s*(?:19|20)\d{2}$""")

        /** The house names a reading with music and a film with live orchestra in the heading. */
        val VENUE_FORMATS =
            mapOf(
                Regex("""(?<!\p{L})liest(?!\p{L})""", RegexOption.IGNORE_CASE) to EventType.READING.name,
                Regex("""(?<!\p{L})(?:original)?film(?!\p{L})""", RegexOption.IGNORE_CASE) to EventType.SCREENING.name
            )

        val CONDUCTOR_CREDIT = Regex("""\s+(?:directed by|unter der leitung von)\s+.*$""", RegexOption.IGNORE_CASE)

        fun splitHeading(heading: String): Pair<String?, String?> {
            val parts = cleanEventTitle(heading).split(HEADING_SEPARATOR, limit = 2)
            val title = parts[0].replace(TRAILING_YEAR, "").blankToNull() ?: parts[0].blankToNull()
            val subtitle = parts.getOrNull(1)?.replace(TRAILING_YEAR, "").blankToNull()
            return title to subtitle
        }

        fun parseDate(
            day: String,
            month: String,
            year: String
        ): LocalDate? =
            try {
                LocalDate.of(year.toInt(), month.toInt(), day.toInt())
            } catch (_: DateTimeException) {
                null
            }

        fun parseClock(
            hour: String,
            minute: String
        ): LocalTime? =
            try {
                LocalTime.of(hour.toInt(), minute.toInt())
            } catch (_: DateTimeException) {
                null
            }
    }
}
