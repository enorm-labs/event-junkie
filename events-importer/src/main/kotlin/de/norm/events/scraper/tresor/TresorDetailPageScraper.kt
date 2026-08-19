package de.norm.events.scraper.tresor

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Pure HTML parser for a Tresor event page (`/event/YYYYMMDD-<slug>/`).
 *
 * The page repeats the listing's floor-grouped lineup and adds the two things the listing lacks: a
 * **set time per artist** (`23:00-02:00`) and a blurb. The model has no per-artist time, so the
 * night's opening set — the first `.lineup-time` in document order, which is the first slot on the
 * first floor — becomes the event's start time; the venue publishes no doors or start time of its
 * own, so this is the only clock it gives.
 *
 * The blurb is followed by an underscore rule and then several screens of guest and ticket policy
 * repeated verbatim on every night ("Garderobe at Tresor is now self-service lockers…"), so only
 * the part above that rule is kept as the description.
 *
 * @see TresorOverviewPageScraper for the listing (discovery, date, floors, fallback).
 * @see TresorWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://tresorberlin.com/event/20260801-tresor-klubnacht/">Example event page</a>
 */
class TresorDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses an event page into a [ScrapedEvent], or `null` when it carries no title.
     *
     * @param sourceUrl the event's URL, used as [ScrapedEvent.sourceUrl] and to derive its date and
     *   [ScrapedEvent.sourceId].
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)
        val title = parseTitle(document)
        if (title == null) {
            logger.warn { "Event page at $sourceUrl has no title, skipping" }
            return null
        }

        // Every event page repeats the whole programme in its footer as `article.event-item` blocks
        // — the same markup the listing uses — so parsing must stay inside this event's own section.
        val content = document.selectFirst(MAIN_CONTENT) ?: document

        return ScrapedEvent(
            title = title,
            description = parseDescription(content),
            eventType = EventType.PARTY.name,
            eventDate = parseSlugDate(slug) ?: UNRESOLVED_EVENT_DATE,
            // The venue states no doors or start time; the night's opening set is the only clock.
            startTime = parseTime(OPENING_TIME.find(content.textAt(".lineup-time").orEmpty())?.value),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.TRESOR.sourceIdPrefix}$slug",
            artists = parseLineup(content)
        )
    }

    /**
     * The event's name, taken from the document title with the site suffix stripped — the page
     * renders no heading of its own. Only used when this page stands alone; a successful merge
     * keeps the listing's `.event-title`.
     */
    private fun parseTitle(document: Document): String? =
        (document.selectFirst("meta[property=og:title]")?.attr("content") ?: document.title())
            .substringBefore(SITE_TITLE_SEPARATOR)
            .trim()
            .takeIf { it.isNotBlank() }
            ?.let(::cleanEventTitle)

    /**
     * The event's own blurb: the `.main-text` lines above the underscore rule.
     *
     * Everything below that rule is the venue's standing guest and ticket policy, identical on every
     * night — storing it would put the same several screens of prose on all 30 events.
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

        /** The underscore rule separating the event's blurb from the standing policy text. */
        val POLICY_RULE = Regex("""_{5,}""")
    }
}
