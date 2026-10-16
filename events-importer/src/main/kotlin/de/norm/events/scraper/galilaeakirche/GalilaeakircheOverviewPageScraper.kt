package de.norm.events.scraper.galilaeakirche

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.START_LABELS
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.blankToNull
import de.norm.events.scraper.headlinersFromTitle
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.labelledClock
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.mapSkippingFailures
import de.norm.events.scraper.parseClock
import de.norm.events.scraper.parseEventStatus
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseGermanShortDate
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure parser for the Very Simple Event List programme, `/programm/`: one `div.vsel-content.vsel-upcoming` per event with
 * the title, a `Fr. 16.10.26` date, a free-text time line (`Beginn: 20:00 Uhr`, `abgesagt`) and the venue's category.
 *
 * The page renders the upcoming list once per category tab, and the archive below it without `vsel-upcoming`, so the rows
 * are read by that class and kept once per event id.
 */
class GalilaeakircheOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("div.vsel-content.vsel-upcoming").distinctBy { it.id() }
        logger.info { "Found ${items.size} event item(s) on the Galiläakirche programme" }
        return items.mapSkippingFailures(logger, "Galiläakirche event item") { parseItem(it, baseUrl) }
    }

    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val link = item.selectFirst("h3.vsel-meta-title a[href]") ?: return null
        val title = link.text().blankToNull() ?: return null
        val url = resolveUrl(baseUrl, link.attr("href"))
        val dates = item.select(".vsel-meta-date").mapNotNull { parseListingDate(it.text()) }
        val date = dates.firstOrNull()
        if (date == null) {
            logger.warn { "Galiläakirche event '$title' has no readable date, skipping" }
            return null
        }
        val timeLine = item.textAt(".vsel-meta-time")
        val category = item.select(".vsel-meta-cats a").firstNotNullOfOrNull { mapEventType(it.text(), CATEGORIES) }
        val eventType = category ?: inferConcertVenueType(title)
        return ScrapedEvent(
            title = title,
            eventType = eventType,
            typeIsFallback = category == null,
            eventDate = date,
            endDate = dates.lastOrNull()?.takeIf { it.isAfter(date) },
            startTime = startTime(timeLine),
            imageUrl = item.selectFirst("img.vsel-image-img[src]")?.absUrl("src").blankToNull(),
            status = timeLine?.let(::parseEventStatus) ?: EventStatus.SCHEDULED.name,
            sourceUrl = url,
            sourceId = galilaeakircheSourceId(url),
            artists = titleActs(title, eventType)
        )
    }

    /**
     * A concert bills its act at the head of the title, before the programme's name for the night: `Mikail Yakut: Kinto’s
     * Dance – Album Release`, `Jimi Tenor “Wormhole Mystic” Record Release Show`. A `präsentiert` title names a series.
     */
    private fun titleActs(
        title: String,
        eventType: String
    ): List<ScrapedArtist> {
        if (eventType != EventType.CONCERT.name || PRESENTS.containsMatchIn(title)) return emptyList()
        return headlinersFromTitle(title.split(TITLE_TAIL, limit = 2).first())
    }

    private companion object {
        /** The tabs the programme files events under, beside the shared `Konzert`, `Lesung` and `Sonstiges`. */
        val CATEGORIES = mapOf("theater" to EventType.SHOW.name, "film" to EventType.SCREENING.name)

        /** `Begin:` and `Uhrzeit:` are spellings the venue uses beside the shared start labels. */
        const val TIME_LABELS = """$START_LABELS|begin|uhrzeit"""

        val PRESENTS = Regex("""\b(?:präsentiert|presents)\b""", RegexOption.IGNORE_CASE)

        /** Where the night's name begins: a colon, a spaced dash, a bracket or quote, or a release-show tag. */
        val TITLE_TAIL = Regex("""\s*:\s|\s+[–—-]\s+|\s+[(“"„]|\s+(?:pre-)?(?:album|record)[- ]?release""", RegexOption.IGNORE_CASE)

        val DATE = Regex("""\d{1,2}\.\d{1,2}\.\d{2,4}""")

        val CLOCK = Regex("""(?<!\d)(\d{1,2}):(\d{2})(?!\d)""")

        fun parseListingDate(text: String): LocalDate? =
            DATE.find(text)?.value?.let { if (it.substringAfterLast('.').length == 2) parseGermanShortDate(it) else parseGermanDate(it) }

        /** The labelled start, else the line's first clock (`12:00 bis 18:00 Uhr`). */
        fun startTime(line: String?): LocalTime? =
            labelledClock(line, TIME_LABELS) ?: line?.let { CLOCK.find(it) }?.destructured?.let { (hour, minute) -> parseClock("$hour:$minute") }
    }
}

/** The event's slug under `/event/`, the same on the listing and on the event page. */
internal fun galilaeakircheSourceId(url: String): String =
    "${EventSource.GALILAEAKIRCHE.sourceIdPrefix}${URI(url).path.removePrefix("/en").removePrefix("/event/").trimEnd('/')}"
