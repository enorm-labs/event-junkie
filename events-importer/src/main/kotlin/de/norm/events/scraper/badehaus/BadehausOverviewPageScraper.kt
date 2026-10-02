package de.norm.events.scraper.badehaus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.genretag.isGenreLabel
import de.norm.events.genretag.normalizeGenre
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.SUPPORT_LABELS
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.dateCheckedAgainstWeekday
import de.norm.events.scraper.inferConcertVenueType
import de.norm.events.scraper.parseGermanDate
import de.norm.events.scraper.parseGermanWeekdayAbbreviation
import de.norm.events.scraper.parseTime
import de.norm.events.scraper.resolveUrl
import de.norm.events.scraper.textAt
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.time.LocalDate
import java.time.LocalTime

/**
 * Pure HTML parser for Badehaus Berlin's WordPress `/events/` listing page.
 *
 * WordPress with the Events Manager plugin, but the theme renders its own cards. The whole
 * programme is on the single `/events/` page as a flat list of card wrappers — one `<div>` per
 * event with an image link, a `.eventinfo` line (date + doors time + ticket link), a title
 * (`h2 > a`) and a subtitle.
 *
 * The overview is the discovery list — every event and its `/events/<slug>/` detail URL, which
 * [BadehausDetailPageScraper] enriches with description, start time (`Beginn`) and promoter —
 * and the **authoritative source** for what the detail page lacks or renders unreliably: the
 * sold-out flag and status class (a CSS class on the card — `AUSVERKAUFT` sold out, `ABGESAGT`
 * cancelled, `VERLEGT` changed, styled into an overlay badge), the subtitle, and the inferred
 * event type (see [inferEventType]). `VERLEGT` covers a postponement and a move alike, so the
 * detail page's notice decides and the class is the fallback (#1578). It also supplies fallback
 * title / date / doors / image. [BadehausWebsiteImporter] merges.
 *
 * @see BadehausWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://badehaus-berlin.com/events/">Badehaus Berlin programme</a>
 */
class BadehausOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the `/events/` listing, one [ScrapedEvent] per card.
     *
     * @param baseUrl the URL the document was fetched from, for resolving relative links.
     */
    fun scrape(
        document: Document,
        baseUrl: String
    ): List<ScrapedEvent> {
        // A card wrapper is the div that directly holds the `.eventlistimg` image block.
        val cards = document.select("div:has(> div.eventlistimg)")
        logger.info { "Found ${cards.size} event card(s) on Badehaus listing" }

        @Suppress("TooGenericExceptionCaught") // Intentional: skip individual malformed cards without aborting the whole import
        return cards.mapNotNull { card ->
            try {
                parseCard(card, baseUrl)
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse event card, skipping" }
                null
            }
        }
    }

    /** Parses one card wrapper into a [ScrapedEvent], or `null` when required fields are missing. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date/url are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val titleLink = card.selectFirst(".nomargin h2 a") ?: return null
        val title = titleLink.text().trim().takeIf { it.isNotBlank() } ?: return null

        val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = badehausEventSlug(sourceUrl)

        val eventInfo = card.textAt(".eventinfo").orEmpty()
        val eventDate =
            parseBadehausDate(eventInfo) ?: run {
                logger.warn { "Could not parse date for '$title' from '$eventInfo', skipping" }
                return null
            }

        val status = parseStatus(card.className())
        val subtitle = parseSubtitle(card)
        val eventType = inferEventType(title, slug)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            genre = parseGenre(subtitle),
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = parseDoorsTime(eventInfo),
            imageUrl = card.selectFirst(".eventlistimg img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BADEHAUS.sourceIdPrefix}$slug",
            ticketUrl = card.selectFirst(".eventinfo a[href^=http]")?.attr("href"),
            soldOut = card.hasClass(SOLD_OUT_CLASS),
            status = status,
            // No artist roster; for concerts the title is the act (support from the subtitle's "Support:"
            // pattern). Non-artist titles (quiz/screening/festival names) are filtered by isNonArtistName.
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    /**
     * Infers the [event type][de.norm.events.event.EventType] from the title/slug.
     *
     * **The listing card carries no category**; the detail page does, and its category wins in
     * [BadehausWebsiteImporter.fillGapsFromOverview] (#1950). This is the fallback for a page
     * without one: the shared concert-venue rule with [BADEHAUS_KEYWORDS], everything else
     * `CONCERT` — a live-music venue where concerts are by far the most common event. A themed night classified `PARTY` matters beyond
     * the label: a `PARTY` extracts no artists, so its event-name title is not minted as a fake act
     * (see [buildArtistsForEventType]).
     */
    private fun inferEventType(
        title: String,
        slug: String
    ): String = inferConcertVenueType("$title $slug", null, BADEHAUS_KEYWORDS)

    /**
     * The subtitle line — the card's `<p>` that is neither the `.eventinfo` line nor the "MORE"
     * link paragraph (identified by carrying an anchor).
     */
    private fun parseSubtitle(card: Element): String? =
        card
            .select(".nomargin > p")
            .firstOrNull { !it.hasClass("eventinfo") && it.selectFirst("a") == null }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /**
     * The known genres in the subtitle line. The line is a style ("Metalcore"), a tour name and a
     * style joined by `|` or `//` ("Kein Plan B-Tour 2026 | Deutschrock"), or prose. Each part that
     * names neither a tour nor support goes to the normalizer: a tour name can hold a genre word
     * ("… WORLD TOUR"). The normalizer keeps an unknown short token as a new genre, so only
     * [isGenreLabel] ones pass.
     */
    private fun parseGenre(subtitle: String?): String? =
        subtitle
            ?.split(PART_SEPARATOR)
            ?.map { it.trim() }
            ?.filterNot { SUPPORT_PART.containsMatchIn(it) || TOUR_PART.containsMatchIn(it) }
            ?.flatMap { normalizeGenre(it) }
            ?.filter(::isGenreLabel)
            ?.distinct()
            ?.joinToString(", ")
            ?.ifBlank { null }

    /** The doors time (`HH:mm` before "UHR") from the `.eventinfo` line. */
    private fun parseDoorsTime(eventInfo: String): LocalTime? = parseTime(TIME_PATTERN.find(eventInfo)?.groupValues?.get(1))

    /**
     * Maps the card wrapper's status class to an [EventStatus] name. Sold-out (`AUSVERKAUFT`) is a
     * separate flag, not a status, and `VERLEGT` reads as relocated only until the detail page's
     * notice says otherwise.
     */
    private fun parseStatus(className: String): String {
        val classes = className.uppercase()
        return when {
            classes.contains(CANCELLED_CLASS) -> EventStatus.CANCELLED.name
            classes.contains(RELOCATED_CLASS) -> EventStatus.RELOCATED.name
            else -> EventStatus.SCHEDULED.name
        }
    }

    private companion object {
        /** A `Support:` part of the subtitle line, which names acts, not a style. */
        private val SUPPORT_PART = Regex("""^(?:$SUPPORT_LABELS)(?!\p{L})""", RegexOption.IGNORE_CASE)

        /** A part of the subtitle line that names a tour: "Wolkenjäger-Tour 2026", "BLOCK BLADI GANGSTER TOUR". */
        private val TOUR_PART = Regex("""\btour\b""", RegexOption.IGNORE_CASE)

        /** What separates the parts of the subtitle line. */
        private val PART_SEPARATOR = Regex("""\s*(?:\||//)\s*""")

        private const val SOLD_OUT_CLASS = "AUSVERKAUFT"
        private const val CANCELLED_CLASS = "ABGESAGT"
        private const val RELOCATED_CLASS = "VERLEGT"

        /**
         * Badehaus's own cues beside the shared ones: themed club nights whose titles are event
         * names, not acts — a themed `… Night`, a decade night (`TOP90s …`), party-décor words
         * (`Konfetti`, `Glitzer`) — and a World Cup screening. Kept narrow to avoid flipping a
         * real band to PARTY (which would drop its headliner): no `jam` (would hit "Pearl Jam"),
         * no `allstars` (a real act, "Heavy Hands Allstars").
         */
        private val BADEHAUS_KEYWORDS =
            listOf("night", "nights", "konfetti", "glitzer", "90s", "2000s", "2010s").associateWith { EventType.PARTY.name } +
                ("world cup" to EventType.SCREENING.name)

        /** The `HH:mm` doors time before the "UHR" suffix. */
        private val TIME_PATTERN = Regex("""(\d{1,2}:\d{2})\s*UHR""", RegexOption.IGNORE_CASE)
    }
}

/**
 * The event slug of a `/events/<slug>/` URL — the last path segment. Both pages build the
 * `sourceId` from it and must agree.
 */
internal fun badehausEventSlug(url: String): String = URI(url).path.trim('/').substringAfterLast('/')

/**
 * The date of a Badehaus date line, "Fr. 25.09.2026 | 19:00 UHR" on the card and on the detail
 * page alike, checked against the weekday before it ([dateCheckedAgainstWeekday]).
 */
internal fun parseBadehausDate(line: String): LocalDate? {
    val match = BADEHAUS_DATE.find(line) ?: return null
    return parseGermanDate(match.groupValues[2])?.let { date ->
        dateCheckedAgainstWeekday(date, parseGermanWeekdayAbbreviation(match.groupValues[1]), line.trim())
    }
}

/** A `DD.MM.YYYY` date, after the two-letter weekday ("Fr.") when the line prints one. */
private val BADEHAUS_DATE = Regex("""(?:\b(\p{L}{2})\.?\s+)?(\d{2}\.\d{2}\.\d{4})""")
