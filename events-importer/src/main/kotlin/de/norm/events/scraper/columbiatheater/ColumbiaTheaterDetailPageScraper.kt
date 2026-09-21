package de.norm.events.scraper.columbiatheater

import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.UNRESOLVED_EVENT_DATE
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.extractEventSlug
import de.norm.events.scraper.hrefAt
import de.norm.events.scraper.imgSrcAt
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document

/**
 * Pure HTML parser for Columbia Theater Berlin event detail pages (`/event/YYYYMMDD-<slug>/`).
 *
 * One `.event-content` block: `h1.header-title`, optional `.header-tour-text` tour name,
 * `.header-support` billing rows, `.header-date` (`Wd. DD.MM.[YY] um HH:mm / Einlass HH:mm`),
 * poster (`img.event-image-img`), `.header-promoters` "präsentiert von …", the ticket-shop
 * button, the `.event-text` blurb, and for a cancelled/relocated/rescheduled show an
 * `.event-status` notice plus the overview's `data-c` / `data-m` / `data-p` flags. No JSON-LD;
 * the WordPress REST API is disabled site-wide.
 *
 * Source for what the overview lacks — doors/start, blurb, ticket URL, presenters — and repeats
 * the shared fields, so a successful fetch is a complete event; the overview fills gaps (or
 * stands in entirely when the fetch fails) via
 * [ColumbiaTheaterWebsiteImporter.fillGapsFromOverview]. The date comes from the permalink slug
 * because the rendered header date usually omits the year.
 *
 * @see ColumbiaTheaterOverviewPageScraper for overview parsing (discovery, date, fallback).
 * @see ColumbiaTheaterWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://columbia-theater.de/event/20260803-soulfly/">Example detail page</a>
 */
class ColumbiaTheaterDetailPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses a detail page into a [ScrapedEvent], or `null` without `.event-content` or a title.
     *
     * @param sourceUrl the event's URL: [ScrapedEvent.sourceUrl], its date and [ScrapedEvent.sourceId].
     */
    @Suppress("ReturnCount") // Guard clauses for the missing content block / title are clearer than nesting
    fun scrape(
        document: Document,
        sourceUrl: String
    ): ScrapedEvent? {
        val content = document.selectFirst("div.event-content")
        if (content == null) {
            logger.warn { "Detail page has no event content block, skipping" }
            return null
        }
        val title = content.textAt("h1.header-title")?.let(::cleanEventTitle)
        if (title == null) {
            logger.warn { "Detail page has no event title, skipping" }
            return null
        }

        val slug = extractEventSlug(sourceUrl, EVENT_PATH_PREFIX)
        val supportRows = content.selectFirst(".header-support")?.let(::supportRowTexts).orEmpty()
        // ".header-date-prev" is the date a rescheduled show was moved *from* — never the event's own.
        val dateLine = content.textAt(".header-date:not(.header-date-prev)").orEmpty()
        val eventType = inferConcertVenueType(title)

        return ScrapedEvent(
            title = title,
            subtitle = columbiaTheaterSubtitle(content.textAt(".header-tour-text"), supportRows),
            description = content.textAt("div.event-text"),
            eventType = eventType,
            eventDate = parseColumbiaTheaterSlugDate(slug) ?: UNRESOLVED_EVENT_DATE,
            doorsTime = parseTime(labelledTime(dateLine, DOORS_LABEL)),
            startTime = parseTime(labelledTime(dateLine, START_LABEL)),
            imageUrl = content.imgSrcAt("img.event-image-img"),
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.COLUMBIA_THEATER.sourceIdPrefix}$slug",
            ticketUrl = content.hrefAt(".event-tickets .ticket-links a")?.let(::firstTicketUrl),
            status = parseColumbiaTheaterStatus(content, content.textAt("div.event-status")),
            artists = columbiaTheaterArtists(title, supportRows, eventType),
            promoters = parseColumbiaTheaterPresenters(content)
        )
    }
}

/** The German doors label on the header date line ("… / Einlass 19:00"). */
private const val DOORS_LABEL = "Einlass"

/** The German start-time preposition on the header date line ("… um 20:00 …"). */
private const val START_LABEL = "um"

/**
 * The `HH:mm` after [label] on the header date line (`"So. 16.08. um 20:00 / Einlass 19:00"`),
 * or `null` — a relocated show renders "Mo. 28.09. / Verlegt" with no times. The label is
 * word-anchored so the short `um` cannot match inside another word.
 */
private fun labelledTime(
    text: String,
    label: String
): String? = Regex("""\b$label\s*:?\s*(\d{1,2}:\d{2})""", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)

/** A URL scheme, used to find where a doubled ticket `href` restarts. */
private val URL_SCHEME = Regex("""https?://""")

/**
 * The first URL of a ticket `href` with **two shop links concatenated** — the CMS occasionally
 * emits `"https://www.eventim.de/…&utm_medium=dphttps://www.eventim.de/…"`, not a resolvable
 * link. Everything from the second `http(s)://` on is dropped; a single-URL href is unchanged.
 */
private fun firstTicketUrl(href: String): String =
    URL_SCHEME
        .findAll(href)
        .drop(1)
        .firstOrNull()
        ?.let { href.substring(0, it.range.first) }
        ?: href
