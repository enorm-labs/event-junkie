package de.norm.events.scraper.ohm

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferYearForWeekday
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import de.norm.events.scraper.textLinesAt
import de.norm.events.slug.SlugGenerator
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Clock
import java.time.LocalDate
import java.time.MonthDay

/**
 * Pure HTML parser for OHM Berlin's home-page programme.
 *
 * The whole upcoming programme is a `ul.event-list`, each `li.event-item` carrying an
 * `.event-date` (`31/07`), an `.event-time` (`23:00`), an `.event-title` and a `<br>`-separated
 * `.event-lineup` of DJs. No per-event URLs, images, prices or ticket links, so this page is the
 * whole source and `sourceId` is the resolved date plus slugified title.
 *
 * **The date carries no year and no weekday.** The year comes from [inferYearForWeekday]'s
 * weekday-less path — the occurrence nearest today — so a night that has just happened stays in
 * the current year rather than rolling twelve months forward. The venue leaves the night in
 * progress on the page until it is over, exactly the case that rule protects.
 *
 * @see OhmWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://ohmberlin.com/">OHM Berlin</a>
 */
class OhmOverviewPageScraper(
    /** Clock for year inference on the year-less dates; override in tests. */
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all events from the programme page, one per listed night.
     *
     * @param baseUrl the URL the document was fetched from; every event's `sourceUrl`, since no
     * per-event pages exist.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        val items = document.select("ul.event-list li.event-item")
        logger.info { "Found ${items.size} event item(s) on OHM overview" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed items without aborting the import
        return items.mapNotNull { item ->
            try {
                parseItem(item, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse OHM event item, skipping" }
                null
            }
        }
    }

    /** Parses one `li.event-item` into a [ScrapedEvent], or `null` without a title or date. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date are clearer than nesting
    private fun parseItem(
        item: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val rawTitle = item.textAt(".event-title")
        if (rawTitle == null) {
            logger.warn { "OHM event item has no title, skipping" }
            return null
        }
        val title = cleanEventTitle(rawTitle)
        val eventDate = parseEventDate(item.textAt(".event-date"))
        if (eventDate == null) {
            logger.warn { "OHM event '$title' has no parseable date, skipping" }
            return null
        }

        return ScrapedEvent(
            title = title,
            // Every OHM night is a DJ programme; the venue publishes no categories.
            eventType = EventType.PARTY.name,
            eventDate = eventDate,
            startTime = parseTime(item.textAt(".event-time")),
            // No per-event pages, so the listing itself is the canonical URL.
            sourceUrl = baseUrl,
            sourceId = "${EventSource.OHM.sourceIdPrefix}$eventDate-${SlugGenerator.slugify(title)}",
            artists = parseLineup(item)
        )
    }

    /**
     * Resolves the year-less `DD/MM` date. `null` when missing or not a day/month pair.
     */
    private fun parseEventDate(text: String?): LocalDate? {
        val match = DAY_MONTH_PATTERN.find(text.orEmpty())
        val monthDay =
            match?.let {
                runCatching { MonthDay.of(it.groupValues[2].toInt(), it.groupValues[1].toInt()) }.getOrNull()
            }
        // No weekday is rendered, so the null-weekday path applies: the occurrence nearest today.
        return monthDay?.let { inferYearForWeekday(it, weekday = null, clock = clock) }
    }

    /**
     * The roster from the `<br>`-separated `.event-lineup` block. The *title* is a party or
     * collective name (`"Ouch x FemmeDecks"`), never an act, so not minted. A bracket annotates
     * a line (#1756): `(Live Painting)` or `(Sonic Visual Installation)` is not a music act and is
     * dropped, `(OPERA live)` bills a `HEADLINER`, every other line a `DJ`. The bracket stays on the
     * name, because `V.` alone would merge into another act's `v` slug.
     */
    private fun parseLineup(item: Element): List<ScrapedArtist> =
        item
            .textLinesAt(".event-lineup")
            .filterNot { isNonArtistName(it) || NON_MUSIC_ANNOTATION.containsMatchIn(it) }
            .map { ScrapedArtist(name = it, role = if (LIVE_ANNOTATION.containsMatchIn(it)) "HEADLINER" else "DJ") }
}

/** A bracket naming a contribution that is not music: `(Live Painting)`, `(Sonic Visual Installation)`. */
private val NON_MUSIC_ANNOTATION = Regex("""\([^()]*\b(?:painting|visuals?|installation|vj)\b[^()]*\)""", RegexOption.IGNORE_CASE)

/** A bracket marking a live act: `(OPERA live)`, `(live)`. */
private val LIVE_ANNOTATION = Regex("""\([^()]*\blive\b[^()]*\)""", RegexOption.IGNORE_CASE)

/** The year-less `DD/MM` date rendering, tolerating single-digit parts. */
private val DAY_MONTH_PATTERN = Regex("""(\d{1,2})\s*/\s*(\d{1,2})""")
