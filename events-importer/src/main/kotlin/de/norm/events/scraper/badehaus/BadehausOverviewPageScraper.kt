package de.norm.events.scraper.badehaus

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.buildArtistsForEventType
import de.norm.events.scraper.parseGermanDate
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
 * Badehaus runs WordPress with the Events Manager plugin, but the theme renders
 * its own event cards. The whole upcoming programme lives on the single `/events/`
 * page as a flat list of card wrappers — one `<div>` per event containing an image
 * link, a `.eventinfo` line (date + doors time + ticket link), a title (`h2 > a`)
 * and a subtitle.
 *
 * The overview serves two purposes:
 * 1. **Discovery** — identifies every event and its `/events/<slug>/` detail URL,
 *    which [BadehausDetailPageScraper] then enriches with the description, start
 *    time (`Beginn`) and promoter.
 * 2. **Authoritative source** for the fields the detail page lacks or renders
 *    unreliably: the sold-out / relocated **status** (a CSS class on the card —
 *    `AUSVERKAUFT` sold out, `ABGESAGT` cancelled, `VERLEGT` relocated, turned into
 *    an overlay badge by the stylesheet), the subtitle, and the inferred event
 *    type (see [inferEventType]). It also supplies fallback title / date / doors /
 *    image. Merging is handled by [BadehausWebsiteImporter].
 *
 * @see BadehausWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://badehaus-berlin.com/events/">Badehaus Berlin programme</a>
 */
class BadehausOverviewPageScraper {
    private val logger = KotlinLogging.logger {}

    /**
     * Parses all event cards from the `/events/` listing document.
     *
     * @param baseUrl the URL the document was fetched from, used to resolve relative links.
     * @return a list of [ScrapedEvent] instances, one per card.
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

    /** Parses one event card wrapper into a [ScrapedEvent], or `null` when required fields are missing. */
    @Suppress("ReturnCount") // Guard clauses for the required title/date/url are clearer than nesting
    private fun parseCard(
        card: Element,
        baseUrl: String
    ): ScrapedEvent? {
        val titleLink = card.selectFirst(".nomargin h2 a") ?: return null
        val title = titleLink.text().trim().takeIf { it.isNotBlank() } ?: return null

        val href = titleLink.attr("href").takeIf { it.isNotBlank() } ?: return null
        val sourceUrl = resolveUrl(baseUrl, href)
        val slug = extractSlug(sourceUrl)

        val eventInfo = card.textAt(".eventinfo").orEmpty()
        val eventDate =
            parseDate(eventInfo) ?: run {
                logger.warn { "Could not parse date for '$title' from '$eventInfo', skipping" }
                return null
            }

        val status = parseStatus(card.className())
        val subtitle = parseSubtitle(card)
        val eventType = inferEventType(title, slug)

        return ScrapedEvent(
            title = title,
            subtitle = subtitle,
            eventType = eventType,
            eventDate = eventDate,
            doorsTime = parseDoorsTime(eventInfo),
            imageUrl = card.selectFirst(".eventlistimg img")?.absUrl("src")?.takeIf { it.isNotBlank() },
            sourceUrl = sourceUrl,
            sourceId = "${EventSource.BADEHAUS.sourceIdPrefix}$slug",
            ticketUrl = card.selectFirst(".eventinfo a[href^=http]")?.attr("href"),
            soldOut = card.hasClass(SOLD_OUT_CLASS),
            status = status,
            // Badehaus exposes no artist roster; for concerts the title is the act
            // (support acts come from the subtitle's "Support:" pattern). Non-artist
            // titles (quiz/screening/festival names) are filtered by isNonArtistName.
            artists = buildArtistsForEventType(title, subtitle, eventType)
        )
    }

