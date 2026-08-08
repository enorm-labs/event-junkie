package de.norm.events.scraper.lark

import de.norm.events.event.EventStatus
import de.norm.events.event.EventType
import de.norm.events.scraper.EventSource
import de.norm.events.scraper.ScrapedArtist
import de.norm.events.scraper.ScrapedEvent
import de.norm.events.scraper.cleanEventTitle
import de.norm.events.scraper.isNonArtistName
import de.norm.events.scraper.mapEventType
import de.norm.events.scraper.refineConcertVenueType
import de.norm.events.scraper.splitHeadlinerTitle
import de.norm.events.scraper.stripArtistSuffix
import io.github.oshai.kotlinlogging.KotlinLogging
import org.jsoup.Jsoup
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.kotlinModule
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeParseException

/**
 * One upcoming LARK event, plus the WordPress attachment id of its poster.
 *
 * The listing carries only a `featured_media` id, so the image URL is resolved separately
 * ([LarkApiScraper.parseMedia]) and applied by [LarkWebsiteImporter]. Keeping the id beside the
 * event lets the parser stay I/O-free.
 */
internal data class LarkEntry(
    val event: ScrapedEvent,
    val featuredMediaId: Long?
)

/**
 * One parsed page of the LARK listing.
 *
 * [postCount] and [oldestDate] drive paging: the listing is ordered newest-first *by event date*
 * (see [LarkApiScraper]), so a page whose oldest event is already past is the last one worth
 * reading.
 */
internal data class LarkPage(
    val entries: List<LarkEntry>,
    val postCount: Int,
    val oldestDate: LocalDate?
)

/**
 * Pure parser for LARK's programme, sourced from its WordPress REST API
 * (`/wp-json/wp/v2/event`) — the venue exposes an Advanced Custom Fields `event` post type in
 * full, so no HTML is scraped (ADR-007 §"Selector Strategy" priority 1).
 * [LarkWebsiteImporter] performs the fetching; this class parses the raw JSON bodies.
 *
 * **The post date *is* the event date.** LARK overloads WordPress's own `post.date` with the
 * show's date and time, leaving `date_gmt` as the publish instant. That is what makes this
 * importer cheap: the endpoint's default newest-first ordering is chronological in event terms,
 * so the upcoming programme sits at the front of page 1 and paging stops as soon as a page
 * reaches the past — unlike [HEIMATHAFEN][de.norm.events.scraper.EventSource.HEIMATHAFEN], whose
 * date lives in an unsortable ACF field and whose whole archive must be walked. Past events are
 * dropped here rather than minting ~600 throwaway events per run.
 *
 * The time on that date is the one the venue itself renders as **`Doors`**, so it is stored as
 * [ScrapedEvent.doorsTime] and no start time is claimed. `acf.event_doors_time` is *not* used: it
 * reads `19:00` on 613 of 623 posts regardless of the real time — including shows that start at
 * 18:30, where trusting it would put doors after the start.
 *
 * **Status is written into the title.** `acf.event_status` reads `Scheduled` on every post, while
 * the venue appends or prefixes the real marker to the title itself — `Flower Face SOLD OUT`,
 * `DOTAN (ausverkauft)`, `CANCELLED: Le Volume Courbe …`, `Mosart … Tour (abgesagt)`. The marker
 * sets [ScrapedEvent.soldOut] / [ScrapedEvent.status] and is then stripped from the stored title.
 *
 * Support acts are also written into the title, as `<act> + <act> (support)`, so the billing is
 * read from there: the title splits on the shared co-bill separators and an act carrying the
 * `(support)` marker is billed [SUPPORT][de.norm.events.event.ArtistRole.SUPPORT]. Dashes are
 * normalised first, because the venue writes its tour tails with an en dash
 * (`Greg Mendez – BEAUTY LAND TOUR`) while the shared [stripArtistSuffix] is keyed on the ASCII
 * hyphen.
 *
 * That same tour tail is stripped **before the event is classified**, because it otherwise decides
 * the type: the shared keyword classifier matches a bare `club`, so `LEILA – 20 SOMETHING CLUB
 * TOUR` — a gig — came back `PARTY` and lost its headliner. Classifying the act rather than the
 * tour it is touring fixes that without weakening the shared keyword list for every other venue.
 *
 * Everything else the venue publishes is a field: `event_type` (a category on ~40% of posts),
 * `event_organizer` (the promoter), `event_tickets_url` and `event_description`. Its remaining
 * ACF fields are unused defaults — `event_entrance_fee` is `None` throughout, and
 * `event_music_genre`, `event_card_subtitle` and the six-slot act repeater are empty on all but
 * one post — so no genre and no prices are stored.
 *
 * @param clock supplies "today"; override in tests for determinism.
 * @see LarkWebsiteImporter for the HTTP fetch orchestrator.
 * @see <a href="https://larkberlin.com/events/">LARK events</a>
 */
