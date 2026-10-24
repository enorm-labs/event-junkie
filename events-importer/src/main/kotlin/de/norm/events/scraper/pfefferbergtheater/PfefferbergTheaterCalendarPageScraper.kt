package de.norm.events.scraper.pfefferbergtheater

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.childrensShowPhrase
import de.norm.events.scraper.collapseWhitespace
import de.norm.events.scraper.forChildrenIf
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.splitSegmentOnConjunctions
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalDate

/**
 * Pure HTML parser for the Pfefferberg Theater calendar (`/spielplan/kalender/`), one ProcessWire page
 * that runs about a year ahead. Each `div.event[data-searchid]` is one performance:
 *
 * ```
 * <div class="event eventyear-2026 eventmonth-12 eventday-15 eventcat-ensemble-literatur-live eventcat-kategorie-fur-alle eventid-29934">
 *   <a href="/spielplan/kalender/lesung-fil-01/?eventdate_id=29935"> <h4 class="date">15.12.</h4><p class="big time">20:00</p>
 *   <h3>Fil mit neuer Show – Winter 26/27 +++ Ausverkauft +++</h3><p class="disposable">Literatur LIVE</p>
 * ```
 *
 * The year sits only in the classes. The `kategorie-*` and `genre-zusatz-*` classes are named by the page's own
 * filter menu, read from the same document. The house writes status into the title between `+++` marks. The
 * `Märchen im Glaspalast` page has the same rows without category classes; its rows are the Glaspalast's.
 */
class PfefferbergTheaterCalendarPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val rows = document.select("div.event[data-searchid]")
        logger.info { "Found ${rows.size} Pfefferberg Theater performance(s) on $baseUrl" }
        val labels = filterLabels(document)
        val room = GLASPALAST.takeIf { GLASPALAST_PATH in baseUrl }
        return rows.mapSkippingFailures(logger, "Pfefferberg Theater performance") { row -> parseRow(row, labels, room) }
    }

    private fun filterLabels(document: Document): Map<String, String> =
        document
            .select("option[value~=^(?:kategorie|genre-zusatz)-]")
            .mapNotNull { option -> option.text().blankToNull()?.let { option.attr("value") to it } }
            .toMap()

    private fun parseRow(
        row: Element,
        labels: Map<String, String>,
        room: String?
    ): ScrapedEvent? {
        val link = row.selectFirst("a[href*=eventdate_id=]") ?: return null
        val dateId = EVENTDATE_ID.find(link.attr("href"))?.groupValues?.get(1) ?: return null
        val eventDate = classDate(row) ?: return null
        val heading = row.textAt("h3")?.replace(' ', ' ') ?: return null
        val markers = STATUS_MARK.findAll(heading).map { it.groupValues[1].lowercase() }.toList()
        val title = heading.replace(STATUS_MARK, " ").collapseWhitespace().blankToNull() ?: return null
        val subtitle = row.textAt("p.disposable")
        val tags = row.classNames().filter { it.startsWith("eventcat-") }.map { it.removePrefix("eventcat-") }
        val category = tags.firstNotNullOfOrNull { tag -> labels[tag].takeIf { tag.startsWith("kategorie-") } }
        val eventType = categoryType(category, tags)
        val fallbackType = inferUnmarkedTitleType(title).takeUnless { it == EventType.OTHER.name }
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType ?: fallbackType ?: EventType.SHOW.name,
            typeIsFallback = eventType == null && fallbackType == null,
            eventDate = eventDate,
            startTime = parseTime(row.textAt("p.time")),
            room = room,
            sourceUrl = link.absUrl("href").substringBefore('?'),
            sourceId = "${EventSource.PFEFFERBERG_THEATER.sourceIdPrefix}$dateId",
            genre = genres(tags, labels).joinToString(", ").ifEmpty { null },
            status = if (markers.any { CANCELLED in it }) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name,
            soldOut = markers.any { SOLD_OUT in it },
            artists = pfefferbergActs(title, readingSeries = tags.contains(READING_SERIES))
        ).forChildrenIf(CHILDRENS_CATEGORY.takeIf { category == it } ?: childrensShowPhrase(title, subtitle))
    }

    private fun classDate(row: Element): LocalDate? {
        val match = CLASS_DATE.find(row.className()) ?: return null
        val (year, month, day) = match.destructured
        return runCatching { LocalDate.of(year.toInt(), month.toInt(), day.toInt()) }.getOrNull()
    }

    /** The category and the extra labels, less the two that mark the ticket offer or the run rather than the show. */
    private fun genres(
        tags: List<String>,
        labels: Map<String, String>
    ): List<String> = tags.mapNotNull { labels[it] }.filterNot { NON_GENRE_LABEL.matches(it) }.distinct()

    private companion object {
        val EVENTDATE_ID = Regex("""eventdate_id=(\d+)""")
        val CLASS_DATE = Regex("""\beventyear-(\d{4})\s+eventmonth-(\d{1,2})\s+eventday-(\d{1,2})\b""")

        /** `+++ ABGESAGT +++`, `+++Ausverkauft+++`, `+++ Premiere am 6. November +++`. */
        val STATUS_MARK = Regex("""\+{3}\s*([^+]*?)\s*\+{3}""")
        const val CANCELLED = "abgesagt"
        const val SOLD_OUT = "ausverkauft"

        val NON_GENRE_LABEL = Regex("""Studis & Azubis|Premieren""")

        /** The category label of the house's children's theatre, Coq au Vin and its guests (#3796). */
        const val CHILDRENS_CATEGORY = "Kindertheater"
    }
}