    /**
     * Infers the [event type][de.norm.events.event.EventType] from the title/slug.
     *
     * Badehaus publishes **no machine-readable category** anywhere in its HTML
     * (no taxonomy term, body class or schema field), so the type is a best-effort
     * heuristic on the event name: pub quizzes, parties/themed club nights and
     * screenings are detected by keyword, and everything else defaults to `CONCERT` —
     * Badehaus is a live-music venue where concerts are by far the most common event.
     * Getting a themed night classified as `PARTY` matters beyond the label: a `PARTY`
     * extracts no artists, so the night's event-name title isn't minted as a fake act
     * (see [buildArtistsForEventType]).
     */
    private fun inferEventType(
        title: String,
        slug: String
    ): String {
        val haystack = "$title $slug".lowercase()
        return when {
            QUIZ_KEYWORDS.any { it in haystack } -> EventType.QUIZ.name
            PARTY_KEYWORDS.any { it in haystack } -> EventType.PARTY.name
            SCREENING_KEYWORDS.any { it in haystack } -> EventType.SCREENING.name
            else -> EventType.CONCERT.name
        }
    }

    /**
     * The subtitle line — the card's `<p>` that is neither the `.eventinfo` line
     * nor the "MORE" link paragraph (identified by carrying an anchor).
     */
    private fun parseSubtitle(card: Element): String? =
        card
            .select(".nomargin > p")
            .firstOrNull { !it.hasClass("eventinfo") && it.selectFirst("a") == null }
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotBlank() }

    /** Parses the `DD.MM.YYYY` date from the `.eventinfo` line (e.g. "Mi. 23.09.2026 | 19:00 UHR"). */
    private fun parseDate(eventInfo: String): LocalDate? = parseGermanDate(DATE_PATTERN.find(eventInfo)?.value)

    /** Parses the doors time (`HH:mm` before "UHR") from the `.eventinfo` line. */
    private fun parseDoorsTime(eventInfo: String): LocalTime? = parseTime(TIME_PATTERN.find(eventInfo)?.groupValues?.get(1))

    /**
     * Maps the card wrapper's status class to an [EventStatus] name. Sold-out
     * (`AUSVERKAUFT`) is intentionally handled as a separate flag, not a status.
     */
    private fun parseStatus(className: String): String {
        val classes = className.uppercase()
        return when {
            classes.contains(CANCELLED_CLASS) -> EventStatus.CANCELLED.name
            classes.contains(RELOCATED_CLASS) -> EventStatus.RELOCATED.name
            else -> EventStatus.SCHEDULED.name
        }
    }

    /** Extracts the event slug from a `/events/<slug>/` URL for a stable `sourceId`. */
    private fun extractSlug(url: String): String = URI(url).path.trim('/').substringAfterLast('/')

    private companion object {
        private const val SOLD_OUT_CLASS = "AUSVERKAUFT"
        private const val CANCELLED_CLASS = "ABGESAGT"
        private const val RELOCATED_CLASS = "VERLEGT"

        private val QUIZ_KEYWORDS = listOf("quiz")

        /**
         * Party signals in the title/slug. Beyond the obvious `party`/`karaoke`, these
         * catch Badehaus's themed club nights, whose titles are event names, not acts —
         * a themed `… Night`, a decade night (`TOP90s …`), and party-décor words
         * (`Konfetti`, `Glitzer`). Kept deliberately narrow to avoid flipping a real
         * band to PARTY (which would drop its headliner): e.g. no `jam` (would hit
         * "Pearl Jam") and no `allstars` (a real act, "Heavy Hands Allstars").
         */
        private val PARTY_KEYWORDS =
            listOf("party", "karaoke", "night", "konfetti", "glitzer", "90s", "2000s", "2010s")
        private val SCREENING_KEYWORDS = listOf("screening", "public viewing", "world cup", "live-screening")

        /** Matches a `DD.MM.YYYY` date in the event-info line. */
        private val DATE_PATTERN = Regex("""\d{2}\.\d{2}\.\d{4}""")

        /** Matches the `HH:mm` doors time before the "UHR" suffix. */
        private val TIME_PATTERN = Regex("""(\d{1,2}:\d{2})\s*UHR""", RegexOption.IGNORE_CASE)
    }
}
