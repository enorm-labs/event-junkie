package de.norm.events.scraper.distel

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.GERMAN_MONTH_NAMES
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.classifyByGenreKeyword
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseGermanMonthAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter

/**
 * Pure HTML parser for one month of the DISTEL calendar (`/spielplan/kalender/?month=YYYYMM`).
 *
 * Each `div.event_list` is one performance: the day, a `Sa<br>Okt` weekday-and-month line, the
 * clock, the show's title and subtitle, and a link into the ticket shop. The shop's `event` id is
 * empty until presale opens, so a performance is keyed by show, date and clock. The year comes
 * from the month navigation's current entry. Availability is a class on `div.available`: `sold_out` when no seat is left.
 *
 * A touring date names its town and hall in the subtitle (`Aurich・Stadthalle Aurich`) on the show
 * pages; the calendar lists the home stage only, and a row with that marker is skipped.
 *
 * Ensemble Kabarett and guest nights share the calendar. A guest night names its act before a colon
 * (`Wenzel: Lieder & Texte`) or as the guest and host of a talk series; [distelEventType] and
 * [distelActs] read both.
 */
class DistelCalendarPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val heading = parseHeading(document)
        val items = document.select("div.event.event_list")
        logger.info { "Found ${items.size} DISTEL performance(s) on $baseUrl" }
        if (heading == null) return emptyList()

        return items.mapSkippingFailures(logger, "DISTEL performance") { item ->
            parseItem(item, heading)
        }
    }

    /** True when the month lists no performance, which ends the walk. */
    fun isEmptyMonth(document: Document): Boolean = document.select("div.event.event_list").isEmpty()

    private fun parseItem(
        item: Element,
        heading: Pair<Month, Int>
    ): ScrapedEvent? {
        val link = item.selectFirst(".title a[href]") ?: return null
        val title = link.text().blankToNull() ?: return null
        val subtitle = item.textAt(".subtitle")
        if (subtitle != null && TOURING_MARKER in subtitle) return null
        val eventDate = parseDate(item, heading) ?: return null
        val startTime = parseTime(item.textAt(".time"))
        val sourceUrl = link.absUrl("href")
        val slug = sourceUrl.trimEnd('/').substringAfterLast('/')
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = distelEventType(title, subtitle),
            eventDate = eventDate,
            startTime = startTime,
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.DISTEL.sourceIdPrefix}$slug-$eventDate-${startTime?.format(HHMM).orEmpty()}",
            ticketUrl = item.selectFirst(".ce_button a[href*=event=]")?.absUrl("href")?.takeUnless { it.endsWith("event=") },
            soldOut = item.selectFirst(".available.sold_out") != null,
            artists = distelActs(title)
        )
    }

    /** The day, and the month from the `Sa<br>Okt` line; a January row on a December page is next year. */
    private fun parseDate(
        item: Element,
        heading: Pair<Month, Int>
    ): LocalDate? {
        val day = item.textAt(".day")?.toIntOrNull() ?: return null
        val month = item.selectFirst(".weekday")?.let { parseGermanMonthAbbreviation(it.ownText().split(' ').last()) } ?: heading.first
        val year = if (month < heading.first) heading.second + 1 else heading.second
        return LocalDate.of(year, month, day)
    }

    /** The month navigation's current entry: `<span class="month">Oktober</span> <span class="year">2026</span>`. */
    private fun parseHeading(document: Document): Pair<Month, Int>? {
        val current = document.selectFirst(".month_navigation .current")
        val month = GERMAN_MONTHS[current?.textAt(".month")?.lowercase()]
        val year = current?.textAt(".year")?.toIntOrNull()
        return if (month != null && year != null) month to year else null
    }

    private companion object {
        const val TOURING_MARKER = "・"
        val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HHmm")

        val GERMAN_MONTHS: Map<String, Month> =
            GERMAN_MONTH_NAMES.mapIndexed { index, name -> name.lowercase() to Month.of(index + 1) }.toMap()
    }
}

/**
 * The format of a calendar row: a talk or a reading the title names, then a concert the subtitle
 * names, then a reading or a talk the subtitle names, then a `<name> liest:` reading. Anything
 * else is the house's Kabarett.
 */
internal fun distelEventType(
    title: String,
    subtitle: String?
): String =
    classifyByGenreKeyword(title)
        ?: when {
            subtitle?.contains("konzert", ignoreCase = true) == true -> EventType.CONCERT.name
            subtitle?.let(SUBTITLE_READING::containsMatchIn) == true -> EventType.READING.name
            READS.containsMatchIn(title.substringBefore(": ")) -> EventType.READING.name
            else -> EventType.COMEDY.name
        }

/**
 * The acts a calendar title bills: the guest and the host of a `<series> – <A> zu Gast bei <B>`
 * talk, else the names before the first colon. A performance marker before the colon
 * (`PREMIERE: Gut im Abgang`) bills nobody, and nor does a title with no colon: the ensemble's shows.
 */
internal fun distelActs(title: String): List<ScrapedArtist> {
    GUEST_AT_HOST.find(title)?.let { match ->
        return match.groupValues
            .drop(1)
            .map(::stripAcademicTitle)
            .filter { it.isNotBlank() }
            .map(::titleAct)
    }
    val names = title.substringBefore(": ", "").trim()
    if (names.isEmpty() || PERFORMANCE_MARKER.matches(names)) return emptyList()
    return names
        .split(',')
        .flatMap { splitSegmentOnConjunctions(it.trim()) }
        .map { stripAcademicTitle(it.replace(READS, "")) }
        .filterNot(::isNonArtistName)
        .map(::titleAct)
}

private fun titleAct(name: String) = ScrapedArtist(name = name, role = "HEADLINER", titleDerived = true)

private fun stripAcademicTitle(name: String): String = name.trim().replace(ACADEMIC_TITLE, "").trim()

/** `MISSVERSTEHEN SIE MICH RICHTIG – Prof. Carlo Masala zu Gast bei Ulrike Herrmann`; the series ends at a colon or a dash. */
private val GUEST_AT_HOST = Regex("""^[^:–]+?(?::|\s+[–-])\s+(.+?)\s+zu Gast bei\s+(.+)$""", RegexOption.IGNORE_CASE)

/** A prefix that marks one performance of an ensemble show, not an act. */
private val PERFORMANCE_MARKER =
    Regex("""premiere|voraufführung|generalprobe|dernière|derniere|matinee|bunter weihnachtsteller""", RegexOption.IGNORE_CASE)

private val ACADEMIC_TITLE = Regex("""^(?:(?:prof|dr)\.\s*)+""", RegexOption.IGNORE_CASE)

/** `Der BILD-Kolumnist liest seine besten Texte`, `Ein Gespräch über den Osten`; `Lesereise` names a tour, not a format. */
private val SUBTITLE_READING = Regex("""\bliest\b|lesung|gespräch""", RegexOption.IGNORE_CASE)

/** `Max Goldt liest: Aber?` is a reading by Max Goldt. */
private val READS = Regex("""\s+liest$""", RegexOption.IGNORE_CASE)
