package de.norm.events.scraper.tresor

import de.norm.events.event.EventType
import de.norm.events.scraper.BERLIN
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for a Tresor event page (`/event/YYYYMMDD-<slug>/`).
 *
 * Repeats the listing's floor-grouped lineup and adds what the listing lacks: a **set time per
 * artist** and a blurb. Each `a.lineup-item` pairs a `.lineup-time` with a `.lineup-name`; the time
 * is written `23:00-02:00`, `04:30 – 07:30` or `07:30-END`, and an unannounced slot still holds its
 * place as `???`. The first slot on the first floor is also the event's start time: the venue
 * publishes no doors or start time.
 *
 * Only a page whose `<body>` is `single-event` is an event. Tresor has answered every event URL
 * with a redirect to its home page (#2000), and parsing the home page as the event would lose the
 * listing's data without a trace.
 *
 * The blurb is followed by an underscore rule and then several screens of guest and ticket
 * policy repeated verbatim on every night ("Garderobe at Tresor is now self-service lockers…"),
 * so only the part above that rule is kept.
 *
 * @see TresorOverviewPageScraper for the listing (discovery, date, floors, fallback).
 * @see TresorWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://tresorberlin.com/event/20260801-tresor-klubnacht/">Example event page</a>
 */
class TresorDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event page into a [ScrapedEvent], or `null` without a title.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl], its date and [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for a non-event page and a missing title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        if (document.selectFirst(EVENT_PAGE) == null) {
            val landedOn = document.selectFirst("link[rel=canonical]")?.attr("href")
            logger.warn { "Not an event page (landed on $landedOn), keeping the listing's data" }
            return null
        }
        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)
        val title = parseTitle(document)
        if (title == null) {
            logger.warn { "Event page has no title, skipping" }
            return null
        }

        // Every event page repeats the whole programme in its footer as `article.event-item` blocks —
        // the listing's markup — so parsing must stay inside this event's own section.
        val content = document.selectFirst(MAIN_CONTENT) ?: document
        val eventDate = parseSlugDate(slug)

        return ScrapedEvent(
            title = title,
            description = parseDescription(content),
            eventType = EventType.PARTY.name,
            eventDate = eventDate ?: UNRESOLVED_EVENT_DATE,
            // No doors or start time is stated; the night's opening set is the only clock.
            startTime = parseTime(OPENING_TIME.find(content.textAt(".lineup-time").orEmpty())?.value),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.TRESOR.sourceIdPrefix}$slug",
            artists = parseRunningOrder(content, eventDate)
        )
    }

    /**
     * The lineup per floor, each act with its slot's start and end (#2002). A floor's first slot
     * before noon is already the next day, as the Globus floor opening at `00:00` is; each later
     * slot starting earlier than the one before has crossed midnight. `END` and a missing time
     * leave the end unknown, and without the night's date no slot gets a time at all.
     */
    private fun parseRunningOrder(
        content: Element,
        eventDate: LocalDate?
    ): List<ScrapedArtist> =
        content
            .select(".floor")
            .flatMap { floor ->
                val stage = floor.textAt(".floor-name")?.let(::normalizeFloor)
                val sets = floorSetTimes(floor.select(".lineup-item").map { it.textAt(".lineup-time").orEmpty() }, eventDate)
                floor.select(".lineup-item").zip(sets).flatMap { (item, set) ->
                    splitActs(item.textAt(".lineup-name").orEmpty())
                        .map { ScrapedArtist(name = it, role = "DJ", stage = stage, setStart = set.first, setEnd = set.second) }
                }
            }.distinctBy { it.name.lowercase() }

    /** Each slot's start and end on one floor, in slot order; see [parseRunningOrder] for the day rules. */
    private fun floorSetTimes(
        slots: List<String>,
        eventDate: LocalDate?
    ): List<Pair<Instant?, Instant?>> {
        var day = eventDate ?: return slots.map { null to null }
        var previous = NOON
        val times = mutableListOf<Pair<Instant?, Instant?>>()
        for (slot in slots) {
            val match = SLOT_TIME.find(slot)
            val start = match?.let { parseTime(it.groupValues[1]) }
            if (start == null) {
                times += null to null
                continue
            }
            if (start < previous) day = day.plusDays(1)
            previous = start
            val end = parseTime(match.groupValues[2])
            val endDay = if (end != null && end <= start) day.plusDays(1) else day
            times += day.atTime(start).atZone(BERLIN).toInstant() to end?.let { endDay.atTime(it).atZone(BERLIN).toInstant() }
        }
        return times
    }

    /**
     * The event's name from the document title with the site suffix stripped — the page renders no
     * heading. Only when this page stands alone; a successful merge keeps the listing's `.event-title`.
     */
    private fun parseTitle(document: Document): String? =
        (document.selectFirst("meta[property=og:title]")?.attr("content") ?: document.title())
            .substringBefore(SITE_TITLE_SEPARATOR)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(::cleanEventTitle)

    /**
     * The event's own blurb: the `.main-text` lines above the underscore rule. Everything below is
     * the standing guest and ticket policy, identical on every night — storing it would put the
     * same several screens of prose on all 30 events.
     */
    private fun parseDescription(content: Element): String? =
        content
            .selectFirst(".main-text")
            ?.wholeText()
            ?.split(POLICY_RULE)
            ?.firstOrNull()
            ?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.joinToString("\n")
            ?.takeIf { it.isNotBlank() }

    private companion object {
        /** The event's own section; the page's footer repeats the whole programme below it. */
        const val MAIN_CONTENT = "main.main-content"

        /** The separator WordPress puts between the event name and the site name. */
        const val SITE_TITLE_SEPARATOR = " | "

        /** The first clock time of a `23:00-02:00` set slot. */
        val OPENING_TIME = Regex("""\d{1,2}:\d{2}""")

        /** A slot's start and, unless it runs to `END`, its end: `23:00-02:00`, `04:30 – 07:30`. */
        val SLOT_TIME = Regex("""(\d{1,2}:\d{2})\s*[-–]\s*(\d{1,2}:\d{2})?""")

        /** Only a `<body>` of this class is an event page; the home page carries `home`. */
        const val EVENT_PAGE = "body.single-event"

        /** A floor's first slot before this is past midnight already. */
        val NOON: LocalTime = LocalTime.NOON

        /** The underscore rule separating the event's blurb from the standing policy text. */
        val POLICY_RULE = Regex("""_{5,}""")
    }
}
