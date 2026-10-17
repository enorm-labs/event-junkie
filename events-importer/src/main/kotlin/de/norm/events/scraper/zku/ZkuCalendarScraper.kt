package de.norm.events.scraper.zku

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.WHITESPACE
import de.norm.events.scraper.billsTradeFairOrConference
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.endOn
import de.norm.events.scraper.inferVenueFormatType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalTime

/**
 * Pure parser for the calendar on ZK/U's TYPO3 homepage. Each day is a hidden popup,
 * `div.calendar-events#d-2026-10-24`, holding one `.list-item` per entry: an `h2` title, a
 * `p.date` line (`24 OCTOBER 2026 / 21:00 – 03:00`), an optional subtitle and the link to
 * `/timeline/<slug>/`. The popup's id gives the day, so an entry over two days is one row a day.
 *
 * The site names no category. Markets, workshops, open studios and meetings are dropped
 * ([isZkuParticipationFormat]), as is a conference; the rest is typed from the title and the
 * subtitle in the house's own words ([ZKU_FORMATS]).
 */
class ZkuCalendarScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val days = document.select("div.calendar-events[id^=d-]")
        val entries = days.flatMap { day -> day.select(".list-item").map { day.id() to it } }
        val events = entries.mapSkippingFailures(logger, "a ZK/U calendar entry") { (dayId, item) -> parseEntry(dayId, item, sourceUrl) }
        logger.info { "Found ${events.size} programme entr(ies) over ${days.size} day(s) of the ZK/U calendar" }
        return events
    }

    private fun parseEntry(
        dayId: String,
        item: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val date = parseIsoDate(dayId.removePrefix("d-")) ?: return null
        val title =
            item
                .selectFirst("h2")
                ?.text()
                ?.clean()
                .blankToNull() ?: return null
        val subtitle =
            item
                .selectFirst("p.list-item__description")
                ?.text()
                ?.clean()
                .blankToNull()
        if (isZkuParticipationFormat(title, subtitle) || billsTradeFairOrConference(title, subtitle)) {
            logger.debug { "Skipping ZK/U entry '$title': not a stage programme" }
            return null
        }
        val href =
            item
                .selectFirst("a.list-item__link")
                ?.attr("href")
                .blankToNull()
                ?.let { resolveUrl(sourceUrl, it) } ?: return null
        val slug = URI(href).path.trimEnd('/').substringAfterLast('/')
        val (start, end) = parseTimes(item.selectFirst("p.date")?.text())
        val eventType = inferVenueFormatType(title, subtitle, ZKU_FORMATS)
        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            typeIsFallback = eventType == EventType.OTHER.name,
            eventDate = date,
            startTime = start,
            endDate = end?.let { endOn(date, start, it) },
            endTime = end,
            imageUrl =
                item
                    .selectFirst(".list-item__img[data-src]")
                    ?.attr("data-src")
                    .blankToNull()
                    ?.let { resolveUrl(sourceUrl, it) },
            sourceUrl = href,
            sourceId = "${EventSource.ZKU.sourceIdPrefix}$slug-$date",
            artists = if (eventType == EventType.READING.name) speakerOf(title) else emptyList()
        )
    }

    /** The start and, for a range, the end of a date line's clock: `/ 21:00 – 03:00`, `/ starting from 18:30`. */
    private fun parseTimes(dateLine: String?): Pair<LocalTime?, LocalTime?> {
        val match = CLOCK_RANGE.find(dateLine.orEmpty().substringAfter('/', "")) ?: return null to null
        return clock(match.groupValues[1]) to match.groupValues[2].blankToNull()?.let(::clock)
    }

    private fun clock(text: String): LocalTime? = parseTime(text.replace('.', ':').padStart(CLOCK_LENGTH, '0'))

    /** The speaker of a talk billed `Name Surname: Topic`. */
    private fun speakerOf(title: String): List<ScrapedArtist> =
        SPEAKER_PREFIX
            .find(title)
            ?.groupValues
            ?.get(1)
            ?.let { listOf(ScrapedArtist(name = it)) }
            .orEmpty()

    private fun String.clean(): String = replace(WHITESPACE, " ").trim()

    private companion object {
        val CLOCK_RANGE = Regex("""(\d{1,2}[:.]\d{2})(?:\s*[–-]\s*(\d{1,2}[:.]\d{2}))?""")
        const val CLOCK_LENGTH = 5

        /** Two to four capitalised words before a colon: `Dorothee Richter: Feminist Perspectives On Curating`. */
        val SPEAKER_PREFIX = Regex("""^(\p{Lu}[\p{L}.'-]+(?:\s+\p{Lu}[\p{L}.'-]+){1,3}):\s""")
    }
}

/**
 * The house's own format words, checked before the shared title cues. FOOD & FOOTAGE is its
 * film-and-dinner series, and its curatorial lectures are billed as talks.
 */
private val ZKU_FORMATS: Map<String, String> =
    linkedMapOf(
        "food & footage" to EventType.SCREENING.name,
        "film" to EventType.SCREENING.name,
        "movie" to EventType.SCREENING.name,
        "dj set" to EventType.PARTY.name,
        "talk" to EventType.READING.name,
        "lecture" to EventType.READING.name,
        "reading" to EventType.READING.name,
        "panel" to EventType.READING.name,
        "concert" to EventType.CONCERT.name,
        "performance" to EventType.SHOW.name,
        "festival" to EventType.FESTIVAL.name
    )

/** A format visitors take part in rather than watch, in the title or the subtitle. */
private val PARTICIPATION_FORMAT =
    Regex(
        """markt|\bmarket\b|\bworkshops?\b|\bopen studios?\b|\bopenhaus\b|editorial meeting|reading group|\bch\.\s*\d|\bknit|\bcourse\b|\bassembly\b""",
        RegexOption.IGNORE_CASE
    )

/** An academy in the title is a hands-on day (`Berlin Cake Academy`). A subtitle may name the curatorial school. */
private val ACADEMY_TITLE = Regex("""\bacademy\b""", RegexOption.IGNORE_CASE)

/**
 * Whether a ZK/U entry is a participation format (EVENT_SCOPE.md §3.2): a market, a workshop, the
 * residency's OPENHAUS open studios, an editorial meeting, a reading group, a knitting course, an
 * assembly or a hands-on academy. A reading-group session is billed by its chapter (`Ch. 12, in full`).
 */
internal fun isZkuParticipationFormat(
    title: String,
    subtitle: String?
): Boolean = PARTICIPATION_FORMAT.containsMatchIn("$title ${subtitle.orEmpty()}") || ACADEMY_TITLE.containsMatchIn(title)