internal class LarkApiScraper(
    private val clock: Clock = Clock.systemDefaultZone()
) {
    private val logger = KotlinLogging.logger {}

    private val jsonMapper: JsonMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule())
            .build()

    /**
     * Parses one page of the `event` listing, keeping only events on or after today.
     *
     * @param json the raw JSON body of a `/wp-json/wp/v2/event?page=<n>` response.
     * @return the upcoming events on the page, the number of posts it held (for the short-page
     *   check) and its oldest event date (for the reached-the-past check). An unparseable or
     *   non-array body yields an empty page, which stops paging.
     */
    @Suppress("TooGenericExceptionCaught") // A malformed payload must degrade to an empty page, never abort the import.
    fun scrapePage(json: String): LarkPage {
        val posts =
            try {
                jsonMapper.readTree(json).takeIf { it.isArray }?.toList()
            } catch (e: Exception) {
                logger.warn(e) { "Failed to parse LARK event listing page" }
                null
            }
        if (posts == null) {
            logger.warn { "LARK event listing page is not a JSON array; stopping" }
            return LarkPage(entries = emptyList(), postCount = 0, oldestDate = null)
        }

        val today = LocalDate.now(clock)
        val dates = posts.mapNotNull { parseDateTime(it.path("date").asString("")) }
        val entries =
            posts.mapNotNull { post ->
                @Suppress("TooGenericExceptionCaught") // Skip one malformed post without losing the page.
                try {
                    parsePost(post, today)
                } catch (e: Exception) {
                    logger.warn(e) { "Failed to parse LARK event, skipping" }
                    null
                }
            }

        return LarkPage(entries = entries, postCount = posts.size, oldestDate = dates.minOrNull()?.toLocalDate())
    }

    /**
     * Parses a `/wp-json/wp/v2/media?include=…` response into attachment id → image URL.
     *
     * Returns an empty map for an unparseable body: a missing poster is worth losing, an import
     * is not.
     */
    @Suppress("TooGenericExceptionCaught") // A malformed media response must not cost the events.
    fun parseMedia(json: String): Map<Long, String> =
        try {
            jsonMapper
                .readTree(json)
                .takeIf { it.isArray }
                ?.mapNotNull { node ->
                    val id = node.path("id").asLong(0L).takeIf { it > 0 } ?: return@mapNotNull null
                    val url =
                        node
                            .path("source_url")
                            .asString("")
                            .trim()
                            .takeIf { it.startsWith("http") } ?: return@mapNotNull null
                    id to url
                }?.toMap()
                .orEmpty()
        } catch (e: Exception) {
            logger.warn(e) { "Failed to parse LARK media response; events keep no poster" }
            emptyMap()
        }

    @Suppress("ReturnCount") // Guard clauses for the required id, date, and title are clearer than nesting.
    private fun parsePost(
        post: JsonNode,
        today: LocalDate
    ): LarkEntry? {
        val id = post.path("id").asLong(0L).takeIf { it > 0 } ?: return null

        val startedAt = parseDateTime(post.path("date").asString(""))
        if (startedAt == null) {
            logger.warn { "LARK event $id has no parseable date, skipping" }
            return null
        }
        // The archive reaches back to 2022; only upcoming shows are worth minting.
        if (startedAt.toLocalDate() < today) return null

        val rawTitle = decodeHtml(post.path("title").path("rendered").asString(""))
        if (rawTitle.isBlank()) {
            logger.warn { "LARK event $id has no title, skipping" }
            return null
        }

        val soldOut = SOLD_OUT_MARKER.containsMatchIn(rawTitle)
        val cancelled = CANCELLED_MARKER.containsMatchIn(rawTitle)
        val title = cleanEventTitle(stripStatusMarkers(rawTitle))

        val acf = post.path("acf")
        // Classify the act, not the tour it is touring: "LEILA – 20 SOMETHING CLUB TOUR" is a gig,
        // but the shared keyword classifier sees the "club" in its tour name and calls it a party.
        val actTitle = stripArtistSuffix(title)
        val eventType = refineConcertVenueType(mapEventType(acf.stringOrNull("event_type"), LARK_EVENT_TYPES), actTitle)

        return LarkEntry(
            event =
                ScrapedEvent(
                    title = title,
                    description = acf.stringOrNull("event_description")?.let(::htmlToPlainText)?.takeIf { it.isNotBlank() },
                    eventType = eventType,
                    eventDate = startedAt.toLocalDate(),
                    // The venue renders this time as "Doors"; it publishes no separate start time.
                    doorsTime = startedAt.toLocalTime(),
                    sourceUrl =
                        post
                            .path("link")
                            .asString("")
                            .trim()
                            .ifBlank { LARK_EVENTS_URL },
                    sourceId = "${EventSource.LARK.sourceIdPrefix}$id",
                    ticketUrl = acf.stringOrNull("event_tickets_url")?.takeIf { it.startsWith("http") },
                    soldOut = soldOut,
                    status = if (cancelled) EventStatus.CANCELLED.name else EventStatus.SCHEDULED.name,
                    artists = artistsFrom(title, eventType),
                    promoters = listOfNotNull(acf.stringOrNull("event_organizer"))
                ),
            featuredMediaId = post.path("featured_media").asLong(0L).takeIf { it > 0 }
        )
    }

    /**
     * The acts billed in a concert title.
     *
     * LARK is a live-music club whose title names the act, so a `CONCERT` title is split on the
     * shared co-bill separators and each part becomes an artist — billed
     * [SUPPORT][de.norm.events.event.ArtistRole.SUPPORT] when it carries the venue's own
     * `(support)` marker, otherwise a headliner. A party or other non-concert format names an
     * event rather than a performer, so it yields none (mirroring `buildArtistsForEventType`).
     */
    private fun artistsFrom(
        title: String,
        eventType: String
    ): List<ScrapedArtist> {
        if (eventType != EventType.CONCERT.name) return emptyList()
        return splitHeadlinerTitle(title)
            .map { act ->
                val support = SUPPORT_ACT_MARKER.containsMatchIn(act)
                val name = stripArtistSuffix(act.replace(SUPPORT_ACT_MARKER, "").trim())
                ScrapedArtist(name = name, role = if (support) "SUPPORT" else "HEADLINER")
            }.filterNot { it.name.isBlank() || isNonArtistName(it.name) }
    }

    /** Removes the venue's in-title status markers (and any note parenthesised right after one). */
    private fun stripStatusMarkers(title: String): String =
        title
            .replace(TITLE_STATUS_MARKER, " ")
            .replace(WHITESPACE, " ")
            .trim()
            .ifBlank { title.trim() }

    /** Parses WordPress's `yyyy-MM-dd'T'HH:mm:ss` local post date, returning null instead of throwing. */
    private fun parseDateTime(raw: String): LocalDateTime? {
        val cleaned = raw.trim().takeIf { it.isNotBlank() } ?: return null
        return try {
            LocalDateTime.parse(cleaned)
        } catch (_: DateTimeParseException) {
            null
        }
    }

    private companion object {
        /** Landing page used as [ScrapedEvent.sourceUrl] when a post carries no permalink. */
        const val LARK_EVENTS_URL = "https://larkberlin.com/events/"

        /**
         * LARK's own `acf.event_type` vocabulary, beyond the shared synonyms. `Live` is the
         * venue's word for a gig; `Club` and `Dance` are both DJ nights; `Seminar` is a workshop
         * with no closer type than `OTHER`.
         */
        val LARK_EVENT_TYPES: Map<String, String> =
            mapOf(
                "live" to EventType.CONCERT.name,
                "club" to EventType.PARTY.name,
                "dance" to EventType.PARTY.name,
                "seminar" to EventType.OTHER.name
            )

        /** A sold-out marker the venue writes into the title. */
        val SOLD_OUT_MARKER = Regex("""\bsold\s?out\b|\bausverkauft\b""", RegexOption.IGNORE_CASE)

        /** A cancellation marker the venue writes into the title. */
        val CANCELLED_MARKER = Regex("""\bcancell?ed\b|\babgesagt\b""", RegexOption.IGNORE_CASE)

        /**
         * Either marker as it is punctuated in a title — optionally led by a separating dash or
         * pipe, optionally parenthesised, optionally followed by its own explanatory parenthetical
         * (`CANCELLED (follow ticket link for refunds)`) — so the whole annotation leaves the
         * stored title.
         */
        val TITLE_STATUS_MARKER =
            Regex(
                """\s*[-–—|]?\s*\(?\b(?:sold\s?out|ausverkauft|cancell?ed|abgesagt)\b!?\s*\)?\s*:?(?:\s*\([^)]*\))?""",
                RegexOption.IGNORE_CASE
            )

        /** The venue's own support-act marker, trailing the act it belongs to. */
        val SUPPORT_ACT_MARKER = Regex("""\s*\(\s*supports?\s*\)\s*""", RegexOption.IGNORE_CASE)

        val WHITESPACE = Regex("""\s+""")
    }
}