/** The type the house's category label names, or the reading series' type; null when neither does. */
internal fun categoryType(
    category: String?,
    tags: List<String>
): String? {
    val label = category?.lowercase()
    return when {
        label != null && ("comedy" in label || "kabarett" in label) -> EventType.COMEDY.name
        label != null && "lesung" in label -> EventType.READING.name
        label != null -> EventType.SHOW.name
        READING_SERIES in tags -> EventType.READING.name
        else -> null
    }
}

/**
 * The people a title bills. A book premiere names its readers after `Buchpremiere mit`; a reading names
 * them before `lesen`, `liest aus`, `im Gespräch`, `präsentiert` or `mit neuer Show`. In the reading
 * series a title that opens with a short name and a colon names its author (`Stefan Schwarz: Oops`).
 * Any other title names a production and bills nobody: the ensemble line is a company or a series.
 */
internal fun pfefferbergActs(
    title: String,
    readingSeries: Boolean
): List<ScrapedArtist> {
    val names =
        BOOK_PREMIERE.find(title)?.groupValues?.get(1)
            ?: READERS.find(title)?.groupValues?.get(1)
            ?: HOSTS.find(title)?.groupValues?.get(1)
            ?: AUTHOR_COLON
                .find(title)
                ?.groupValues
                ?.get(1)
                ?.takeIf { readingSeries }
            ?: return emptyList()
    return splitSegmentOnConjunctions(names)
        .map { it.replace(ACADEMIC_TITLE, "").trim() }
        .mapNotNull { it.blankToNull() }
        .filterNot(::isNonArtistName)
        .map { ScrapedArtist(name = it, titleDerived = true) }
}

/** The path of the `Märchen im Glaspalast` season page, whose rows play in the [GLASPALAST]. */
internal const val GLASPALAST_PATH = "/spielplan/maerchenimglaspalast"

/** The house's second room, a glass pavilion, where the Hexenberg Ensemble plays its fairy tales. */
private const val GLASPALAST = "Glaspalast"

/** The house's own reading series, whose titles open with the author. */
private const val READING_SERIES = "ensemble-literatur-live"

private val BOOK_PREMIERE = Regex("""Buchpremiere mit (.+)$""")
private val READERS = Regex("""^(?:[^:]+:\s*)?(.+?)\s+lesen\b""")
private val HOSTS = Regex("""^([^:]+?)\s+(?:im Gespräch|präsentier(?:t|en)|presents|liest aus|mit neuer Show)\b""")
private val AUTHOR_COLON = Regex("""^((?:(?:\p{Lu}[\p{L}.-]*|von|zu|van|de)\s+){0,3}\p{Lu}[\p{L}-]*):""")
private val ACADEMIC_TITLE = Regex("""^(?:Dr\.|Prof\.)\s+(?:med\.\s+)?""")
