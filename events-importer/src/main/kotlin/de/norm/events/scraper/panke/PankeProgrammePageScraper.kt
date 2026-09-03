package de.norm.events.scraper.panke

import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.attrAt
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.inferUnmarkedTitleType
import de.norm.events.scraper.parseIsoDate
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.time.LocalTime

/**
 * Pure HTML parser for Panke Culture's `/programme/` page — the Wedding club and gallery's whole
 * published programme, server-rendered in one WordPress (Divi) page.
 *
 * The page renders two lists from the same template, headed **UPCOMING EVENTS** and **PAST
 * EVENTS**. Only the first is read: the venue's own division is the authoritative one, and parsing
 * the twenty-odd past entries every run only to have the persistence boundary drop them is waste.
 *
 * Each event is an `<article>` whose WordPress post id identifies it — the venue publishes no
 * per-event page at all, expanding the full text inline instead, so every event's `sourceUrl` is
 * the programme page itself. The date is an ISO `data-date` attribute; the clock is stated in prose
 * ("The event takes place on the 5th of August starting at 19:00."), sometimes with seconds.
 *
 * **The lineup is read only from Resident Advisor links.** The bodies are free prose with no shared
 * convention — one event lists its DJs one per paragraph under a `LINE UP:` heading, the next packs
 * them into a sentence beside a timetable — so the only unambiguous artist marker is an anchor
 * pointing at an `ra.co/dj/…` profile. An event without one stores no artists rather than a guess.
 *
 * Those same links also type the event: the venue publishes no category, and its titles are series
 * names rather than formats, so a billed DJ lineup is the best evidence that a night is a club
 * night. See [eventTypeOf].
 *
 * @see PankeWebsiteImporter for the HTTP fetch orchestrator.
 */
class PankeProgrammePageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses every upcoming event on the programme page.
     *
     * @param sourceUrl the URL the document was fetched from, stored as every event's `sourceUrl`.
     */
    fun scrape(
        document: Document,
        sourceUrl: String
    ): List<ScrapedEvent> {
        val articles = document.select("$UPCOMING_MODULE article[id]")

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed articles without aborting the import
        val events =
            articles.mapNotNull { article ->
                try {
                    parseArticle(article, sourceUrl)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse Panke event article, skipping" }
                    null
                }
            }
        logger.info { "Found ${events.size} upcoming event(s) on the Panke programme page" }
        return events
    }

    /** Parses one article into a [ScrapedEvent], or `null` when it has no date or title. */
    @Suppress("ReturnCount") // Guard clauses for the required id/date/title are clearer than nesting
    private fun parseArticle(
        article: Element,
        sourceUrl: String
    ): ScrapedEvent? {
        val postId = article.id().takeIf { it.isNotBlank() } ?: return null
        val eventDate = article.attrAt(".event-content-wrapper", "data-date")?.let { parseIsoDate(it) }
        if (eventDate == null) {
            logger.warn { "Panke article '$postId' states no date, skipping" }
            return null
        }
        val title = article.textAt("h2.entry-title")?.let { cleanEventTitle(it) }
        if (title.isNullOrBlank()) {
            logger.warn { "Panke article '$postId' has no title, skipping" }
            return null
        }
        val lineup = residentAdvisorLineup(article)

        return ScrapedEvent(
            title = title,
            description = descriptionOf(article),
            eventType = eventTypeOf(title, lineup),
            eventDate = eventDate,
            startTime = parseStartTime(article.textAt(".eventInfo")),
            imageUrl = parseBackgroundImageUrl(article.attr("style")),
            sourceUrl = sourceUrl,
            // The venue publishes no per-event page, so its WordPress post id is the identity.
            sourceId = "${EventSource.PANKE.sourceIdPrefix}$postId",
            artists = lineup
        )
    }

    /**
     * The event's type. The venue states none, so two signals decide it.
     *
     * An unmistakable title keyword wins first — a market is not a club night, a rave is. Where the
     * title says nothing, **an event that bills DJs on Resident Advisor is a club night**: the
     * venue links a profile only for the acts playing its floor, so the presence of a lineup is a
     * far better signal than the event's name, which is a series title rather than a format.
     * Anything with neither stays `OTHER` rather than being guessed a concert.
     */
    private fun eventTypeOf(
        title: String,
        lineup: List<ScrapedArtist>
    ): String {
        val fromTitle = inferUnmarkedTitleType(title)
        return if (fromTitle == EventType.OTHER.name && lineup.isNotEmpty()) EventType.PARTY.name else fromTitle
    }

    /**
     * The event's text: the full body the "Show more" button reveals, falling back to the teaser
     * shown on the card. The full block repeats the date and clock in its own first column, so only
     * the second is taken.
     */
    private fun descriptionOf(article: Element): String? =
        article.textAt(".post-content-full .et_pb_column_3_4")
            ?: article.textAt(".post-content-excerpt")

    /**
     * The DJs an event links to on Resident Advisor, in the order the venue lists them.
     *
     * Deduplicated on the profile URL: a night that bills the same DJ twice — once in the lineup
     * and once in a timetable — must not store them twice.
     */
    private fun residentAdvisorLineup(article: Element): List<ScrapedArtist> =
        article
            .select("a[href]")
            .filter { RESIDENT_ADVISOR_PROFILE.containsMatchIn(it.attr("href")) }
            .distinctBy { it.attr("href").trimEnd('/').lowercase() }
            .mapNotNull { it.text().trim().takeIf(String::isNotEmpty) }
            .map { ScrapedArtist(name = it, role = DJ_ROLE) }

    /**
     * Reads the clock out of the venue's prose line, which states it either as `HH:mm` or with
     * seconds it never means (`23:00:00`). Returns `null` when the line names no time.
     */
    private fun parseStartTime(info: String?): LocalTime? = parseTime(START_TIME.find(info.orEmpty())?.groupValues?.get(1))
}

/**
 * Reads the poster out of an article's inline `background-image: url(…)`, the only place the
 * listing carries one — the template renders no `<img>` for an event at all.
 */
internal fun parseBackgroundImageUrl(style: String?): String? =
    BACKGROUND_URL_PATTERN
        .find(style.orEmpty())
        ?.groupValues
        ?.get(1)
        ?.takeIf { it.startsWith("http") }

/** The venue's own "upcoming" list, as opposed to the identically templated past one. */
private const val UPCOMING_MODULE = ".et_pb_events_0"

/** `"starting at 19:00."` or `"starting at 23:00:00."` — the seconds are template noise. */
private val START_TIME = Regex("""starting\s+at\s+(\d{1,2}:\d{2})(?::\d{2})?""", RegexOption.IGNORE_CASE)

/** A Resident Advisor artist profile, the page's one unambiguous artist marker. */
private val RESIDENT_ADVISOR_PROFILE = Regex("""^https?://(?:www\.)?ra\.co/(?:dj|artist)/""", RegexOption.IGNORE_CASE)

/** Matches the URL inside a CSS `url(…)` value, with or without quotes. */
private val BACKGROUND_URL_PATTERN = Regex("""url\(\s*['"]?([^'")]+)['"]?\s*\)""")

/** Every act the venue links on Resident Advisor is billed there as a DJ. */
private const val DJ_ROLE = "DJ"