/** Trimmed string value of [field] on this node, or null when absent, blank, or not a string. */
private fun JsonNode.stringOrNull(field: String): String? = path(field).asString("").trim().takeIf { it.isNotBlank() }

/**
 * Decodes the HTML entities WordPress emits in `title.rendered` (`&#8211;` → `–`, `&#038;` → `&`).
 *
 * The field is *rendered* HTML, so entities are expected; Jsoup's parser is used rather than a
 * hand-rolled table so every entity the venue can emit is covered.
 */
private fun decodeHtml(raw: String): String = Parser.unescapeEntities(raw.trim(), false).trim()

/**
 * Renders `acf.event_description` — which is *markup*, not text — down to a plain-text blurb.
 *
 * The venue writes the field in the WordPress editor, so it arrives carrying `<p class="p1">`
 * wrappers and `<a href>` links, and one event's whole description is a single anchor tag. Stored
 * raw those tags reached the frontend verbatim, so the markup is parsed and only its visible text
 * kept: `<br>` and `<p>` become line breaks, an anchor collapses to its label (the URL is dropped,
 * the same call Frannz makes for its Markdown links), and entities decode as a side effect of
 * reading the text out of the parse tree.
 */
private fun htmlToPlainText(raw: String): String {
    val fragment = Jsoup.parseBodyFragment(raw)
    fragment.select("br").forEach { it.replaceWith(TextNode("\n")) }
    fragment.select("p").forEach { it.appendChild(TextNode("\n")) }
    return fragment
        .wholeText()
        .replace('\r', '\n')
        .replace(BLANK_LINE_RUN, "\n\n")
        .trim()
}

/** Three or more consecutive newlines, collapsed to one blank line. */
private val BLANK_LINE_RUN = Regex("""\n{3,}""")
